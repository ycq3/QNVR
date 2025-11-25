package com.qnvr.stream

import java.io.OutputStream
import java.net.Socket
import kotlin.random.Random

class RtpH264Sender(private val out: OutputStream) {
  private var seq = Random.nextInt(0, 65535)
  private val ssrc = Random.nextInt()

  fun sendNal(nal: ByteArray, timestamp90k: Int, channel: Int = 0) {
    val maxPayload = 1400
    val type = nal[0].toInt() and 0x1F
    if (nal.size <= maxPayload) {
      val header = rtpHeader(timestamp90k)
      val rtp = header + nal
      writeInterleaved(channel, rtp)
    } else {
      val fuIndicator = (nal[0].toInt() and 0xE0) or 28
      val naluHeader = nal[0].toInt()
      var offset = 1
      var start = true
      var end = false
      while (offset < nal.size) {
        val remaining = nal.size - offset
        val chunk = if (remaining > maxPayload - 2) maxPayload - 2 else remaining
        end = offset + chunk >= nal.size
        val fuHeader = ((if (start) 0x80 else 0x00) or (if (end) 0x40 else 0x00) or (naluHeader and 0x1F)).toByte()
        val payload = ByteArray(2 + chunk)
        payload[0] = fuIndicator.toByte()
        payload[1] = fuHeader
        System.arraycopy(nal, offset, payload, 2, chunk)
        val header = rtpHeader(timestamp90k)
        val rtp = header + payload
        writeInterleaved(channel, rtp)
        offset += chunk
        start = false
      }
    }
  }

  private fun rtpHeader(ts90k: Int): ByteArray {
    val b = ByteArray(12)
    b[0] = 0x80.toByte()
    b[1] = 96.toByte()
    b[2] = ((seq shr 8) and 0xFF).toByte()
    b[3] = (seq and 0xFF).toByte()
    seq = (seq + 1) and 0xFFFF
    b[4] = ((ts90k shr 24) and 0xFF).toByte()
    b[5] = ((ts90k shr 16) and 0xFF).toByte()
    b[6] = ((ts90k shr 8) and 0xFF).toByte()
    b[7] = (ts90k and 0xFF).toByte()
    b[8] = ((ssrc shr 24) and 0xFF).toByte()
    b[9] = ((ssrc shr 16) and 0xFF).toByte()
    b[10] = ((ssrc shr 8) and 0xFF).toByte()
    b[11] = (ssrc and 0xFF).toByte()
    return b
  }

  private fun writeInterleaved(channel: Int, payload: ByteArray) {
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
