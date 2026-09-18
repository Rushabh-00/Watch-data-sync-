# Phases

## Phase 1 — BLE lab
Status: in progress

- Android project bootstrap
- scan
- connect
- GATT discovery
- event logging

## Phase 2 — Protocol identification
- choose exact Fastrack model
- capture GATT layout
- identify notification endpoint
- identify history-sync trigger
- derive framing/checksum rules

## Phase 3 — First decoded record
- implement one model adapter
- decode one data class
- add fixture-based parser tests

## Phase 4 — Sync engine
- incremental history cursor
- deduplication
- retry/reconnect
- local database

## Phase 5 — User product
- watch setup
- sync status
- history dashboards
- export / Health Connect where appropriate

## Phase 6 — Compatibility
- add model adapters only when independently verified
