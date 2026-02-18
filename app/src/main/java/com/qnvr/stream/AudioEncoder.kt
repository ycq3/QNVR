package com.qnvr.stream

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.util.concurrent.CopyOnWriteArrayList
import io.sentry.Sentry

class AudioEncoder(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 1,
    private val bitrate: Int = 64000
) {
    data class EncodedAudioFrame(val data: ByteArray, val timeUs: Long)
    data class AudioConfig(val sampleRate: Int, val channelCount: Int, val audioSpecificConfig: ByteArray)

    interface FrameCallback {
        fun onFrame(frame: EncodedAudioFrame)
    }

    private val callbacks = CopyOnWriteArrayList<FrameCallback>()
    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var isStarted = false
    private var audioSpecificConfig: ByteArray? = null

    fun addCallback(callback: FrameCallback) {
        callbacks.add(callback)
    }

    fun removeCallback(callback: FrameCallback) {
        callbacks.remove(callback)
    }

    fun start() {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec?.start()

            val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = if (minBuffer <= 0) 4096 else minBuffer * 2
            audioRecord = createAudioRecord(channelConfig, bufferSize)
            audioRecord?.startRecording()

            isStarted = true
            Thread { inputLoop(bufferSize) }.start()
            Thread { outputLoop() }.start()
        } catch (e: Exception) {
            android.util.Log.e("AudioEncoder", "Failed to start audio encoder", e)
            Sentry.captureException(e)
            throw e
        }
    }

    fun stop() {
        isStarted = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
    }

    fun getAudioConfig(): AudioConfig? {
        val config = audioSpecificConfig ?: buildAudioSpecificConfig(sampleRate, channelCount)
        return AudioConfig(sampleRate, channelCount, config)
    }

    private fun createAudioRecord(channelConfig: Int, bufferSize: Int): AudioRecord {
        val sources = intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
        for (source in sources) {
            try {
                val record = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return record
                }
                try { record.release() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    }

    private fun inputLoop(bufferSize: Int) {
        val record = audioRecord ?: return
        val codec = codec ?: return
        val buffer = ByteArray(bufferSize)
        var totalSamples = 0L
        val bytesPerSample = 2 * channelCount

        while (isStarted) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue

            val index = codec.dequeueInputBuffer(10000)
            if (index >= 0) {
                val inputBuf = codec.getInputBuffer(index)
                inputBuf?.clear()
                inputBuf?.put(buffer, 0, read)
                val sampleCount = read / bytesPerSample
                val timeUs = (totalSamples * 1_000_000L) / sampleRate
                totalSamples += sampleCount
                codec.queueInputBuffer(index, 0, read, timeUs, 0)
            }
        }
    }

    private fun outputLoop() {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (isStarted) {
            val index = codec.dequeueOutputBuffer(info, 10000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = codec.outputFormat
                val csd = fmt.getByteBuffer("csd-0")
                if (csd != null && csd.remaining() > 0) {
                    val data = ByteArray(csd.remaining())
                    csd.get(data)
                    audioSpecificConfig = data
                }
            } else if (index >= 0) {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(data)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val frame = EncodedAudioFrame(data, info.presentationTimeUs)
                        for (cb in callbacks) {
                            cb.onFrame(frame)
                        }
                    } else {
                        if (audioSpecificConfig == null && data.isNotEmpty()) {
                            audioSpecificConfig = data
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun buildAudioSpecificConfig(sampleRate: Int, channelCount: Int): ByteArray {
        val audioObjectType = 2
        val sampleRateIndex = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 4
        }
        val config = (audioObjectType shl 11) or (sampleRateIndex shl 7) or (channelCount shl 3)
        return byteArrayOf(((config shr 8) and 0xFF).toByte(), (config and 0xFF).toByte())
    }
}
