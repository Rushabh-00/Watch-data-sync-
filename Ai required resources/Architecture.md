# Architecture

## Layers

```
Compose UI
  ↓
ViewModel / application state
  ↓
Sync engine + protocol adapter
  ↓
BLE/GATT transport
  ↓
Fastrack watch
```

## Boundaries

### BLE transport
Owns scanning, connection lifecycle, GATT discovery, reads, writes, notifications, MTU and packet logging.

### Protocol adapter
Owns model-specific UUIDs, command frames, response parsing, checksums/CRC, date/time encoding and record decoding.

### Sync engine
Owns synchronization state, cursor/high-water mark, deduplication and retries.

### Domain
Uses normalized records such as StepSample, HeartRateSample, SleepSession and WorkoutSession rather than vendor packet structures.

### Storage
Uses a local database so synchronization does not depend on a network connection.

## Key design rule

No guessed vendor UUIDs or packet formats belong in the transport layer.
