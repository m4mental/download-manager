package com.example.speeddown.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * High-performance hardware-accelerated Media Muxer engine.
 * Merges separate video and audio streams (e.g. YouTube DASH 1080p/720p video-only + M4A/WebM audio)
 * into a single unified media file with perfectly synchronized tracks.
 *
 * Uses Android's native MediaMuxer and MediaExtractor framework APIs.
 * Zero external libraries, zero re-encoding loss, and zero KB APK overhead.
 */
object MediaMuxerEngine {
    private const val TAG = "MediaMuxerEngine"
    private const val DEFAULT_BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB buffer
    const val MAX_VIDEO_BUFFER_BUDGET = 32 * 1024 * 1024 // 32 MB max allocation budget for video
    const val MAX_AUDIO_BUFFER_BUDGET = 8 * 1024 * 1024  // 8 MB max allocation budget for audio

    /**
     * Calculates the bounded buffer allocation size for a track based on the required sample size and default buffer size.
     * Ensures that the required sample size fits within the allocation budget, returning a failure if it exceeds the budget
     * rather than truncating below the required sample size.
     */
    internal fun calculateBufferSize(requiredSampleSize: Int, defaultSize: Int, budget: Int): Result<Int> {
        if (requiredSampleSize > budget) {
            return Result.failure(
                IllegalArgumentException("Required sample size ($requiredSampleSize bytes) exceeds buffer allocation budget ($budget bytes)")
            )
        }
        val size = maxOf(defaultSize, requiredSampleSize, requiredSampleSize * 2).coerceAtMost(budget)
        return Result.success(size)
    }

    /**
     * Muxes a video file and an audio file into an output file.
     * Interleaves sample writing by timestamp to prevent buffer overflows and ensure monotonic presentation times.
     */
    fun mux(videoFile: File, audioFile: File, outputFile: File): Result<File> {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            return Result.failure(IllegalStateException("Video track file is missing or empty"))
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return Result.failure(IllegalStateException("Audio track file is missing or empty"))
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStopped = false

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            // 1. Locate video track
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                return Result.failure(IllegalStateException("No valid video track found in ${videoFile.name}"))
            }

            // 2. Locate audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return Result.failure(IllegalStateException("No valid audio track found in ${audioFile.name}"))
            }

            // Ensure output file parent directory exists and purge any previous incomplete target
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""

            val isWebm = videoMime.contains("vp8", ignoreCase = true) ||
                         videoMime.contains("vp9", ignoreCase = true) ||
                         audioMime.contains("opus", ignoreCase = true) ||
                         audioMime.contains("vorbis", ignoreCase = true) ||
                         outputFile.extension.equals("webm", ignoreCase = true)

            val outputMuxerFormat = if (isWebm) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            muxer = MediaMuxer(outputFile.absolutePath, outputMuxerFormat)

            // Sanitize formats to strip vendor-specific decoder keys that cause addTrack() to fail on OEM ROMs
            val safeVideoFormat = sanitizeVideoFormat(videoFormat)
            val safeAudioFormat = sanitizeAudioFormat(audioFormat)

            val muxerVideoTrack = try {
                muxer.addTrack(videoFormat)
            } catch (e1: Exception) {
                Log.w(TAG, "Raw video format addTrack failed (${e1.message}), trying sanitized format...")
                try {
                    muxer.addTrack(safeVideoFormat)
                } catch (e2: Exception) {
                    Log.e(TAG, "Both raw and sanitized video format failed: ${e2.message}")
                    throw e1
                }
            }

            val muxerAudioTrack = try {
                muxer.addTrack(audioFormat)
            } catch (e1: Exception) {
                Log.w(TAG, "Raw audio format addTrack failed (${e1.message}), trying sanitized format...")
                try {
                    muxer.addTrack(safeAudioFormat)
                } catch (e2: Exception) {
                    Log.e(TAG, "Both raw and sanitized audio format failed: ${e2.message}")
                    throw e1
                }
            }

            muxer.start()

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            val videoMaxInput = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 1024 * 1024
            val audioMaxInput = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 256 * 1024

            val videoBufferSizeResult = calculateBufferSize(videoMaxInput, DEFAULT_BUFFER_SIZE, MAX_VIDEO_BUFFER_BUDGET)
            if (videoBufferSizeResult.isFailure) {
                if (outputFile.exists()) {
                    try { outputFile.delete() } catch (_: Exception) {}
                }
                return Result.failure(videoBufferSizeResult.exceptionOrNull() ?: IllegalStateException("Unsupported video sample size"))
            }
            val audioBufferSizeResult = calculateBufferSize(audioMaxInput, 512 * 1024, MAX_AUDIO_BUFFER_BUDGET)
            if (audioBufferSizeResult.isFailure) {
                if (outputFile.exists()) {
                    try { outputFile.delete() } catch (_: Exception) {}
                }
                return Result.failure(audioBufferSizeResult.exceptionOrNull() ?: IllegalStateException("Unsupported audio sample size"))
            }

            val videoBufferSize = videoBufferSizeResult.getOrThrow()
            val audioBufferSize = audioBufferSizeResult.getOrThrow()

            val videoBuffer = ByteBuffer.allocateDirect(videoBufferSize)
            val audioBuffer = ByteBuffer.allocateDirect(audioBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var hasVideo = true
            var hasAudio = true
            var lastVideoTimeUs = -1L
            var lastAudioTimeUs = -1L
            var muxerStopped = false

            // Strict timestamp interleaving to prevent buffer overflows and ensure monotonic presentation times
            while (hasVideo || hasAudio) {
                val videoTime = if (hasVideo) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioTime = if (hasAudio) audioExtractor.sampleTime else Long.MAX_VALUE

                if (hasVideo && (!hasAudio || videoTime <= audioTime)) {
                    bufferInfo.offset = 0
                    bufferInfo.size = videoExtractor.readSampleData(videoBuffer, 0)
                    if (bufferInfo.size >= 0) {
                        val currentSampleTime = videoExtractor.sampleTime.coerceAtLeast(0L)
                        bufferInfo.presentationTimeUs = if (currentSampleTime > lastVideoTimeUs) {
                            currentSampleTime
                        } else {
                            lastVideoTimeUs + 1000L
                        }
                        lastVideoTimeUs = bufferInfo.presentationTimeUs
                        bufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(muxerVideoTrack, videoBuffer, bufferInfo)
                        hasVideo = videoExtractor.advance()
                    } else {
                        hasVideo = false
                    }
                } else if (hasAudio) {
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(audioBuffer, 0)
                    if (bufferInfo.size >= 0) {
                        val currentSampleTime = audioExtractor.sampleTime.coerceAtLeast(0L)
                        bufferInfo.presentationTimeUs = if (currentSampleTime > lastAudioTimeUs) {
                            currentSampleTime
                        } else {
                            lastAudioTimeUs + 1000L
                        }
                        lastAudioTimeUs = bufferInfo.presentationTimeUs
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, audioBuffer, bufferInfo)
                        hasAudio = audioExtractor.advance()
                    } else {
                        hasAudio = false
                    }
                }
            }

            // Select success only after muxer.stop() completes successfully
            muxer.stop()
            muxerStopped = true

            Log.d(TAG, "Muxing complete: ${outputFile.name} (size=${outputFile.length()} bytes)")
            return Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Muxing failed: ${e.message}", e)
            if (outputFile.exists()) {
                try { outputFile.delete() } catch (_: Exception) {}
            }
            return Result.failure(e)
        } finally {
            if (!muxerStopped) {
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping MediaMuxer: ${e.message}")
                }
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMuxer: ${e.message}")
            }
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
        }
    }

    private fun sanitizeVideoFormat(raw: MediaFormat): MediaFormat {
        val mime = raw.getString(MediaFormat.KEY_MIME) ?: "video/avc"
        val width = if (raw.containsKey(MediaFormat.KEY_WIDTH)) raw.getInteger(MediaFormat.KEY_WIDTH) else 1920
        val height = if (raw.containsKey(MediaFormat.KEY_HEIGHT)) raw.getInteger(MediaFormat.KEY_HEIGHT) else 1080
        val clean = MediaFormat.createVideoFormat(mime, width, height)

        for (i in 0..3) {
            val key = "csd-$i"
            if (raw.containsKey(key)) {
                raw.getByteBuffer(key)?.let { buf ->
                    val dup = buf.duplicate()
                    dup.position(0)
                    clean.setByteBuffer(key, dup)
                }
            }
        }
        if (raw.containsKey(MediaFormat.KEY_ROTATION)) {
            clean.setInteger(MediaFormat.KEY_ROTATION, raw.getInteger(MediaFormat.KEY_ROTATION))
        }
        if (raw.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            clean.setInteger(MediaFormat.KEY_FRAME_RATE, raw.getInteger(MediaFormat.KEY_FRAME_RATE))
        }
        return clean
    }

    private fun sanitizeAudioFormat(raw: MediaFormat): MediaFormat {
        val mime = raw.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
        val channels = if (raw.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) raw.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val sampleRate = if (raw.containsKey(MediaFormat.KEY_SAMPLE_RATE)) raw.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val clean = MediaFormat.createAudioFormat(mime, sampleRate, channels)

        for (i in 0..3) {
            val key = "csd-$i"
            if (raw.containsKey(key)) {
                raw.getByteBuffer(key)?.let { buf ->
                    val dup = buf.duplicate()
                    dup.position(0)
                    clean.setByteBuffer(key, dup)
                }
            }
        }
        if (raw.containsKey(MediaFormat.KEY_BIT_RATE)) {
            clean.setInteger(MediaFormat.KEY_BIT_RATE, raw.getInteger(MediaFormat.KEY_BIT_RATE))
        }
        return clean
    }
}
