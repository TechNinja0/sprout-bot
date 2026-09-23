package org.familyrobot.core

import org.junit.Assert.*
import org.junit.Test

class ResourceVersionsTest {
    @Test fun ordinaryRepublishAfterHistoricalUnlistDoesNotInterruptValidReading() {
        assertFalse(ResourceVersions.withdrawn("second","third",setOf("first")))
        assertTrue(ResourceVersions.withdrawn("first","third",setOf("first")))
        assertFalse(ResourceVersions.withdrawn("third","third",setOf("first")))
    }
    @Test fun unlistingAndDeletionAlwaysRemoveCachedVersions() {
        assertTrue(ResourceVersions.withdrawn("second","",emptySet()))
        assertFalse(ResourceVersions.withdrawn("","",emptySet()))
    }
    @Test fun olderServicesKeepConservativeRevocationBehavior() {
        assertTrue(ResourceVersions.withdrawn("second","third",null))
        assertFalse(ResourceVersions.withdrawn("third","third",null))
    }
}
