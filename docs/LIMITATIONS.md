# What Android does not allow — and what PixelForge does instead

| Requested capability | Reality on Android 14–17 | PixelForge's genuine alternative |
|---|---|---|
| Delete other apps' caches / data | Sandbox: `Android/data`, `/data/data` are private | Own cache (Safe); `pm trim-caches` and `pm clear --cache-only` via Shizuku (Advanced); deep links to each app's storage page |
| “Free RAM” by killing apps | Killing apps triggers restarts, costing CPU/battery; Android's LMK already manages memory | Memory pressure (PSI / LMK headroom) analysis, low-memory events, per-process PSS (privileged), advice; single user-requested force-stop only |
| CPU/GPU frequency, governor, overclock | Requires root / kernel access | Read-only frequency & throttling telemetry; Game Mode, fixed performance mode, Battery Saver, refresh rate (Advanced); sustained-performance mode for own window |
| Replace GPU driver | Updatable drivers ship with system updates only | Driver/Vulkan/GLES capability report |
| Exact battery health % | `BATTERY_PROPERTY_STATE_OF_HEALTH` is a system API | Cycle count (public), full-capacity estimate from charge counter (labelled estimate), SoH only if `BATTERY_STATS` is granted and the HAL reports it |
| Per-app battery mAh | `BatteryStatsManager` is system-only | `dumpsys batterystats` via Shizuku/DUMP; otherwise Android's battery usage screen |
| CPU utilisation from /proc/stat | Blocked by SELinux for apps since Android 8 | Shizuku read; else frequency-ratio estimate, explicitly labelled |
| Per-sensor temperatures | Thermal HAL list is privileged | Battery temp + thermal status/headroom (public); full sensor list via `dumpsys thermalservice` (Shizuku/DUMP) |
| Malware detection | No reliable on-device signal for third parties | Permission / special-access review; Play Protect deep link |
| Improve mobile radio | Modem firmware not controllable | Measurement only (signal, latency, loss, DNS, throughput) |
| Override desktop mode | System UI owns windowing | Presentation dashboard on external displays; developer flags via Desktop profile (Advanced, reboot required) |
| Background “always-on” optimiser | Background limits, Doze, App Standby | WorkManager routines only |

Hidden-API use is limited to one best-effort reflection (`PowerProfile.getBatteryCapacity`) that
degrades to “unknown”; the user can enter the design capacity in Settings.
