# Rules

1. Do not invent or guess UUIDs, packet layouts, checksums or commands.
2. Separate transport, protocol and domain code.
3. Start discovery with reads and notifications; avoid firmware/configuration writes.
4. Record raw packets before interpreting them.
5. Every decoded field must be traceable to observed bytes and repeatable captures.
6. Keep sensitive identifiers out of source code, UI and committed logs.
7. Support multiple watch models through adapters rather than branching the whole app.
8. Do not make medical claims from watch sensors; present wellness data only.
9. Keep synchronization idempotent so retries do not duplicate records.
10. Every feature has an explicit evidence state: Verified, Observed structure, Pending evidence, Phone-ready or Not verified.
11. A public companion-app feature list is product-scope evidence, not protocol evidence.
12. Related-watch reverse engineering is research context only until independently reproduced on FT_38093.
13. Never promote candidate activity/calorie/distance values to authoritative Today totals without direct FT_38093 correlation.
14. Keep notification-listener access out of internet-sideload APKs.
15. Do not enable OTA/DFU, watch-face installation or undocumented configuration writes without a model-specific safe procedure.
16. Every release must pass unit tests, both distribution builds, signing and artifact publication before being called release-ready.
