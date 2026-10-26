package com.example.varcamera.usbtest

import java.nio.ByteBuffer

/**
 * Keeps isochronous transfers running on one endpoint and hands every packet that
 * carried data to [sink]. The URB plumbing lives in iso_pump.c because Android's Java
 * USB API cannot do isochronous transfers at all.
 *
 * [packetSize] is what one packet may hold: the endpoint's bytes per service interval.
 */
class IsoPump(
    private val packetSize: Int,
    private val log: (String) -> Unit,
    private val onPacketLost: () -> Unit,
    private val sink: (buffer: ByteBuffer, offset: Int, length: Int) -> Unit
) {
    @Volatile var stopRequested = false
    private var deadlineNanos = Long.MAX_VALUE

    var urbs = 0L; private set
    var packets = 0L; private set
    var dataPackets = 0L; private set
    var emptyPackets = 0L; private set
    var firstDataAtNanos = 0L; private set

    /** Packet status (a negative errno, e.g. -18 = missed) to how often it happened. */
    val packetErrors = sortedMapOf<Int, Long>()
    val urbErrors = sortedMapOf<Int, Long>()
    var callbackError: Throwable? = null; private set

    /** Blocks until [durationMs] has passed or [stopRequested]; returns 0 or a negative errno. */
    fun run(fd: Int, endpoint: Int, packetsPerUrb: Int, urbCount: Int, durationMs: Long): Int {
        deadlineNanos = System.nanoTime() + durationMs * 1_000_000L
        return nativeRun(fd, endpoint, packetSize, packetsPerUrb, urbCount)
    }

    /** Called from native code for every completed URB; false stops the stream. */
    fun onUrb(buffer: ByteBuffer, lengths: IntArray, statuses: IntArray, count: Int, urbStatus: Int): Boolean {
        try {
            urbs++
            if (urbStatus != 0) urbErrors[urbStatus] = (urbErrors[urbStatus] ?: 0L) + 1
            for (p in 0 until count) {
                packets++
                val status = statuses[p]
                if (status != 0) {
                    packetErrors[status] = (packetErrors[status] ?: 0L) + 1
                    onPacketLost()
                    continue
                }
                val length = lengths[p]
                if (length == 0) {
                    emptyPackets++
                    continue
                }
                dataPackets++
                if (firstDataAtNanos == 0L) firstDataAtNanos = System.nanoTime()
                sink(buffer, p * packetSize, length)
            }
        } catch (t: Throwable) {
            callbackError = t
            return false
        }
        return !shouldStop()
    }

    /** Called from native code while no transfer completes. */
    fun shouldStop(): Boolean = stopRequested || System.nanoTime() > deadlineNanos

    /** Called from native code. */
    fun onLog(message: String) = log(message)

    private external fun nativeRun(fd: Int, endpoint: Int, packetSize: Int, packetsPerUrb: Int, urbCount: Int): Int

    companion object {
        init {
            System.loadLibrary("isopump")
        }
    }
}
