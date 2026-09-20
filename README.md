# Watch Data Sync

Minimal Android BLE companion for the Fastrack/Titan FT_38093 watch.

This rebuild intentionally does only two things:

- Sync the watch clock to the phone time.
- Receive live heart rate.

No history sync, steps, calories, distance, SpO2, sleep, battery, notifications, watch faces, vendor-history decoding, diagnostics, or background health-data sync.

Verified protocol used:

- Service: 000055ff-0000-1000-8000-00805f9b34fb
- Time write: 000033f1-0000-1000-8000-00805f9b34fb
- Live heart rate: 000033f2-0000-1000-8000-00805f9b34fb
- Time packet: A3 YYYY MM DD HH MM SS
- Live heart rate frame: E5 11 00 BPM

The repository was rebuilt from scratch. Previous project files and memory documents are not included.
