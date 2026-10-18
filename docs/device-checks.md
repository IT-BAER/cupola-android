# Device checks on the Galaxy A35 (plan P2.5, P2.6)

SM-A356B, Android 16, One UI 8.0, adb <adb serial>. Run 18 October 2026 with the other automation off (all automation
workflows unpublished by the owner), animation scales 1 during the run and 0 afterwards.

## P2.5

| Check | Result | Evidence |
|---|---|---|
| 16 KB pages | Pass | `llvm-readelf -lW`: every LOAD segment aligned 0x4000 in `libcupola_render.so` (debug arm64 and x86_64, release arm64) and the debug validation layer; `zipalign -c -P 16 4` exit 0 on the debug and the release APK |
| OC2 taps reach `onCommand` | Not forwarded | The debug gate logs every `Engine.onCommand`. Single tap, double tap (with "double tap to turn off screen" on and off) and page swipes on an empty home area: no command at all. Positive control: `android.wallpaper.goingtosleep`, `android.wallpaper.wakingup` and `samsung.android.wallpaper.blocktoucharea` arrive. With the One UI switch on (`system/double_tap_to_sleep=1`, the default) the launcher takes the double tap and turns the screen off. Fallback per OC2: info only in settings, no double-tap overlay (P6.6 not built) |
| OC4 lock screen | Partial | The live-wallpaper picker offers home, lock, and home + lock for Cupola. With home + lock the engine logs `flags=3 system=true lock=true` (one engine for both). The A35 has no keyguard configured, so the lock-screen display itself was not seen; enabling a lock screen is a security setting left to the owner |
| Active and preview engine on one `VkDevice` | Pass | Picker preview open over the active wallpaper: two Cupola layers in SurfaceFlinger, no attach failure, no crash; total PSS 122,834 kB (limit 512 MiB). Skeleton only, no textures loaded |

## P2.6 skeleton gate (synthetic load N = 7, GPU p95 12.8-12.9 ms)

Load calibration (`load` iterations -> GPU p95): 4 -> 8.33 ms, 6 -> 11.35 ms, 7 -> 12.84 ms, 8 -> 14.45 ms.
N = 7 is the first step at or above the planned 12 ms budget.

| Check | Rule | First run (00cba2e) | Rerun after the fixes below |
|---|---|---|---|
| 60 s rotation trace | >= 99 % of intervals in 16.7 +- 2 ms, none > 33.4 ms | Fail: 3007 intervals, 94.58 % within, max 283.6 ms | 3107 intervals: 2942 in 16.7 +- 2 ms, 164 intended still frames in 66.7 +- 2 ms, 1 other; max 67.1 ms. Raw rule 94.69 %; 99.97 % with the still frames excluded, none above one still period |
| Still | 15 +- 1 fps | Fail: 32.64 fps (16-50 per second) | Pass: 15.0 fps over 30 s at load 0 and at load 7, 15-16 every second |
| Hidden | frame counter unchanged for 60 s | Pass: 20542 at 5 s and 60 s after screen off | Not rerun (vote and scheduler unchanged while hidden: 0) |
| Battery saver | one frame per visibility change | Pass: 0 frames over 5 s visible, +1 on becoming visible | Not rerun (vote unchanged: 0) |

The trace holds (8 s, 30-39 s) and slow sweeps past the clamp are still by design (P2.3: 60 fps only while moving
or springing back), so the raw 99 % rule cannot pass on this trace; the still frames are counted separately.

Causes, each confirmed by changing one variable (CSV `gate-trace-20261018-205554.csv` and short 9 s trace loops):
1. Long gaps at both ends of every still phase (116-284 ms entering, 133 ms leaving). The 15 fps
   `Surface.setFrameRate` vote let SurfaceFlinger lower the render rate (`renderRate=30.00 Hz` in `dumpsys
   SurfaceFlinger`): present latency rose from 25 to 58 ms in still mode and the switch back to 60 fps waited for
   SurfaceFlinger. With a 60 vote: 0 gaps above 70 ms, still latency 23 ms. Fix: `FrameScheduler.voteFps` is 60
   whenever anything renders; still mode skips vsyncs itself.
2. Still detection with the phone flat on the table (pitch -89.3 deg): yaw is ill-conditioned at the pole and
   jittered 2.04 deg/s (median) while the view direction moved 0.03 deg/s. Fix: motion and spring settling are
   measured on the view sphere (yaw step times cos(pitch)). Replay of 40 s of real samples: 0 moving frames.
3. Remaining bursts (18 fps mean): queued sensor events reached the render thread about 1 ms apart, and speed
   used delivery time. Fix: speed uses `SensorEvent.timestamp`.
