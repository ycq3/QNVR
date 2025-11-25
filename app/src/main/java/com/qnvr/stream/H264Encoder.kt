package com.qnvr.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class H264Encoder(private val width: Int, private val height: Int, private val fps: Int, private val bitrate: Int) {
  private lateinit var codec: MediaCodec
  private lateinit var inputSurface: Surface
  private val outQueue = LinkedBlockingQueue<EncodedFrame>()
  private var sps: ByteArray? = null
  private var pps: ByteArray? = null
  data class EncodedFrame(val data: ByteArray, val timeUs: Long, val keyframe: Boolean)

  fun start() {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
    format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    inputSurface = codec.createInputSurface()
    codec.start()
    Thread { drainLoop() }.start()
  }

  fun stop() {
    try { codec.stop() } catch (_: Exception) {}
    try { codec.release() } catch (_: Exception) {}
  }

  fun getInputSurface(): Surface = inputSurface

  fun poll(): EncodedFrame? = outQueue.poll()

  fun getSpsPps(): Pair<ByteArray, ByteArray>? {
    val a = sps ?: return null
    val b = pps ?: return null
    return Pair(a, b)
  }

  private fun drainLoop() {
    val info = MediaCodec.BufferInfo()
    while (true) {
      val index = codec.dequeueOutputBuffer(info, 10000)
      if (index >= 0) {
        val buf = codec.getOutputBuffer(index) ?: continue
        val data = ByteArray(info.size)
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        buf.get(data)
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
          parseSpsPps(data)
        } else {
          val key = (info.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0
          outQueue.offer(EncodedFrame(data, info.presentationTimeUs, key))
        }
        codec.releaseOutputBuffer(index, false)
      }
    }
  }

  private fun parseSpsPps(conf: ByteArray) {
    var i = 0
    while (i + 4 < conf.size) {
      if (conf[i].toInt() == 0 && conf[i + 1].toInt() == 0 && conf[i + 2].toInt() == 0 && conf[i + 3].toInt() == 1) {
        val start = i + 4
        var j = start
        while (j + 4 < conf.size && !(conf[j].toInt() == 0 && conf[j + 1].toInt() == 0 && conf[j + 2].toInt() == 0 && conf[j + 3].toInt() == 1)) j++
        val nal = conf.copyOfRange(start, if (j + 4 < conf.size) j else conf.size)
        val type = nal[0].toInt() and 0x1F
        if (type == 7) sps = nal
        if (type == 8) pps = nal
        i = j
      } else i++
    }
  }
}
