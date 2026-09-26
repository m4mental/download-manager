# SpeedDown - High-Performance Multi-Threaded Android Download Manager & Browser

SpeedDown is a high-performance Android download accelerator and privacy-centric web browser built with Kotlin, Jetpack Compose, Mozilla GeckoView, and OkHttp. It accelerates downloads by dividing files into dynamic segments using HTTP Range requests with up to 100 parallel worker threads, and features an integrated browser engine with built-in uBlock Origin ad-blocking.

---

## ⚡ Key Capabilities

### 🚀 Ultra High-Speed Multi-Threading
- **Dynamic Segment Chunking**: Dynamically breaks files into up to 100 concurrent chunk streams with HTTP Range requests.
- **Dedicated Parallel Engine**: Custom coroutine dispatcher with high parallelism (`limitedParallelism(128)`).
- **Fine-Grained Worker Slider**: Flexible 1 to 100 threads slider with 1-tap presets (`8T`, `16T`, `32T`, `64T`, `100T`).
- **Resilient Pause & Resume**: Automatically preserves partial chunks (`.part`) across pauses, app restarts, or network disruptions, resuming from exact downloaded byte offsets.
- **Instant Socket Cancellation**: Immediate, clean socket teardown on pause or cancellation without deadlocks or stalled threads.

### 🌐 Built-in Mozilla GeckoView Browser
- **Mozilla GeckoView Core**: Powered by Mozilla GeckoView (Firefox engine) for standards compliance, modern web features, and high-performance page rendering.
- **Built-in uBlock Origin WebExtension**: Ships with pre-installed uBlock Origin WebExtension to eliminate intrusive network ads, trackers, popups, and crypto-miners at the engine level.
- **Rogue Redirect & Fake Link Shield**: Real-time prompt and navigation interceptors that block ad traps, deceptive redirect hops, and unauthorized popunders before they load.
- **Smart Media Sniffer**: Automatic background detection for embedded media, direct video downloads, and M3U8 HLS streaming playlists with multi-bitrate quality selection.
- **Immersive HTML5 Video Fullscreen**: Full edge-to-edge fullscreen video playback with automatic sensor landscape rotation, transient system bar hiding, and clean gesture exit.
- **Seamless Multi-Tab & Rotation State**: Multi-tab browsing with persistent tab restoration, incognito browsing, desktop mode toggle, and uninterrupted screen orientation handling.

### 📱 Modern User Experience & Telemetry
- **Dynamic Gesture Tab Navigation**: Filter tabs (**All**, **Queued**, **Downloading**, **Saved**) powered by `HorizontalPager` with real-time badges.
- **Live Telemetry**: EMA (Exponential Moving Average) smoothed real-time speed metrics, ETA calculations, and diagnostic error insights.
- **Smart Storage Management**: Dual-option deletion confirmation (remove history or permanently purge files/chunks from storage).
- **Reliable Background Execution**: Android Foreground Service with persistent notifications and auto-resume capabilities on device reboot.

---

## 🏗️ Architecture

SpeedDown follows a clean, modular Android MVVM architecture:

```
SpeedDown
├── engine/
│   ├── MultiThreadDownloader   # HTTP Range probing, multi-part chunking, socket cancellation, EMA speed
│   ├── GeckoEngine             # Shared Mozilla GeckoRuntime lifecycle, profile setup, & WebExtension host
│   ├── GeckoTabSession         # GeckoSession delegates (Navigation, Content, Prompt, Progress, Fullscreen)
│   └── AdBlockEngine           # Domain matching, host filtering, and rogue redirect shield
├── data/
│   ├── DownloadItem            # Download state model (status, progress, speed, ETA, threads)
│   ├── DownloadStore           # Persistent local download store using Jetpack DataStore Preferences
│   └── BrowserSettingsStore    # Persistent browser configurations (search engines, shortcuts, desktop mode)
├── service/
│   └── DownloadService         # Foreground Service with notification controls & active job queue
├── ui/
│   ├── DownloadManagerScreen   # Jetpack Compose download list, tab row, statistics, and dialogs
│   ├── browser/
│   │   ├── BrowserScreen       # Compose browser UI, address bar, tabs sheet, & GeckoView bridge
│   │   └── BrowserHomeScreen   # Customizable speed dial shortcuts and search engine integration
│   └── theme/                  # Material 3 dynamic dark theme
```

---

## 🛠️ Tech Stack & Dependencies

- **Language**: Kotlin 2.3+
- **UI Toolkit**: Jetpack Compose (Material 3) with Compose Foundation Pager & Accompanist
- **Browser Engine**: Mozilla GeckoView (`org.mozilla.geckoview:geckoview-omni`)
- **Ad-Blocking**: Built-in uBlock Origin WebExtension + AdBlockEngine domain rules
- **Networking**: OkHttpClient with custom connection pooling (120 idle connections)
- **Concurrency**: Kotlin Coroutines (`Dispatchers.IO.limitedParallelism(128)`) & Flow
- **Persistence**: AndroidX DataStore Preferences + KotlinX Serialization
- **Target SDK**: Android 16 (API 36) / Minimum SDK: Android 8.0 (API 26)

---

## 🚀 Building & Installing

### Prerequisites
- JDK 17 or higher
- Android SDK (API 36)
- Gradle 9.0+

### Build Debug APK
```bash
./gradlew assembleDebug
```

### Build Signed Release APK (R8 Minified)
```bash
./gradlew assembleRelease
```

---

## 📜 License
MIT License. See [LICENSE](LICENSE) for details.
