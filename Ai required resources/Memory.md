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

## Evidence state

No Fastrack model-specific UUID/packet schema has been verified yet.

Standard Bluetooth SIG characteristics are handled only where the watch exposes the corresponding standard service/characteristic. Steps, sleep, workouts, calories and historical vendor records still require an observed model-specific protocol before decoding or syncing them.

## Important project assumption

"Fastrack" is a product family, not a single protocol. The implementation must identify and lock the target watch model before implementing vendor-specific sync commands.

## Next evidence needed

A real watch capture containing:
- model name
- service UUIDs
- notify/write characteristics
- notification payloads
- one reproducible history-sync request/response exchange
- raw packets for each supported history data class

## UI direction

The product UI is now the main app surface. Keep diagnostics available for compatibility work while making the normal user flow:
Watch connection -> Sync available data -> Today metrics -> History.
