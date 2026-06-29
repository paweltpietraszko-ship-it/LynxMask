package com.lynxmask.app

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LynxAppSessionTest {

    @After
    fun tearDown() {
        LynxAppSession.lock()
    }

    @Test
    fun startsLocked() {
        LynxAppSession.lock()
        assertFalse(LynxAppSession.isUnlocked)
    }

    @Test
    fun expressUnlocksWithoutLibrary() {
        LynxAppSession.unlockExpress()
        assertTrue(LynxAppSession.isUnlocked)
        assertTrue(LynxAppSession.isExpress)
        assertFalse(LynxAppSession.allowsLibrary)
    }

    @Test
    fun authenticatedAllowsLibrary() {
        LynxAppSession.unlockAuthenticated()
        assertTrue(LynxAppSession.isAuthenticated)
        assertTrue(LynxAppSession.allowsLibrary)
    }
}
