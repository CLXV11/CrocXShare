package com.crocxshare.app

import com.crocxshare.app.core.protocol.PathsGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathsGuardTest {
    @Test fun rejectsTraversalAndAbsolute() {
        assertNull(PathsGuard.sanitize("../evil"))
        assertNull(PathsGuard.sanitize("a/../../b"))
        assertNull(PathsGuard.sanitize("/abs/path"))
        assertNull(PathsGuard.sanitize("a/./../b"))
    }

    @Test fun rejectsHostileNames() {
        assertNull(PathsGuard.sanitize("CON.txt"))
        assertNull(PathsGuard.sanitize("LPT1"))
        assertNull(PathsGuard.sanitize("bad\u0000name"))
        assertNull(PathsGuard.sanitize("trail."))
        assertNull(PathsGuard.sanitize("pad "))
        assertNull(PathsGuard.sanitize("x".repeat(300)))
    }

    @Test fun acceptsNormalPaths() {
        assertEquals("Project/src/main.kt", PathsGuard.sanitize("Project/src/main.kt"))
        assertEquals("a/b/c.txt", PathsGuard.sanitize("a\\b\\c.txt"))
        assertEquals("f.txt", PathsGuard.sanitize("./f.txt"))
        assertNull(PathsGuard.sanitize(""))
    }
}
