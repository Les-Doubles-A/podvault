package com.podvault.app.data.media

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.podvault.app.data.database.dao.EpisodeDao
import com.podvault.app.data.database.dao.PodcastDao
import com.podvault.app.data.database.entities.EpisodeEntity
import com.podvault.app.data.database.entities.PodcastEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Emitted while a scan is running so the UI can show live progress (section 10 of the spec). */
data class ScanProgress(
    val filesScanned: Int,
    val totalFilesSoFar: Int,
    val currentPodcast: String,
    val currentFile: String
)

/** Emitted once a scan finishes (section 11 of the spec). */
data class ScanReport(
    val filesScanned: Int,
    val newEpisodes: Int,
    val removedEpisodes: Int,
    val updatedEpisodes: Int,
    val errors: List<String>
)

/**
 * Recursively walks one or more SAF tree URIs, finds supported audio files, extracts
 * their metadata, and reconciles the result against Room:
 *  - new files -> new episodes (+ new podcast row if the containing folder is new)
 *  - known files whose lastModified/size changed -> metadata re-read
 *  - known files no longer found -> marked isMissing (never deleted outright)
 *  - a file matching a missing episode's name+size found in a different location ->
 *    re-linked to the existing episode instead of creating a duplicate
 *
 * This intentionally does a light "is anything different" check first (name, size,
 * lastModified) so a re-scan of an unchanged library doesn't re-read every file's tags.
 */
class MediaScanner(
    private val context: Context,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val metadataReader: MetadataReader = MetadataReader(context)
) {

    suspend fun scan(
        rootUris: List<Uri>,
        onProgress: (ScanProgress) -> Unit
    ): ScanReport = withContext(Dispatchers.IO) {
        var filesScanned = 0
        var newEpisodes = 0
        var updatedEpisodes = 0
        val errors = mutableListOf<String>()
        val seenUris = mutableSetOf<String>()
        var scanComplete = true

        for (rootUri in rootUris) {
            try {
                val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
                if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
                    scanComplete = false
                    errors += "Un dossier sélectionné est inaccessible. Vérifiez son autorisation d'accès."
                    continue
                }
                walk(rootDoc) { fileDoc ->
                val name = fileDoc.name ?: return@walk
                if (!MetadataReader.isSupportedAudioFile(name)) return@walk

                filesScanned++
                val podcastLabel = fileDoc.parentFile?.name ?: "Podcasts"
                onProgress(ScanProgress(filesScanned, filesScanned, podcastLabel, name))

                try {
                    val uriString = fileDoc.uri.toString()
                    seenUris += uriString
                    val existing = episodeDao.getByUri(uriString)
                    val fileSize = fileDoc.length()
                    val lastModified = fileDoc.lastModified()

                    if (existing != null) {
                        // Same file, unchanged -> nothing to do (light scan).
                        if (existing.fileSize == fileSize && existing.lastModifiedFile == lastModified && !existing.isMissing) {
                            return@walk
                        }
                        // Same file but changed (or was missing and reappeared) -> refresh metadata.
                        val meta = metadataReader.read(fileDoc.uri)
                        episodeDao.update(
                            existing.copy(
                                title = meta?.title ?: existing.title,
                                durationMs = meta?.durationMs?.takeIf { it > 0 } ?: existing.durationMs,
                                bitrate = meta?.bitrate ?: existing.bitrate,
                                coverUri = meta?.coverFilePath ?: existing.coverUri,
                                fileSize = fileSize,
                                lastModifiedFile = lastModified,
                                modifiedAt = System.currentTimeMillis(),
                                isMissing = false
                            )
                        )
                        updatedEpisodes++
                        return@walk
                    }

                    // Not found by exact URI. Maybe it's a file that moved/was renamed:
                    // match by (fileName, fileSize) against a previously "missing" episode.
                    val relinkCandidate = episodeDao.findMissingCandidate(name, fileSize)
                    if (relinkCandidate != null) {
                        episodeDao.update(
                            relinkCandidate.copy(
                                audioUri = uriString,
                                lastModifiedFile = lastModified,
                                modifiedAt = System.currentTimeMillis(),
                                isMissing = false
                            )
                        )
                        updatedEpisodes++
                        return@walk
                    }

                    // Genuinely new file. Read tags first so grouping can use the
                    // album/album-artist tags instead of just the folder name.
                    val meta = metadataReader.read(fileDoc.uri)
                    val podcast = resolvePodcastFor(meta, fileDoc, rootUri)
                    val parsed = parseFromFileName(name)

                    episodeDao.insert(
                        EpisodeEntity(
                            podcastId = podcast.id,
                            title = meta?.title ?: parsed.title ?: name.substringBeforeLast('.'),
                            audioUri = uriString,
                            fileName = name,
                            fileSize = fileSize,
                            durationMs = meta?.durationMs ?: 0L,
                            coverUri = meta?.coverFilePath,
                            seasonNumber = parsed.season,
                            episodeNumber = parsed.episode,
                            codec = meta?.mimeType,
                            bitrate = meta?.bitrate,
                            lastModifiedFile = lastModified
                        )
                    )
                    newEpisodes++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    scanComplete = false
                    errors += "${fileDoc.name ?: "Fichier"}: ${e.javaClass.simpleName}"
                }
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanComplete = false
                errors += "Un dossier sélectionné n'a pas pu être analysé : ${e.javaClass.simpleName}"
            }
        }

        // Une analyse partielle ne permet pas de conclure qu'un fichier a disparu.
        val removedCount = if (scanComplete) markUnseenAsMissing(seenUris) else 0

        ScanReport(
            filesScanned = filesScanned,
            newEpisodes = newEpisodes,
            removedEpisodes = removedCount,
            updatedEpisodes = updatedEpisodes,
            errors = errors
        )
    }

    private suspend fun markUnseenAsMissing(seenUris: Set<String>): Int {
        // Pragmatic pass: rely on DAO to iterate; for very large libraries this should be
        // batched, but Room + Flow.first() keeps this a single read here.
        var count = 0
        val allPodcasts = podcastDaoSnapshot()
        for (podcast in allPodcasts) {
            val uris = episodeDao.getUrisForPodcast(podcast.id)
            for (uri in uris) {
                if (uri !in seenUris) {
                    val episode = episodeDao.getByUri(uri) ?: continue
                    if (!episode.isMissing) {
                        episodeDao.markMissing(episode.id)
                        count++
                    }
                }
            }
        }
        return count
    }

    private suspend fun podcastDaoSnapshot(): List<PodcastEntity> =
        podcastDao.observeAll().first()

    /**
     * Finds (or creates) the podcast this episode belongs to.
     * Section 19 of the spec: prefer real tags over folder structure so files that
     * share an album tag are grouped together even if they sit in different folders,
     * falling back to the containing folder name when no album tag is present.
     */
    private suspend fun resolvePodcastFor(
        meta: ExtractedMetadata?,
        fileDoc: DocumentFile,
        rootUri: Uri
    ): PodcastEntity {
        val parent = fileDoc.parentFile
        val folderKey = parent?.uri?.toString() ?: rootUri.toString()
        val folderTitle = parent?.name?.trim()?.takeIf { it.isNotBlank() } ?: "Podcasts"

        // Prefer the album tag as the show title (a podcast's episodes are almost
        // always tagged with the show name as "album"), then album artist, then
        // finally the folder name if the file has no useful tags at all.
        val tagTitle = meta?.album?.trim()?.takeIf { it.isNotBlank() }
            ?: meta?.albumArtist?.trim()?.takeIf { it.isNotBlank() }
        val resolvedTitle = tagTitle ?: folderTitle
        val resolvedAuthor = meta?.albumArtist?.trim()?.takeIf { it.isNotBlank() }
            ?: meta?.artist?.trim()?.takeIf { it.isNotBlank() }

        // A tag-based title takes priority as the grouping key so two files with the
        // same album tag merge into one podcast even from different folders. If there
        // is no tag, fall back to keying by folder as before.
        val groupKey = tagTitle?.let { "album:${it.lowercase()}" } ?: "folder:$folderKey"

        podcastDao.getByGroupKey(groupKey)?.let { return it }
        // Avoid near-duplicate shows from case/whitespace-only differences in title.
        podcastDao.getByTitle(resolvedTitle)?.let {
            // Backfill the group key so future scans match on it directly.
            if (it.groupKey != groupKey) podcastDao.update(it.copy(groupKey = groupKey))
            return it
        }

        val id = podcastDao.insert(
            PodcastEntity(
                title = resolvedTitle,
                author = resolvedAuthor,
                folderUri = folderKey,
                groupKey = groupKey
            )
        )
        return podcastDao.getById(id)
            ?: error("Podcast just inserted with id=$id could not be re-read")
    }

    private suspend fun walk(dir: DocumentFile, onFile: suspend (DocumentFile) -> Unit) {
        val children = dir.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                walk(child, onFile)
            } else if (child.isFile) {
                onFile(child)
            }
        }
    }

    data class ParsedFileName(val title: String?, val season: Int?, val episode: Int?)

    /**
     * Best-effort parsing of "Episode 042 - Les trous noirs.mp3" style file names
     * when tags are missing (section 16 of the spec). Never overwrites real tags —
     * only used as a fallback when MediaMetadataRetriever returned nothing.
     */
    private fun parseFromFileName(fileName: String): ParsedFileName {
        val base = fileName.substringBeforeLast('.')
        val epRegex = Regex("""(?i)e(?:pisode)?\.?\s*(\d{1,4})""")
        val seasonRegex = Regex("""(?i)s(?:eason|aison)?\.?\s*(\d{1,3})""")
        val episodeNum = epRegex.find(base)?.groupValues?.get(1)?.toIntOrNull()
        val seasonNum = seasonRegex.find(base)?.groupValues?.get(1)?.toIntOrNull()

        // "Episode 042 - Les trous noirs" -> title after the dash if present.
        val dashIndex = base.indexOf('-')
        val title = if (dashIndex in 0 until base.length - 1) {
            base.substring(dashIndex + 1).trim().takeIf { it.isNotBlank() }
        } else null

        return ParsedFileName(title = title, season = seasonNum, episode = episodeNum)
    }
}
