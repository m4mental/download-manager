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

    fun isValidYouTubeHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val cleanHost = host.trim().lowercase()
        return cleanHost == "youtube.com" ||
                cleanHost.endsWith(".youtube.com") ||
                cleanHost == "youtu.be" ||
                cleanHost.endsWith(".youtu.be")
    }

    fun isYouTubeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val clean = url.trim()
        val withScheme = if (!clean.contains("://")) "https://$clean" else clean
        val host = try {
            java.net.URI(withScheme).host
        } catch (_: Exception) {
            return false
        }
        return isValidYouTubeHost(host)
    }

    fun getVideoDeduplicationKey(resolution: String?, fps: Int, format: String?, codec: String?): String {
        val cleanRes = Regex("\\d+p").find(resolution ?: "")?.value ?: (resolution ?: "unknown")
        val cleanFmt = format?.uppercase() ?: "UNKNOWN"
        val cleanCodec = codec?.lowercase() ?: ""
        val fpsPart = if (fps > 0) "${fps}fps" else "default"
        return "${cleanRes}_${fpsPart}_${cleanFmt}_$cleanCodec"
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
            val seenVideoKeys = mutableSetOf<String>()

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
                        sizeBytes = 0L
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

            // 2. Extract Combined Video Streams (Video + Audio already muxed by YouTube)
            streamInfo.videoStreams?.forEach { video: VideoStream ->
                val res = video.resolution ?: "720p"
                val formatName = video.format?.name?.uppercase() ?: "MP4"
                val fpsStr = if (video.fps > 30) " ${video.fps}fps" else ""
                val dedupKey = getVideoDeduplicationKey(res, video.fps, formatName, video.codec)
                if (seenVideoKeys.add(dedupKey)) {
                    videoList.add(
                        YouTubeMediaStream(
                            format = formatName,
                            quality = "$res$fpsStr (Audio Included)",
                            url = video.content,
                            isAudioOnly = false,
                            isVideoOnly = false,
                            itag = video.itag,
                            sizeBytes = 0L,
                            audioUrl = null // Pre-muxed by YouTube
                        )
                    )
                }
            }

            // 3. Extract High-Res Video Streams (1080p, 1440p, 4K) paired strictly with matching audio codec
            // Note: Filter out AV01 (itag 394..401) because Android native MediaMuxer cannot mux AV01
            val validVideoOnly = (streamInfo.videoOnlyStreams ?: emptyList()).filterNot { video ->
                val fmt = video.format?.name?.lowercase() ?: ""
                val codec = video.codec?.lowercase() ?: ""
                codec.startsWith("av01") || fmt.contains("av01") || video.itag in listOf(394, 395, 396, 397, 398, 399, 400, 401)
            }

            // Sort: highest resolution first, then highest fps, then prefer MP4 (H.264) over WebM (VP9)
            val sortedVideoOnly = validVideoOnly.sortedWith(
                compareByDescending<VideoStream> {
                    Regex("\\d+").find(it.resolution ?: "")?.value?.toIntOrNull() ?: 0
                }.thenByDescending {
                    it.fps
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

                // CRITICAL FOR MEDIAMUXER & STRICT PAIRING:
                // MP4 (H.264) video MUST be paired ONLY with M4A (AAC) audio!
                // WebM (VP9) video MUST be paired ONLY with WebM (Opus) audio!
                // Do NOT offer video-only option without compatible audio.
                val pairedAudio = if (isMp4) bestM4aAudio else bestOpusAudio
                if (pairedAudio != null) {
                    val formatName = if (isMp4) "MP4" else "WEBM"
                    val dedupKey = getVideoDeduplicationKey(res, video.fps, formatName, video.codec)

                    // Preserve codec and frame-rate variants during deduplication
                    if (seenVideoKeys.add(dedupKey)) {
                        val cleanRes = Regex("\\d+p").find(res)?.value ?: res
                        val fpsStr = if (video.fps > 30) " ${video.fps}fps" else ""
                        videoList.add(
                            YouTubeMediaStream(
                                format = formatName,
                                quality = "$cleanRes$fpsStr HD (Audio Included)",
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
            }

            // Sort audio: M4A first (preferred for mobile audio players), then highest bitrate
            val sortedAudio = audioList.sortedWith(
                compareByDescending<YouTubeMediaStream> { it.format == "M4A" }
                    .thenByDescending { it.quality }
            )

            // Sort video: highest resolution first, then highest fps, then MP4 over WEBM
            val sortedVideo = videoList.sortedWith(
                compareByDescending<YouTubeMediaStream> {
                    Regex("\\d+").find(it.quality)?.value?.toIntOrNull() ?: 0
                }.thenByDescending {
                    Regex("(\\d+)fps").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }.thenBy {
                    if (it.format == "MP4") 0 else 1
                }
            )

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
