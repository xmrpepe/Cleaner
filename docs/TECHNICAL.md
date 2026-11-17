# PixelForge — technical documentation

## Architecture

```
app/
├── src/main/cpp/                 Native lab (C++20, no exceptions/RTTI, static libc++)
│   ├── bench_cpu.cpp             Integer mix / FP32 SGEMM / sort kernels, per-thread CPU pinning
│   ├── bench_memory.cpp          Streaming bandwidth + Sattolo pointer-chase latency curve
│   ├── bench_storage.cpp         fsync write, O_DIRECT sequential + random 4 KiB reads
│   ├── vulkan_lab.cpp            dlopen(libvulkan) probe, compute (FMA) + offscreen fill benchmarks
│   ├── shaders/*.comp|vert|frag  GLSL sources → spirv_*.h (tools/compile_shaders.sh)
│   └── jni_bridge.cpp            JNI → JSON strings
├── src/main/aidl/                IShellService (Shizuku UserService, shell UID)
└── src/main/java/dev/pixelforge/
    ├── PixelForgeApp / MainActivity
    ├── di/AppContainer           Manual DI (lazy singletons, zero reflection)
    ├── core/
    │   ├── monitor/              CpuInfoReader, CpuSampler, MemoryReader, BatteryReader, ThermalReader, LiveMonitor
    │   ├── storage/              StorageScanner, DuplicateFinder, SimilarImageFinder, TrashManager
    │   ├── files/                FileManager (java.io + DocumentFile), zip/unzip
    │   ├── apps/                 AppRepository, AppActions (reversible privileged actions)
    │   ├── optimize/             OptimizationEngine, ProfileCatalog/ProfileEngine, ChangeReverter
    │   ├── bench/                BenchmarkRunner, models, export
    │   ├── gpu/                  GlesSession (probe + offscreen ES 3.x benchmarks)
    │   ├── privacy/ network/ device/ display/ automation/ settings/ data/ shizuku/ util/
    └── ui/                       Compose: theme, components (gauges, charts, treemap), navigation, screens
```

### Data flow
* **LiveMonitor** is a cold `flow` turned into a `StateFlow` with `SharingStarted.WhileSubscribed(3000)`.
  Screens collect it with `collectAsStateWithLifecycle`, so sampling **stops automatically 3 s after
  the UI is hidden**. Interval is user-configurable and doubles under thermal pressure / Battery Saver.
* **Background** work is exclusively WorkManager (`TelemetryWorker` ≥ 15 min, `MaintenanceWorker`
  daily, idle + battery-not-low). There is no foreground or persistent service.
* **Room** stores telemetry, the audit log, recycle-bin entries, benchmark runs, optimisation reports,
  reversible changes and file bookmarks. **DataStore** stores preferences.
* **PrivilegedExec** is the single choke point for privileged operations: it chooses the least
  privileged route (ADB-granted permission → Shizuku), refuses when none exists, validates package
  names against injection, and audit-logs every command with its output.
* **ReversibleChange** rows capture the previous value of every system setting, package state,
  app-op, standby bucket or game mode PixelForge changes. `ChangeReverter` restores the *earliest*
  recorded value for a key, so stacking profiles never loses the original.

### Tiers
`Tier.SAFE` (ordinary app operations), `Tier.ADVANCED` (Shizuku / ADB-granted / special access,
always confirmed) and `Tier.UNAVAILABLE` (shown, never executed). Profiles evaluate availability per
step at runtime and report each step as VERIFIED / APPLIED / OPENED / SKIPPED_UNAVAILABLE / FAILED.

### Health index
`0–100 = storage headroom (30) + memory headroom (25) + thermal (25) + battery (20)`; the breakdown is
shown on the dashboard. It summarises measured conditions and is explicitly *not* a speed score.

### Benchmarks
* Scores = `1000 × measured ÷ reference` (geometric mean across sub-tests). References are fixed,
  versioned normalisation constants (`BenchmarkRunner.REF_*`), not device claims.
* Each run stores test version, metrics, duration and conditions (battery temperature, thermal
  status/headroom, battery %, charge-counter delta → measured mAh when unplugged, Battery Saver).
* Only runs with the same `testId` + `testVersion` are compared; a >10 % drop below the median of
  previous runs is flagged as a possible regression.
* The thermal guard cancels native work (`NativeLab.setCancelled`) at THERMAL_STATUS_SEVERE.
* While a benchmark runs the Lab keeps the screen on and requests
  `Window.setSustainedPerformanceMode(true)` for PixelForge's own window.

### Tensor G4 specifics
* Cores are identified from MIDR part numbers: `0xd82` Cortex‑X4, `0xd81` Cortex‑A720, `0xd80`
  Cortex‑A520; clusters from cpufreq `related_cpus`. Single-thread tests run on the prime core and on
  one core of every cluster to expose the 1 + 3 + 4 topology.
* GPU (Mali‑G715 MC7) clock/utilisation are read from Mali kbase sysfs when readable (often only via
  Shizuku); otherwise reported as n/a.
* Pixel 9 supports USB‑C DisplayPort output; PixelForge uses a `Presentation` to drive a dedicated
  dashboard and the Desktop profile can enable Android's desktop-on-external-display developer flag.

## Performance budget
* No polling while hidden; adaptive intervals; thermal sensors refreshed asynchronously every ≥5 s.
* Expensive calls are rate-limited (`getThermalHeadroom` ≤ 1/2 s, `Debug.getPss` ≤ 1/10 s).
* Release builds use R8 full mode + resource shrinking; native code is `-O3`, `-fvisibility=hidden`,
  16 KB page aligned.
* The dashboard's developer diagnostics show PixelForge's own CPU %, PSS and heap.

## Testing
* `app/src/test` — JVM unit tests for parsers (/proc/stat, meminfo, PSI, thermalservice, dumpsys
  meminfo), formatting, package-name injection guard, shell quoting, scoring maths, latency stats and
  treemap geometry.
* `app/src/androidTest` — native lab smoke tests, Room DAO semantics, and a Compose UI smoke test
  that launches the app and navigates Dashboard → Monitor → Lab.
* CI runs lint, unit tests, debug + release builds and the instrumentation suite on an emulator.
