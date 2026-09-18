# Watch Data Sync

A professional, model-first Android companion application for communicating with the exact observed Fastrack/Titan FT_38093 smartwatch directly over Bluetooth Low Energy (BLE).

The app is designed to replace the vendor companion app for normal local watch-data access where the exact FT_38093 protocol is verified. It does not rely on Android OS pairing for its BLE workflow.

## Current release

- Version: 0.4.2
- Direct FT_38093 BLE/GATT
- Automatic reconnect
- Verified HR / HR history / SpO2 history / battery / time sync
- Evidence-aware sleep and vendor-history discovery
- Professional dashboard + History + Watch + More + Diagnostics
- Sideload-safe distribution flavor without notification-listener declaration
- v0.5.0 release tested and published by CI
- Play distribution flavor with notification relay support

## Evidence status

The complete-sync capture proves repeatable FT_38093 vendor history structures including EC 01/EC 02 and 44 FA pages/completion markers. Their measurement semantics are intentionally not guessed.

The current FT_38093 captures do not prove that B1/B2 total steps or the 26 01 activity candidate is the authoritative Today activity source, so those values are not promoted into user totals.

## Feature scope

The public Fastrack Smart World surface includes watch management, health, fitness/multisport/sleep, notifications, contacts, Google Fit and weather. Our feature hub tracks these features individually and shows the evidence state for each.

## Build

Open `android/` in Android Studio with JDK 17 and the declared Gradle/Android plugin versions.

CI builds both distribution flavors and publishes the signed sideload-safe APK.

## Project resources

- `Ai required resources/` — architecture, product design, feature matrix, research, rules, phases and memory
- `docs/PROTOCOL_DISCOVERY.md` — evidence-first reverse-engineering workflow
- `android/` — native Android application

## Safety

No guessed commands, no sensitive IDs in source/logs, no undocumented OTA/DFU, and no medical claims.
