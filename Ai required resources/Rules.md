# Rules

1. Do not invent or guess UUIDs, packet layouts, checksums or commands.
2. Separate transport, protocol and domain code.
3. Start discovery with reads and notifications; avoid firmware/configuration writes.
4. Record raw packets before interpreting them.
5. Every decoded field must be traceable to observed bytes and repeatable captures.
6. Keep sensitive identifiers out of source code and committed logs.
7. Support multiple watch models through adapters rather than branching the entire app.
8. Do not make medical claims from watch sensors; treat metrics as wellness data.
9. Keep synchronization idempotent so retrying a session does not duplicate records.
