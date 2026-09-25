# SpeedDown - High-Performance Multi-Threaded Android Download Manager

SpeedDown is a modern, ultra-fast download manager for Android built with Kotlin, Jetpack Compose, and OkHttp. It accelerates downloads by dividing files into dynamic segments using HTTP Range requests with up to 100 parallel worker threads.

---

## ⚡ Key Capabilities

- **Ultra High-Speed Multi-Threading**:
  - Dynamically breaks files into up to 100 concurrent chunk streams with HTTP Range requests.
  - Dedicated coroutine dispatcher with high parallelism (`limitedParallelism(128)`).
  - Fine-grained thread slider (1 to 100 threads) with quick 1-tap presets (`8T`, `16T`, `32T`, `64T`, `100T`).

- **Seamless Pause & Resume**:
  - Automatically preserves `.part` file chunks on pause or network interruption.
  - Seamless resumption from exact downloaded byte offsets without re-downloading existing chunks.
  - Immediate socket cancellation on user pause or cancel without blocking threads.

- **Dynamic Gesture Tab Navigation**:
  - Four dedicated filter tabs: **All**, **Queued**, **Downloading**, and **Saved**.
  - Left / Right swipe gesture navigation powered by `HorizontalPager` synchronized with `TabRow`.
  - Real-time download counter badges on each category tab.

- **Smart Deletion Safety**:
  - Dual-option deletion confirmation dialog:
    - **Remove from App Only**: Clears item from download history while keeping downloaded files safe in local storage.
    - **Delete File & from App**: Permanently purges target file and all temporary chunks (`.part*`) from device storage.

- **Real-Time Telemetry & Progress**:
  - EMA (Exponential Moving Average) smoothed download speed metering.
  - Accurate remaining time (ETA) calculation.
  - Informative error details dialog for network and HTTP status codes (403 direct download blocked, 404, timeouts, storage full).

- **Reliable Background Execution**:
  - Android Foreground Service with notification progress updates.
  - Boot receiver support for persistent task recovery.

---

## 🏗️ Architecture

SpeedDown follows clean Android MVVM architecture:

```
SpeedDown
├── engine/
│   └── MultiThreadDownloader   # HTTP Range probing, multi-part chunking, socket cancellation, EMA speed
├── data/
│   ├── DownloadItem            # Download state model with status, progress, speed, ETA, and threads
│   └── DownloadStore           # Persistent local store using Jetpack DataStore Preferences
├── service/
│   └── DownloadService         # Foreground Service with notification controls & active job queue
├── ui/
│   ├── DownloadManagerScreen   # Jetpack Compose UI, HorizontalPager tabs, cards, and dialogs
│   └── main/                   # App navigation and top-level scaffolding
└── theme/                      # Material 3 dynamic dark theme
```

---

## 🛠️ Tech Stack & Dependencies

- **Language**: Kotlin 2.3+
- **UI Toolkit**: Jetpack Compose (Material 3) with Compose Foundation Pager
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
