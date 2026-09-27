package com.example.speeddown.extractor

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Zero-dependency native hardware muxer utilizing Android's built-in MediaMuxer and MediaExtractor.
 * Combines separate YouTube video and audio tracks into a single container (MP4 / WebM)
 * in 1-2 seconds with zero re-encoding and zero quality loss.
 */
object VideoAudioMuxer {
    private const val TAG = "VideoAudioMuxer"

    suspend fun mux(videoFile: File, audioFile: File, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || !audioFile.exists() || videoFile.length() == 0L || audioFile.length() == 0L) {
            Log.e(TAG, "Input files missing or empty: video=${videoFile.length()}b, audio=${audioFile.length()}b")
            return@withContext false
        }

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var tempOut: File? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            // 1. Locate Video Track
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

            // 2. Locate Audio Track
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

            if (videoTrackIndex < 0 || audioTrackIndex < 0 || videoFormat == null || audioFormat == null) {
                Log.e(TAG, "Track detection failed: videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex")
                return@withContext false
            }

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            // Select container format: WebM (VP8/VP9) or MPEG-4 (H.264/AAC)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val isWebm = videoMime.contains("vp8", ignoreCase = true) || videoMime.contains("vp9", ignoreCase = true)
            val muxerFormat = if (isWebm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4

            tempOut = File(outputFile.parentFile, "${outputFile.name}.muxing")
            if (tempOut.exists()) tempOut.delete()

            muxer = MediaMuxer(tempOut.absolutePath, muxerFormat)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxVideoBuf = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 2 * 1024 * 1024

            val maxAudioBuf = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 512 * 1024

            val bufferSize = maxOf(maxVideoBuf, maxAudioBuf, 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // Pump video samples
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // Pump audio samples
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null

            videoExtractor.release()
            videoExtractor = null

            audioExtractor.release()
            audioExtractor = null

            // Clean up temporary parts
            videoFile.delete()
            audioFile.delete()

            if (outputFile.exists()) outputFile.delete()
            tempOut.renameTo(outputFile)

            Log.d(TAG, "Native hardware muxing successful: ${outputFile.name} (${outputFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Muxing failed: ${e.message}", e)
            tempOut?.delete()
            false
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
