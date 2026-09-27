package com.example.speeddown.extractor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.atomic.AtomicBoolean

data class YouTubeMediaStream(
    val format: String, // e.g. "M4A", "Opus", "MP4"
    val quality: String, // e.g. "128 kbps", "160 kbps", "720p", "1080p"
    val url: String,
    val isAudioOnly: Boolean,
    val isVideoOnly: Boolean = false,
    val itag: Int = 0,
    val sizeBytes: Long = 0L,
    val audioUrl: String? = null
)

data class YouTubeMediaInfo(
    val originalUrl: String,
    val id: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0L,
    val audioStreams: List<YouTubeMediaStream> = emptyList(),
    val videoStreams: List<YouTubeMediaStream> = emptyList()
)

object YouTubeExtractorEngine {
    private const val TAG = "YouTubeExtractorEngine"
    private val isInitialized = AtomicBoolean(false)

    fun init(context: Context) {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                NewPipe.init(NewPipeDownloaderImpl())
                Log.d(TAG, "NewPipeExtractor initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NewPipeExtractor", e)
                isInitialized.set(false)
            }
        }
    }

    fun isYouTubeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val clean = url.trim().lowercase()
        return clean.contains("youtube.com/watch") ||
                clean.contains("youtu.be/") ||
                clean.contains("youtube.com/shorts/") ||
                clean.contains("music.youtube.com/") ||
                clean.contains("m.youtube.com/") ||
                clean.contains("youtube.com/embed/") ||
                clean.contains("youtube.com/live/") ||
                clean.contains("youtube.com/v/")
    }

    @Suppress("DEPRECATION")
    suspend fun extract(url: String, context: Context? = null): Result<YouTubeMediaInfo> = withContext(Dispatchers.IO) {
        try {
            if (context != null) {
                init(context)
            } else if (!isInitialized.get()) {
                NewPipe.init(NewPipeDownloaderImpl())
                isInitialized.set(true)
            }

            val cleanUrl = url.trim()
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, cleanUrl)

            val audioList = mutableListOf<YouTubeMediaStream>()
            val videoList = mutableListOf<YouTubeMediaStream>()

            // 1. Extract Audio Streams (Music / Podcast)
            streamInfo.audioStreams?.forEach { audio: AudioStream ->
                val formatName = when (audio.format?.name?.uppercase()) {
                    "M4A", "WEBMA" -> if (audio.format?.name?.uppercase() == "M4A") "M4A" else "Opus"
                    else -> audio.format?.name ?: "Audio"
                }
                val qualityLabel = if (audio.averageBitrate > 0) {
                    "${audio.averageBitrate} kbps"
                } else {
                    "${audio.quality ?: "Standard"}"
                }
                audioList.add(
                    YouTubeMediaStream(
                        format = formatName,
                        quality = qualityLabel,
                        url = audio.content,
                        isAudioOnly = true,
                        itag = audio.itag,
                        sizeBytes = 0L // Populated or calculated if needed
                    )
                )
            }

            val bestM4aAudio = streamInfo.audioStreams?.filter {
                val fmt = it.format?.name?.uppercase() ?: ""
                fmt == "M4A" || it.itag == 140
            }?.maxByOrNull { it.averageBitrate }

            val bestOpusAudio = streamInfo.audioStreams?.filter {
                val fmt = it.format?.name?.uppercase() ?: ""
                fmt == "WEBMA" || fmt == "OPUS" || fmt == "WEBMA_OPUS" || it.itag in listOf(251, 250, 249)
            }?.maxByOrNull { it.averageBitrate }

            // Fallback audio if specific format isn't found
            val fallbackAudio = bestM4aAudio ?: bestOpusAudio ?: streamInfo.audioStreams?.firstOrNull()

            // 2. Extract Combined Video Streams (Video + Audio already muxed by YouTube)
            streamInfo.videoStreams?.forEach { video: VideoStream ->
                val res = video.resolution ?: "720p"
                val formatName = video.format?.name?.uppercase() ?: "MP4"
                videoList.add(
                    YouTubeMediaStream(
                        format = formatName,
                        quality = "$res (Audio Included)",
                        url = video.content,
                        isAudioOnly = false,
                        isVideoOnly = false,
                        itag = video.itag,
                        sizeBytes = 0L,
                        audioUrl = null // Pre-muxed by YouTube
                    )
                )
            }

            // 3. Extract High-Res Video Streams (1080p, 1440p, 4K) paired strictly with matching audio codec
            // Note: Filter out AV01 (itag 394..401) because Android native MediaMuxer cannot mux AV01
            val validVideoOnly = (streamInfo.videoOnlyStreams ?: emptyList()).filterNot { video ->
                val fmt = video.format?.name?.lowercase() ?: ""
                val codec = video.codec?.lowercase() ?: ""
                codec.startsWith("av01") || fmt.contains("av01") || video.itag in listOf(394, 395, 396, 397, 398, 399, 400, 401)
            }

            // Sort: highest resolution first, then prefer MP4 (H.264) over WebM (VP9)
            val sortedVideoOnly = validVideoOnly.sortedWith(
                compareByDescending<VideoStream> {
                    Regex("\\d+").find(it.resolution ?: "")?.value?.toIntOrNull() ?: 0
                }.thenByDescending {
                    val fmt = it.format?.name?.uppercase() ?: ""
                    fmt == "MP4" || fmt == "MPEG_4" || it.itag in listOf(137, 136, 135, 134, 133, 160)
                }
            )

            sortedVideoOnly.forEach { video: VideoStream ->
                val res = video.resolution ?: ""
                val isMp4 = video.format?.name?.uppercase() == "MP4" ||
                            video.format?.name?.uppercase() == "MPEG_4" ||
                            video.itag in listOf(137, 136, 135, 134, 133, 160)

                // CRITICAL FOR MEDIAMUXER:
                // MP4 (H.264) video MUST be paired with M4A (AAC) audio!
                // WebM (VP9) video MUST be paired with WebM (Opus) audio!
                // Mixing them causes MediaMuxer.addTrack() to throw IllegalArgumentException.
                val pairedAudio = if (isMp4) {
                    bestM4aAudio ?: fallbackAudio
                } else {
                    bestOpusAudio ?: fallbackAudio
                }

                val formatName = if (isMp4) "MP4" else "WEBM"
                val cleanRes = Regex("\\d+p").find(res)?.value ?: res

                // Only add if this resolution hasn't been added yet (MP4 preferred over WebM)
                if (cleanRes.isNotBlank() && videoList.none { it.quality.contains(cleanRes) } && pairedAudio != null) {
                    videoList.add(
                        YouTubeMediaStream(
                            format = formatName,
                            quality = "$cleanRes HD (Audio Included)",
                            url = video.content,
                            isAudioOnly = false,
                            isVideoOnly = true,
                            itag = video.itag,
                            sizeBytes = 0L,
                            audioUrl = pairedAudio.content
                        )
                    )
                }
            }

            // Sort audio: M4A first (preferred for mobile audio players), then highest bitrate
            val sortedAudio = audioList.sortedWith(
                compareByDescending<YouTubeMediaStream> { it.format == "M4A" }
                    .thenByDescending { it.quality }
            )

            // Sort video: highest resolution first
            val sortedVideo = videoList.sortedByDescending {
                val num = Regex("\\d+").find(it.quality)?.value?.toIntOrNull() ?: 0
                num
            }

            val bestThumbnail = streamInfo.thumbnails.maxByOrNull { it.width * it.height }?.url
                ?: streamInfo.thumbnails.firstOrNull()?.url

            val info = YouTubeMediaInfo(
                originalUrl = cleanUrl,
                id = streamInfo.id ?: "",
                title = streamInfo.name ?: "YouTube Media",
                uploader = streamInfo.uploaderName ?: "YouTube Creator",
                thumbnailUrl = bestThumbnail,
                durationSeconds = streamInfo.duration,
                audioStreams = sortedAudio,
                videoStreams = sortedVideo
            )

            Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting YouTube stream info: ${e.message}", e)
            Result.failure(e)
        }
    }
}
