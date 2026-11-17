# Verification status

What has actually been executed for this build, and what still needs real Pixel 9 hardware.

## Executed (build container, no physical device)

| Check | Result |
|---|---|
| Compile (Kotlin, C++ for arm64-v8a + x86_64, AIDL, Room/KSP) | ✅ `assembleDebug`, `assembleRelease` succeed |
| Dependency resolution (AGP 9.5.1, Kotlin 2.4.21, Compose BOM 2026.09.00, Gradle 9.9.1, compileSdk 37) | ✅ |
| Android Lint (`lintDebug`, abortOnError) | ✅ 0 errors (remaining warnings are stylistic / documented) |
| JVM unit tests (`testDebugUnitTest`) | ✅ 16/16 pass |
| R8-minified release APK | ✅ 4.2 MB, installs and runs (validates keep rules for Shizuku UserService, JNI, Room) |
| Install + launch on Android 14 x86_64 emulator (software CPU emulation, no KVM) | ✅ onboarding → dashboard → monitor → lab, no crashes |
| Instrumentation: `NativeLabTest` (5), `DatabaseTest` (2) on emulator | ✅ 7/7 pass (8/8 including UI smoke test) |
| Native benchmark end-to-end in the running release app (memory bandwidth → conditions → Room → UI) | ✅ |
| Instrumentation: `UiSmokeTest` (onboarding → Dashboard → Monitor → Lab) | ✅ passes (after fixing a test bug: the onboarding button needed `performScrollTo()`; timeouts raised for slow emulators) |
| Android 17 (API 37) emulator | ⚠️ Booted, but its system_server crash-looped under software emulation (watchdog), so on-device tests ran on API 34 instead |

Bugs found and fixed through this testing: text colour outside `Surface`s rendered near-black on dark
backgrounds; filter-chip and chart-legend overflow on narrow screens; sub-MHz cpufreq values
displayed as "0 MHz".

## Requires real Pixel 9 hardware (not verifiable here)

* Tensor G4 topology labels (Cortex‑X4 / A720 / A520) and per-cluster frequencies — logic is unit-tested against `/proc/cpuinfo` formats, but the Pixel kernel's actual readability of cpufreq sysfs must be confirmed.
* Mali‑G715 Vulkan probe, Vulkan compute/fill and GLES benchmark numbers (emulator uses SwiftShader).
* Thermal headroom values, thermal HAL sensor list (`dumpsys thermalservice` via Shizuku), throttling behaviour in sustained tests.
* Battery current/power, cycle count, charge-counter capacity estimate, state-of-health exposure with `BATTERY_STATS`.
* Shizuku flows: UserService binding, `pm trim-caches`, `pm clear --cache-only`, `cmd package bg-dexopt-job`, `cmd power set-mode`, `cmd game mode`, refresh-rate settings, and restore of every reversible change.
* Storage cleaning/recycle bin on real shared storage with All files access; USB OTG and external display (DisplayPort) presentation.
* Battery overhead of telemetry sampling and PixelForge's idle CPU/memory footprint over a day.
* Release signing with a real keystore (CI falls back to the debug key when no secret is set).
