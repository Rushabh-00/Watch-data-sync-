# Watch Data Sync

Minimal Android BLE companion for the Fastrack/Titan FT_38093 watch.

## Only two functions

1. Sync the watch clock to the phone time.
2. Receive live heart rate.

No history sync, steps, calories, distance, SpO2, sleep, battery, notifications, watch faces, vendor-history decoding, diagnostics, or background health-data sync.

## Verified FT_38093 protocol

- Service: `000055ff-0000-1000-8000-00805f9b34fb`
- Time write characteristic: `000033f1-0000-1000-8000-00805f9b34fb`
- Live heart-rate characteristic: `000033f2-0000-1000-8000-00805f9b34fb`
- Time packet: `A3 YYYY MM DD HH MM SS`
- Live heart-rate frame: `E5 11 00 BPM`

The repository has been rebuilt from a clean root tree. The previous protocol/history/diagnostics resources are not included.
