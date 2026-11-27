package com.geniex.demo

import org.junit.Assert.*
import org.junit.Test

class QairtArchiveDownloaderTest {
    @Test fun acceptsExactRangeWithLargeOffsets() {
        assertEquals(4000000000L, QairtArchiveDownloader.rangeTotal("bytes 2500000000-3999999999/4000000000", 2500000000L))
    }
    @Test fun rejectsWrongStartOrEndAndMalformedRange() {
        for (header in listOf(null, "bytes 0-99/100", "bytes 50-90/100", "bytes 50-99/*", "bytes 50-49/50")) {
            assertTrue("Must reject " + header, runCatching { QairtArchiveDownloader.rangeTotal(header, 50L) }.isFailure)
        }
    }
    @Test fun requiresStrongEntityTag() {
        assertEquals("\"abc-10\"", QairtArchiveDownloader.strongTag("\"abc-10\""))
        for (tag in listOf(null, "", "W/\"abc\"", "abc", "\"\"")) {
            assertNull(QairtArchiveDownloader.strongTag(tag))
        }
    }
}
