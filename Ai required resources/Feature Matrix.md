# FT_38093 Feature Matrix

This matrix separates the public Fastrack companion feature surface from what this project can currently prove on the exact observed FT_38093 firmware.

| Feature area | App surface | FT_38093 evidence state | Implementation rule |
|---|---|---|---|
| Connection / reconnect | Watch tab + automatic reconnect | Verified | Direct BLE/GATT only |
| Live heart rate | Home dashboard + history | Verified | E5 11 frames on 33F2 |
| Heart-rate history | History | Verified | F7 FA / F7 FD family path |
| SpO2 history | History + Home | Verified | 34 FA history records and completion |
| Watch battery | Home + Watch status | Verified | A2 response |
| Watch clock sync | Automatic sync | Verified | A3 time frame |
| Sleep session markers | History / Diagnostics | Verified structure | 31 01 observed; stage semantics gated |
| EC vendor timeline | Diagnostics / evidence card | Verified structure | EC 01 / EC 02 framing only |
| 44 FA vendor history | Diagnostics / evidence card | Verified structure | 44 FA pages + FD markers only |
| Steps | Home / History | Candidate only | B1/B2 structure is retained as raw evidence; not authoritative Today data |
| Calories | Home | Candidate only | 26 01 activity candidate is not trusted on FT_38093 |
| Distance | Home | Candidate only | No authoritative FT_38093 mapping yet |
| Stress | More | Pending | Need one-feature capture + display correlation |
| Blood pressure | More | Pending / not verified | Do not expose as decoded watch data |
| Breathe | More | Pending | Watch command/response must be observed |
| Multisport | More | Pending | Need sport start/stop/history captures |
| Workout history | More | Pending | Related protocol research is not proof for FT_38093 |
| Watch faces | More | Phone-ready only | Local image preparation; watch transfer blocked pending proof |
| Brightness / DND / screen-on | More | Pending | No undocumented write frames |
| Alarm | More | Pending | Need exact FT_38093 write/response capture |
| Find phone | More | Phone-ready | Phone-side utility; watch trigger remains pending |
| Weather | More | Phone-ready | Watch weather transfer pending |
| Music / camera control | More | Phone-ready | Watch trigger packets pending |
| Contacts / call / SMS replies | More | Pending | Avoid call/SMS permissions until critical protocol and Play policy path are verified |
| Notification relay | More | Play-only | Notification listener lives only in the Play flavor |
| Health Connect / Google Fit | More | Pending | Normalize stable domain records before export |
| Firmware / OTA | More | Intentionally disabled | Safe model-specific DFU procedure not verified |
| Diagnostics / export | Diagnostics | Verified | Raw packets, GATT metadata, sync logs, rolling capture |

## Public companion-app surface

The official Fastrack Smart World listing currently describes connection/disconnection, firmware and watch settings, health data/settings, notification access, fitness/multisport/sleep sync, favorite contacts, Google Fit, call/SMS/third-party notifications and weather. Titan Smart World separately describes dashboard customization, trends, sports, Health Connect and themes. These sources establish the product feature surface, not FT_38093 protocol bytes.
