package app.iptv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleMimeTest {
    @Test fun detectsByExtension() {
        assertEquals("application/x-subrip", subtitleMime("Movie.en.srt"))
        assertEquals("text/vtt", subtitleMime("a.VTT"))
        assertEquals("text/x-ssa", subtitleMime("a.ass"))
        assertEquals("text/x-ssa", subtitleMime("a.ssa"))
        assertEquals("application/ttml+xml", subtitleMime("a.ttml"))
        assertEquals("application/x-subrip", subtitleMime("no_extension"))
    }
}
