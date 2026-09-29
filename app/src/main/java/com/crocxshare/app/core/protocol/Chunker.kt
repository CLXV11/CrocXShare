package com.crocxshare.app.core.protocol

/** Block/chunk math for large-file streaming. All sizes are 64-bit safe. */
object Chunker {
    const val DEFAULT_BLOCK_SIZE = 4L * 1024 * 1024       // 4 MiB
    const val DEFAULT_CHUNK_SIZE = 256 * 1024             // 256 KiB
    const val MIN_CHUNK_SIZE = 16 * 1024
    const val MAX_CHUNK_SIZE = 4 * 1024 * 1024

    /** Overflow-safe: never adds blockSize to size. */
    fun blockCount(size: Long, blockSize: Long): Long {
        if (size < 0 || blockSize <= 0) throw IllegalArgumentException("bad size")
        if (size == 0L) return 0L
        val full = size / blockSize
        return if (size % blockSize == 0L) full else full + 1
    }

    /** Number of blocks must be representable as an Int bitmap (2^22 blocks = 8 TiB at 4 MiB). */
    fun blockCountChecked(size: Long, blockSize: Long): Int {
        val n = blockCount(size, blockSize)
        if (n > BlockBitmap.MAX_BLOCKS) throw ProtocolException("file too large for block bitmap: $n blocks")
        return n.toInt()
    }

    fun blockLength(blockIndex: Long, size: Long, blockSize: Long): Long {
        val count = blockCount(size, blockSize)
        if (blockIndex < 0 || blockIndex >= count) throw ProtocolException("block index out of range")
        val start = blockIndex * blockSize
        return minOf(blockSize, size - start)
    }

    fun chunksInBlock(blockIndex: Long, size: Long, blockSize: Long, chunkSize: Int): Int {
        val len = blockLength(blockIndex, size, blockSize)
        return ((len + chunkSize - 1) / chunkSize).toInt()
    }
}
