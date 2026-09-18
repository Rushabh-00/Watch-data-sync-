# Design

## Main screen

The first build uses a BLE lab screen with four areas:

1. connection state
2. nearby BLE devices
3. discovered GATT services/characteristics
4. raw event log

## Product UI direction

Once a protocol is verified, replace the lab emphasis with:

- Watch connection
- Sync now
- Last sync
- Today
- History
- Data types
- Diagnostics

## Diagnostics

A hidden/advanced diagnostics screen should keep the raw GATT metadata and packet capture available because it is essential for compatibility work.

## Data model direction

Normalize vendor records into:
- ActivitySample
- HeartRateSample
- BloodOxygenSample
- SleepSession
- WorkoutSession
- DailySummary

The protocol adapter can emit these records without exposing vendor byte arrays to the rest of the app.
