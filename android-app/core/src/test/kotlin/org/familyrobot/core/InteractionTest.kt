package org.familyrobot.core

import org.junit.Assert.*
import org.junit.Test

class InteractionTest {
    @Test fun touchRegionsFollowFaceInBothOrientations() {
        for((w,h) in listOf(1000f to 600f,600f to 1000f)) {
            assertTrue(FaceRegion.contains(w/2,h*.46f,w,h))
            assertFalse(FaceRegion.contains(1f,1f,w,h))
            assertTrue(FaceRegion.management(w*.95f,h*.05f,w,h))
        }
    }
    @Test fun strokeDistinguishesTapPetTickleAndAccidentalDrag() {
        assertEquals(FaceTouch.TAP,FaceStroke(500f,276f,1000f,600f,8f,48f).result())
        val pet=FaceStroke(450f,190f,1000f,600f,8f,48f)
        pet.move(530f,190f);assertEquals(FaceTouch.PET,pet.result())
        val rub=FaceStroke(630f,370f,1000f,600f,8f,48f)
        rub.move(680f,370f);assertNull(rub.result())
        rub.move(615f,370f);assertEquals(FaceTouch.TICKLE,rub.result())
        rub.move(990f,370f);assertNull(rub.result())
        assertNull(FaceStroke(5f,5f,1000f,600f,8f,48f).result())
    }
    @Test fun openingAndWorkStatesOverrideOldPerformance() {
        assertEquals("waking",interactionFace(SessionState.OPENING,performance="cry"))
        assertEquals("speaking",interactionFace(SessionState.SPEAKING,performance="tickle"))
        assertEquals("thinking",interactionFace(SessionState.THINKING,performance="laugh"))
        assertEquals("hearing",interactionFace(SessionState.LISTENING,performance="tickle",voiceActive=true))
        assertEquals("blocked",interactionFace(SessionState.BLOCKED,performance="happy"))
        assertEquals("listening",interactionFace(SessionState.FOLLOW_UP))
    }
    @Test fun feedbackPreferenceAndPlayfulToggleAlwaysWin() {
        val replies=WakeReplies()
        assertNull(replies.next(FaceTouch.TICKLE,"visual",true))
        assertEquals("chime",replies.next(FaceTouch.TICKLE,"chime",true))
        assertEquals("wake_touch",replies.next(FaceTouch.TICKLE,"voice",false))
        assertEquals("tickle",replies.next(FaceTouch.TICKLE,"voice",true))
        val selected=List(6) { replies.next(null,"voice",true) }
        assertTrue(selected.zipWithNext().all { it.first!=it.second })
    }
    @Test fun speechDuringOpeningSurvivesOpeningCompletion() {
        val s=Session();val ticket=s.wake(0,true)!!
        s.voiceStarted(200);s.openingFinished(ticket,1000)
        assertEquals(SessionState.LISTENING,s.state)
        assertNull(s.followUpAt)
        assertEquals(200L,s.lastInputAt)
    }
    @Test fun echoDoesNotRenewWaitingAndCannotReviveCancelledSession() {
        val s=Session();val t=s.wake(0,false)!!;s.openingFinished(t,1000)
        s.discardOpeningEcho(t,1000,0)
        assertFalse(s.valid(t));s.tick(31_000);assertEquals(SessionState.CLOSING,s.state)
        assertEquals("chime",s.closingPrompt);assertFalse(s.camera)
        s.tick(31_400);assertEquals(SessionState.STANDBY,s.state)
        s.discardOpeningEcho(t,50_000,50_000);assertEquals(SessionState.STANDBY,s.state)
    }
    @Test fun firstTurnCleansKnownEchoWithoutRemovingTheRequest() {
        assertEquals("讲个故事",OpeningTranscript.clean("小伙伴，我在。讲个故事","小伙伴","wake"))
        assertEquals("",OpeningTranscript.clean("哎呀，好痒！","小伙伴","tickle"))
        assertEquals("我在家里",OpeningTranscript.clean("我在家里","小伙伴",null))
        assertEquals("我在家里",OpeningTranscript.clean("我在家里","小伙伴","wake"))
        assertEquals("怎么了",OpeningTranscript.clean("怎么了","小伙伴","wake"))
        assertEquals("讲个故事",OpeningTranscript.clean("相机暂时用不了，我们可以说话。讲个故事","小伙伴","camera_unavailable"))
    }
}
