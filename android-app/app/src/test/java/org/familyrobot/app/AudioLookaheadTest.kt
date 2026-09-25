package org.familyrobot.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AudioLookaheadTest {
    @Test fun nextAudioLoadsBeforeCurrentPlaybackEndsAndMemoryIsBounded() = runBlocking {
        val nextLoaded=CompletableDeferred<Unit>()
        val releaseFirst=CompletableDeferred<Unit>()
        val loaded=mutableListOf<Int>()
        val played=mutableListOf<Int>()
        val job=async {
            playWithLookahead(4,load={ i ->
                loaded.add(i)
                if(i==1)nextLoaded.complete(Unit)
                "audio-$i"
            },play={ i,audio ->
                assertEquals("audio-$i",audio)
                if(i==0)releaseFirst.await()
                played.add(i)
                true
            })
        }
        withTimeout(2000) { nextLoaded.await() }
        assertEquals(listOf(0,1),loaded)
        assertTrue(played.isEmpty())
        releaseFirst.complete(Unit)
        assertTrue(job.await())
        assertEquals(listOf(0,1,2,3),played)
    }

    @Test fun speculativeFailureDoesNotInterruptCurrentParagraph() = runBlocking {
        val failed=CompletableDeferred<Unit>()
        var firstCompleted=false
        try {
            playWithLookahead(2,load={ i ->
                if(i==1) { failed.complete(Unit);error("next unavailable") }
                i
            },play={ _,_ ->
                withTimeout(2000) { failed.await() }
                firstCompleted=true
                true
            })
            fail("next failure must surface at its playback boundary")
        } catch(error:IllegalStateException) {
            assertEquals("next unavailable",error.message)
        }
        assertTrue(firstCompleted)
    }

    @Test fun pauseCancelsLookaheadAndDoesNotAdvanceToNextParagraph() = runBlocking {
        val loading=CompletableDeferred<Unit>()
        val cancelled=CompletableDeferred<Unit>()
        val played=mutableListOf<Int>()
        val result=playWithLookahead(3,load={ i ->
            if(i==1)try { loading.complete(Unit);awaitCancellation() }
            finally { cancelled.complete(Unit) }
            i
        },play={ i,_ ->
            played.add(i)
            withTimeout(2000) { loading.await() }
            false
        })
        assertFalse(result)
        assertTrue(cancelled.isCompleted)
        assertEquals(listOf(0),played)
    }

    @Test fun cancellingSessionCancelsPendingNetworkWork() = runBlocking {
        val loading=CompletableDeferred<Unit>()
        val stopped=CompletableDeferred<Unit>()
        val job=launch {
            playWithLookahead(2,load={
                try { loading.complete(Unit);awaitCancellation() }
                finally { stopped.complete(Unit) }
            },play={ _,_ -> fail("cancelled audio cannot play");true })
        }
        withTimeout(2000) { loading.await() }
        job.cancelAndJoin()
        assertTrue(stopped.isCompleted)
    }
}
