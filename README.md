# AE2Things Reforged

This fork is a reworked version of the original AE2 Things that
keeps every feature of the original mod. It targets the modern AE2 API (19.2.18) and NeoForge 21.1.248 on
Minecraft 1.21.1, and it removes a large amount of the extra performance overhead that high concurrency used to cause:

- A key is serialized once and the result is reused, instead of re-running the DFU codecs for every stored key on
  every persist.
- Persisting a disk whose contents only changed in amount updates the stored amounts in place, instead of rebuilding
  and re-encoding the whole key list.
- The item count is tracked with a known delta instead of being recomputed from every stored key on each change.
- Cloning a disk copies its contents, so the clone no longer shares (and overwrites) the contents of the original disk.
- A disk whose stored amount list and key list have mismatched lengths no longer crashes or silently drops data.
- Missing translation keys were added, and the previously hardcoded tooltips are now translatable.

---

# AE2Things
Neat little additions to AE2: Forge
This mod adds a new type of cell the DISK: Deep Item Storage disK. This cell has no type limits and one item counts as one byte. Due to limitations with the cell format, you lose 24 bytes per kibibyte, but hey a small price to pay for no types right?