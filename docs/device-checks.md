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

| Check | Rule | Result |
|---|---|---|
| 60 s rotation trace | >= 99 % of intervals in 16.7 +- 2 ms, none > 33.4 ms | Fail: 3007 intervals, 94.58 % within, 163 above 33.4 ms, max 283.6 ms |
| Still | 15 +- 1 fps | Fail: 32.64 fps mean over 30 s (16-50 frames per second) |
| Hidden | frame counter unchanged for 60 s | Pass: 20542 at 5 s and 60 s after screen off (32 frames in the second before `visible=false` arrived) |
| Battery saver | one frame per visibility change | Pass: 0 frames over 5 s visible, +1 frame on becoming visible |

Trace breakdown (`gate-trace-20261018-205554.csv`): 2844 intervals in 16.7 +- 2 ms and none between 18.7 and
33.4 ms, so pacing while moving drops no vsync. 141 intervals of about 66.7 ms are intended still-mode frames
(the trace holds, and slow sweeps past the clamp where the view moves below 0.2 deg/s). The 22 intervals of
116-283 ms all sit at the switch from still mode back to moving: the first moving frame comes one to four still
periods late. Even with the still frames excluded the rule fails on these 22.

Still breakdown: with the sensor blocked (trace holds) the scheduler runs a clean 15 fps. With the real
`TYPE_GAME_ROTATION_VECTOR` on a resting phone it does not settle, so sensor input keeps the look-around
"moving". Unverified hypothesis: the speed is measured between consecutive samples (about 20 ms apart at
`SENSOR_DELAY_GAME`), so sample noise of 0.01 deg already reaches the 0.5 deg/s still threshold.

Open for P2.6: diagnose and fix (1) the still-to-moving wake latency and (2) still detection with real sensor
noise, then rerun the trace and still checks.
