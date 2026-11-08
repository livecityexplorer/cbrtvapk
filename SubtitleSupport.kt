package app.iptv.feature.player

/** MIME type for an external subtitle file, from its name. SubRip is the fallback. */
internal fun subtitleMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "vtt" -> "text/vtt"
    "ass", "ssa" -> "text/x-ssa"
    "ttml", "xml", "dfxp" -> "application/ttml+xml"
    else -> "application/x-subrip"
}
