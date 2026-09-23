package org.familyrobot.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CoreTest {
    @Test fun followupStartsAtActualPlaybackEnd() {
        val s=Session();val t=s.wake(0,true)!!
        s.openingFinished(t);s.thinking(t);s.tick(35_000)
        assertTrue(s.active)
        s.speaking(t);s.playbackEnded(t,40_000)
        s.tick(69_999);assertTrue(s.active)
        s.tick(70_000);assertFalse(s.active);assertFalse(s.camera)
    }
    @Test fun cancelledResponsesCannotResume() {
        val s=Session();val old=s.wake(0,true)!!;s.pause(100)
        s.speaking(old);assertEquals(SessionState.FOLLOW_UP,s.state)
        assertFalse(s.valid(old));s.stop();assertFalse(s.resume(200))
    }
    @Test fun mediaDoesNotKeepCameraOrBypassBlock() {
        val s=Session();s.wake(0,true);s.startMedia(10,ActivityMode.BOOK)
        s.tick(30_000);assertFalse(s.camera);assertTrue(s.mediaPlaying)
        s.configure(false,false,true);assertFalse(s.mediaPlaying);assertFalse(s.microphone)
        assertNull(s.wake(40_000,true));assertFalse(s.resume(40_000))
    }
    @Test fun limitClosesQuestionEvenWithInput() {
        val s=Session();s.wake(0,true);s.voiceStarted(590_000);s.tick(600_000)
        assertFalse(s.active);assertFalse(s.camera)
    }
    @Test fun dialogueTimeoutKeepsMediaTicketUntilIndependentMediaLimit() {
        val s=Session();val ticket=s.wake(0,true)!!;s.startMedia(0,ActivityMode.BOOK)
        s.tick(600_000);assertFalse(s.active);assertFalse(s.camera)
        assertTrue(s.mediaPlaying);assertTrue(s.valid(ticket))
        s.tick(1_799_999);assertTrue(s.valid(ticket))
        s.tick(1_800_000);assertFalse(s.mediaPlaying);assertFalse(s.valid(ticket))
    }
    @Test fun speechNearFollowupDeadlineKeepsListeningButOwnOutputDoesNot() {
        val s=Session();val ticket=s.wake(0,true)!!;s.openingFinished(ticket,1000)
        s.voiceStarted(30_000);s.tick(31_001);assertTrue(s.active)
        val answer=s.recognizing(40_000)!!;s.speaking(answer);s.voiceStarted(41_000)
        assertEquals(SessionState.SPEAKING,s.state)
        s.playbackEnded(answer,50_000);s.tick(80_000);assertFalse(s.active)
    }
    @Test fun conversationLimitOffersOnlyBoundedCancellableClosingCue() {
        val s=Session();val old=s.wake(0,true)!!;s.tick(600_000)
        assertEquals(SessionState.CLOSING,s.state);assertFalse(s.active);assertFalse(s.camera)
        assertFalse(s.valid(old));assertTrue(s.valid(s.generation))
        s.voiceStarted(601_000);assertEquals(SessionState.CLOSING,s.state)
        s.tick(604_000);assertEquals(SessionState.STANDBY,s.state)
        s.wake(605_000,true);s.tick(1_205_000);s.pause(1_205_010)
        assertEquals(SessionState.STANDBY,s.state)
    }
    @Test fun wakeLeadInIsBoundedAndNeverReplaysAfterOrdinaryReplies() {
        val b=WakeAudioBuffer(4);b.offer(floatArrayOf(1f,2f,3f))
        assertNull(b.takeForFirstUtterance())
        b.arm();b.offer(floatArrayOf(4f,5f));assertArrayEquals(floatArrayOf(2f,3f,4f,5f),b.takeForFirstUtterance(),0f)
        b.offer(floatArrayOf(6f,7f));assertNull(b.takeForFirstUtterance())
        b.arm();b.clear();assertNull(b.takeForFirstUtterance())
    }
    @Test fun enablingCameraNeverOpensIt() {
        val s=Session();s.wake(0,true);s.configure(true,false,false);s.configure(true,false,true)
        assertFalse(s.camera)
    }
    private fun ms(s:String)=Instant.parse(s).toEpochMilli()
    @Test fun quietHoursAcrossMidnightAndWeekday() {
        val p=UsagePolicy(zone=ZoneId.of("UTC"),intervals=listOf(QuietInterval(setOf(1),LocalTime.of(20,0),LocalTime.of(9,0))))
        assertEquals(Permission.ALLOWED,PolicyEngine.permission(p,ms("2026-09-21T19:59:59Z"),0))
        assertEquals(Permission.SCHEDULED,PolicyEngine.permission(p,ms("2026-09-21T20:00:00Z"),0))
        assertEquals(Permission.SCHEDULED,PolicyEngine.permission(p,ms("2026-09-22T08:59:59Z"),0))
        assertEquals(Permission.ALLOWED,PolicyEngine.permission(p,ms("2026-09-22T09:00:00Z"),0))
        assertEquals(Permission.ALLOWED,PolicyEngine.permission(p,ms("2026-09-23T08:00:00Z"),0))
    }
    @Test fun quotaAndUntrustedTimeOutrankWake() {
        val p=UsagePolicy(intervals=emptyList(),dailyMinutes=1)
        assertEquals(Permission.QUOTA,PolicyEngine.permission(p,0,60_000))
        assertEquals(Permission.UNTRUSTED_TIME,PolicyEngine.permission(p,0,0,false))
    }
    @Test fun ledgerSplitsMidnightAndNeverDoubleCounts() {
        val l=UsageLedger();val wall=ms("2026-09-21T23:59:58Z");val z=ZoneId.of("UTC")
        l.tick(0,wall,z,true);l.tick(4000,wall+4000,z,true);l.tick(4000,wall+4000,z,true)
        assertEquals(2000L,l.totals["2026-09-21"]);assertEquals(2000L,l.totals["2026-09-22"])
        l.tick(5000,wall-3600000,z,true);assertFalse(l.trusted)
    }
    @Test fun idleFollowupIsNotBillable() {
        val s=Session();val t=s.wake(0,true)!!;s.playbackEnded(t,100)
        assertFalse(s.billable)
    }
    @Test fun silentWakeReturnsToStandby() {
        val s=Session();val ticket=s.wake(0,true)!!;s.openingFinished(ticket)
        s.tick(29_999);assertTrue(s.active)
        s.tick(30_000);assertFalse(s.active);assertFalse(s.camera)
    }
    @Test fun staleOpeningCannotReplaceMediaAndTimerStartsAfterCue() {
        val s=Session();val ticket=s.wake(0,true)!!;s.openingFinished(ticket,1000)
        s.tick(30_500);assertTrue(s.active)
        s.tick(31_000);assertFalse(s.active)
        val next=s.wake(32_000,true)!!;s.startMedia(32_100,ActivityMode.BOOK)
        s.openingFinished(next,32_500);assertEquals(SessionState.SPEAKING,s.state)
    }
    @Test fun listeningPlanOnlyRunsOnLiveBoundary() {
        val p=ListeningPlan("one","list",setOf(1),LocalTime.of(18,30),10,true)
        val engine=ListeningScheduler();val at=ms("2026-09-21T18:30:00Z");val zone=ZoneId.of("UTC")
        assertTrue(engine.due(at-200,zone,listOf(p)).isEmpty())
        assertEquals(listOf(p),engine.due(at,zone,listOf(p)))
        assertTrue(engine.due(at+200,zone,listOf(p)).isEmpty())
        assertTrue(ListeningScheduler().due(at,zone,listOf(p)).isEmpty())
        val gap=ListeningScheduler();gap.due(at-5000,zone,listOf(p));assertTrue(gap.due(at,zone,listOf(p)).isEmpty())
    }
    @Test fun profileGrowthIsPureAndGradual() {
        assertEquals(6,Growth.age(5,LocalDate.of(2026,9,22),LocalDate.of(2027,9,22)))
        assertEquals(5,Growth.age(5,LocalDate.of(2026,9,22),LocalDate.of(2027,9,21)))
    }
}
