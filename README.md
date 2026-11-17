# PixelForge

**A Google Pixel 9 control centre: intelligent cleaning, hardware diagnostics, benchmarking and
genuine optimisation — engineered within Android's real APIs and security model.**

PixelForge combines a storage cleaner, file manager, CPU/GPU/RAM/battery/thermal monitor, a native
C++/Vulkan benchmarking lab, a privacy dashboard, network diagnostics, performance profiles and an
automation engine in one Kotlin + Jetpack Compose app. It is written for the Pixel 9 (Google Tensor
G4, Mali‑G715 GPU, Android 17) and runs on any Android 14+ arm64 or x86_64 device.

> **Honesty contract.** PixelForge never fakes an optimisation. Every action is real, measured and
> tagged **Safe**, **Advanced** (needs Shizuku/ADB/special access) or **Unavailable** (blocked by
> Android's security model). There are no “RAM boosters”, no invented battery-health numbers, no
> malware-scanner claims and no cosmetic scores.

## Feature map

| Area | What it does | How (API) | Tier |
|---|---|---|---|
| Dashboard | Health index (transparent formula), CPU/RAM/storage/battery gauges, temperature, GPU, uptime, network, memory pressure, warnings, live sparklines | `ActivityManager`, `/proc/meminfo`, cpufreq sysfs, `BatteryManager`, `PowerManager` thermal APIs, `StorageStatsManager` | Safe |
| Smart Optimize | Analyse → recommend → confirm → execute → **measure before/after** → save report | Rules engine (`OptimizationEngine`) | Safe / Advanced |
| Cleaner | Quick / Deep / Media / APK / Large scans: temp & partial files, thumbnail caches, obsolete APKs (version-compared with installed apps), empty folders, old downloads/screenshots, backup leftovers, logs, large files, **SHA‑256-verified duplicates**, **perceptual-hash similar photos**, extracted archives, squarified storage treemap | `MANAGE_EXTERNAL_STORAGE`, `PackageManager.getPackageArchiveInfo`, `MediaStore.loadThumbnail` | Safe |
| Recycle bin | Cleaned files are *renamed* into a hidden same-volume bin, restorable for N days | `File.renameTo`, Room | Safe |
| App caches | Own cache; all apps via `pm trim-caches`; per-app `pm clear --cache-only` | Shizuku | Advanced |
| RAM | Used/available/cached, LMK threshold, zRAM, kernel PSI, trends, low-memory events, own-heap diagnostics, per-process PSS | `MemoryInfo`, `/proc/pressure/memory`, `dumpsys meminfo` (privileged) | Safe / Advanced |
| CPU (Tensor G4) | Cluster topology (X4 / A720 / A520 via MIDR part IDs), per-core frequency, governor, throttling caps, true utilisation when `/proc/stat` is reachable (else a *labelled* frequency estimate) | `/proc/cpuinfo`, cpufreq sysfs, Shizuku for `/proc/stat` | Safe / Advanced |
| GPU & Vulkan | Vulkan instance/device/driver/conformance, limits, features, formats (ASTC/ETC2/BC…), heaps, queues, extensions; GLES/EGL renderer, limits, extensions; live Mali clock when readable | Native `vkGetPhysicalDevice*` via dlopen, EGL14/GLES3x | Safe |
| Battery | Level, status, source, temperature, voltage, current, power (W), charge counter, cycle count, full-capacity **estimate**, state-of-health only when the system exposes it, discharge history & projection, per-app mAh from batterystats | `BatteryManager`, telemetry DB, `dumpsys batterystats` (privileged) | Safe / Advanced |
| Thermal | Thermal status, headroom + 10 s forecast, throttling interpretation, HAL sensors (CPU/GPU/TPU/skin/modem), 24 h history, thermal guard for benchmarks | `PowerManager.getThermalHeadroom`, `dumpsys thermalservice` (privileged) | Safe / Advanced |
| Benchmark Lab | CPU single (prime + each cluster) & multi-thread (int / FP32 SGEMM / sort), memory bandwidth & latency curve, storage (fsync write, O_DIRECT read, random 4K IOPS), GLES fill/geometry/texture/compute, Vulkan fill + compute, sustained CPU/GPU throttling, UI frame pacing, cold app-launch timing; conditions recorded; regression detection; JSON/CSV export | C++20 (NDK), Vulkan, GLES 3.1, `am start -W` (Shizuku) | Safe / Advanced |
| Apps | Inventory, search/sort/filter, sizes, usage, permissions, installer, battery exemption, standby bucket; open/info/uninstall; disable/enable, background restriction, ART `speed-profile` compile, Game Mode, one-off force-stop — all reversible and audited | `PackageManager`, `StorageStatsManager`, `UsageStatsManager`, Shizuku | Safe / Advanced |
| Files | Volumes (internal, SD, **USB OTG**), any SAF location, copy/move/rename/delete, new folder, multi-select, ZIP create/extract (zip-slip safe), previews (image/text), metadata, search, sort, hidden files, favourites, recents, share | `java.io` + `DocumentFile`, `FileProvider` | Safe |
| Privacy | Posture (patch age, Mainline version, lock, encryption, dev options, USB/wireless ADB, verified boot, Private DNS, VPN), permission groups, accessibility, notification listeners, device admins, install sources, VPN providers | Public framework APIs | Safe |
| Network | Transport, validation, metering, link estimates, addresses, DNS, Private DNS, Wi‑Fi (RSSI history, band, Wi‑Fi 6E/7 standard), cellular, TCP + ICMP latency/jitter/loss, DNS timing, **opt-in** speed test, monthly data usage per app | `ConnectivityManager`, `WifiInfo`, `NetworkStatsManager` | Safe |
| Profiles | Max Performance, Balanced, Battery Saver, Gaming, Development, Desktop; each step verified and **restorable** | Settings provider / `cmd power` / `cmd game` via Shizuku or ADB-granted `WRITE_SECURE_SETTINGS` | Advanced |
| Automation | Telemetry sampling, daily maintenance, low-storage / high-temp / low-battery alerts, dev diagnostics, desktop auto-present | WorkManager (no persistent service) | Safe |
| External display | Live dashboard on a USB‑C DisplayPort monitor without mirroring; adaptive sidebar layout, keyboard shortcuts (Ctrl+1…9, Esc) | `Presentation`, `DisplayManager` | Safe |
| Device | Tensor G4 detection, CPU/GPU/RAM/storage/displays (refresh modes, HDR), connectivity (6E/7/UWB/NFC/eSIM/USB host), platform features, sensors — each marked Supported / Not supported / Not exposed | `PackageManager` features, `DisplayManager`, `SensorManager` | Safe |

See [`docs/TECHNICAL.md`](docs/TECHNICAL.md) for architecture and [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md)
for what Android does not allow and the supported alternative PixelForge uses instead.

## Install

1. Download `pixelforge-release-apk` from the latest **Actions** run (or a GitHub Release for `v*` tags).
2. Install it (`adb install app-release.apk` or open the file on the phone and allow the installer).
3. On first launch grant, as you wish: **All files access** (cleaner/files), **Usage access** (app sizes,
   usage, data usage) and **Notifications** (alerts). Everything else works without them.

### Optional advanced access (no root)

* **Shizuku** — start it via *Wireless debugging*, then *More → Advanced → Grant permission*.
  PixelForge then has `adb shell` rights (package/settings/dumpsys), never root.
* **One-off ADB grants** (from a computer):
  ```sh
  adb shell pm grant dev.pixelforge android.permission.WRITE_SECURE_SETTINGS   # profiles
  adb shell pm grant dev.pixelforge android.permission.DUMP                    # sensors, per-process RAM, batterystats
  adb shell pm grant dev.pixelforge android.permission.BATTERY_STATS           # state of health (if exposed)
  ```

## Build

Requirements: JDK 17+, Android SDK Platform 37 (Android 17), Build-Tools 36.1.0, NDK 30.0.16248370, CMake 4.1.2.

```sh
./gradlew :app:assembleDebug        # debug APK  (app/build/outputs/apk/debug)
./gradlew :app:assembleRelease      # minified release APK (app/build/outputs/apk/release)
./gradlew :app:lintDebug :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest   # needs a device/emulator
```

Release signing: create `keystore.properties` (git-ignored) with `storeFile`, `storePassword`,
`keyAlias`, `keyPassword`, or set CI secrets `PF_KEYSTORE_BASE64`, `PF_KEYSTORE_PASSWORD`,
`PF_KEY_ALIAS`, `PF_KEY_PASSWORD`. Without them, release builds are signed with the debug key so
they remain installable for testing (not suitable for store distribution).

Shaders for the Vulkan lab are embedded as SPIR‑V headers; regenerate with `tools/compile_shaders.sh`
(requires `glslangValidator`).

## Stack

Kotlin 2.4 · Jetpack Compose (BOM 2026.09) · Material 3 · Coroutines/StateFlow · Room 2.8 (KSP) ·
DataStore · WorkManager · Navigation Compose · NDK r30 / C++20 · Vulkan 1.3 · OpenGL ES 3.1 ·
Shizuku API 13 · AGP 9.5 · Gradle 9.9 · compileSdk/targetSdk 37 · minSdk 34.
