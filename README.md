# Watch Data Sync

Minimal FT_38093 Android companion focused on live heart rate and watch time.

## Included

- Auto-connect to the saved FT_38093 watch.
- Auto-reconnect while the background service is running.
- Automatic watch-time sync after the live HR channel is enabled.
- Live heart rate in the app.
- Session average, minimum and maximum BPM.
- Lightweight live trend graph held in RAM only.
- Ongoing visible notification with the current BPM and session stats.
- Optional floating heart-rate overlay.
- Foreground connected-device service for background BLE notifications.
- ARM64-only APK output.
- R8/minified release build with resource shrinking.

The app intentionally does not implement health history, steps, calories, distance, SpO2, sleep, battery history, diagnostics, or other watch data.

The saved watch address is stored locally so the app can reconnect automatically after the app is opened again and after supported service restarts.
