# Project memory

## Repository

GitHub repository: Rushabh-00/Watch-data-sync-

## Current branch

`dev`

## Current implementation

A native Android BLE/GATT discovery app exists under `android/`.

It currently:
- scans BLE peripherals
- lists devices and RSSI
- connects over GATT
- discovers services and characteristics
- logs service/characteristic metadata
- has a notification logging hook
- provides a protocol adapter boundary

## Evidence state

No Fastrack model-specific UUID/packet schema has been verified yet.

## Important project assumption

"Fastrack" is a product family, not a single protocol. The implementation must identify and lock the target watch model before implementing sync commands.

## Next evidence needed

A real watch capture containing:
- model name
- service UUIDs
- notify/write characteristics
- notification payloads
- one reproducible history-sync request/response exchange
