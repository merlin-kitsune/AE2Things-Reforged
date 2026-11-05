## AE2 Things Reforged 1.3.0 — Minecraft 1.20.1 / Forge

First release of the 1.20.1 Forge line. Requires Forge 47.1.47+ and AE2 15.4.12 – 15.x. Java 17.

This is the full 1.21.1 feature set ported to 1.20.1, including every performance fix and bug fix of the fork, with the GUI still using the classic 1.20.1 assets (AE2 redrew all of them in 1.21.1).

### Features

- DISK cells 1k / 4k / 16k / 64k / 256k with no type limit, one item = one byte, plus the disk housing and the drive recipes.
- Partitioning, fuzzy and inverter card support, ME Drive and ME Chest support.
- `/ae2things help`, `/ae2things getuuid`, `/ae2things recover <uuid> [DISK]`.
- The restored ME Chest cell handler: clicking an ME Chest holding a DISK opens the ME storage GUI, as in upstream 1.2.1.

### Performance (same as the 1.21.1 line)

- Serialized keys are cached instead of being re-encoded for every stored key on every persist.
- Amount-only changes update the stored amounts in place instead of rebuilding the key list.
- The item count is tracked by delta instead of being recomputed.
- Reading a disk is read-only, loading the table does not mark it dirty, and rendering no longer allocates a cell inventory per frame.

### Fixed

- Cloning a disk (creative middle-click) copies the contents instead of sharing the original's entry.
- `/ae2things recover` mints a fresh id and copies the entry instead of putting two cells on one id.
- Unreadable table entries are preserved instead of being erased, and one bad entry no longer wipes the table.
- A `SIMULATE` insert no longer mints an id or writes item data.
- Disassembling a disk removes its table entry.
- A cell whose amount list and key list had mismatched lengths no longer crashes or drops data.
- Writing to a cell while the server-side table is unavailable is refused.

### Compatibility

- AE2 MEGA Things 1.0.8: verified on a real Forge 1.20.1 server with the mod installed — both of its mixins apply and the `/ae2things` help text is rewritten as expected.
