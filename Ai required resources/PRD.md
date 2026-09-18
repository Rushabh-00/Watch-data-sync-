# PRD — Watch Data Sync

## Product goal

Create an Android application that can communicate with compatible Fastrack/Titan smartwatches directly over BLE and synchronize watch-owned fitness/health records without requiring the vendor companion app for normal data access.

## MVP

1. BLE scan and watch identification
2. GATT service/characteristic discovery
3. Packet/event capture
4. Model-specific protocol adapter
5. Read-only historical data synchronization
6. Local persistence
7. Export/integration layer

## Non-goals for the first build

- firmware updates
- watch-face installation
- undocumented configuration writes
- copying proprietary cloud/account services
- assuming one protocol works for every Fastrack model

## Success criterion

Given one selected compatible watch model, the app can connect, request history, decode at least one supported data class, persist it locally, and repeat synchronization without the vendor app.

## Current status

MVP phase 1: BLE/GATT discovery.
