package com.qnvr.stream

import java.io.OutputStream
import kotlin.random.Random

class RtpAudioSender(
    private val out: OutputStream,
    private val payloadType: Int = 97
) {
    private var seq = Random.nextInt(0, 65535)
    private val ssrc = Random.nextInt()

    fun sendAacFrame(aac: ByteArray, timestamp: Int, channel: Int) {
        val auSize = aac.size
        
        val auHeadersLength = 16
        val auHeader = (auSize shl 3) or 0
        
        val payload = ByteArray(2 + 2 + aac.size)
        payload[0] = ((auHeadersLength shr 8) and 0xFF).toByte()
        payload[1] = (auHeadersLength and 0xFF).toByte()
        payload[2] = ((auHeader shr 8) and 0xFF).toByte()
        payload[3] = (auHeader and 0xFF).toByte()
        System.arraycopy(aac, 0, payload, 4, aac.size)

        val header = rtpHeader(timestamp)
        val rtp = header + payload
        writeInterleaved(channel, rtp)
    }

    private fun rtpHeader(ts: Int): ByteArray {
        val b = ByteArray(12)
        b[0] = 0x80.toByte()
        b[1] = (payloadType or 0x80).toByte()
        b[2] = ((seq shr 8) and 0xFF).toByte()
        b[3] = (seq and 0xFF).toByte()
        seq = (seq + 1) and 0xFFFF
        b[4] = ((ts shr 24) and 0xFF).toByte()
        b[5] = ((ts shr 16) and 0xFF).toByte()
        b[6] = ((ts shr 8) and 0xFF).toByte()
        b[7] = (ts and 0xFF).toByte()
        b[8] = ((ssrc shr 24) and 0xFF).toByte()
        b[9] = ((ssrc shr 16) and 0xFF).toByte()
        b[10] = ((ssrc shr 8) and 0xFF).toByte()
        b[11] = (ssrc and 0xFF).toByte()
        return b
    }

    private fun writeInterleaved(channel: Int, payload: ByteArray) {
        synchronized(out) {
            val header = ByteArray(4)
            header[0] = 0x24
            header[1] = channel.toByte()
            header[2] = ((payload.size shr 8) and 0xFF).toByte()
            header[3] = (payload.size and 0xFF).toByte()
            out.write(header)
            out.write(payload)
            out.flush()
        }
    }
}
