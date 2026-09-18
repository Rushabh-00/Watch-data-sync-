# BLE protocol discovery

## Goal

Reproduce the subset of the vendor companion application's watch communication needed to read health/fitness data directly.

## Evidence we need

For one specific watch model, collect:
1. advertised device name
2. BLE address (local-only diagnostic value; do not ship it in source)
3. advertised service UUIDs
4. all GATT service/characteristic UUIDs and properties
5. descriptors, especially the Client Characteristic Configuration Descriptor (CCCD)
6. characteristic values produced immediately after connection
7. packets produced after a known watch action:
   - sync/request history
   - request steps
   - request sleep
   - request heart rate
8. packet ordering, length, checksum/CRC, counters and timestamps

## Capture strategy

Use the app in this repository first as a passive GATT explorer:
- Scan.
- Connect.
- Discover services.
- Enable notifications only when a characteristic is clearly a notify/indicate endpoint.
- Record notification payloads before sending any write command.
- Change exactly one watch state at a time and compare captures.

The protocol adapter should only be promoted from "unknown" to "implemented" when a command/response can be reproduced consistently.

## Architecture boundary

The Android BLE layer must not know how a Fastrack packet encodes steps, sleep, heart rate, SpO2, workouts, etc.

```
BLE transport
    ↓
GATT service/characteristic discovery
    ↓
watch protocol adapter
    ↓
domain records
    ↓
local database / sync engine
    ↓
UI / export / Health Connect
```

## Safety

Do not use firmware-update endpoints or undocumented write commands during initial discovery. Start with reads/notifications and only add writes that are required for data synchronization.
