package org.familyrobot.core

import org.junit.Assert.*
import org.junit.Test

class FaceFeedbackTest {
    @Test fun stalledAudioWarnsWithoutMistakingLongPlaybackForFailure() {
        val tracker=FaceFeedbackTracker()
        tracker.present("story",1,0,0)
        assertEquals(FaceSignal.SPEAK,tracker.present("story",1,20000,320000).signal)
        assertEquals(FaceSignal.WAIT,tracker.present("story",1,30000,320000).signal)
        assertEquals("播放暂未推进",tracker.present("story",1,31000,320000).title)
        assertFalse(tracker.hasFailure(1))
        assertEquals(FaceSignal.SPEAK,tracker.present("story",1,32000,336000).signal)
        assertEquals(FaceSignal.SPEAK,tracker.present("story",2,50000,336000).signal)
    }
    @Test fun waitClockFollowsPhaseAndGenerationNotEntireStoryLength() {
        val tracker=FaceFeedbackTracker()
        tracker.present("thinking",1,0)
        assertFalse(tracker.present("thinking",1,9999).detail.contains("已等待"))
        assertTrue(tracker.present("thinking",1,10000).detail.contains("10 秒"))
        assertEquals("等待有点久",tracker.present("thinking",1,30000).title)
        assertEquals("正在准备声音",tracker.present("preparing",1,31000).title)
        assertEquals("正在讲故事",tracker.present("story",1,90000).title)
        assertEquals("正在讲故事",tracker.present("story",1,180000).title)
        assertFalse(tracker.present("preparing",1,181000).detail.contains("已等待"))
        assertFalse(tracker.present("thinking",2,200000).detail.contains("已等待"))
        assertTrue(tracker.present("thinking",2,230000).detail.contains("已等待"))
        tracker.beginAttempt()
        assertFalse(tracker.present("thinking",2,240000).detail.contains("已等待"))
    }

    @Test fun confirmedFailurePersistsUntilNewTurnButDoesNotOverrideRestrictions() {
        val tracker=FaceFeedbackTracker()
        tracker.fail(7,"等待超时，请再试一次")
        assertEquals(FaceSignal.ERROR,tracker.present("listening",7,100).signal)
        assertEquals(FaceSignal.ERROR,tracker.present("standby",7,60000).signal)
        assertEquals(FaceSignal.MUTED,tracker.present("muted",7,61000).signal)
        assertEquals(FaceSignal.LOCK,tracker.present("blocked",7,62000).signal)
        assertEquals(FaceSignal.LISTEN,tracker.present("listening",8,63000).signal)
        assertFalse(tracker.hasFailure(8))
        tracker.fail(8,"播放失败")
        tracker.clearFailure()
        assertEquals(FaceSignal.SPEAK,tracker.present("story",8,64000).signal)
    }

    @Test fun realConversationKeepsSleepListeningRecognitionThinkingAndPlaybackDistinct() {
        val session=Session()
        assertEquals(FaceSignal.SLEEP,faceFeedback(interactionFace(session.state)).signal)
        val opening=session.wake(0,false)!!
        assertEquals(FaceSignal.WAKE,faceFeedback(interactionFace(session.state)).signal)
        session.openingFinished(opening,100)
        assertEquals(FaceSignal.LISTEN,faceFeedback(interactionFace(session.state)).signal)
        val turn=session.recognizing(200)!!
        assertEquals(FaceSignal.RECOGNIZE,faceFeedback(interactionFace(session.state)).signal)
        session.thinking(turn)
        assertEquals(FaceSignal.THINK,faceFeedback(interactionFace(session.state)).signal)
        session.speaking(turn);session.tick(45000)
        assertEquals(FaceSignal.SPEAK,faceFeedback(interactionFace(session.state)).signal)
        assertNull(session.followUpAt)
        session.playbackEnded(turn,45000);session.tick(75000)
        assertEquals(FaceSignal.SLEEP,faceFeedback(interactionFace(session.state)).signal)
    }
}
