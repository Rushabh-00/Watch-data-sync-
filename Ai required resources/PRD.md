# PRD — Watch Data Sync

## Product goal

Create a professional Android companion app for the exact observed Fastrack FT_38093 firmware that communicates directly over BLE, syncs verified watch-owned wellness data locally and provides an evidence-driven path to the broader companion-app feature surface.

## Core principles

- Direct BLE; no Android OS pairing requirement for the app workflow.
- Model-specific adapter.
- Raw packets are captured before interpretation.
- No guessed UUIDs, writes, checksums or field meanings.
- Unknown data stays visible without fake semantics.
- Metrics are wellness data, not medical measurements.
- Sync is retry-safe and idempotent.
- Sensitive permissions are isolated to the distribution that actually needs them.

## Verified product capability

- connect/reconnect
- live heart rate
- heart-rate history
- SpO2 history
- watch battery
- time synchronization
- sleep session markers
- EC vendor-history structure
- 44 FA vendor-history structure
- diagnostics/export
- professional dashboard
- local profile/goals
- phone-side utility surface
- safe sideload distribution

## Broader companion feature target

The public Fastrack Smart World surface establishes the desired companion coverage:
- fitness/multi-sport/sleep
- notifications
- contacts
- calls/SMS
- Google Fit
- weather
- watch settings
- watch faces
- firmware updates

These are product targets only. FT_38093 watch-side commands must still be independently verified.

## Safety boundary

The project will not:
- send undocumented configuration writes
- install watch faces without a verified transfer
- perform OTA/DFU without a model-specific safe procedure
- expose candidate values as authoritative Today data
- ship sensitive identifiers in logs/source
