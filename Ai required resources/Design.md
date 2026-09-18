# Product Design — Watch Data Sync

## Product direction

The app should feel like a modern wearable companion, not a BLE laboratory tool. Lab/protocol information remains one navigation level away in Diagnostics.

## Navigation

Bottom navigation:
1. Home
2. History
3. Watch
4. More
5. Diagnostics

## Home

The Home screen is the product command center:
- connected/disconnected status
- prominent Sync now action
- last sync
- battery
- live heart rate
- latest verified SpO2
- verified sample counts
- evidence-aware status for unverified metrics
- compact protocol-health summary

## History

History is organized by data type:
- heart rate
- SpO2
- sleep
- vendor timelines
- future activity/workout categories only when verified

## Watch

Watch setup and lifecycle:
- discover/connect
- bound watch
- reconnect state
- connection diagnostics shortcut
- forget watch

## More

Feature hub for:
- local profile/goals
- phone utilities
- watch faces
- notification relay
- weather
- integrations
- capability matrix
- privacy/data controls

Every watch-side item must show its evidence state.

## Diagnostics

Diagnostics remains advanced:
- services/characteristics
- raw packet capture
- structured vendor decoders
- sync operation queue
- reconnect events
- export/copy
- feature capture markers

## Visual language

- Material 3
- rounded 20–24dp cards
- strong typography hierarchy
- compact status chips
- meaningful empty states
- restrained information density
- light/dark theme compatibility
- no fake medical confidence indicators

## Product writing

Use precise labels:
- Verified
- Observed structure
- Pending evidence
- Phone-ready
- Not verified

Never label a protocol-family candidate as a verified FT_38093 measurement.
