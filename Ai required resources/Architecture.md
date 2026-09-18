# Architecture

## Product layers

```
Compose UI
  ↓
ViewModel / application state
  ↓
Sync engine
  ↓
FT_38093 protocol adapter
  ↓
BLE/GATT transport
  ↓
Watch
```

## BLE transport

Owns:
- scan and device selection
- connection lifecycle
- service/characteristic discovery
- CCCD configuration
- MTU negotiation
- serialized reads/writes
- response timeouts/retries
- reconnect handling
- raw packet logging

The transport only knows observed UUIDs and GATT properties. It does not interpret health semantics.

## Protocol adapter

Owns:
- FT_38093 UUIDs
- command frames already validated for the model
- response prefix/completion matching
- date/time encoding
- field decoding
- structural vendor-history parsing

Unknown fields remain opaque.

## Sync engine

Owns:
- sync plan
- operation ordering
- response matching
- retry policy
- completion state
- idempotence/cursors

## Domain

Normalized records:
- HeartRateSample
- BloodOxygenSample
- SleepSession / SleepStageSample
- ActivitySample
- WorkoutSession
- DailySummary

Only verified protocol data can become normalized user metrics.

## Storage

Current release uses local persistence with bounded history/capture retention. The next storage step is a normalized local database with migrations and explicit deletion/export controls.

## Distribution

Two Android flavors:
- `sideload`: minimum permissions, no notification-listener declaration
- `play`: notification relay service included for the legitimate wearable-notification use case

## Key rule

No guessed vendor packet format belongs in transport, UI or domain code.
