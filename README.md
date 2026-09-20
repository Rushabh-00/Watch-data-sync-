# Watch Data Sync

Minimal FT_38093 Android companion focused on live heart rate and automatic watch time synchronization.

## Included

- Saved FT_38093 device with automatic reconnect.
- Automatic time sync after the live-heart-rate channel is enabled.
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

The app keeps the FT_38093 GATT live-data subscription active in a connected-device foreground service and periodically re-subscribes when the stream goes quiet.

The watch firmware may still stop transmitting live heart-rate packets after its own live-measurement timeout. The currently verified protocol evidence confirms the E5 11 00 BPM notification frame and the GATT live-data channel, but does not establish a separate keep-alive/start command that forces continuous sensor streaming while the watch display is off. The app therefore reconnects/re-subscribes when possible instead of inventing an unverified command.

## Scope

No health history, steps, calories, distance, SpO2, sleep, battery history, diagnostics, or other watch-data synchronization is included.

## Notification icon

The status-bar icon follows the current BPM using a generated monochrome notification icon, modeled on the approach used by the open-source Beam Android app, which renders a value into an Icon from a bitmap for its notification indicator.

## Build

GitHub Actions runs tests, builds the ARM64 release, signs it with the repository signing secrets, uploads the APK artifact, and creates the GitHub Release.
