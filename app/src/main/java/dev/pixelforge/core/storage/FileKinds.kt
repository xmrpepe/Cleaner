package dev.pixelforge.core.storage

import java.util.Locale

enum class FileKind(val label: String) {
    IMAGE("Images"), VIDEO("Videos"), AUDIO("Audio"), DOCUMENT("Documents"),
    ARCHIVE("Archives"), APK("APKs"), OTHER("Other"),
}

object FileKinds {
    private val image = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng", "raw", "avif", "jxl")
    private val video = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v", "ts", "wmv")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr", "mid", "wma")
    private val docs = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "odt", "ods", "epub", "rtf", "json", "xml", "html")
    val archives = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4")
    val apks = setOf("apk", "apks", "xapk", "apkm")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun kindOf(name: String): FileKind {
        val e = ext(name)
        return when (e) {
            in image -> FileKind.IMAGE
            in video -> FileKind.VIDEO
            in audio -> FileKind.AUDIO
            in docs -> FileKind.DOCUMENT
            in archives -> FileKind.ARCHIVE
            in apks -> FileKind.APK
            else -> FileKind.OTHER
        }
    }

    fun mimeOf(name: String): String = when (val e = ext(name)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "heic", "heif" -> "image/heif"; "avif" -> "image/avif"; "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "mov" -> "video/quicktime"; "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "flac" -> "audio/flac"; "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "pdf" -> "application/pdf"; "txt", "md", "csv", "log" -> "text/plain"
        "json" -> "application/json"; "xml" -> "text/xml"; "html" -> "text/html"
        "zip" -> "application/zip"; "apk" -> "application/vnd.android.package-archive"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> if (e.isEmpty()) "application/octet-stream" else "*/*"
    }

    fun isTextLike(name: String): Boolean = ext(name) in setOf("txt", "md", "csv", "log", "json", "xml", "html", "ini", "conf", "cfg", "yaml", "yml", "kt", "java", "py", "js", "sh", "prop", "properties")
}
