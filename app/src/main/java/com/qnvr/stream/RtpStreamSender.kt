package com.qnvr.stream

import android.media.MediaFormat
import java.io.OutputStream
import kotlin.random.Random
import io.sentry.Sentry

class RtpStreamSender(
    private val out: OutputStream, 
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
) {
  private var seq = Random.nextInt(0, 65535)
  private val ssrc = Random.nextInt()

  fun sendNal(nal: ByteArray, timestamp90k: Int, channel: Int = 0) {
    if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
      sendNalHevc(nal, timestamp90k, channel)
    } else {
      sendNalAvc(nal, timestamp90k, channel)
    }
  }

  private fun sendNalAvc(nal: ByteArray, timestamp90k: Int, channel: Int) {
    try {
      val maxPayload = 1400
      if (nal.size <= maxPayload) {
        val header = rtpHeader(timestamp90k)
        val rtp = header + nal
        writeInterleaved(channel, rtp)
      } else {
        val fuIndicator = (nal[0].toInt() and 0xE0) or 28
        val naluHeader = nal[0].toInt()
        var offset = 1
        var start = true
        while (offset < nal.size) {
          val remaining = nal.size - offset
          val chunk = if (remaining > maxPayload - 2) maxPayload - 2 else remaining
          val end = offset + chunk >= nal.size
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
    } catch (e: Exception) {
      Sentry.captureException(e)
    }
  }

  private fun sendNalHevc(nal: ByteArray, timestamp90k: Int, channel: Int) {
    try {
      val maxPayload = 1400
      if (nal.size <= maxPayload) {
        val header = rtpHeader(timestamp90k)
        val rtp = header + nal
        writeInterleaved(channel, rtp)
      } else {
        // HEVC Fragmentation (FU)
        // NAL Unit Header: 2 bytes
        val nalType = (nal[0].toInt() shr 1) and 0x3F
        val layerId = ((nal[0].toInt() and 0x01) shl 5) or ((nal[1].toInt() shr 3) and 0x1F)
        val tid = nal[1].toInt() and 0x07
        
        // PayloadHdr: Type=49 (FU), LayerId, TID
        val payloadHdr = ByteArray(2)
        payloadHdr[0] = ((49 shl 1) or (layerId shr 5)).toByte()
        payloadHdr[1] = ((layerId shl 3) or tid).toByte()
        
        var offset = 2 // Skip original NAL header (2 bytes)
        var start = true
        
        while (offset < nal.size) {
          val remaining = nal.size - offset
          val chunk = if (remaining > maxPayload - 3) maxPayload - 3 else remaining
          val end = offset + chunk >= nal.size
          
          // FU Header: S | E | FuType
          val fuHeader = ((if (start) 0x80 else 0x00) or (if (end) 0x40 else 0x00) or nalType).toByte()
          
          val payload = ByteArray(3 + chunk)
          payload[0] = payloadHdr[0]
          payload[1] = payloadHdr[1]
          payload[2] = fuHeader
          System.arraycopy(nal, offset, payload, 3, chunk)
          
          val header = rtpHeader(timestamp90k)
          val rtp = header + payload
          writeInterleaved(channel, rtp)
          
          offset += chunk
          start = false
        }
      }
    } catch (e: Exception) {
      Sentry.captureException(e)
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
    try {
      val header = ByteArray(4)
      header[0] = 0x24
      header[1] = channel.toByte()
      header[2] = ((payload.size shr 8) and 0xFF).toByte()
      header[3] = (payload.size and 0xFF).toByte()
      out.write(header)
      out.write(payload)
      out.flush()
    } catch (e: Exception) {
      Sentry.captureException(e)
    }
  }
}