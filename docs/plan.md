# Cupola implementation plan (draft for owner review, 18 October 2026)

Status: plan only. Nothing exists yet: no repo, no code, no commits, no pushes, no Play Console
changes. Work starts only after the owner says "proceed".

Scope: Android live wallpaper "Cupola: ISS Live Wallpaper", design decisions 1-42 in the design
ledger (owner-confirmed 18 October 2026). This plan adds no product features.

Evidence key: (M) measured in this planning session with a named tool result, (C) carried from the
design ledger, wiki or research reports and not re-measured here, (I) inferred.

---

## 1. Owner decisions

Asked with AskUserQuestion and answered by the owner on 18 October 2026.

| ID | Decision | Owner's answer |
|---|---|---|
| D1 | Repo folder and hosting | `D:\VSC\Cupola`, plus a private GitHub repo `IT-BAER/cupola-android` (renamed from `IT-BAER/cupola` on 23 October 2026) for CI, the TLE fetch and the Play upload (HandType pattern) |
| D2 | Viewers for the photoreal gate (decision 16) | The owner alone. Bias control: the NASA photos used in G-PR are a hold-out set the owner does not see before the gate run (see P4.1 and G-PR) |
| D3 | Battery budget for the final performance gate | At most 1 %/h extra drain, screen on, phone still (15 fps), live against static wallpaper |
| D4 | Closed test for a paid app (testers must buy it) | Decided at the start of P8; the OC5 promo-code check is an input |

Defaults taken without a question (the owner can override any of them):

- applicationId and namespace `com.baer.cupola`, following `com.baer.handtype` (C, wiki handtype).
- Orbit data source for CI: CelesTrak GP API, one request per feature release. Credit line for
  US Space Force data, because Space-Track grants blanket approval to redistribute TLEs
  "conditioned on appropriate citation" (M, Space-Track user agreement).
- SGP4: own Kotlin port of python-sgp4 (MIT, M) with its MIT notice in the credits. Never derive
  code from `CelesTrak/fundamentals-of-astrodynamics`, which is AGPL-3.0 (M, GitHub licence API).
- One packaging-only Gradle module `:earthpack` (install-time asset pack, no code) next to the
  four code modules of decision 21, because Texture Compression Format Targeting is documented
  for asset packs only (M, Play texture-compression page).
- Repository interfaces are declared in `:feature:settings` and implemented in `:app` (feature-owned
  ports), because the feature cannot depend on `:app` and decision 21 has no data module.
- Release ABI arm64-v8a only; x86_64 only in debug builds for emulators (I: Vulkan 1.1 is
  guaranteed on 64-bit Android 10+ devices, C baseline).
- Drag-freeze threshold 14 days. The orbit keeps the altitude it has at the threshold, not the
  epoch altitude, because a continuous position wins over the ledger wording (I, see P1.6).
- Battery saver: render one frame each time the wallpaper becomes visible, then 0 fps. This keeps
  decision 6 (true position) and decision 8 (0 fps in battery saver) both true (I).

---

## 2. Measured baseline (18 October 2026)

### 2.1 Toolchain pins

All rows (M) from Google Maven and Maven Central `maven-metadata.xml`, `services.gradle.org`, the
SDK `repository2-3.xml` and GitHub release APIs, read 18 October 2026. They match the ledger's
late-2026 baseline. Re-verify again on scaffold day (P0.4) before writing `libs.versions.toml`.

| Item | Pin |
|---|---|
| Android Gradle Plugin | 9.4.1 (built-in Kotlin; no `org.jetbrains.kotlin.android`) |
| Gradle | 9.8.1 |
| Kotlin, Compose compiler plugin | 2.4.21 |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 (ui 1.12.1, material3 1.4.0) |
| Dagger / Hilt | 2.60.1 (Hilt Gradle plugin supports AGP 9 since 2.59, release notes) |
| androidx.hilt | 1.4.0 (`hiltViewModel()` in `hilt-lifecycle-viewmodel-compose`) |
| Navigation 3 | 1.2.0 |
| Lifecycle | 2.11.0 |
| DataStore | 1.2.1 |
| activity-compose / core-ktx | 1.13.0 / 1.19.1 |
| kotlinx-coroutines (core, test) | 1.11.0 |
| Turbine / JUnit / Robolectric | 1.2.1 / 4.13.2 / 4.17 |
| androidx.test ext-junit / runner / uiautomator | 1.3.0 / 1.7.0 / 2.4.0 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 33. The SDK repository lists API 37 as `platforms;android-37.0`. Play floor is target 36 since 31 August 2026 (C baseline) |
| Build tools | 37.0.0 |
| NDK | r30 = 30.0.16248370 (GitHub release 8 September 2026; 16 KB aligned by default since r28) |
| CMake | 4.1.2 (newest in the SDK repository) |
| JDK | 21 (Android Studio JBR 21.0.9 on this PC) |
| Android Studio | Quail 4 (2026.1.4) or Rabbit 1 (2026.2.1) needed for AGP 9.4. Installed: Panda, build 253, which supports AGP up to 9.2 (M, AGP compatibility table) |
| Vulkan validation layers (debug only, never shipped) | vulkan-sdk-1.4.363.0 |
| astcenc / KTX-Software / libvips (asset tools, never shipped) | 5.7.0 (Apache-2.0) / v4.4.2 / 8.18.7 (LGPL-2.1, used as a CLI) |
| RenderDoc | v1.46 |
| GitHub Actions | checkout v7.0.1, setup-java v6.1.0, gradle/actions v6.4.0, android-actions/setup-android v4.0.4, upload-artifact v7.0.2, r0adkll/upload-google-play v1.1.5 (pin by commit SHA) |

Cut (not used): Room, WorkManager, Play Billing, Play Core and the asset-delivery library,
analytics, Crashlytics, VMA, volk, glm, libktx. Swappy (`androidx.games:games-frame-pacing` 2.1.3)
only if the P2 pacing gate fails.

### 2.2 This PC (M)

- Android SDK at `D:\Software\Android\Sdk`: NDK 25.1 and 27.1 only (r30 missing), CMake 3.22.1
  only, platforms up to android-36 (37 missing), build tools up to 36.1.0.
- No `java` on PATH and no JAVA_HOME. A JDK exists only inside Android Studio
  (`D:\Software\Android\Android Studio\jbr`, 21.0.9).
- `adb` is not on PATH; use `D:\Software\Android\Sdk\platform-tools\adb.exe`.
- Blender 5.2 at `C:\Program Files\Blender Foundation\Blender 5.2`.
- Node 22.23.2, git, gh (logged in as IT-BAER, scopes repo and workflow).
- `python` on PATH resolves to the WindowsApps alias; the idea-check found no Python install (C).
  Blender's bundled Python covers bpy work.
- No vips, magick, astcenc or ktx on PATH.
- Gotcha (C, wiki handtype): Gradle started from Claude Code on this PC needs
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\jtmp`, else tasks fail with "Unable to
  establish loopback connection".

### 2.3 Galaxy A35 (M, adb over Wi-Fi)

- SM-A356B, Android 16 (SDK 36), One UI 8.0 (`ro.build.version.oneui` = 80000), security patch
  1 October 2025.
- Exynos 1380 (`erd8835`), Mali-G68, driver r38p1. Vulkan feature version 0x403000 = 1.3.0.
- Display 1080 x 2340 with 60 Hz and 120 Hz modes. MemTotal 5,557,184 kB.
- All three animation scales are 0. Reduced motion is therefore on, and decision 28 would switch
  off look-around and idle float on this phone.
- Locale de-AT. Current wallpaper: the static system ImageWallpaper.
- Shared device: other automation drives the same phone over the same adb address, so changes
  there affect a live setup (C).

### 2.4 Play and data rules (M)

- Closed test: personal accounts created after 13 November 2023 need at least 12 testers opted in
  for 14 consecutive days (Play Help 14151465). The owner's account appears affected, because
  HandType ran a closed test before its production application (I from C wiki).
- "Testers must purchase paid apps when participating in open or closed tests." "For internal
  tests, testers can install paid apps for free." Internal tests allow up to 100 testers (Play Help
  9845334).
- "Once your app has been offered for free, the app can't be changed to paid." (Play Help 6334373).
- Promo codes for paid apps: up to 500 per quarter per app. The page does not say whether they
  work during closed testing (Play Help 6321495).
- Texture Compression Format Targeting: documented for asset packs; install-time packs need no
  Play Asset Delivery API; a default folder without suffix is required, else Play "marks the app
  as not available for the device".
- Space-Track: "USSPACECOM has provided express blanket approval for transfer/redistribution of
  basic SSA data ... conditioned on appropriate citation"; basic SSA data includes TLEs.
- CelesTrak usage policy: GP data updates every 2 hours; download once per update; stop on any
  non-200 response. No statement on redistribution or attribution.

---

## 3. Ground rules for the build

1. Test-first per the tdd skill: pick the seam, write one failing test, show it fail (red), write
   the minimal code (green), refactor, rerun. Each phase lists its seams in the order of the slices.
2. Every pin in 2.1 is re-verified on scaffold day. Any newer stable version is checked against
   the AGP 9.4 compatibility notes before it replaces a pin.
3. No `uses-permission` except `android.permission.INTERNET` (orbit data download, P7.6) and the
   androidx.core signature permission `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
   The CI guard (P0.6) enforces it.
4. The only network code is the orbit data download (P7.6: HttpURLConnection, at most once a day,
   only while the wallpaper is visible, settings toggle). No analytics, no WorkManager, no wake
   locks. Crashes and ANRs come from Play Console Android vitals.
5. Licence hygiene: every shipped third-party asset or code file has a credits entry; no AGPL
   code; no NASA or ESA name or logo in the app, icon, store text, screenshots or video; the ESA
   reference clip is used for internal comparison only and is never shipped or published.
6. Device gates run on the A35 only after the other automation on it is paused. Animation scales are
   set to 1 for the run and restored to 0 afterwards, because that automation depends on 0.
7. Execution follows the bounded-plan-execution skill. bounded-implementer (Sonnet 5.5) takes
   bounded batches (Compose screens, ViewModels, CI files); opus-complex-implementer takes the
   SGP4 port and drag guard, the Vulkan core, the atmosphere and cloud shading, and the panorama
   mixing; opus-risk-reviewer reviews the JNI and render-thread concurrency, the asset pipeline
   determinism and the release workflow. Browser work stays in the main session.
8. Completion ledger: the IDs in this plan (P0.1 to P8.6, G-PERF, G-PR, OC1 to OC6) are the
   checklist. In the repo it lives in `docs/plan.md` with a status per ID.

---

## 4. Repository and module layout

```
D:\VSC\Cupola                       (pending D1)
  settings.gradle.kts
  gradle/libs.versions.toml         pins from 2.1
  gradle/wrapper                    Gradle 9.8.1
  app/                :app          Application, manifest, WallpaperService wiring,
                                    repository implementations, Hilt modules
  core/orbit/         :core:orbit   pure Kotlin/JVM: TLE, SGP4, drag guard, time scales,
                                    frames, sun and moon, eclipse, geodesy, city index,
                                    home-city pass prediction
  core/render/        :core:render  Android library, no Compose: Kotlin scene logic and JNI
                                    facade; src/main/cpp: Vulkan renderer (C++20, CMake);
                                    GLSL shaders compiled to SPIR-V by the NDK's glslc
  feature/settings/   :feature:settings  Compose M3: settings, info, city search, credits,
                                    share; declares the repository interfaces it needs
  earthpack/          :earthpack    install-time asset pack, no code:
                                    textures#tcf_astc/ and textures/ (ETC2 fallback)
  tools/blender/      bpy scripts: build_cupola.py, bake_panorama.py, export_station.py
  tools/textures/     tool bootstrap (pinned downloads, SHA-256), sources.lock, pipeline
  tools/orbit/        TLE fetch and validation, JPL Horizons fixture fetcher
  reference/          photoreal reference metadata (photo IDs, times, poses)
  docs/plan.md        this plan and the completion ledger
  .github/workflows/  ci.yml, release-prep.yml, release.yml
```

Dependencies: `:app` -> `:feature:settings`, `:core:render`, `:core:orbit`;
`:feature:settings` -> `:core:render`, `:core:orbit`; `:core:render` -> `:core:orbit`;
`:core:orbit` -> Kotlin standard library only.

Large binaries (mirrored source imagery, EXR bakes, reference photos) stay outside git in a cache
folder; git keeps scripts, manifests with SHA-256 values, and the final compressed textures.

---

## 5. Phase order

| Phase | Needs | Runs in parallel with | Ends with |
|---|---|---|---|
| P0 Prerequisites and scaffold | owner "proceed", D1 | - | green build, permission guard |
| P1 Orbit math (TDD) | P0 | P2, P4, P5.1 | vector tests green |
| P2 Renderer skeleton and wallpaper wiring | P0 | P1, P4, P5.1 | skeleton 60 fps gate, device checks |
| P4 Blender Cupola and panorama bake | P0 (tools only) | P1, P2 | interior review pass |
| P5 Texture pipeline and asset pack | P5.1 from P0; P4 outputs | P1, P2, P3 | 96 MiB budget check |
| P3 Earth, atmosphere, clouds, night side | P2, P5 (P3a first) | P6 | goldens, GPU budget |
| P6 Settings, info, city search, share | P1, P2 (preview needs P3) | P3 | UI and intent tests |
| P7 CI and TLE per feature release | P0, P1 | P3 to P6 | release-prep run |
| G-PERF and G-PR gates | P3, P4, P5, P6 | - | both pass on one build |
| P8 Release pipeline | both gates | - | internal track install |

---

## P0. Prerequisites and scaffold

Work:
- P0.1 Owner answers D1 to D4 and says "proceed".
- P0.2 Update Android Studio to Rabbit 1 (2026.2.1) or Quail 4 or newer. Needed only to open the
  project in Studio; Gradle on the command line works with the existing JBR.
- P0.3 Install SDK packages with sdkmanager: `platforms;android-37.0`, `build-tools;37.0.0`,
  `ndk;30.0.16248370`, `cmake;4.1.2`.
- P0.4 Re-verify every pin in 2.1 (same sources as this plan) and write `libs.versions.toml`.
- P0.5 Create the repo in the D1 folder: git init, `.gitignore`, Gradle wrapper 9.8.1,
  `settings.gradle.kts` with the four code modules, Hilt application class, minimal manifest:
  wallpaper service with `android:permission="android.permission.BIND_WALLPAPER"`,
  `uses-feature android.software.live_wallpaper` required, `uses-feature
  android.hardware.vulkan.version` 0x401000 (Vulkan 1.1) required,
  `android.hardware.sensor.gyroscope` not required. Private GitHub repo per D1. Copy this plan to
  `docs/plan.md`.
- P0.6 Manifest permission guard (open check OC3): a Gradle task reads the merged release
  manifest and fails on any `uses-permission` outside the allowlist in rule 3.
- P0.7 `ci.yml` on version tag push (`v*`): build, unit tests, lint, guard.
- P0.8 Open check OC1: look at the Orbital Window Play screenshots
  (`com.orbitalwindow.wallpaper`) and rate its coverage. Read-only browser task in the main
  session (chrome-automation did not connect in this planning session, M). Report to the owner
  before any wiki update.
- P0.9 Mirror the source imagery early (P5.1), because the Blue Marble 2002 cloud map sits on a
  legacy host only (C).

Test-first seam: the guard task. Red: a planted permission outside the allowlist (`INTERNET` before
P7.6, `ACCESS_NETWORK_STATE` after) fails the build. Green: the
build passes after removal.

Acceptance:
- `gradlew build` exits 0 locally and in CI.
- Guard red and green runs are shown in the phase report.
- Independent check: `apkanalyzer manifest permissions` on the release APK prints only the
  allowlisted permission.
- `libs.versions.toml` pins match the scaffold-day re-verification.

## P1. Orbit math in `:core:orbit` (TDD, JVM)

Slices in this order, each red then green:
- P1.1 TLE parsing with checksum validation; epoch to `Instant`; B*, ndot, nddot, elements.
- P1.2 SGP4 near-Earth propagation, ported from python-sgp4 (MIT). Test vectors:
  `SGP4-VER.TLE` and `tcppver.out` as shipped in python-sgp4. Deep-space TLEs (period 225 min or
  more) are rejected with a typed error; the ISS never needs them.
- P1.3 Time scales and frames: UTC to TT with a bundled leap-second table, GMST (IAU-82, as the
  TEME frame of SGP4 expects), TEME to ECEF to WGS-84 geodetic.
- P1.4 Sun and Moon positions with low-precision series; Moon seen from the ISS (topocentric,
  parallax up to about 1 degree); Moon phase.
- P1.5 Sunlit or eclipsed with a penumbra ramp (smooth 0..1 transition).
- P1.6 Old-data drag guard (needed by decision 40). Up to age T (default 14 days) the result is
  plain SGP4. After T, the drag-dependent secular terms are frozen at their value at T: the
  semi-major axis and eccentricity terms stay constant, and the mean anomaly, argument of perigee
  and node continue linearly with their rates at T. The position is continuous at T, and the
  orbit keeps the altitude it has at T. The ledger wording "keeps the epoch altitude" differs by
  the modelled decay over T; the test reports that difference.
- P1.7 Geo index: nearest city from the GeoNames cities15000 list (CC BY 4.0), else the ocean or
  sea name from Natural Earth marine polygons (public domain, rasterized at build time); city
  search, diacritics-insensitive, ranked by population.
- P1.8 Home-city pass prediction on the app's own orbit (decision 28): next time window in which
  the home city is inside the visible cap, plus the time of closest approach.
- P1.9 `OrbitModel.stateAt(instant)` facade for the wallpaper, the preview and the info screen.

Acceptance:
- SGP4: every near-Earth case in `tcppver.out` matches within the tolerance that python-sgp4's own
  test suite uses (read before the red test; expected 1 mm or better, I).
- GMST within 0.1 s of time against JPL Horizons sidereal time at 10 epochs.
- Sun direction within 0.01 degrees and Moon within 0.3 degrees of JPL Horizons at 50 epochs
  between 2026 and 2035. The fixture fetch script and the fixtures are committed.
- Eclipse: at beta angle 0 the night fraction matches the cylindrical-shadow value of the ledger
  (38.9 %, C; recomputed inside the test) within 0.5 percentage points.
- Drag guard: (a) identical to plain SGP4 up to T; (b) position jump across T at most the
  distance flown in the sampling step plus 1 mm, velocity jump at most 1 m/s; (c) after T the
  orbit-averaged altitude stays within 1 km of its value at T for 10 years; (d) no SGP4 error
  code for 10 years after the epoch; (e) the altitude drop between epoch and T is reported.
- Geo: 20 fixture points (cities, oceans, deserts) return the expected label; "Wien" finds Vienna;
  "Sao Paulo" finds São Paulo; the city asset is at most 2 MiB.
- Pass prediction: start and closest-approach times within 1 s of brute-force 1 s sampling of the
  same propagator, 5 cities over 7 days.

## P2. Renderer skeleton, wallpaper wiring, device checks

Work:
- P2.1 Native core: Vulkan 1.1 instance and device; queries for ASTC support and
  `maxImageDimension2D`; one `VkDevice` and texture set per process, shared by the active engine,
  the picker preview engine and the settings preview (decision 33); one swapchain per surface
  from `ANativeWindow_fromSurface`; pre-rotation; FIFO present; one render thread paced by
  `AChoreographer`; Vulkan timestamp queries; ATrace sections. Validation layers in debug builds.
- P2.2 JNI facade `NativeRenderer`: attach and detach surface, set frame inputs (plain floats),
  render, render offscreen to an RGBA buffer (share and golden tests).
- P2.3 Kotlin scene logic in `:core:render`, all JVM-testable:
  - FrameScheduler: 60 fps moving or springing back, about 15 fps still, 0 when hidden, one frame
    per visibility change in battery saver (decision 8 and the default in section 1).
  - LookAroundController: clamp about +-20 degrees, smoothing, spring-back (decision 3), idle
    float about +-2 degrees (decision 18), off under reduced motion (decision 28), small yaw pan
    from launcher offsets.
  - PreviewClock: one orbit in 20 to 30 s, only when `isPreview()` (decision 17).
  - QualityGovernor: quality steps from frame time and thermal headroom (`getThermalHeadroom` on
    API 33 to 35, headroom listener on 36 and later, PerformanceHintManager session for the
    render thread).
- P2.4 `:app` wallpaper service: engine lifecycle; `TYPE_GAME_ROTATION_VECTOR` at
  `SENSOR_DELAY_GAME` registered only while visible; `onOffsetsChanged`; `onCommand` with
  `WallpaperManager.COMMAND_TAP` and a double-tap detector; `getWallpaperFlags` and
  `onWallpaperFlagsChanged` (API 34); `onComputeColors`; `Surface.setFrameRate` with
  `FRAME_RATE_COMPATIBILITY_DEFAULT`.
- P2.5 Device checks on the A35, results in `docs/device-checks.md`:
  - OC2: single and double taps on an empty home-screen area reach `onCommand`. Repeat with One
    UI "double tap to turn off screen" on and off (I: it may consume double taps).
  - OC4: whether One UI 8 shows a third-party live wallpaper on the lock screen, and which engine
    flags it uses.
  - Active and preview engines at the same time on one `VkDevice`.
  - 16 KB: `llvm-readelf -l` shows LOAD alignment 0x4000; `zipalign -c -P 16` passes.
- P2.6 Skeleton 60 fps gate with a synthetic load shader sized to the planned GPU budget.

Test-first seams: JVM tests for every controller in P2.3; an instrumented offscreen test whose
first red run expects a fixed test pattern.

Acceptance:
- JVM tests green for scheduler, look-around, preview clock and governor.
- Offscreen test pattern is byte-exact on the A35.
- Skeleton gate on the A35 (bot paused, animation scales 1), 60 s replayed rotation trace:
  at least 99 % of present intervals within 16.7 ms +- 2 ms (SurfaceFlinger `--latency` for the
  wallpaper layer), none above 33.4 ms; still mode 15 +- 1 fps; hidden: frame counter unchanged
  for 60 s; battery saver: exactly one frame per visibility change.
- Device-check results recorded, with the fallback taken where a check fails.
- Total PSS with active and preview engines at most 512 MiB (`dumpsys meminfo`). Engineering
  ceiling; the February 2027 memory vitals start at 1.25 GB for 6 GB devices (C baseline).

## P3. Earth, atmosphere, clouds and night side

Work:
- P3a Nadir realism probe, first: render the nadir view with the 16384 px day map, atmosphere and
  clouds at the time and pose of one NASA nadir photo; the owner compares both on the A35. This
  addresses the top ledger risk (4.6x magnification at nadir, C) before the rest of P3. Outcome:
  procedural detail at nadir inside the budget, or a budget increase, which needs the owner's
  approval.
- P3.1 Ray-cast globe with analytic texture derivatives across tiles (no mesh LOD); monthly
  colour blend plus detail luminance; ocean sun glint from a water mask; relief from the normal map.
- P3.2 Clouds: Blue Marble 2002 map with flow-field advection, deformation and coverage change
  over days (decisions 7 and 31); cloud shadows on the ground.
- P3.3 Atmosphere: transmittance and multi-scattering lookup tables computed once at start
  (Hillaire 2020 method), ray-marched limb, aerial perspective, terminator colours.
- P3.4 Night side (decision 10): Black Marble city lights, airglow shell, procedural lightning
  and aurora, Moon phase and moonlit clouds, stars and Milky Way.
- P3.5 Sun disc, bloom and window glare (decision 18); auto exposure with slow adaptation
  (decision 11).
- P3.6 Station exterior parts from P4.6, lit by the real sun; arrays track the sun; Canadarm2
  parked (decision 24).

Test-first seams: instrumented golden images at fixed frame inputs; analytic geometry checks.

Acceptance:
- 10 goldens (day nadir, limb, terminator, sun glint, sun in view, city lights, aurora,
  lightning, moonlit clouds, stars) approved once by the owner; later runs at PSNR 40 dB or more.
- Limb within 2 px of the analytic horizon; glint centre within 1 degree of the analytic specular
  point; terminator within 0.5 degrees of the analytic sun terminator.
- Seam detector over a 360-degree yaw sweep finds no tile or wrap seam above its threshold.
- GPU time for the full scene (Earth, interior, post) at the default quality step on the A35:
  p95 at most 12 ms.
- Night passes are never black: mean luminance after exposure stays inside the target band.

## P4. Blender Cupola model and relightable panorama

Runs on this PC from P0 on, in parallel with P1 and P2.

Work:
- P4.1 Reference pack: public-domain NASA Cupola photos (iss035e010551, iss038e013587,
  iss073e0025760 and the others in the asset report, C) with their metadata; ESA window sizes
  (Deloo 2009); clip frames for internal comparison only. The photos are split into a
  development set (used in P3a, P4.7 and the goldens) and a G-PR hold-out set of 7 or 8 photos,
  day and night. The hold-out photos are stored outside the repo and are not shown to the owner
  before the G-PR run (D2).
- P4.2 `build_cupola.py` builds the model from scratch (decision 23): shell, window frames,
  shutters, handrails, laptop, cameras, cables, PBR materials, all branding stripped. Run with
  `blender -b -P`, so the `.blend` is generated, never hand-edited.
- P4.3 Visible-region computation: rest directions x look-around clamp x idle float x widest
  field of view decide which cube faces get baked. The budget plans 3 of 6 faces; this step
  measures the real number.
- P4.4 `bake_panorama.py`: Cycles path tracing from the fixed eye point (decision 22; owner 24 October 2026: 1.55 m
  from the nadir pane, z -0.10947) into
  separate layers: Earthlight with the window alpha, cabin, emissive (laptop and LEDs for the
  night interior, decision 26), ambient. OIDN denoise; EXR to LDR with a scale per layer.
- P4.5 Glass overlay: interior reflections and dust (decision 18).
- P4.6 Station exterior from NASA ISS (D) IGOAL (GLB 95,875,648 B, free, C): visible parts only,
  decimated, textures baked.
- P4.7 Review rounds: composites at the reference poses next to the NASA photos; the owner
  reviews each round (decision 25); iteration has no cap (decision 27).

Acceptance:
- Apertures measured by script match the ESA sizes within 5 mm: top window 70.6 cm clear
  aperture; side windows clear trapezoid 40.5 cm high, sides 40.0 and 64.4 cm, corner radius
  3.24 cm (C ledger).
- Bake reproducible: same script and seed give identical EXR hashes, or at most 1/255 difference
  after denoise.
- Residual noise per layer: mean absolute difference to a 4x-sample reference at most 1 %.
- Branding checklist signed off by the owner: no NASA or ESA logos, patches, stickers,
  third-party brands or people.
- Exterior meshes and textures at most 5 MiB.
- Interior-only review: the owner says "pass" before the interior enters G-PR.

## P5. Texture pipeline and asset pack (96 MiB budget)

Work:
- P5.1 Mirror sources into a cache outside the repo and commit `sources.lock` (URL, SHA-256,
  licence, credit text): BMNG 2004 monthly maps, Blue Marble 2002 clouds, Black Marble 2016,
  GEBCO_2026 or ETOPO 2022, and a Gaia-free star source (ESO eso0932a CC BY 4.0 or SVS 2012).
- P5.2 Tool bootstrap: pinned astcenc 5.7.0, KTX-Software v4.4.2 and libvips 8.18.7 binaries,
  each checked by SHA-256.
- P5.3 Processing: crop to the latitudes the ISS can see (0.8 of the rows, C), resize, tile at
  4096 px or less (the 16384 px day map becomes 4 x 2 tiles in an array texture), normal map from
  terrain, full mip chains, ASTC per the allocation below, ETC2 fallback one mip level lower.
- P5.4 `:earthpack` install-time asset pack with `textures#tcf_astc/` and the default `textures/`
  folder; texture split enabled in the bundle config.
- P5.5 Native loader: `AAssetManager` stream into a staging buffer, then into the image; own
  minimal KTX2 reader, tested on the host with fixture files; no Java bitmaps (engineering
  default in the ledger).

Allocation (computed with a script in this session: ASTC, full mip chains, cropped rows):

| Asset | Size and format | MiB |
|---|---|---|
| Day detail map | 16384 x 6554, ASTC 8x8 | 34.2 |
| 12 monthly colour maps | 2048 x 819 each, ASTC 6x6 | 11.5 |
| Clouds | 8192 x 3277, ASTC 8x8 | 8.5 |
| City lights | 8192 x 3277, ASTC 8x8 | 8.5 |
| Normal map | 4096 x 1638, ASTC 6x6 | 3.8 |
| Stars and Milky Way | 4096 x 2048, ASTC 6x6 | 4.8 |
| Interior, 3 visible faces of 2048 px | Earthlight+alpha 6x6, cabin 8x8, emissive 8x8, ambient 512 px 8x8 | 15.4 |
| Subtotal | | 86.7 |
| Headroom for station parts, lookup tables, city list, overlays | | 9.3 |

Baking all 6 faces with 4 layers at 6x6 would take 57.2 MiB instead of 15.4 and bring the total
to 128.4 MiB (same script). The visible-face cull in P4.3 is therefore required.

Acceptance:
- Texture payload for an ASTC device (sum of files in `textures#tcf_astc`) at most 96 MiB;
  `bundletool get-size total` with the A35 device spec reported as the download size.
- An ETC2 device spec builds, and the default folder exists.
- On the A35 all textures load, and Java heap growth during loading is at most 10 MiB
  (`dumpsys meminfo`).
- Every shipped file has a credits entry (automated comparison of `sources.lock` with the
  credits list).

## P6. Settings, info, home-city search, share

Work:
- P6.1 One Activity, Compose Material 3 1.4.0, always dark with dynamic colour (decision 36),
  edge-to-edge, predictive back, Navigation 3. Screens: Settings with a large live preview on top
  (SurfaceView on the shared renderer), Info, Home city, Credits, About and privacy.
- P6.2 "Set wallpaper": `ACTION_CHANGE_LIVE_WALLPAPER` with `EXTRA_LIVE_WALLPAPER_COMPONENT`.
- P6.3 Settings: rest direction, look-around strength, idle float, effects (bloom, glare,
  reflections and dust), home city (decision 19).
- P6.4 Data: SettingsRepository (Preferences DataStore, one instance per process, engines observe
  its Flow), TleRepository (bundled TLE and its metadata), CityRepository (bundled list into the
  `:core:orbit` index).
- P6.5 Info content: ground point (nearest city or ocean), local time there, time to daylight on
  night passes, orbit data date; "the ISS is retired" note when the release sets that flag
  (decision 38).
- P6.6 Double-tap overlay on the wallpaper, only if OC2 passes: text drawn with Android Canvas
  into a small texture, shown for a few seconds.
- P6.7 Home-city marker on the wallpaper when the city is in view; no notification (decision 28).
- P6.8 Share: offscreen render to PNG in the cache folder, FileProvider URI, `ACTION_SEND`
  chooser; no permission.
- P6.9 Credits: NASA Earth Observatory (BMNG, Black Marble, Blue Marble 2002 clouds), GEBCO or
  ETOPO, the star source, GeoNames (CC BY 4.0), Natural Earth, python-sgp4 MIT notice, orbit data
  citation, and "Based on NASA 3D model and imagery; not endorsed by NASA" (C ledger wording).

Test-first seams: ViewModel tests with fake repositories and Turbine (JVM); Compose UI tests on
Robolectric for semantics and flows; instrumented tests for the share and set-wallpaper intents.

Acceptance:
- JVM and UI tests green; Accessibility Scanner on the A35 reports no errors; a TalkBack pass over
  every screen is recorded.
- A settings change reaches the running wallpaper within 1 s (instrumented test).
- Share produces a PNG of the current view and opens the chooser (instrumented test).
- Edge-to-edge and predictive back checked on the A35 with screenshots.
- The permission guard still passes.

## P7. CI and orbit data per feature release

Work:
- P7.1 Extend `ci.yml`: native build (arm64-v8a), JVM tests, lint, permission guard,
  `apkanalyzer` check, bundle build including `:earthpack`.
- P7.2 `release-prep.yml` (manual dispatch, feature releases only, decisions 13 and 40): one
  request to the CelesTrak GP API for catalogue number 25544 in TLE format; validate checksums,
  catalogue number, mean motion 15.3 to 15.8 rev/day, eccentricity below 0.01, inclination
  51.6 +- 0.2 degrees, epoch age below 3 days; write `assets/orbit/iss.tle` and `iss.json`
  (epoch, source, fetch time); open a pull request to the release branch.
- P7.3 Release guard: the release build fails if the bundled TLE epoch is older than 7 days at
  tag time.
- P7.4 If the catalogue entry is missing or decayed, release-prep reports it and the owner decides
  on the retired flag (decision 38).
- P7.5 `pages.yml` (cron every 6 h, manual dispatch, push to main under `docs/`): copies `docs/`,
  runs `:tools:tle` fetch with the P7.2 validation and publishes `orbit/iss.tle` and `iss.json` on
  GitHub Pages. A failed fetch re-publishes the last published files unchanged. Pages source is
  GitHub Actions. release-prep stays for the bundled TLE.
- P7.6 In-app updater (owner decision 24 October 2026): fetches the Pages mirror at most once per
  24 h plus 0 to 4 h jitter, only while the wallpaper is visible, toggle `Update orbit data online`
  (default on). Validation as P7.2 plus epoch newer than the TLE in use and not more than 1 day in
  the future; atomic write to `filesDir/orbit`; the newest valid of bundled and downloaded wins;
  running sessions pick it up on the next frame. Any failure keeps the current TLE until the next
  window.

Acceptance:
- release-prep on a test branch opens a pull request with a valid TLE; a planted checksum error
  fails (red); a fresh TLE passes (green).
- A planted 30-day-old TLE fails the release guard (red); a fresh one passes (green).
- `ci.yml` green on main.
- P7.6: updater, repository and swap tests red before the code, green after; release manifest
  permissions are INTERNET plus the signature permission only (apkanalyzer).
- P7.5: after the Pages switch, `https://it-baer.github.io/cupola-android/orbit/iss.tle` serves a TLE
  that passes the P7.2 validation.

## P8. Release pipeline

Work:
- P8.1 New upload key for Cupola, kept outside the repo; GitHub secrets as in HandType; Play App
  Signing.
- P8.2 `release.yml` (HandType pattern, C wiki): tag `v*.*.*` checks versionName, CHANGELOG and
  tag; builds the AAB with the asset pack; uploads to the internal track with
  r0adkll/upload-google-play pinned by commit SHA (re-verify the SHA for v1.1.5); `whatsnew`
  per locale for about 8 markets (decision 29); GitHub release.
- P8.3 Play Console (browser, main session, every irreversible step confirmed first): create the
  app; set it to paid at $0.99 before the first upload to any track, because free cannot become
  paid later; store listing with the title "Cupola: ISS Live Wallpaper" in English plus about 8
  localized listings; screenshots and video without NASA or ESA marks; only the wording
  "realistic ISS orbit", never "live position"; Data safety "no data collected"; content rating;
  privacy policy page; target audience; no ads.
- P8.4 Internal track: the paid app installs free for up to 100 internal testers.
- P8.5 Closed test: at least 12 testers for 14 consecutive days. How testers get the paid app is
  decided at the start of P8 (D4). Input OC5: one tester tries to redeem a promo code on the
  closed track first. Options then: promo codes, testers pay $0.99, or another route the owner
  names.
- P8.6 Production application, staged rollout, then Android vitals watch (crash rate below
  1.09 %, ANR rate below 0.47 %, memory vitals from February 2027).

Acceptance:
- `release.yml` uploads a signed AAB to the internal track, and the app installs from Play on the
  A35.
- APKs generated from the AAB with the A35 device spec show only the allowlisted permission.
- The owner reviews the Data safety form and the listing before submission.

---

## 6. Gates

### G-PERF, final performance gate (decision 14)

Runs on the build that also passes G-PR, at the default quality step, on the A35, with the other automation
paused and animation scales set to 1 (restored to 0 afterwards).

- 10 minutes of replayed look-around: at least 99 % of present intervals within 16.7 ms +- 2 ms,
  none above 33.4 ms; GPU p95 at most 12 ms; thermal status MODERATE or lower after 10 minutes;
  the governor never steps below the default quality step.
- Still: 15 +- 1 fps. Hidden: 0 frames. Battery saver: one frame per visibility change.
- Battery (D3): at most 1 %/h extra drain. Measured over 2 hours with the screen on at fixed
  brightness, phone still (15 fps), flight mode, same start charge band, live wallpaper against
  the static system wallpaper. If the gate fails, idle quality or the idle frame rate goes down
  first (owner accepted this trade-off with the stricter budget).
- Memory: total PSS at most 512 MiB with the active and preview engines running.

### G-PR, photoreal side-by-side gate (decision 16, hard gate)

No store screenshots, no video and no production release before G-PR passes.

- Reference set of about 10: the 7 or 8 hold-out NASA Cupola photos from P4.1, day and night,
  each with its metadata (time, nadir point, sun angles, focal length), plus 2 or 3 frames of the
  ESA clip (internal only; the owner already knows the clip).
- A debug build sets the time, the ISS position (an override that bypasses the TLE), the camera
  direction and the field of view for each pair; an alignment helper matches the photo framing.
- Same build and quality step as G-PERF, on the A35, full screen, render and photo side by side
  with random sides.
- Blind protocol per viewer: 20 trials (10 pairs, each in both side orders), 3 s each; the viewer
  taps the render. Pass: at most 14 of 20 correct. Guessing alone reaches 15 or more with a
  probability of 0.021 (computed in this session), so 15 or more counts as "identifiable at a
  glance".
- Viewer: the owner alone (D2). Reduced-bias rule: if the owner recognises a hold-out photo
  before the run, that pair is replaced by another unseen photo.
- On failure: find the cue that gives the render away (interior, nadir softness, atmosphere,
  clouds, exposure), fix it, rerun the full set. No iteration cap (decision 27). A texture budget
  above 96 MiB needs the owner's approval.

---

## 7. Open checks

| ID | Check | When | Pass, or fallback |
|---|---|---|---|
| OC1 | Orbital Window screenshots: coverage rating | P0.8 | Report only. Its strength is 0 (C), so the GO (NICHE) verdict cannot change |
| OC2 | `COMMAND_TAP` forwarding on One UI 8 (A35) | P2.5 | Forwarded: overlay. Not forwarded: info only in settings, as designed |
| OC3 | Merged manifest has only INTERNET and the signature permission (rule 3) | P0.6, every CI run, P8 | Allowlist only |
| OC4 | One UI 8 shows the live wallpaper on the lock screen | P2.5 | Record; adjust store text if not |
| OC5 | Promo codes redeem on a closed track | P8.5 | Else per D4 |
| OC6 | Android Studio update for AGP 9.4 | P0.2 | Command-line builds are unaffected |

---

## 8. Risks

- R1 The scripted interior fails G-PR (C ledger). Mitigation: interior review rounds in P4.7
  before full integration; no iteration cap.
- R2 Nadir softness at 4.6x magnification (C). Mitigation: P3a probe first.
- R3 60 fps on Mali-G68 with atmosphere and clouds. Mitigation: lookup-table atmosphere, render
  scale as a quality step, GPU timing from P2 on.
- R4 The A35 is shared with other live automation, and its animation scales are 0 (M). Mitigation:
  pause that automation and restore its settings around every gate run.
- R5 Closed testers must buy the paid app (M). Mitigation: decision at the start of P8 (D4) with
  the OC5 result; plan time for the 14-day closed test in the release schedule.
- R12 The owner alone judges G-PR and knows the development references (D2). Mitigation: hold-out
  photos (P4.1), replacement of any recognised pair.
- R6 Free cannot become paid (M). Mitigation: set the price before the first upload to any track.
- R7 Licences: the Vallado repo is AGPL-3.0 (M). Mitigation: python-sgp4 port, citations, credits
  check in P5.
- R8 One UI quirks (taps, lock screen, double tap to turn off screen). Mitigation: info also in
  settings.
- R9 Android 17 memory limiter and memory vitals from February 2027 (C). Mitigation: streamed
  texture uploads, PSS ceiling in the gates.
- R10 ISS deorbit planned for 2030 (C). Mitigation: decision 38 path (drag-frozen last orbit,
  retired note).
- R11 NASA merchandise question stays open (C, decision 32). Mitigation: no NASA name or logo,
  credit line only.

## 9. Rollback

Nothing exists before "proceed". P0 creates a local folder and, per D1, a private GitHub repo;
removing either is a delete that needs the owner's confirmation. No Play Console change happens
before P8. The first irreversible Play step is the paid or free setting, and it is set to paid
before any upload.

## 10. Deferred

- Material 3 Expressive: only in 1.5.0 betas (M). Revisit when 1.5.0 is stable; no impact now.
- API 37 Play deadline: not published (C). The app already targets 37.
- Platforms 37.1 and 37.2 exist (M). Not needed unless an API there is wanted.
- Swappy frame pacing: only if the P2.6 gate fails.
- Whether GPU memory counts in the memory vitals: unverified (C). G-PERF measures PSS anyway.

## 11. Sources

Design ledger `~/.claude/handoff-evidence/local-scratch-2026-10-18-41081c/iss-wallpaper/iss-wallpaper-design-final.md`;
wiki-baer `projects/cupola.md`, `ideas/iss-cupola-wallpaper-idea-check.md`, `projects/handtype.md`,
`projects/android-testing.md`; parent research reports android-2026-baseline.md and
earth-sky-textures.md; Maven metadata, SDK repository2-3.xml, services.gradle.org, GitHub APIs
(android/ndk, google/dagger, CelesTrak/fundamentals-of-astrodynamics, brandon-rhodes/python-sgp4);
Play Help 14151465, 9845334, 6334373, 6321495; developer.android.com texture-compression,
agp-9-4-0-release-notes, about-agp; space-track.org user agreement; celestrak.org usage policy;
adb on the A35; local file system checks.

---

## 12. Completion ledger

Status values: open, in progress, done (with evidence), blocked (with the measured blocker),
excluded (by the owner).

| ID | Status | Evidence |
|---|---|---|
| P0.1 | done | Owner answered D1-D4 and said "proceed", 18 October 2026 |
| P0.2 | open | Owner action: Android Studio update (only needed to open the project in Studio) |
| P0.3 | done | sdkmanager exit 0: platforms;android-37.0, build-tools;37.0.0, ndk;30.0.16248370, cmake;4.1.2 |
| P0.4 | done | Pins from the 18 October 2026 sweep in gradle/libs.versions.toml; build resolves AGP 9.4.1, KGP 2.4.21 |
| P0.5 | done | Commit 3a27425 on main, private repo IT-BAER/cupola created and pushed; local gradlew build exit 0 |
| P0.6 | done | Red: planted INTERNET, `:app:checkReleaseManifestPermissions` failed "Merged manifest declares forbidden permissions: android.permission.INTERNET". Green: removed, task and `:app:assembleRelease` passed. apkanalyzer `manifest permissions` on app-release-unsigned.apk: empty |
| P0.7 | done | .github/workflows/ci.yml, actions pinned by SHA; run 39137521404 on 3a27425: success (gradlew build + apkanalyzer check) |
| P0.8 | done | OC1 report 18 October 2026, built-in browser (owner choice; Chrome device file missing): Lizzner Apps, 1+ downloads, $0.19, updated 25 Sep 2026; 6 screenshots: 3 orbit renders (soft low-res clouds, plain globe from far, thin limb glow), 3 settings screens (zoom, tumbling, orbit speed, cloud coverage, weather with location). No Cupola interior, no real ISS orbit ("does not track the station's actual position"), not photoreal; shares location data. Coverage of Cupola's niche: low. GO (NICHE) unchanged |
| P0.9 | done | Blue Marble 2002 clouds mirrored to local/sources/bm2002-clouds (8192 TIF, 21600 px E and W PNG halves; sizes match the server, PNG IEND present, TIF header valid, pixel decode not checked); tools/textures/sources.lock with SHA-256 |
| P1.1 | done | Tle.parse with checksum, line and catalog validation; TleTest 5/5 |
| P1.2 | done | SGP4 near-Earth port of python-sgp4 2.27 (6e7428d, MIT); TcppverTest: 9 near-Earth satellites, 158 lines, max deviation 1.0e-8 (limit 2e-7), error codes 22312->1, 28350->1, 28872->6, 29141->6 at the tcppver steps; 24 deep-space rejected |
| P1.3 | done | UTC->TT leap-second table (valid to 2027-06-30, IERS Bulletin C 72), GMST82, TEME->ECEF->WGS-84; GMST+eq. of equinoxes vs Horizons LAST at 10 epochs 1950-1960 (UT1 tags): max 0.0205 s (limit 0.1 s) |
| P1.4 | done | Sun and Moon vs Horizons, 50 epochs 2026-2035: Sun max 0.00838 deg (limit 0.01, thin margin: Meeus low-precision Sun), Moon max 0.0033 deg (limit 0.3); topocentric Moon parallax and phase tests |
| P1.5 | done | Conical shadow with penumbra ramp linear in disk overlap; 420 km, beta 0: cylindrical 38.7525 % (recomputed; the carried 38.9 % was slightly off), conical 38.7536 % |
| P1.6 | done | DragGuardedPropagator (ISS TLE of 18 Oct 2026, T = 14 d): (a) identical to SGP4 every 60 s up to T; (b) jump within distance flown + 1 mm; excess over plain SGP4 0.54 mm at h = 1 s, 32.7 mm at h = 60 s, which is the removed decay rate (0.53 mm/s), checked against 2x that rate (controller ruling); velocity excess 1.9e-5 m/s; (c) largest averaged-altitude deviation 0.107 km in 10 years (T altitude 425.616 km); (d) no SGP4 error in 10 years; (e) epoch->T drop 0.6375 km. orbit tests 34/34 |
| P1.7 | done | `:tools:geoindex` generator (deterministic, test) -> core/orbit resources geo/: cities.bin 1,625,675 B (34,140 cities, 164,756 Latin-script aliases; PPLX search-only, historical codes dropped), marine.bin 68,095 B (0.1 deg raster, rivers excluded), NOTICE.txt; total 1,694,244 B <= 2 MiB. Label rule (controller ruling): within 50 km max population/(d+5 km)^2, else sea name, else nearest city. 20/20 fixtures, "Wien" -> Vienna, "Sao Paulo"/"são paulo" -> São Paulo, Kawasaki not Tokyo; GeoIndexTest 28/28; label 0.046 ms, load 0.8 s cold on desktop JVM |
| P1.8 | done | PassPredictor (min elevation 10 deg, controller ruling for "visible cap"): 5 cities x 7 days vs 1 s brute force: Vienna 39, Sao Paulo 25, Tokyo 32, Anchorage 20, Nairobi 18 passes, all matched, largest start/end/closest difference 0.997 s (grid limit); pass found at epoch + 400 d |
| P1.9 | done | OrbitModel.stateAt facade on DragGuardedPropagator; OrbitModelTest 5/5 (geocentric latitude <= i + 0.1 deg; geodetic <= i + 0.25 deg, because the geodetic sub-point lies about 0.18 deg higher). Full `gradlew build` exit 0, 115 tests |
| P2.1-P2.2 | done | Vulkan core + JNI facade NativeRenderer (merge 7d43a80). Threading: Kotlin HandlerThread `CupolaRender` + Choreographer (controller ruling). A35 (Mali-G68, Vulkan 1.3.219, ASTC yes, timestamps yes; owner ruling: no emulator): red with a constant-colour shader "first mismatch at pixel (0, 0): expected [0, 0, 0, 255] actual [128, 64, 0, 255]"; green `am instrument`: OK (3 tests), OffscreenPatternTest byte-exact 64x48 + SurfaceTargetTest swapchain readback. Validation layer loads in debug. llvm-readelf: all LOAD 0x4000 (debug, release). Release APK lib/ holds only arm64-v8a/libcupola_render.so. JVM 52 tests green. Open: `connectedDebugAndroidTest` exits 1 although the device reports 3/3 passed (host-side, undiagnosed); risk review after P2.4 |
| P2.3 | done | FrameScheduler 10, LookAroundController 13, PreviewClock 7, QualityGovernor 14 JVM tests green; thermal headroom listener is API 36 (javap android-36 vs 35), polling on 33-35 |
| P2.4 | done (risk fix round open) | CupolaWallpaperService (@AndroidEntryPoint), RendererHost singleton (one NativeRenderer per process, ref-counted), RenderSession, OrientationSensor, TapDetector (merge 89f0c7b); 23 JVM tests. A35: livepicker restored for user 0 with owner OK (`cmd package install-existing com.android.wallpaper.livepicker`; undo `pm uninstall --user 0 ...`); preview of com.baer.cupola.debug shows the full-screen test pattern 1080x2340, flags=1 (system), game rotation vector 50 Hz while visible and unregistered after back, no crash or ANR. Gaps: FrameInputs has no yaw/pitch slots (look-around not yet sent to the shader); validation layer absent in the :app debug APK; QualityGovernor output unused until P3 |
| P2 risk fix | done | Review: 0 Critical, 1 Important (I1), 5 Minor. Fix round (merge 24d5daa): RenderTargets (no Vulkan failure throws; failing id dropped; DEVICE_LOST re-attaches once), native dead targets + fence reset before submit, M1 100 ms acquire timeout, M2 frame-rate vote generation, M3 dirty_ kept; look yaw/pitch push constants; :app ndkVersion (release .so 996,920 B -> 122,896 B). Red then green: RenderTargetsTest 8 failed with IllegalStateException, layout and vote tests failed first. Controller: fresh unit runs core/render 60/0, app 25/0; full build exit 0; A35 am instrument OK (4 tests) incl. DroppedSurfaceTest (closed ImageReader -> VkResult -1000000000, id dropped); 0 VUID lines; preview renders, sensor unregisters, no crash or ANR. Validation layer is in app-debug.apk (earlier absence came from a worktree without local\). Opus re-review: approved, no Critical/Important. Minor, not fixed: `recovering` not reset after all surfaces drop; DEVICE_LOST on attach not recovered; resize not replayed after recovery; dead target may destroy a pending acquire semaphore; 100 ms timeout honoured only on Android 11+ (inferred). Look-around shift not yet seen on the device (phone still) |
| P2.5 | done | docs/device-checks.md (branch p2-6-gate): 16 KB pass; OC2 not forwarded on One UI 8 (no onCommand for taps/swipes; sleep/wake commands arrive) -> fallback info in settings only; OC4 partial (picker offers lock, engine flags=3; no keyguard on the A35); two engines on one VkDevice pass, PSS 122,834 kB. Other automation on the A35: paused permanently by the owner, 18 October 2026 |
| P2.6 | done | Owner ruling 18 October 2026: intended still frames are counted separately from the trace rule (moving 99 % in 16.7 +- 2 ms, still 66.7 +- 2 ms, nothing above one still period). | Gate tooling merge 00cba2e; fixes on branch p2-6-fix, causes and numbers in docs/device-checks.md: frame-rate vote 60 whenever rendering (controller ruling; the 15 vote let SurfaceFlinger lower the render rate), motion and settling measured on the view sphere, speed from SensorEvent.timestamp. Red then green: FrameSchedulerTest "expected 60 but was 15", RestingSensorReplayTest (40 s real A35 samples) 122 of 140 frames moving, LookAroundControllerTest bunched delivery 46 moving frames. Unit tests core/render 62/0, app 35/0, full build exit 0. A35 rerun at load 7: trace 2942 moving + 164 intended still + 1 other interval, max 67.1 ms (raw rule 94.69 %, 99.97 % with still frames excluded); still 15.0 fps PASS (load 0 and 7). Hidden and battery saver not rerun (their vote and scheduler paths unchanged). Battery cost of the 60 vote (app vsync at 60 Hz while still) to be measured in G-PERF |
| P3.1 | done except GPU budget | Merge 797a23f (p3-earth): SceneInputs (LVLH from Blender = Rz(-30 deg), measured velocity azimuth +30 deg, JVM test red with identity), shared texture set held per process, WGS-84 ray cast, monthly blend + detail, terrain normal, glint, water_mask, deep-ocean albedo (openOceanIsUniform 1.333 -> 0.260, limit 1.0), interior panorama composite from the r3 bake (interior ASTC 15.14 MiB, total about 88.6 MiB; interior_*.ktx2 UNTRACKED until the P3a milestone, rebuild with `build_textures.ps1 -Assets interior -BakeDir locallender3ake`), scaled Earth pass (QualityGovernor 0.4/0.5/0.6, default 0.6) with its own descriptor set. Fixed: 60 x VUID-vkCmdDraw-imageLayout-00344; abort `__thread_specific_ptr construction failed` caused by recreating instance/device/texture set on every surfaceless offscreen render (117 times), now held. Controller A35 rerun 20 October 2026: `:core:render` OK (10 tests), app OK (12 tests), 0 VUID each; main `gradlew build :earthpack:checkTexturePack :app:checkReleaseManifestPermissions` exit 0. GPU (offscreen, 1080x2340, GPU clock seen 351-455 of 949 MHz): NADIR scale 0.6 p50 10.40 / p95 10.59 ms (Earth 5.56, composite 4.95); scale 0.5 p95 9.38 ms; Earth-only full res p50 11.06 ms. OPEN: full-scene budget p95 12 ms must also hold atmosphere and clouds; next levers: merge static interior layers at pack time (interior fetches cost about 2.85 ms), surface-based timing at real clocks|
| P3a | in progress | Probe poses iss073e0764640 (73.6 deg FOV) and iss022e066972 (89.3 deg) render on the A35 (now with deep-ocean colour), still without atmosphere and clouds; owner comparison after P3.2/P3.3 |
| P3.2-P3.5 | done | Release ledger rows R1-R4 (handoff-evidence v1-release/LEDGER.md), all verified on the A35 23-24 October 2026: P3.3 atmosphere 1bb287a (exposure with airlight, AtmosphereLutTest 5), P3.2 clouds eb2b134 (Blue Marble 2002 cover advected by a zonal wind profile, cloud tops, shadows; EarthRenderTest 21/21), P3.4 night side b6d5694 + b40f306 (Black Marble city lights, moonlight, stars and Milky Way, Moon with phase, airglow, aurora, lightning), P3.5 Sun disc, bloom, window glare and auto exposure 9f91a62 (sun-in-frame exposure 481c075). SHAs are post-rewrite |
| P3.6 | partly done | Exterior parts from P4.6 are rendered and lit (ledger P4.6 row: Russian segment parts and 8 array wings + IROSA visible at rest, Canadarm2 and radiators not visible). Not verified: arrays tracking the Sun. Judge the rest at G-PR |
| P4.1 | done | EOL back 20 October 2026 (ISS038 E 5000 returns nadir 16.9 S, 107.9 W, sun elevation 75): `eol` objects filled; dev 17 = 9 ok + 8 not in EOL (empty page), hold-out 8 = 5 ok + 3 not in EOL (cache manifest only; controller check: 0 hold-out IDs in tracked files); EXIF vs EOL time 0 s on 7 dev photos (camera clock is GMT). Nadir-probe candidates: iss073e0764640 (sun 61 deg), iss022e066972 (sun 45 deg). Earlier: Hold-out swap 18 October 2026 (worker report p4-1-holdout-swap.md, IDs only in the cache): the two exposed photos removed, hold-out now 8 = 5 day + 2 twilight + 1 night (night slot: full frame, stars, faint airglow, Earth almost black; EXIF lens data unreliable). Controller check: 25/25 manifest files match bytes and SHA-256, 0 excluded IDs in the hold-out, all hold-out files under holdout\. Earlier state: | 25 photos in local/reference (dev 17 incl. iss035e010551, iss038e013587, iss073e0025760; holdout 8 = 3 twilight + 5 day, IDs only in the cache); dev metadata in reference/dev-set.json; clip frames f060/f120/f180 extracted with Blender 5.2. Owner 18 October 2026: night photo with a dark or barely visible frame accepted; swap the two hold-out photos whose IDs appeared as text (swap in progress). EOL blocked (measured 18 October 2026 17:44 UTC): `photo.pl` answers HTTP 200 "***** SQL failed *****", header "Invalid object name 'GeoRef'"; nadir point and sun angles missing until EOL works (needed by G-PR, not by P4.2-P4.7). Retry 18 October 2026 about 20:30 UTC: still HTTP 200 "***** SQL failed *****" |
| P4.2 | done | Merge fa98d13: tools/blender build_cupola.py, measure_apertures.py, render_preview.py. Red: top aperture 0.60 m -> exit 1 "top_d 600.00 vs 706.00 FAIL"; green: 7 apertures PASS, all differences 0.000 mm (controller re-run: build exit 0, `APERTURES=7 RESULT=PASS`, measured from mesh polygons). Determinism: two builds give the same dump (31 objects, 11,640 vertices, 7,861 faces, identical SHA-256). No text objects or image textures, 19 procedural materials. Previews in local/blender/preview (rest view + 3 dev poses, compare_*.png); window layout matches iss038e013587. Open for P4.7: flat grey look, procedural clutter (decision 16 risk); outer size 2.95 x 1.5 m and hatch 1.27 m carried, not re-checked |
| P4.3 | done | Owner ruling 18 October 2026: the settings offer two rest directions, Cam_Rest diagonal and nadir (straight down); envelope recomputed with both (branch p4-4-bake, NADIR_REST, self-test red NameError then green): all aspects 5 faces, 3.799 face-equivalents, est. 19.5 MiB (diagonal only 16.7; six side windows 23.2, rejected for budget). Earlier: tools/blender/visible_region.py (self-test red then green): yaw +-26, pitch +-22 deg, 14 mm on 36 mm (104.3 deg long axis), phone + tablet + foldable aspects -> 5 faces (+X -X +Y -Y +Z, never -Z), cropped 3.25 face-equivalents with a world-aligned cube, est. 16.7 MiB vs 15.4 planned (total about 88.0 of 96 MiB); best rotation found saves 0.17 face. Re-run 24 October 2026 from the 1.55 m eye (ab64041): 5 faces, 3.433 face-equivalents, est. 17.6 MiB; widest view ray 107 deg from +Z (was 118), still no -Z |
| P4.4 | done (merged into main 20 October 2026) | tools/blender/bake_panorama.py (Opus worker, report cupola-ref/reports/p4-4-bake.md): OptiX RTX 5060 Ti, one render per face with light groups earth/cabin/emissive + 512 px ambient render, OIDN per pass (albedo + normal); 128 spp, seed 1, 137 s for 5 faces; 20 EXR 401 MiB, 20 PNG 10.5 MiB in local/blender/bake; crop = two-rest-view envelope (3.80 face-eq). Orientation check red (no row flip/mirror: worst 2047.059 texels, 64/64 bad per face) then green (worst 0.087 texel, both grids). Determinism: EXR hashes differ (max 3.5e-6), PNG max diff 1/255 (acceptance met via the 1/255 rule). Noise vs 4x reference, 2 tiles/face: MAD earth 0.0010, cabin 0.0007, emissive 0.0002, ambient 0.0027 (limit 0.01). Scales earth 0.2024, cabin 2.762, emissive 1.047, ambient 0.2009; alpha-0 fraction per face 0.19-0.38. Controller check (independent): Laptop_Screen centre projects to -X (651, 1287) by the contract; near-white emissive pixels centre at (604, 1274); a mirror would give x 1397. No opus risk review (contract proven by the orientation test and the geometry check). Risks: earth RGB premultiplied; emissive scale set by the laptop screen; 0.01 MAD is loose, so judge previews in P4.7. Re-bake 24 October 2026 (a70a3bd, 1.55 m eye, CBM passage closed): BAKE_OK 128 spp, orientation PASS (worst 0.087 texel), noise PASS (worst ambient 0.0066); scales earth 0.2256, cabin 3.5687, emissive 4.8031, ambient 0.2795; atlas 2048 x 7296 (ETC2 half), interior ASTC 13.69 MiB (was 15.14) |
| P4.5 | done | Ledger row R5, 0438552 (24 October 2026, A35): reflections of the cabin light in the panes (pane picked from the ray, normals from build_cupola.py, Schlick F0 0.08) and hashed dust specks lit like the window glare; EarthRenderTest reflectionsShowTheCabinInNightGlass and dustSparklesOnSunlitGlass red then green; motion GPU p95 16.3 ms, still p50 39.4 ms at the minimum clock. Earlier plan: glass reflections from the cabin layer sampled in the reflected direction plus procedural dust in the shader (no extra texture; budget left 3 MiB was given to P4.6). Reflections toward -Z (no baked face) use a constant Node 3 cabin radiance (2906121); reflectionsShowTheCabinInNightGlass on the Pixel 7 Pro: mean +0.52, 9662 of 157950 samples brighter |
| P4.6 | done (visual pivot check and owner branding check open) | Merge 61b0d63 (ae95e74): IGOAL GLB 95,875,648 B in local/sources/igoal + sources.lock row; export_station.py, station_mesh.py reader (self-test red on bad magic/version/truncation/trailing bytes, green on the real file), build_station.ps1. 11 nodes, 37,110 triangles; station.mesh 1,716,385 B + ASTC albedo 626,752 + ORM 350,032 = 2,693,169 B (controller limit 3.0 MiB); ETC2 175,216 B each. Two exports identical SHA-256. Controller rerun `:earthpack:checkTexturePack :app:checkCredits` exit 0. Visible at rest: nadir-side Russian segment parts (MLM, MRM1, node, covers) and 8 array wings + IROSA by joint sweep; radiators and Canadarm2 not visible. Measured: Cupola windows at 30 + 60k deg from velocity in IGOAL, so velocity is at +30 deg azimuth in the Cupola frame; controller ruling 20 October 2026 (owner AFK, recommended option): renderer uses the measured 30 deg, not identity. Open: atlas fill 29 %; array pivots not proven visually; decals not inspected beyond *Logo* meshes; station textures were committed (small; deviation from the milestone rule, accepted) |
| P4.7 | in progress | Round 1 merged d7180bb: satin anodized frames, denser hardware, procedural preview Earth; apertures PASS (0.000 mm), dump SHA-256 stable (55,644 vertices), branding inventory clean, bake 128 spp BAKE_OK (8 min 39 s), noise worst ambient 0.0078 (limit 0.01), orientation worst 0.019 texel; bake PNGs 11 -> 17 MB. Controller view of compare_iss038e013587: better, still CG (sparse large clutter, boxy bags, repetitive bolts). Round 2 merged 44f88da (35b0bc5): bag BMesh bug fixed (new-vertex detection broke after subdivision), clutter, cloth bags, rack walls, BMNG preview Earth; controller rerun of measure_apertures on r2: APERTURES=7 RESULT=PASS, inventory empty; worker: dump stable (90,630 vertices), BAKE_OK 486 s, noise worst ambient 0.0079, orientation 0.019 texel, PNGs 17.50 MiB. Controller view: denser, still underexposed, no colour accents. Round 3 merged 9dfb295 (c8a23d8): Standard view transform (was AgX), material albedos raised (anodize 3 %, shell 10 %, panels 20 %), screens/LEDs scale with cabin light, colour accents, molded cameras, laptop with keyboard and procedural screen; controller rerun measure_apertures r3: PASS, inventory empty; worker: dump stable, BAKE_OK 218 s, noise worst ambient 0.0068, orientation 0.019 texel, PNGs 18.16 MiB, emissive scale 4.80. Measured: day interior median 27 vs photo 37 (r2 14); night median 33.4 = photo. Remaining tells: day still darker (only lever left is a gain policy), even night light, grid-like screen UI, uniform black frames. Not started: round 4, because owner review of rounds 1-3 is pending (decision 25); images sent to the owner 20 October 2026. Round 4 (owner 24 October 2026, merge a78f3a5): eye 1.55 m from the nadir pane, black bezel ledges, dark bolts (B, C); day cabin tone target = the Earth-lit photos iss022e066972 / iss029e043374 (A). Test dayCabinToneMatchesTheEarthLitPhotos replaces the floor test dayInteriorIsNotDarkerThanP31 (median 27): frame median 7-26 and at least 40 % of pixels below luma 20 at lens 89.3, sun el 45.1. Gains EARTHLIGHT 6.5, EARTH_FILL 1.0 (was 18 / 6). Pixel 7 Pro (ASTC slice), merged main: app androidTest 42/42 (median 9.3, dark 41.7 %; goldens 74.7-82.9 dB), core:render 15/15; JVM app 107, core:orbit 71, core:render 106, settings 36, tle 25, 0 failures. Device metrics at 1464 x 1000: nadir window 27.5 deg, bezel median 15 (limit 40), top plate > 150 0.0 %. Open: station.mesh not re-exported for the new eye (blocked on this PC 25 October 2026: local/sources/igoal is absent, the IGOAL GLB must be mirrored again per sources.lock); the diagonal rest view is only 19.5 deg off nadir (owner decision); thin bright bezel rings at grazing angles (judge at G-PR) |
| P5.1 | done | 24 sources mirrored to local/sources (12 BMNG 2004 monthly world.topo.bathy, 8 July 2004 86400 tiles, Black Marble 2016 colour + gray, ESO eso0932a TIF CC BY 4.0, ETOPO 2022 60 s surface GeoTIFF; ETOPO chosen over GEBCO, CC0) plus the earlier BM2002 clouds, GeoNames, Natural Earth; every size equals the server Content-Length; rows with SHA-256, licence and credit in tools/textures/sources.lock; controller re-hash of 2 files matched |
| P5.2 | done | tools/textures/bootstrap.ps1 + tools.lock (GitHub release digests). Red: one changed hash digit -> "checksum mismatch for astcenc", exit 1, nothing extracted. Green: astcenc v5.7.0, ktx v4.4.2, vips-8.18.7 report their versions; rerun uses the cache. astcenc hash also matches Arm's release-sha256.txt |
| P5.3 | done | Merge 424a072: tools/textures/build_textures.ps1 + README (runtime contract for P5.5). Measured: no earlier pinned tool encodes ETC2; owner ruling 18 October 2026: Compressonator CLI 4.5.52 (MIT) pinned (file hash, GitHub gives no digest; bootstrap red one-digit hash refused, green prints V4.5.52; bootstrap needs PowerShell 7). Controller: ASTC 6 files 71.294 MiB (plan 71.3), ETC2 6 files 32.321 MiB, `ktx validate` 0 failures on 12 files, stars source SHA-256 still matches sources.lock, day and normal previews correct (orientation, flat oceans). Worker: two runs identical SHA-256 on all 12 files; day tile order check 0.958 (swap scores 10.34/45.43, fails above 4). Open for P5.5/P3: normal strength factor 10 is a guess (tune on device); clouds linear, city lights sRGB; no wrap at 180 deg and per-tile resampling, so the shader clamps at tile edges |
| P5.4 | done | Owner ruling 18 October 2026: textures in plain git (not LFS), commit texture sets only at milestones (P3a result, G-PR candidate). `:earthpack` install-time pack (AGP 9.4.1 `com.android.asset-pack`), `bundle.texture.enableSplit`; textures `textures#tcf_astc/` (ASTC) and default `textures/` (ETC2). Guard `:earthpack:checkTexturePack`: red missing ETC2 `stars.ktx2` "Texture folders differ: only ASTC [stars.ktx2]"; red planted 110,602,080 B "budget is 100663296 (96 MiB)"; green ASTC 6 files 74,757,472 B (71.3 MiB), ETC2 6 files 33,890,888 B. Credits: `app/src/main/assets/credits.tsv` + `:app:checkCredits`, red without the ESO row "No credits.tsv row for: ESO/S. Brunier (CC BY 4.0)", green 7/7. bundletool 1.18.3 on the release AAB: `get-size total` A35 spec 55,971,589 B, ETC2 spec (A35 spec without ASTC GL extensions) 19,868,368 B; `extract-apks` gives `earthpack-astc.apk` (A35) or `earthpack-other_tcf.apk` (ETC2), files stored uncompressed at `assets/textures/<name>.ktx2` (suffix stripped). `assembleDebug` APK has no pack (measured). A35: `EarthpackLoadTest` red on the plain debug APK (status 1 ASSET_OPEN for all 6), green after `install-apks` of the debug bundle (`split_earthpack.config.astc.apk`): all 6 ASTC files load with README format/size/layers/levels, Java heap growth 16 kB (limit 10 MiB, `summary.java-heap`), 0 VUID lines. Full build exit 0 (app 35/0, core/render 62/0, core/orbit 70/0, tools/tle 25/0) |
| P5.5 | done | Merge 22e79d4 (commit 8ef60df): ktx2_reader, texture_loader, JNI loadTextureInfo, host ctest (MSVC) 9/9 incl. all 12 real files, A35 TextureLoaderTest 6/6, full instrumented suite 10/10, 0 VUID lines. Opus risk review: approved, 0 Critical, 0 Important, 6 Minor not fixed (no per-format image limits check; final barrier fragment stage only; NPE instead of OOM on a JNI string failure; no pixel readback and the 32 MiB split path untested; one fence wait per chunk blocks the render thread; import order); controller re-read of the split path agrees. Merged main: full build exit 0 (core/render 60/0, app 34/0); CI run 39158280206 success incl. "Native host tests" (g++) |
| P6.1-P6.5, P6.8, P6.9 | done (device checks open) | Merge f36e0c2 (ee16f59 data layer, f2a36c4 UI): Settings/Info/Home city/Credits/About screens, Navigation 3, dark dynamic theme, edge-to-edge, predictive back flag, live preview port, set-wallpaper intent, share via FileProvider (`${applicationId}.share`), credits from credits.tsv + python-sgp4 notice. Controller rerun: `gradlew build` exit 0, feature:settings 22/0, app 49/0, release permissions = DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION only. Red shown by mutation (worker). Open: A35 run of preview, share (FileProvider end to end), Accessibility Scanner, TalkBack, edge-to-edge screenshots, settings reach the wallpaper within 1 s (wiring batch after P3); homeCityId is a "name|cc|lat|lon" key (index has no GeoNames ids) |
| P6.6 | excluded | OC2 failed on One UI 8 (no tap forwarding); info in settings only, as planned |
| P6.7 | done | Ledger row R8, f35f070 (24 October 2026, A35): home city -> RenderSettings.homeCity -> HOME_CITY input -> earth.frag ring + dot at constant screen size; EarthRenderTest homeCityMarkerSitsOnTheCity within 0.7 px of Vienna by day and night, no marker with the city behind the Earth |
| P7.1 | done | ci.yml installs ndk;30.0.16248370 and cmake;4.1.2 for the native build; `./gradlew :app:bundleRelease` step with :earthpack (commit 26ef0e9). CI run 39162179046 on f0ca473: success, steps build, bundleRelease, Native host tests, apkanalyzer all success |
| P7.2-P7.4 | done | `:tools:tle` fetch/validate/guard (merge a89fcb6), 25 JVM tests; red: 13 tests against stubs. Controller checks: CLI rejects a hand-edited TLE "checksum: line 2 checksum is '4' but computes to 5"; release guard with -Pcupola.releaseTag=true fails at 13.9 days. Bundled TLE epoch 2026-10-18T03:28:15Z in app/src/main/assets/orbit/. Live run 39150060891 (owner enabled "Allow GitHub Actions to create and approve pull requests"; default token stays read): success, opened PR #1 with epoch 2026-10-18T11:12:47Z; owner said merge now: TLE checksums recomputed (line 1: 9, line 2: 6, both match), merged as ba08be9 |
| P7.5-P7.6 | done | Live checks 24-25 October 2026: pages.yml runs 40135543002 and 40136977320 success, https://it-baer.github.io/cupola-android/orbit/iss.tle and iss.json live (catalog 25544, epoch 2026-10-24T05:01:11Z, checksums valid). Pixel 7 Pro end to end (debug 00727c2 and the R8 release build): wallpaper visible -> files/orbit written (epoch 2026-10-24 over the bundled 2026-10-18) -> Info "Orbit data from 2026-10-24"; toggle off -> no attempt; airplane mode -> no files, data in use kept, 0 FATAL; restart without files -> bundled set. Details in the release ledger rows N2 and ST10. Commit 8742167. Red: new tests failed to compile before the code; swap test red when the session captured the orbit once. Green: `:tools:tle:test :app:testDebugUnitTest :feature:settings:testDebugUnitTest :core:render:testDebugUnitTest :app:checkReleaseManifestPermissions :app:assembleRelease` exit 0 (tle 25/0, app 107/0, settings 36/0, render 106/0). OC3 red: planted ACCESS_NETWORK_STATE fails the guard. apkanalyzer: INTERNET + DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION only. Action pins: upload-pages-artifact v5.0.0 fc324d3, deploy-pages v5.0.1 368f825 |
| P8.1-P8.6 | open | |
| G-PERF | open | |
| G-PR | open | |
| OC1-OC6 | open | |
