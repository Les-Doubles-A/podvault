package com.podvault.app.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Raw metadata pulled from an audio file's embedded tags, plus a locally-cached
 * cover image path if the file has embedded artwork.
 */
data class ExtractedMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val durationMs: Long,
    val bitrate: Int?,
    val coverFilePath: String?,
    val mimeType: String?
)

/**
 * Wraps MediaMetadataRetriever to pull ID3/Vorbis/MP4 tags plus embedded artwork
 * from a content:// (SAF) Uri. This never touches the network — everything comes
 * from the file itself.
 */
class MetadataReader(private val context: Context) {

    fun read(uri: Uri): ExtractedMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val yearRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            val trackRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val durationRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val bitrateRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)

            val coverPath = extractEmbeddedCover(retriever, uri)

            ExtractedMetadata(
                title = title.cleanTag(512),
                artist = artist.cleanTag(512),
                album = album.cleanTag(512),
                albumArtist = albumArtist.cleanTag(512),
                genre = genre.cleanTag(128),
                year = yearRaw?.take(4)?.toIntOrNull(),
                trackNumber = trackRaw?.substringBefore("/")?.toIntOrNull(),
                durationMs = durationRaw?.toLongOrNull() ?: 0L,
                bitrate = bitrateRaw?.toIntOrNull(),
                coverFilePath = coverPath,
                mimeType = mime
            )
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de lire les métadonnées du fichier local (${e.javaClass.simpleName})")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** Saves embedded artwork (if any) into the app's cache dir and returns the file path. */
    private fun extractEmbeddedCover(retriever: MediaMetadataRetriever, sourceUri: Uri): String? {
        val bytes = retriever.embeddedPicture?.takeIf { it.isNotEmpty() && it.size <= MAX_COVER_BYTES } ?: return null
        return try {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val fileName = "cover_${sourceUri.toString().hashCode()}.jpg"
            val outFile = File(coversDir, fileName)
            FileOutputStream(outFile).use { it.write(bytes) }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de mettre en cache la pochette (${e.javaClass.simpleName})")
            null
        }
    }

    companion object {
        private const val TAG = "MetadataReader"
        private const val MAX_COVER_BYTES = 8 * 1024 * 1024

        /** Extensions we claim to support, matching what ExoPlayer/Media3 can actually decode. */
        val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "wav", "flac")

        fun isSupportedAudioFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in SUPPORTED_EXTENSIONS
        }
    }
}

private fun String?.cleanTag(maxLength: Int): String? = this
    ?.filterNot { it.isISOControl() && it != '\n' && it != '\t' }
    ?.trim()
    ?.take(maxLength)
    ?.takeIf { it.isNotBlank() }

/**
 * Cover paths cached by [MetadataReader] are raw filesystem paths (e.g. from
 * context.filesDir), not URIs. Coil and Media3's artwork loader both need an
 * actual scheme to resolve them, so this normalizes "/data/.../cover.jpg" into
 * "file:///data/.../cover.jpg" while leaving content:// / http(s):// URIs untouched.
 */
fun String?.toDisplayUri(): String? {
    if (this.isNullOrBlank()) return null
    return if (this.contains("://")) this else "file://$this"
}
