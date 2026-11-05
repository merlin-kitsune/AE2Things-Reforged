## AE2 Things Reforged 1.4.5 — Minecraft 1.21.1 / NeoForge

Requires NeoForge 21.1.248+ and AE2 19.2.18 – 19.x. Java 21. Drop-in replacement for the same-version upstream AE2 Things.

Note on the version number: this build is the contents of 1.4.4, bumped to 1.4.5 only because GitHub refused to reuse the release tag `1.4.4`. Same code, same jar layout.

### Fixed

- **Two cells could end up sharing one disk entry.** `/ae2things recover <uuid>` now mints a fresh id and copies the contents, exactly like the creative middle-click clone, instead of handing out a second item on the same id (two inventories writing one entry, last writer wins, and emptying one deleted the entry the other still pointed at).
- **A disk entry that could not be read was erased for good.** Entries with a missing or malformed id, or with an undecodable payload, are now kept verbatim and written back on the next save; a single bad entry no longer wipes the rest of the side table either.
- **A cell that was only being capacity-probed wrote data.** A `SIMULATE` insert no longer mints a disk id or writes the item count, so probing a cell (pipes, import strategies, client-side prediction) no longer dirties the save or creates stray entries.
- **Disassembling a disk left its table entry behind.** The side-table entry is now removed when the disk is taken apart, so empty entries stop accumulating.
- **Writing to a cell while the server-side table was unavailable was silently lost.** Such inserts and extracts now refuse instead of keeping the contents in memory only.

### Changed

- Reading a disk is now genuinely read-only: the read path uses a non-creating lookup, so displaying or scanning a disk no longer creates a table entry or marks the table dirty.
- Loading the table no longer marks it dirty, so the first autosave after a world load does not rewrite the whole table.
- Item tint and tooltip read the cell state straight from the stack instead of building a whole cell inventory per frame.
- Cloning a disk deep-copies the serialized key tags instead of sharing them with the new entry.
- The mixin config is now `required`: a failed injection fails the start instead of silently disabling the creative-clone guard.

### Compatibility

- AE2 MEGA Things 2.0.4: all 17 symbols it references are present, and all three injection points it patches still match (verified against the built jar).
