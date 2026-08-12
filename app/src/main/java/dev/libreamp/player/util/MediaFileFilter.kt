package dev.libreamp.player.util

/** Extension allow-list used by the filesystem file picker to decide which files are pickable. */
object MediaFileFilter {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "aac", "wma", "aiff", "ape", "mid", "midi"
    )
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "wmv", "flv", "ts"
    )

    fun matches(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS || ext in VIDEO_EXTENSIONS
    }
}
