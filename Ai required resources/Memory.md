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
