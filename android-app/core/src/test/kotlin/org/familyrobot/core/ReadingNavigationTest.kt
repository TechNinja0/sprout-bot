package org.familyrobot.core

import org.junit.Assert.*
import org.junit.Test

class ReadingNavigationTest {
    private fun segment(id:String,label:String="",chapter:String="")=ReadingNavigation.Segment(id,label,chapter)
    @Test fun explicitControlsDoNotCaptureQuestions() {
        assertEquals(ReadingNavigation.Command.Page("三","二"),ReadingNavigation.command("请读第二章第三页。"))
        assertEquals(ReadingNavigation.Command.Restart,ReadingNavigation.command("从头开始读"))
        assertEquals(ReadingNavigation.Command.Chapter("十二"),ReadingNavigation.command("从第十二章开始读"))
        assertNull(ReadingNavigation.command("第三页的小熊为什么哭了"))
        assertNull(ReadingNavigation.choice("第二页"))
        assertEquals(1,ReadingNavigation.choice("读第二本"))
        assertEquals(105,ReadingNavigation.number("一百零五"))
    }
    @Test fun usesPrintedLabelsAndDoesNotConfuseSegmentWithPage() {
        val pages=listOf(segment("p1","7"),segment("p1","7"),segment("p2","9"))
        assertEquals(ReadingNavigation.Location.Found(2),ReadingNavigation.locate(ReadingNavigation.Command.Page("九"),pages))
        assertTrue(ReadingNavigation.locate(ReadingNavigation.Command.Page("2"),pages) is ReadingNavigation.Location.Unavailable)
    }
    @Test fun onlyUnlabelledBooksUseAnnouncedStoredOrder() {
        val pages=listOf(segment("p1"),segment("p1"),segment("p2"))
        val result=ReadingNavigation.locate(ReadingNavigation.Command.Page("2"),pages) as ReadingNavigation.Location.Found
        assertEquals(2,result.index);assertTrue(result.notice.contains("录入顺序"))
        assertTrue(ReadingNavigation.locate(ReadingNavigation.Command.Page("4"),pages) is ReadingNavigation.Location.Unavailable)
    }
    @Test fun duplicateLabelsRequireChapter() {
        val pages=listOf(segment("p1","3","第一章 熊"),segment("p2","3","第二章 雨"))
        assertTrue(ReadingNavigation.locate(ReadingNavigation.Command.Page("三"),pages) is ReadingNavigation.Location.Unavailable)
        assertEquals(ReadingNavigation.Location.Found(1),ReadingNavigation.locate(ReadingNavigation.Command.Page("三","二"),pages))
        assertEquals(ReadingNavigation.Location.Found(1),ReadingNavigation.locate(ReadingNavigation.Command.Chapter("二"),pages))
    }
}
