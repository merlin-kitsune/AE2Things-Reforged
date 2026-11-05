# Changelog

**English** | [简体中文](CHANGELOG_ZH.md)

All notable changes to this fork, newest first. The two Minecraft lines are released in parallel and carry
independent version numbers, so each section names the line it belongs to.

- **1.21.1 / NeoForge** — current: **1.4.5**
- **1.20.1 / Forge** — current: **1.3.1**

Versions marked *not published* were built, tested and then dropped: the contents shipped unchanged in the next
version, the number only moved because the release tag had already been used.

## 1.21.1 / NeoForge

### 1.4.5 — 2026-11-05

Requires NeoForge 21.1.248+ and AE2 19.2.18 – 19.x. Java 21. Drop-in replacement for the same-version upstream
AE2 Things.

**Fixed**

- Two cells could end up sharing one disk entry: `/ae2things recover <uuid>` now mints a fresh id and copies the
  contents, exactly like the creative middle-click clone, instead of handing out a second item on the same id (two
  inventories writing one entry, last writer wins, and emptying one deleted the entry the other still pointed at).
- A disk entry that could not be read was erased for good. Entries with a missing or malformed id, or with an
  undecodable payload, are now kept verbatim and written back on the next save; one bad entry no longer wipes the
  rest of the side table either.
- A cell that was only being capacity-probed wrote data: a `SIMULATE` insert no longer mints a disk id or writes the
  item count, so probing a cell (pipes, import strategies, client-side prediction) no longer dirties the save or
  leaves stray entries behind.
- Disassembling a disk left its table entry behind; the entry is now removed when the disk is taken apart.
- Writing to a cell while the server-side table was unavailable was silently lost; such inserts and extracts now
  refuse instead of keeping the contents in memory only.

**Changed**

- Reading a disk is now genuinely read-only: the read path uses a non-creating lookup, so displaying or scanning a
  disk no longer creates a table entry or marks the table dirty.
- Loading the table no longer marks it dirty, so the first autosave after a world load does not rewrite the whole
  table.
- Item tint and tooltip read the cell state straight from the stack instead of building a whole cell inventory per
  frame.
- Cloning a disk deep-copies the serialized key tags instead of sharing them with the new entry.
- The mixin config is now `required`: a failed injection fails the start instead of silently disabling the
  creative-clone guard.

**Compatibility**

- AE2 MEGA Things 2.0.4: all 17 symbols it references are present, and all three injection points it patches still
  match (verified against the built jar).

### 1.4.4 — not published

Built and tested in game, then folded into 1.4.5 unchanged. It is the build that carried the backport of every fix
the 1.20.1 port had gained, so 1.4.5 documents it.

### 1.4.3 — 2026-10-06

First release of the fork. Requires NeoForge 21.1.248+ and AE2 19.2.18 – 19.x. Java 21.

**Performance**

- `persist()` reuses the per-disk serialized key tags and updates the stored amounts in place instead of re-encoding
  every key on every save, falling back to a full rebuild when the cached key index is stale or unknown.
- `saveChanges(long delta)` keeps the stored item count incremental (O(1)) instead of summing the whole key set on
  every change.
- Measured on the same machine: 500 amount-only saves on a 32-key disk cost 1 ms instead of 9 ms, and 2000
  zero-value extracts cost 0 ms instead of 81 ms.

**Fixed**

- Cloning a DISK no longer shares one `DataStorage` instance between the two disks, which let either disk overwrite
  the contents of the other.
- A cell whose amounts/tags lists have mismatched lengths is neither indexed past the end nor silently truncated.
- `IDISKCellItem.isBlackListed()` no longer assumes an item key, and null-checks the cell inventory it queries.
- The DISK tooltip and the `Disk UUID` line are translation keys, and all five language files carry the same key set
  (the four non-English files were missing `text.ae2things.disk_drives`).

**Changed**

- The fork is its own release: `displayURL` points here, the authors list adds MerlinKitsune, and the maven group
  moves off the upstream author's namespace. `displayName` stays as is, and `modId` / save format are unchanged.
- Release jars exclude the dev-only golden sample harness.
- Verified byte-identical on-disk output against upstream 1.4.2 (9 golden cases, 108 keys), so replacing the jar
  keeps existing disks readable.

## 1.20.1 / Forge

### 1.3.1 — 2026-11-05

First release of the 1.20.1 Forge line. Requires Forge 47.1.47+ and AE2 15.4.12 – 15.x. Java 17.

This is the full 1.21.1 feature set ported to 1.20.1 — every performance fix and bug fix of the fork — with the GUI
still using the classic 1.20.1 assets, since AE2 redrew all of them in 1.21.1. `modId`, block and item ids and the
save format match upstream 1.2.1, the last upstream release for this Minecraft version, so it replaces that jar in
place.

**Features**

- DISK cells 1k / 4k / 16k / 64k / 256k with no type limit, one item = one byte, plus the disk housing and the drive
  recipes.
- Partitioning, fuzzy and inverter card support, ME Drive and ME Chest support.
- `/ae2things help`, `/ae2things getuuid`, `/ae2things recover <uuid> [DISK]`.
- The restored ME Chest cell handler: clicking an ME Chest holding a DISK opens the ME storage GUI, as in upstream
  1.2.1. (AE2 19.2.18 dropped the extension point and handles the case itself, so the 1.21.1 line does not need it.)

**Performance**

- Serialized keys are cached instead of being re-encoded for every stored key on every persist.
- Amount-only changes update the stored amounts in place instead of rebuilding the key list.
- The item count is tracked by delta instead of being recomputed.
- Reading a disk is read-only, loading the table does not mark it dirty, and rendering no longer allocates a cell
  inventory per frame.

**Fixed**

- Cloning a disk (creative middle-click) copies the contents instead of sharing the original's entry.
- `/ae2things recover` mints a fresh id and copies the entry instead of putting two cells on one id.
- Unreadable table entries are preserved instead of being erased, and one bad entry no longer wipes the table.
- A `SIMULATE` insert no longer mints an id or writes item data.
- Disassembling a disk removes its table entry.
- A cell whose amount list and key list had mismatched lengths no longer crashes or drops data.
- Writing to a cell while the server-side table is unavailable is refused.

**Compatibility**

- AE2 MEGA Things 1.0.8: verified on a real Forge 1.20.1 server with the mod installed — both of its mixins apply
  and the `/ae2things` help text is rewritten as expected.

### 1.3.0 — not published

Built and tested in game, then folded into 1.3.1 unchanged.
