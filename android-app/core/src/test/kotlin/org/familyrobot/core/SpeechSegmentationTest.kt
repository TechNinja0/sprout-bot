package org.familyrobot.core

import org.junit.Assert.*
import org.junit.Test

class SpeechSegmentationTest {
    private fun split(text:String):List<String> {
        val chunks=SpeechSegmentation.split(text)
        assertEquals(text,chunks.joinToString(""))
        assertTrue(chunks.all { it.isNotEmpty() && it.length<=600 })
        return chunks
    }
    @Test fun firstCompleteSentenceStartsEarlyAndLaterShortSentencesAreMerged() {
        assertEquals(listOf("你好！","我们一起探索。今天读故事。准备好了吗？"),split("你好！我们一起探索。今天读故事。准备好了吗？"))
    }
    @Test fun commasDoNotCutTheFirstSentence() {
        val first="你好，小朋友，今天我们一起读故事。"
        assertEquals(listOf(first,"准备好了吗？开始吧！"),split(first+"准备好了吗？开始吧！"))
    }
    @Test fun laterChunksReachModerateLengthAtSentenceBoundaries() {
        val sentence="小".repeat(39)+"。"
        val chunks=split("你好！"+sentence.repeat(12))
        assertEquals(listOf(3,160,160,160),chunks.map { it.length })
        assertTrue(chunks.drop(1).all { it.endsWith("。") })
    }
    @Test fun decimalsVersionsAndDomainsStayTogether() {
        val first="Pi is 3.14; use v1.2.3 at example.com. "
        assertEquals(listOf(first,"Ready? Go!"),split(first+"Ready? Go!"))
    }
    @Test fun titlesInitialsAndAbbreviationsStayTogether() {
        for(first in listOf("Dr. Smith met Mr. Jones. ","J. R. R. Tolkien wrote books. ","The U.S. team arrives at 9 a.m. today. ","Try fruit, e.g. apples, i.e. fresh ones. ","A Ph.D. student arrived. ")) {
            assertEquals(listOf(first,"Hello! Goodbye."),split(first+"Hello! Goodbye."))
        }
    }
    @Test fun chineseAndNestedQuotesAreKeptWhole() {
        for(first in listOf("他说：“你好！你好吗？”","她说：「小熊问『谁在家？』我回答了。」","他说：“你好！” ")) {
            assertEquals(listOf(first,"接着我们读故事。今天很开心！"),split(first+"接着我们读故事。今天很开心！"))
        }
    }
    @Test fun quotedPunctuationDoesNotBreakAttributionOrAComma() {
        for(first in listOf("\"Are you ready?\" she asked. ","He said \"Go!\", then left. ","“准备好了吗？”，她问道。")) {
            assertEquals(listOf(first,"Next. Done!"),split(first+"Next. Done!"))
        }
    }
    @Test fun contractionsPossessivesAndSingleQuotesDoNotSwallowBoundaries() {
        for(first in listOf("Don't touch the robots' toys. ","It’s the robot’s turn. ","He said 'Let's go!' ","He called it ‘fun’. ")) {
            assertEquals(listOf(first,"Next. Done!"),split(first+"Next. Done!"))
        }
    }
    @Test fun parentheticalSentencesRemainInsideTheMainSentence() {
        val first="听我说（别着急！马上开始），我们读故事。"
        assertEquals(listOf(first,"好呀！再读一本。"),split(first+"好呀！再读一本。"))
    }
    @Test fun punctuationRunsAndAllWhitespaceArePreserved() {
        assertEquals(listOf(" \t真的？！\r\n  ","是的…… 后来呢？\n\t结束。  "),split(" \t真的？！\r\n  是的…… 后来呢？\n\t结束。  "))
        assertEquals(listOf("Wait... ","Really?! Yes!!"),split("Wait... Really?! Yes!!"))
    }
    @Test fun emptyUnpunctuatedAndUnclosedQuotedTextIsNotLost() {
        assertEquals(emptyList<String>(),split(""))
        for(text in listOf(" \t\n","没有标点的回答","他说：“你好！后面没有闭合引号","Done.","Dr."))assertEquals(listOf(text),split(text))
        assertEquals(listOf("你好。","最后一句没有句号"),split("你好。最后一句没有句号"))
    }
    @Test fun longSentencesStayCompleteWithinTheLimit() {
        val first="小".repeat(599)+"。"
        assertEquals(listOf(first),split(first))
        assertEquals(listOf("好。",first),split("好。"+first))
        assertEquals(listOf(600,600,1),split("字".repeat(1201)).map { it.length })
    }
    @Test fun hardLimitDoesNotSplitSurrogatePairs() {
        val chunks=split("字".repeat(599)+"😀"+"字".repeat(601))
        assertTrue(chunks.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }
    @Test fun languageIsChosenFromTheWholeAnswer() {
        assertEquals("en",SpeechSegmentation.language("Hello! Let's read a story."))
        assertEquals("zh",SpeechSegmentation.language("你好！Let's read a story."))
        assertEquals("zh",SpeechSegmentation.language("Hello! 我们一起读故事。"))
        assertEquals("zh",SpeechSegmentation.language("㐀"))
        assertEquals("en",SpeechSegmentation.language("123?!"))
    }
}
