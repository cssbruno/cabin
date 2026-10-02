package com.cabin.audio

/** Bounded, synchronized PCM16 ring. Every cursor and discard is a whole interleaved frame. */
class AudioRingBuffer(private val capacityMs: Int, private val sampleRate: Int, private val channels: Int) {
    init { require(capacityMs > 0 && sampleRate > 0 && channels in 1..8) }
    private val bytesPerFrame = channels * 2
    private val capacityFrames = ((capacityMs.toLong() * sampleRate + 999L) / 1000L).also {
        require(it in 1..(Int.MAX_VALUE / bytesPerFrame).toLong())
    }.toInt()
    private val capacity = capacityFrames * bytesPerFrame
    private val buffer = ByteArray(capacity)
    private var writePos = 0
    private var readPos = 0
    private var used = 0
    @Volatile var totalBytesWritten = 0L; private set
    @Volatile var totalBytesRead = 0L; private set
    @Volatile var overflowCount = 0; private set
    @Volatile var underflowCount = 0; private set
    @Volatile var discardedBytes = 0L; private set

    /** Unaligned input is rejected, rather than silently shifting channels on the next packet. */
    @Synchronized fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        require(length % bytesPerFrame == 0) { "PCM input must contain whole frames" }
        if (length == 0) return 0
        // Oversize writes retain the newest complete frames, matching normal overflow policy.
        val count = minOf(length, capacity)
        val skipped = length - count
        val discard = maxOf(0L, used.toLong() + count - capacity).toInt()
        if (discard > 0 || skipped > 0) {
            readPos = (readPos + discard) % capacity
            used -= discard
            discardedBytes += discard.toLong() + skipped
            overflowCount++
        }
        val first = minOf(count, capacity - writePos)
        data.copyInto(buffer, writePos, offset + skipped, offset + skipped + first)
        if (count > first) data.copyInto(buffer, 0, offset + skipped + first, offset + length)
        writePos = (writePos + count) % capacity
        used += count
        totalBytesWritten += count
        return count
    }

    /** Odd destination lengths are rounded down; no partial frame is ever returned. */
    @Synchronized fun read(out: ByteArray, offset: Int = 0, length: Int = out.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset <= out.size - length)
        val count = minOf(length - length % bytesPerFrame, used)
        if (count == 0) { if (used == 0 && length >= bytesPerFrame) underflowCount++; return 0 }
        val first = minOf(count, capacity - readPos)
        buffer.copyInto(out, offset, readPos, readPos + first)
        if (count > first) buffer.copyInto(out, offset + first, 0, count - first)
        readPos = (readPos + count) % capacity
        used -= count
        totalBytesRead += count
        return count
    }
    @Synchronized fun availableForRead(): Int = used
    @Synchronized fun availableForWrite(): Int = capacity - used
    @Synchronized fun fillLevel(): Float = used.toFloat() / capacity
    @Synchronized fun fillLevelMs(): Int = (used.toLong() * 1000 / bytesPerFrame / sampleRate).toInt()
    @Synchronized fun clear() { writePos = 0; readPos = 0; used = 0 }
    @Synchronized fun getStats(): Map<String, Any> = mapOf(
        "capacityMs" to capacityMs, "capacityBytes" to capacity, "fillLevelMs" to fillLevelMs(),
        "fillPercent" to (fillLevel() * 100).toInt(), "totalBytesWritten" to totalBytesWritten,
        "totalBytesRead" to totalBytesRead, "overflowCount" to overflowCount, "underflowCount" to underflowCount,
        "discardedBytes" to discardedBytes, "sampleRate" to sampleRate, "channels" to channels,
    )
}
