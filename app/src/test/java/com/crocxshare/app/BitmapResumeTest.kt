package com.crocxshare.app

import com.crocxshare.app.core.protocol.BlockBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapResumeTest {
    @Test fun setUnsetRoundTrip() {
        val b = BlockBitmap(10)
        b.set(0); b.set(3); b.set(9)
        val r = BlockBitmap.fromBytes(10, b.copyBytes())
        assertTrue(r.isSet(0) && r.isSet(3) && r.isSet(9))
        assertFalse(r.isSet(4))
        assertEquals(3, r.receivedCount())
        assertFalse(r.isComplete())
    }

    @Test fun nextUnsetWalksInOrder() {
        val b = BlockBitmap(5)
        b.set(0); b.set(1)
        assertEquals(2, b.nextUnset(0))
        b.set(2); b.set(3); b.set(4)
        assertEquals(-1, b.nextUnset(0))
        assertTrue(b.isComplete())
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun outOfBoundsThrows() {
        BlockBitmap(3).set(3)
    }
}
