package com.crocxshare.app.core.protocol

/**
 * Compact bitmap of received blocks. Backed by a byte array; total block count
 * is bounded so bitmaps never exhaust memory (worst case 512 KiB of bitmap).
 */
class BlockBitmap(val totalBlocks: Int) {
    init {
        require(totalBlocks in 0..MAX_BLOCKS) { "block count $totalBlocks out of range" }
    }

    private val bits = ByteArray((totalBlocks + 7) / 8)

    fun isSet(index: Int): Boolean {
        checkIndex(index)
        return (bits[index / 8].toInt() shr (index % 8)) and 1 == 1
    }

    fun set(index: Int) {
        checkIndex(index)
        bits[index / 8] = (bits[index / 8].toInt() or (1 shl (index % 8))).toByte()
    }

    fun setAll() {
        bits.fill(0xFF.toByte())
        maskUnusedHighBits()
    }

    fun isComplete(): Boolean {
        for (i in 0 until totalBlocks) if (!isSet(i)) return false
        return true
    }

    /** First unset index >= [from], or -1 if all set. */
    fun nextUnset(from: Int): Int {
        var i = maxOf(0, from)
        while (i < totalBlocks) { if (!isSet(i)) return i; i++ }
        return -1
    }

    fun receivedCount(): Int {
        var n = 0
        for (i in 0 until totalBlocks) if (isSet(i)) n++
        return n
    }

    fun copyBytes(): ByteArray = bits.copyOf()

    fun loadBytes(data: ByteArray) {
        if (data.size < bits.size) throw ProtocolException("bitmap too short")
        System.arraycopy(data, 0, bits, 0, bits.size)
        maskUnusedHighBits()
    }

    private fun maskUnusedHighBits() {
        val rem = totalBlocks % 8
        if (rem != 0 && bits.isNotEmpty()) {
            val last = bits.size - 1
            bits[last] = (bits[last].toInt() and ((1 shl rem) - 1)).toByte()
        }
    }

    private fun checkIndex(index: Int) {
        if (index < 0 || index >= totalBlocks) throw IndexOutOfBoundsException("block $index / $totalBlocks")
    }

    companion object {
        /** 4 Mi blocks * 4 MiB = 16 TiB ceiling, bitmap <= 512 KiB. */
        const val MAX_BLOCKS = 1 shl 22

        fun fromBytes(totalBlocks: Int, data: ByteArray): BlockBitmap {
            val b = BlockBitmap(totalBlocks)
            b.loadBytes(data)
            return b
        }
    }
}
