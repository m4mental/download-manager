# SpeedDown - High-Performance Multi-Threaded Android Download Manager & Browser

SpeedDown is a high-performance Android download accelerator and privacy-centric web browser built with Kotlin, Jetpack Compose, Mozilla GeckoView, and OkHttp. It accelerates downloads by dividing files into dynamic segments using HTTP Range requests with up to 100 parallel worker threads, and features an integrated browser engine with built-in uBlock Origin ad-blocking.

---

## ⚡ Key Capabilities

### 🚀 Ultra High-Speed Multi-Threading & Integrity
- **Dynamic Segment Chunking**: Dynamically breaks files into up to 100 concurrent chunk streams with HTTP Range requests.
- **HTTP Transfer Integrity**: Enforces HTTP 206 `Content-Range` byte-boundary matching, detects HTTP 200 responses on ranged requests to safely restart single-threaded from byte zero, bounds writes, and retries short parts.
- **Validator & Partition Tracking**: Persists `ETag`, `Last-Modified`, content length, and actual thread counts in SQLite. Sends `If-Range` validators on resume and cleanly resets if the remote file or partition layout changed.
- **Dedicated Parallel Engine**: Custom coroutine dispatcher with high parallelism (`limitedParallelism(128)`).
- **Fine-Grained Worker Slider**: Flexible 1 to 100 threads slider with 1-tap presets (`8T`, `16T`, `32T`, `64T`, `100T`).
- **Resilient Worker Lifecycle**: Tracks active coroutine jobs per download ID with cancellation tokens, cleanly joining and tearing down previous runs before new attempts.

### 🎬 Streaming, Muxing & Media Extraction
- **HLS Adaptive Stream Downloader (`HlsDownloader`)**: Downloads M3U8 playlists with segment verification, AES-128 decryption (with IV left-padding and media-sequence derivation), `EXT-X-MAP` initialization segments, and `EXT-X-BYTERANGE` range fetching.
- **Local Loopback Stream Server (`LocalStreamServer`)**: Binds securely to `127.0.0.1` with per-session token validation. Streams downloaded parts directly to external media players with HTTP Range and suffix-range support, returning HTTP 416 for unsatisfiable requests.
- **Hardware-Accelerated Muxing (`MediaMuxerEngine`)**: Asynchronously combines separate video (`.video.tmp`) and audio (`.audio.tmp`) streams into standard MP4 containers, cleaning up source artifacts only on confirmed success.
- **YouTube Media Extraction (`YouTubeExtractorEngine`)**: Resolves YouTube streams with strict codec compatibility (MP4 + M4A, WebM + Opus), validated host matching, and codec/framerate preservation.
- **Torrent Metadata & Webseeds (`TorrentEngine`)**: Downloads and caches `.torrent` metadata files from magnet URIs, validates SHA-1 infohashes against bencoded `info` dictionaries, and extracts HTTP `url-list` webseed mirrors via an internal Bencode parser rather than running an active peer swarm client.

### 🌐 Built-in Mozilla GeckoView Browser
- **Mozilla GeckoView Core**: Powered by Mozilla GeckoView 130 (Firefox engine) for standards compliance, modern web features, and high-performance page rendering.
- **Built-in uBlock Origin WebExtension**: Ships with pre-installed uBlock Origin WebExtension (`uBlock0@raymondhill.net`) directly controlled via `WebExtensionController` to eliminate ads, trackers, and popups at the engine level.
- **Privacy & DNS Controls**: Integrated DNS-over-HTTPS (Cloudflare, Google, AdGuard, Quad9, and Custom DoH) with strict non-fallback failure handling and UDP transaction ID verification. In incognito mode, third-party favicon lookups and disk caching are completely disabled.
- **Smart Media Sniffer**: Automatic background detection for embedded media, direct video downloads, and M3U8 HLS streaming playlists.

### 📱 Lifecycle, Quick Settings & Platform Architecture
- **Quick Settings Tile (`SpeedDownTileService`)**: Quick access tile with API 34+ `startActivityAndCollapse(PendingIntent)` compatibility.
- **Foreground Service & Boot Handling (`DownloadService` / `BootReceiver`)**: Immediate foreground promotion with notification controls. Android 15+ (API 35+) boot resume uses interactive notifications rather than direct background service starts to honor platform execution restrictions.
- **FGS Timeout Protection**: Overrides `onTimeout` on API 35+ to safely pause active transfers, persist state to SQLite, and display a resume notification.
- **FileProvider Security**: Scoped `provider_paths.xml` allowing only public `Download/` and app-external downloads with strict canonical path containment checks.

---

## 🏗️ Architecture

SpeedDown follows a clean, modular Android MVVM architecture:

```
SpeedDown
├── engine/
│   ├── MultiThreadDownloader   # HTTP Range verification, chunking, validator persistence, cancel-and-join
│   ├── HlsDownloader           # HLS playlist parsing, AES-128 decryption, EXT-X-MAP, segment merging
│   ├── LocalStreamServer       # Secure 127.0.0.1 HTTP range streaming server with token authentication
│   ├── MediaMuxerEngine        # Hardware-accelerated audio/video MP4 multiplexer
│   ├── TorrentEngine           # Magnet metadata fetcher, SHA-1 infohash verification, bencode url-list parser
│   ├── GeckoEngine             # Shared Mozilla GeckoRuntime lifecycle, profile setup, & WebExtension host
│   ├── GeckoTabSession         # GeckoSession delegates (Navigation, Content, Prompt, Progress, Fullscreen)
│   ├── SecureDnsHelper         # Strict DNS-over-HTTPS resolver and UDP transaction validator
│   └── AdBlockEngine           # Domain matching, host filtering, and rogue redirect shield
├── data/
│   ├── DownloadItem            # Download state model (status, progress, speed, ETA, ETag, threads)
│   ├── DownloadStore           # Serialized download store backed by SQLite (DownloadDatabaseHelper)
│   ├── BrowserSettings         # Browser configurations (search engines, shortcuts, desktop mode, DoH)
│   └── FaviconLoader           # Favicon loader with strict incognito privacy safeguards
├── service/
│   ├── DownloadService         # Foreground Service with FGS timeout handling & wake lock control
│   ├── BootReceiver            # Device boot receiver with API 35+ notification-based resume
│   └── SpeedDownTileService    # Quick Settings tile service (API 34+ PendingIntent support)
├── extractor/
│   └── YouTubeExtractorEngine  # YouTube stream extraction with format pairing & host validation
├── ui/
│   ├── DownloadManagerScreen   # Jetpack Compose download list, tab row, statistics, and dialogs
│   ├── browser/
│   │   ├── BrowserScreen       # Compose browser UI, address bar, tabs sheet, & GeckoView bridge
│   │   └── BrowserHomeScreen   # Speed dial shortcuts with incognito-aware favicon loading
│   └── theme/                  # Material 3 dynamic dark theme
```

---

## 🛠️ Tech Stack & Dependencies

- **Language**: Kotlin 2.3+
- **UI Toolkit**: Jetpack Compose (Material 3) with Compose Foundation
- **Browser Engine**: Mozilla GeckoView (`org.mozilla.geckoview:geckoview-omni:130.0.20240913135723`)
- **Ad-Blocking**: Built-in uBlock Origin WebExtension + AdBlockEngine domain rules
- **Networking**: OkHttpClient with custom connection pooling and DNS-over-HTTPS
- **Stream Resolution**: NewPipeExtractor for YouTube format resolution
- **Concurrency**: Kotlin Coroutines (`Dispatchers.IO.limitedParallelism(128)`) & Flow
- **Persistence**: SQLite (`DownloadDatabaseHelper`) + AndroidX DataStore Preferences (`BrowserSettings`)
- **Target SDK**: Android 16 (API 36) / Minimum SDK: Android 8.0 (API 26)

---

## 🚀 Building & Installing

### Prerequisites
- JDK 17 or higher
- Android SDK (API 36)
- Gradle 9.0+

### Run Unit Tests & Lint
```bash
./gradlew testDebugUnitTest lintDebug
```

### Build Debug APK
```bash
./gradlew assembleDebug
```

### Build Signed Release APK
Place your non-empty release keystore file at `keystore/speeddown-release.jks`, configure the signing environment variables (`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), and run:
```bash
./gradlew assembleRelease
```

---

## 📜 License
MIT License. See [LICENSE](LICENSE) for details.
