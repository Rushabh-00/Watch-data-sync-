# Watch Data Sync

Minimal FT_38093 Android companion focused on live heart rate and automatic watch time synchronization.

## Included

- Saved FT_38093 device with automatic reconnect.
- Automatic watch-time sync when the app is opened.
- Watch battery read using the verified A2 command, refreshed automatically while connected.
- Live graph history is RAM-only, bounded to the 3-hour window, and is discarded when the monitoring session stops.
- Background BLE monitoring through a connected-device foreground service.
- Notification status-bar icon that renders the current BPM.
- Current BPM, average, minimum and maximum.
- RAM-only live graph with 5m, 10m, 30m, 1h, 2h and 3h views.
- Tap the graph to inspect a heart-rate sample and timestamp.
- Floating BPM overlay with drag, lock and size controls.
- Edge-to-edge full-screen UI.
- ARM64-only release APK.
- R8/resource shrinking for a small release APK.
- Optional background-monitoring/notification switch.

## Live stream behavior

The app enables the FT_38093 GATT live-data channel in a connected-device foreground service and starts the live stream with the verified UTE/GloryFit-family sequence:

1. `D6 02` selects dynamic/continuous heart-rate mode.
2. After about 1.5 seconds, `E5 11` starts live heart-rate streaming.
3. Live packets are `E5 11 00 <BPM>`.

The reverse-engineered RyzeWaveWatch project documents and uses this exact D6 02 → E5 11 sequence for live HR. This app also monitors the packet flow while connected. When the stream becomes stale, it first re-sends the dynamic start sequence, then refreshes the BLE subscription, and finally reconnects if recovery still fails.

Live-HR recovery is always enabled; there is no separate screen-off setting. Firmware behavior can still vary by device, so the FT_38093 needs real-device validation with the display off.

## Scope

No health history, steps, calories, distance, SpO2, sleep, battery history, diagnostics, or other watch-data synchronization is included.

## Notification icon

The status-bar icon follows the current BPM using a generated monochrome notification icon, and the notification content ends with the watch battery level when available.

## Build

GitHub Actions runs tests, builds the ARM64 release, signs it with the repository signing secrets, uploads the APK artifact, and creates the GitHub Release.
