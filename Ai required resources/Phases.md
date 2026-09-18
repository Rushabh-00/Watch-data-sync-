# Implementation Phases

## Phase 1 — BLE transport
Status: operational

- BLE scan
- direct GATT connection
- service/characteristic discovery
- notification configuration
- MTU negotiation
- reconnect
- rolling packet capture

## Phase 2 — FT_38093 protocol identification
Status: operational, ongoing

Verified:
- 33F1/33F2 control/live channel
- 34F1/34F2 data channel
- 6001/6002 and 6101/6102 observed vendor channels
- fd03/fd04 and 6487 observed auxiliary channels
- live HR
- watch battery
- time
- HR history
- SpO2 history
- sleep session marker framing
- EC and 44 FA vendor-history structures

## Phase 3 — Evidence-backed decoders
Status: operational, ongoing

Fixture-tested:
- live heart rate
- HR history
- SpO2 history
- watch-face 26 01 config classification
- EC 01 / EC 02 structure
- 44 FA page structure
- transfer completion markers

## Phase 4 — Sync engine hardening
Status: operational

- serialized GATT operations
- response matching
- retry for step-history request
- connection lifecycle cleanup
- automatic reconnect
- idempotent in-memory/persisted merges
- notification setup restricted to observed endpoints

## Phase 5 — Product UI
Status: operational, redesign active

- professional dashboard
- history
- watch setup
- feature hub
- diagnostics
- evidence-aware copy
- safer sideload/Play distribution split

## Phase 6 — Verified FT_38093 expansion
Status: next protocol work

For each feature, collect paired:
1. watch state before action
2. exact outbound command
3. exact inbound response(s)
4. watch-display/result correlation
5. repeat capture

Promote to verified only after repeatability.

## Phase 7 — Integrations and local database
Status: planned

- normalized persistent domain tables
- Health Connect
- Google Fit export where supported
- explicit export/import
- data deletion controls

## Phase 8 — Release quality
Status: ongoing

- build/test both distribution flavors
- minimize permissions
- signed APK
- Play Protect-safe sideload flavor
- regression fixtures
- release notes
- protocol evidence archive
