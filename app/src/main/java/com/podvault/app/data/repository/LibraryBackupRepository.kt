package com.podvault.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.podvault.app.data.database.AppDatabase
import com.podvault.app.data.database.entities.BookmarkEntity
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.database.entities.PlaybackStateEntity
import com.podvault.app.data.database.entities.PodcastEntity
import com.podvault.app.data.database.entities.ListeningDayEntity
import org.json.JSONArray
import org.json.JSONObject

/** Export et import versionnés de la bibliothèque, sans toucher aux fichiers audio. */
class LibraryBackupRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context)
) {
    suspend fun export(uri: Uri) {
        val root = JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("podcasts", JSONArray().apply { db.podcastDao().getAll().forEach { put(it.toJson()) } })
            .put("episodes", JSONArray().apply { db.episodeDao().getAll().forEach { put(it.toJson()) } })
            .put("bookmarks", JSONArray().apply { db.bookmarkDao().getAll().forEach { put(it.toJson()) } })
            .put("playbackStates", JSONArray().apply { db.playbackStateDao().getAll().forEach { put(it.toJson()) } })
            .put("listeningDays", JSONArray().apply { db.listeningDayDao().getAll().forEach { put(it.toJson()) } })

        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Impossible de créer le fichier d'export.")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(root.toString()) }
    }

    suspend fun import(uri: Uri): ImportSummary {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Impossible de lire le fichier sélectionné.")
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_IMPORT_BYTES) { "Le fichier d'import dépasse la taille autorisée." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optInt("formatVersion", -1) == FORMAT_VERSION) { "Version de sauvegarde non prise en charge." }
        val podcasts = root.requireArray("podcasts", MAX_RECORDS).map { it.toPodcast() }
            .also { require(it.map(PodcastEntity::id).distinct().size == it.size) { "Identifiants de podcasts dupliqués." } }
        val episodes = root.requireArray("episodes", MAX_RECORDS).map { it.toEpisode() }
            .also { require(it.map(EpisodeEntity::id).distinct().size == it.size) { "Identifiants d'épisodes dupliqués." } }
        val bookmarks = root.requireArray("bookmarks", MAX_RECORDS).map { it.toBookmark() }
            .also { require(it.map(BookmarkEntity::id).distinct().size == it.size) { "Identifiants de points mémoire dupliqués." } }
        val positions = root.requireArray("playbackStates", MAX_RECORDS).map { it.toPlaybackState() }
            .also { require(it.map(PlaybackStateEntity::episodeId).distinct().size == it.size) { "Positions de lecture dupliquées." } }
        val listeningDays = root.optJSONArray("listeningDays")?.let { array ->
            require(array.length() <= MAX_RECORDS) { "La sauvegarde contient trop d'éléments." }
            (0 until array.length()).map { array.optJSONObject(it)?.toListeningDay() ?: error("Jour d'écoute invalide.") }
        }.orEmpty()

        return db.withTransaction {
            val podcastIds = mutableMapOf<Long, Long>()
            podcasts.forEach { imported ->
                val existing = imported.groupKey.takeIf { it.isNotBlank() }?.let { db.podcastDao().getByGroupKey(it) }
                    ?: db.podcastDao().getByFolderUri(imported.folderUri)?.takeIf { it.title == imported.title }
                val target = existing ?: run {
                    val newId = db.podcastDao().insertIgnoringConflict(imported.copy(id = 0))
                    if (newId > 0) db.podcastDao().getById(newId)
                    else db.podcastDao().getByGroupKey(imported.groupKey)
                        ?: db.podcastDao().getByFolderUri(imported.folderUri)
                }
                requireNotNull(target) { "Un podcast n'a pas pu être importé." }
                if (existing != null && (imported.userTitle != null || imported.userAuthor != null || imported.userCoverUri != null)) {
                    db.podcastDao().update(target.copy(
                        userTitle = imported.userTitle ?: target.userTitle,
                        userAuthor = imported.userAuthor ?: target.userAuthor,
                        userCoverUri = imported.userCoverUri ?: target.userCoverUri
                    ))
                }
                podcastIds[imported.id] = target.id
            }

            val episodeIds = mutableMapOf<Long, Long>()
            episodes.forEach { imported ->
                val podcastId = podcastIds[imported.podcastId]
                    ?: error("Un épisode référence un podcast absent de la sauvegarde.")
                val existing = db.episodeDao().getByUri(imported.audioUri)
                val targetId = existing?.id ?: run {
                    val newId = db.episodeDao().insertIgnoringConflict(imported.copy(id = 0, podcastId = podcastId))
                    if (newId > 0) newId else db.episodeDao().getByUri(imported.audioUri)?.id
                }
                val resolvedId = requireNotNull(targetId) { "Un épisode n'a pas pu être importé." }
                if (existing != null && (imported.userTitle != null || imported.userAuthor != null || imported.userCoverUri != null || imported.isFavorite || imported.isPlayed)) {
                    db.episodeDao().update(existing.copy(
                        userTitle = imported.userTitle ?: existing.userTitle,
                        userAuthor = imported.userAuthor ?: existing.userAuthor,
                        userCoverUri = imported.userCoverUri ?: existing.userCoverUri,
                        isFavorite = existing.isFavorite || imported.isFavorite,
                        isPlayed = existing.isPlayed || imported.isPlayed
                    ))
                }
                episodeIds[imported.id] = resolvedId
            }

            bookmarks.forEach { imported ->
                val episodeId = episodeIds[imported.episodeId]
                    ?: error("Un point mémoire référence un épisode absent de la sauvegarde.")
                if (!db.bookmarkDao().existsAt(episodeId, imported.positionMs, imported.title, imported.note)) {
                    db.bookmarkDao().insert(imported.copy(id = 0, episodeId = episodeId))
                }
            }
            positions.forEach { imported ->
                val episodeId = episodeIds[imported.episodeId]
                    ?: error("Une position référence un épisode absent de la sauvegarde.")
                val current = db.playbackStateDao().getForEpisode(episodeId)
                if (current == null || imported.lastPlayedAt > current.lastPlayedAt) {
                    db.playbackStateDao().upsert(imported.copy(episodeId = episodeId))
                }
            }
            listeningDays.forEach { imported ->
                val current = db.listeningDayDao().getForDay(imported.day)
                if (current == null || imported.listenedMs > current.listenedMs) db.listeningDayDao().upsert(imported)
            }
            ImportSummary(podcasts.size, episodes.size, bookmarks.size, positions.size)
        }
    }

    private fun PodcastEntity.toJson() = JSONObject()
        .put("id", id).put("title", title).put("author", author.jsonValue())
        .put("description", description.jsonValue()).put("coverUri", coverUri.jsonValue())
        .put("folderUri", folderUri).put("category", category.jsonValue()).put("groupKey", groupKey)
        .put("userTitle", userTitle.jsonValue()).put("userAuthor", userAuthor.jsonValue()).put("userCoverUri", userCoverUri.jsonValue())
        .put("createdAt", createdAt).put("updatedAt", updatedAt)

    private fun EpisodeEntity.toJson() = JSONObject()
        .put("id", id).put("podcastId", podcastId).put("title", title)
        .put("description", description.jsonValue()).put("audioUri", audioUri).put("fileName", fileName)
        .put("fileSize", fileSize).put("durationMs", durationMs).put("coverUri", coverUri.jsonValue())
        .put("publicationDate", publicationDate.jsonValue()).put("seasonNumber", seasonNumber.jsonValue())
        .put("episodeNumber", episodeNumber.jsonValue()).put("codec", codec.jsonValue())
        .put("userTitle", userTitle.jsonValue()).put("userAuthor", userAuthor.jsonValue()).put("userCoverUri", userCoverUri.jsonValue())
        .put("bitrate", bitrate.jsonValue()).put("addedAt", addedAt).put("modifiedAt", modifiedAt)
        .put("lastModifiedFile", lastModifiedFile).put("isFavorite", isFavorite).put("isPlayed", isPlayed)
        .put("isMissing", isMissing)

    private fun BookmarkEntity.toJson() = JSONObject()
        .put("id", id).put("episodeId", episodeId).put("positionMs", positionMs)
        .put("title", title.jsonValue()).put("note", note.jsonValue())
        .put("createdAt", createdAt).put("updatedAt", updatedAt)

    private fun PlaybackStateEntity.toJson() = JSONObject()
        .put("episodeId", episodeId).put("positionMs", positionMs).put("lastPlayedAt", lastPlayedAt)
        .put("playCount", playCount).put("completed", completed).put("totalListenedMs", totalListenedMs)

    private fun ListeningDayEntity.toJson() = JSONObject().put("day", day).put("listenedMs", listenedMs)

    private fun JSONObject.toPodcast() = PodcastEntity(
        id = positiveId("id"), title = requiredText("title"), author = optionalText("author"),
        description = optionalText("description"), coverUri = optionalText("coverUri"),
        folderUri = requiredText("folderUri", 4096), category = optionalText("category"),
        groupKey = optionalText("groupKey", 1024).orEmpty(), userTitle = optionalText("userTitle"),
        userAuthor = optionalText("userAuthor"), userCoverUri = optionalText("userCoverUri", 4096),
        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
    )

    private fun JSONObject.toEpisode() = EpisodeEntity(
        id = positiveId("id"), podcastId = positiveId("podcastId"), title = requiredText("title"),
        description = optionalText("description"), audioUri = requiredText("audioUri", 4096),
        fileName = requiredText("fileName", 1024), fileSize = nonNegativeLong("fileSize"),
        durationMs = nonNegativeLong("durationMs"), coverUri = optionalText("coverUri"),
        publicationDate = optNullableLong("publicationDate"), seasonNumber = optNullableInt("seasonNumber"),
        episodeNumber = optNullableInt("episodeNumber"), codec = optionalText("codec", 128),
        userTitle = optionalText("userTitle"), userAuthor = optionalText("userAuthor"), userCoverUri = optionalText("userCoverUri", 4096),
        bitrate = optNullableInt("bitrate"), addedAt = optLong("addedAt"), modifiedAt = optLong("modifiedAt"),
        lastModifiedFile = optLong("lastModifiedFile").coerceAtLeast(0), isFavorite = optBoolean("isFavorite"),
        isPlayed = optBoolean("isPlayed"), isMissing = optBoolean("isMissing")
    )

    private fun JSONObject.toBookmark() = BookmarkEntity(
        id = positiveId("id"), episodeId = positiveId("episodeId"), positionMs = nonNegativeLong("positionMs"),
        title = optionalText("title"), note = optionalText("note"), createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
    )

    private fun JSONObject.toPlaybackState() = PlaybackStateEntity(
        episodeId = positiveId("episodeId"), positionMs = nonNegativeLong("positionMs"),
        lastPlayedAt = nonNegativeLong("lastPlayedAt"), playCount = optInt("playCount").coerceAtLeast(0),
        completed = optBoolean("completed"), totalListenedMs = optLong("totalListenedMs").coerceAtLeast(0L)
    )

    private fun JSONObject.toListeningDay() = ListeningDayEntity(
        day = requiredText("day", 10).also { require(runCatching { java.time.LocalDate.parse(it) }.isSuccess) { "Date invalide." } },
        listenedMs = nonNegativeLong("listenedMs")
    )

    private fun JSONObject.requireArray(name: String, maximum: Int): List<JSONObject> {
        val array = optJSONArray(name) ?: error("La sauvegarde est incomplète.")
        require(array.length() <= maximum) { "La sauvegarde contient trop d'éléments." }
        return (0 until array.length()).map { array.optJSONObject(it) ?: error("Élément JSON invalide.") }
    }

    private fun JSONObject.requiredText(name: String, maxLength: Int = 512) =
        optString(name).takeIf { it.isNotBlank() && it.length <= maxLength && it.none { character -> Character.isISOControl(character) } }
            ?: error("La sauvegarde contient un texte invalide.")
    private fun JSONObject.optionalText(name: String, maxLength: Int = 2048): String? =
        if (isNull(name)) null else requiredText(name, maxLength)
    private fun JSONObject.positiveId(name: String) = optLong(name, -1).takeIf { it > 0 } ?: error("Identifiant invalide.")
    private fun JSONObject.nonNegativeLong(name: String) = optLong(name, -1).takeIf { it >= 0 } ?: error("Valeur numérique invalide.")
    private fun JSONObject.optNullableLong(name: String): Long? = if (isNull(name)) null else nonNegativeLong(name)
    private fun JSONObject.optNullableInt(name: String): Int? = if (isNull(name)) null else optInt(name, -1).takeIf { it >= 0 } ?: error("Valeur numérique invalide.")
    private fun String?.jsonValue(): Any = this ?: JSONObject.NULL
    private fun Number?.jsonValue(): Any = this ?: JSONObject.NULL

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MAX_RECORDS = 100_000
        private const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
    }
}

data class ImportSummary(val podcasts: Int, val episodes: Int, val bookmarks: Int, val playbackStates: Int)
