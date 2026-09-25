package com.crocxshare.app

import com.crocxshare.app.core.protocol.Chunker
import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkerTest {
    private val block = 4L * 1024 * 1024

    @Test fun blockMath() {
        assertEquals(0L, Chunker.blockCount(0, block))
        assertEquals(1L, Chunker.blockCount(1, block))
        assertEquals(2L, Chunker.blockCount(block + 1, block))
        assertEquals(block, Chunker.blockLength(0, 10L * 1024 * 1024, block))
        assertEquals(2L * 1024 * 1024, Chunker.blockLength(1, 6L * 1024 * 1024, block))
        assertEquals(4, Chunker.chunksInBlock(0, block * 2, block, 1024 * 1024))
    }

    @Test fun noOverflowNearLongMax() {
        val n = Chunker.blockCount(Long.MAX_VALUE - 1, block)
        assert(n > 0)
        assertEquals(Long.MAX_VALUE / block + 1, n)
    }

    @Test(expected = com.crocxshare.app.core.protocol.ProtocolException::class)
    fun outOfRangeBlockThrows() {
        Chunker.blockLength(99, 10, block)
    }
}
