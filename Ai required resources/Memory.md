# Project memory

## Repository

GitHub repository: Rushabh-00/Watch-data-sync-

## Current branch

`main` only for ongoing work. Do not create or use a `dev` branch for new work.

## Current implementation

A native Android BLE/GATT app exists under `android/`.

It currently:
- scans BLE peripherals
- lists devices and RSSI
- connects over GATT
- discovers services and characteristics
- logs service/characteristic metadata
- captures notification/read values
- handles common BLE disconnect statuses including status 19
- reads/observes standard Battery, Heart Rate and Pulse Oximeter characteristics when exposed
- provides a polished product UI with Home, History, Watch and Diagnostics areas
- shows health/fitness categories for heart rate, SpO2, steps, sleep, calories, workouts, activity and battery
- keeps raw GATT data visible for protocol discovery
- automatically remembers the selected FT_38093 watch and attempts to reconnect it on app start
- scanner filters discovery to the target FT_38093/Fastrack watch instead of showing arbitrary BLE peripherals
- excludes already-verified heart-rate packets from protocol-discovery capture and diagnostics logs while keeping live heart rate in the normal app UI
- omits the untrusted standard battery packet from discovery capture
- retains unknown/non-heart-rate captures locally for up to 24 hours for protocol investigation, including across app restarts
- provides feature capture markers for SpO₂, sleep, stress, steps, workout and history-sync tests
- keeps the selected FT_38093 watch bound by address, discovers only the target watch family, and auto-reconnects the bound watch with multiple retry attempts

## Evidence state

The user's FT_38093 watch has a verified live heart-rate channel:
- service 000055ff-0000-1000-8000-00805f9b34fb
- characteristic 000033f2-0000-1000-8000-00805f9b34fb (NOTIFY)
- observed frame E5 11 00 [BPM], where the fourth byte matches the displayed live heart rate

The standard 00002a19 Battery Level value is not trusted for this watch because it reported 100% while the watch showed about 30%. The app therefore hides the battery metric until a watch-specific battery packet is verified.

Steps, SpO2, sleep, workouts, calories and historical vendor records still require observed model-specific protocol evidence before semantic decoding or syncing.

## Important project assumption

"Fastrack" is a product family, not a single protocol. The implementation must identify and lock the target watch model before implementing vendor-specific sync commands.

## Next evidence needed

Use Diagnostics as a capture lab:
- clear old capture before testing a new measurement
- inspect generic byte decoding for unknown packets
- identify the characteristic and packet changes produced by one known watch action or measurement
- later capture a reproducible history-sync request/response exchange
- verify raw packets for each supported history data class before assigning metric meanings

## UI direction

The product UI is now the main app surface. Keep diagnostics available for compatibility work while making the normal user flow:
Watch connection -> Sync available data -> Today metrics -> History.


## FT_38093 sync implementation update

The app now has an automatic FT_38093 sync path on the direct BLE/GATT connection:
- no Android Bluetooth bonding request is made by the app
- the remembered watch address is preferred, but target-name discovery can recover after an address changes
- after FT_38093 GATT verification, the app enables the observed notification channels and runs the verified protocol-family handshake/initialization
- today's activity summary now uses the verified B2 cumulative step-history records (18-byte records); the previous 0x26 interpretation was removed because 0x26 is watch-face/dial protocol, not activity data
- watch battery response, heart-rate history, SpO₂ history and step-history requests are persisted for the product UI
- calories and distance are not fabricated from B2: the verified B2 layout contains cumulative steps plus run/walk subcounts, but no calorie/distance fields
- a safe AA step/sleep status query is also sent so the next hardware capture can identify any FT_38093 status response that carries the watch's displayed calories/distance
- History now shows synced heart-rate and SpO₂ records instead of only raw discovery captures
- the normal Home flow auto-discovers the FT_38093 watch and syncs automatically after connection
- the 14-byte EB 01 ... vendor records observed on 33F2 are retained as protocol evidence but their later fields are still not assigned to steps/stress/workout semantics without direct evidence
- the verified protocol-family 0x32 sleep-stage response is now decoded and shown in History
- workout/stress semantic decoding remains disabled until a verified request/response mapping is observed for FT_38093


## Latest test results — 2026-09-18 22:05 IST

The latest FT_38093 device test confirms:
- Live heart rate is displaying correctly in the app.
- Heart-rate history sync/display is working and appears correct.
- Battery sync is working; the app showed 26% during the test.
- SpO₂ sync is working; History showed a synced 18 Sep 18:00 value of 96%.
- Watch time/date synchronization appears to be working and should remain part of automatic sync.
- The user wants automatic syncing of sleep history, SpO₂ history and time synchronization to remain part of the normal product flow.

Important data discrepancy found:
- The watch display showed about 6001 steps and 347 Cal during the test.
- The app displayed 50030 steps and 377 calories.
- Therefore the current activity-summary decoding/field offsets or units for steps/calories are not trusted and must be corrected before presenting those values as authoritative.
- Do not use the current 50030 / 377 values as correct in the product UI.
- Preserve the raw response bytes used to derive activity totals so the step/calorie field mapping can be rechecked against the watch display.
- Distance was visible on the watch as 2.29 km, so the activity packet should also be validated against the watch's displayed distance when revisiting the field mapping.

Current goal for the next continuation:
- Keep the known-good heart rate, heart-rate history, battery, SpO₂ history and time-sync behavior intact.
- Fix the FT_38093 daily activity decoding so steps, calories and distance match the watch itself.
- Continue automatic sleep-history synchronization and decoding.
- Keep the app on main only; do not create/use a dev branch.

 
## FT_38093 activity/sleep protocol correction — 2026-09-18 continuation

- The previous 50030-step / 377-calorie result came from the earlier daily-activity decoder reading the four-byte flags field as part of the metrics. Matching protocol evidence documents `26 01` as a four-byte flags field followed by little-endian 16-bit Steps, Calories and Distance, then Active Minutes. The captured reference packet `26 01 00 F2 8D C1 01 40 01 7C 01 00 10 ...` therefore maps to 320 steps, 380 kcal, 256 m and 16 active minutes; the corrected field offsets are bytes 6-7, 8-9, 10-11 and 12.
- The FT_38093 implementation now requests `26 01` again and decodes only those documented fields. Raw activity response bytes remain in the rolling diagnostic capture and the sync log.
- B2 step-history packets are no longer allowed to overwrite the authoritative Today summary because their full FT_38093 layout is not independently verified in this project. They remain captured as raw evidence.
- Existing persisted activity data is invalidated by an activity-decoder schema version, preventing the old 50030/377 values from being shown after upgrade. A new verified `26 01` response must repopulate Today.
- Sleep: `31 01` produces session/date markers, followed by `0x32` sleep packets on the channel-2 notification path. The observed matching capture shows `0x32` as repeated five-byte records after the opcode: `HH, mm, stage, duration_hi, duration_lo`. The decoder now parses that layout and `31 02` marks transfer completion.
- The next FT_38093 hardware validation must compare the new `26 01` values with the watch display (target observation was about 6001 steps, 347 Cal, 2.29 km) and confirm that real `0x32` sleep-stage records populate History. Until that hardware run, those target values remain validation targets, not claims of current app output.


## FT_38093 hardware test correction — 2026-09-18 18:10 UTC

- The supplied FT_38093 diagnostics from the latest test show the app did **not** emit a `SYNC_QUEUE` or `WRITE` for `26 01`. The actual sync queue in that capture jumps from `AA` to `B2 FA`, then `31 01`, HR history and SpO₂ history.
- Therefore this test did not exercise the new `26 01` activity probe at all, and the absence of Steps/Calories on History is expected from the captured run. The decoder must not be considered validated from this test.
- The same capture shows `31 01` session markers for 12–18 Sep followed directly by `31 02`, with no `32` or `CB` sleep-stage/batch packets. Sleep decoding therefore also remains unvalidated on this firmware.
- v0.3.2 adds explicit `SYNC_PLAN` diagnostics so a hardware run can verify that the activity probe is actually queued before interpreting any response.
- Do not claim that the watch returned 6001 steps / 347 Cal / 2.29 km over BLE yet. Those remain the watch-display validation targets from the prior observation.
 
## UI + activity response validation iteration — 2026-09-18 18:19 UTC

- The supplied screenshot shows the v0.3.2 diagnostics path now queues and starts the `26 01` activity probe, but the visible History screen still has Steps and Calories as —. The screenshot does not show a decoded `26 01` response, so the activity values are still not validated from the watch in this run.
- The app remains intentionally gated to verified activity data; it must not display the earlier incorrect 50030 / 377 values.
- The sync client now exposes an explicit activity-probe status, waits for the GATT operation queue to become idle before marking sync complete, and marks the activity probe as unverified when no valid `26 01` response arrived.
- Vendor response parsing is allowed on the observed FT_38093 notification channels (33F2, 34F2, 6002, 6102, FD04 and 6487) without assigning those channels new semantics. A valid packet is still accepted only when it matches the already-tested `26 01` activity layout.
- History UI is upgraded with verified Today cards, HR/SpO₂ trend charts, sleep-stage visualization, data-integrity status and a Sync Now action. Chart data is drawn from records already persisted by the app; no synthetic values are introduced.
- Version 0.3.3 is the next validation build. After installation, the critical hardware evidence is a `SYNC_DATA activity=` line with actual 26 01 response bytes followed by Today showing the same watch values.

## Build validation correction — 2026-09-18 18:26 UTC

- v0.3.3 first CI compile attempt reached the Kotlin compile stage but failed only on the new chart file import: Compose `Stroke` must come from `ui.graphics.drawscope`.
- No protocol test failure was reported before compilation stopped. The import is corrected on main before the next build run.
- The GATT queue cleanup also now clears the sync-in-progress state when a connection is dropped, avoiding a stale Syncing UI state after disconnect/reconnect.
