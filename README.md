# Watch Data Sync

A model-first Android app for communicating with Fastrack/Titan smartwatches directly over Bluetooth Low Energy (BLE), without depending on the vendor companion app.

## Current milestone

The first milestone is **BLE/GATT discovery**, not yet full health-data synchronization.

The app can:
- scan for nearby BLE devices
- connect to a selected device
- discover GATT services and characteristics
- expose characteristic properties
- log GATT events and notification payloads as hexadecimal bytes
- keep the vendor protocol isolated behind a protocol adapter layer

The exact health-data protocol is intentionally not hard-coded until it is identified for the target watch model.

## Project layout

- `android/` — native Android application
- `Ai required resources/` — project memory, architecture, rules, phases and design notes
- `docs/PROTOCOL_DISCOVERY.md` — reverse-engineering workflow and evidence format

## Build

Open `android/` in Android Studio, using JDK 17 and the Gradle/Android plugin versions declared by the project.

The app targets Android API 37 and uses Jetpack Compose.

## Important

Fastrack has multiple watch families and companion apps, and the public product catalog contains many different models. The BLE protocol must therefore be discovered per compatible model instead of assuming one UUID/packet format for the whole brand.
