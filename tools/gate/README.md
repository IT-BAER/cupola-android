# P2.6 skeleton gate

Debug build only (`com.baer.cupola.debug`). The release build has a no-op `Gate` and no receiver.
Prereq (plan ground rule 6): other automation paused, animation scales set to 1 for the run, restored to 0 afterwards.

```powershell
$adb = 'D:\Software\Android\Sdk\platform-tools\adb.exe'
$s = '<adb serial>'
$gate = 'com.baer.cupola.debug/com.baer.cupola.wallpaper.GateReceiver'
function Gate { & $adb -s $s shell am broadcast -n $gate @args }
function GateLog { & $adb -s $s logcat -d -s CupolaGate:I }
```

Commands (explicit component, extras `cmd` and `arg`):
`Gate --es cmd load --ei iterations N` (0 = off), `Gate --es cmd trace --es arg start|stop`,
`Gate --es cmd stats --es arg reset|dump`. Output is on logcat tag `CupolaGate`:
`gate id=.. preview=.. visible=.. frames=.. fps1s=.. gpu_p50_ms=.. gpu_p95_ms=.. gpu_max_ms=.. load=..`
(one line per second per surface while frames flow, and on `stats dump`).
Every `Engine.onCommand` is logged as `gate command action=<a> x= y= z= extras=<keys|null>` (all actions, not only taps).

## 1. Install and set as wallpaper
```powershell
.\gradlew :app:installDebug -PgeoSources=D:\VSC\Cupola\local\sources   # or adb install of app-debug.apk
& $adb -s $s shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER `
  --es android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT com.baer.cupola.debug/com.baer.cupola.wallpaper.CupolaWallpaperService
```
Confirm the wallpaper dialog on the phone ("Set wallpaper"), then `& $adb -s $s logcat -c`.
Frames are queued on the BLAST child layer, not on the layer named after the service. On the A35
(Android 16) it is `Wallpaper BBQ wrapper <n>_system...`, so use `-Layer '^Wallpaper BBQ wrapper .*system'`
and pass `-Adb $adb` (adb is not on PATH on BM-PC).

## 2. Load calibration (target: GPU p95 about 12 ms)
Bisect N between 0 and a high bound (start 64): set `load`, `stats reset`, wait 10 s with the screen on,
`stats dump`, read `gpu_p95_ms`.
```powershell
Gate --es cmd load --ei iterations 64 | Out-Null; Gate --es cmd stats --es arg reset | Out-Null
Start-Sleep 10; Gate --es cmd stats --es arg dump | Out-Null; GateLog | Select-String 'gpu_p95' | Select-Object -Last 2
```
p95 above 12 -> halve N; below 12 -> go midway to the last too-high N; stop at 12 +- 0.5 ms.
Record N. Keep it set for sections 3 to 7 (it persists until the process dies; re-send after a restart).

## 3. 60 s rotation trace (60 fps pass rule)
Wake the screen, show the home screen with the wallpaper, then:
```powershell
Gate --es cmd stats --es arg reset | Out-Null
Gate --es cmd trace --es arg start | Out-Null
pwsh .\tools\gate\skeleton_gate.ps1 -Serial $s -Layer '<regex>' -Seconds 60 -Mode trace
Gate --es cmd stats --es arg dump | Out-Null; GateLog | Select-String 'gate trace done|gate id='
```
PASS: >= 99 % of actual-present intervals within 16.7 +- 2 ms and none above 33.4 ms. Exit code 0 on PASS.
The CSV (`gate-trace-*.csv`) holds all de-duplicated rows. Start the script within about 1 s of `trace start`;
the trace ends itself after 60 s (`gate trace done`).

## 4. Still (15 +- 1 fps)
Trace off, no touch, no motion (phone on the table, so the sensor is still), wait 5 s:
```powershell
Gate --es cmd trace --es arg stop | Out-Null
pwsh .\tools\gate\skeleton_gate.ps1 -Serial $s -Layer '<regex>' -Seconds 30 -Mode still
```

## 5. Hidden (no frames)
Note `frames=` from `stats dump`, turn the screen off (or open an app over the wallpaper), wait 60 s,
dump again: `frames` must be unchanged and no per-second lines appear while hidden.
```powershell
Gate --es cmd stats --es arg dump | Out-Null; GateLog | Select-String 'gate id=' | Select-Object -Last 2
# hide, Start-Sleep 60, then dump again and compare frames=
```

## 6. Battery saver (one frame per visibility change)
```powershell
& $adb -s $s shell settings put global low_power 1
```
Dump, toggle visibility (screen off/on), dump: `frames` rises by exactly 1 per visibility change while
visible stays steady with no per-second lines. Restore: `settings put global low_power 0`.

## 7. PSS with active and preview engine
Active engine only, then open the wallpaper picker preview (second engine, `preview=true` in the dump):
```powershell
& $adb -s $s shell dumpsys meminfo com.baer.cupola.debug | Select-String 'TOTAL PSS|TOTAL  '
```
Run once before the preview, once with the preview open; record both TOTAL PSS values.
