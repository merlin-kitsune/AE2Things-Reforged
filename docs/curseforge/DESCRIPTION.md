# AE2Things Reforged

**A reworked fork of AE2 Things: every feature of the original, the modern AE2 API on each Minecraft version, and a large amount of the extra performance overhead removed.**

*DISK = Deep Item Storage disK. A storage cell with no type limit — one item costs one byte.*

## Which file do I need?

| Minecraft | Loader | Mod version | Requires | File |
| --- | --- | --- | --- | --- |
| 1.21.1 | NeoForge 21.1.248 or newer | 1.4.5 | AE2 19.2.18 – 19.x, Java 21 | `AE2-Things-1.4.5.jar` |
| 1.20.1 | Forge 47.1.47 or newer | 1.3.1 | AE2 15.4.12 – 15.x, Java 17 | `AE2-Things-1.3.1.jar` |

Both lines keep `modId=ae2things`, the same block and item ids and the same save format as the upstream release of their own Minecraft version, so they are drop-in replacements: existing worlds and existing disks keep working.

**This is an alternative build of the same mod, not an add-on.** Do not install it next to AE2 Things or AE2 Things Forge — replace the old jar.

## What is a DISK?

- A storage cell with **no type limit**: 1k / 4k / 16k / 64k / 256k, one item counts as one byte.
- Because of the cell format it costs 24 bytes per kibibyte — a small price for unlimited types.
- Works in an **ME Drive**; put it in an **ME Chest** to read it on its own.
- Partition it in a Cell Workbench, and use Fuzzy / Inverter cards as usual.
- `/ae2things getuuid` shows the id of the disk in your hand, `/ae2things recover <uuid> [DISK]` spawns a drive for that id (operator only).

## Why use this fork?

### Performance

The DISK keeps its contents in a side table (`world/data/disk_manager.dat`) instead of inside the item, which is what makes "no type limit" possible. The original mod rewrote that table on every single content change, and on a large network that became the biggest cost of the mod. This fork changes that:

- A key is serialized once and the result is reused, instead of re-running the DFU codecs for every stored key on every persist.
- Persisting a disk whose contents only changed in *amount* updates the stored amounts in place instead of rebuilding and re-encoding the whole key list.
- The item count is tracked with a known delta instead of being recomputed from every stored key on each change.
- Reading a disk no longer creates and dirties a table entry, so a disk that is only displayed or scanned does not touch the save file.
- Loading the table no longer marks it dirty, so the first autosave after a world load does not rewrite the whole table.
- Rendering no longer builds a full cell inventory every frame: item tint and tooltip read the state straight from the stack.

### Bug fixes

- Cloning a disk copies its contents, so a clone no longer shares — and overwrites — the contents of the original. `/ae2things recover` likewise mints a fresh id and copies the entry.
- A disk whose amount list and key list have mismatched lengths no longer crashes or silently drops data.
- A table entry that cannot be read is kept verbatim and written back instead of being erased, and one bad entry no longer wipes the rest of the table.
- A cell that is only being capacity-probed no longer mints a disk id or writes item data.
- Disassembling a disk removes its table entry, so empty entries no longer pile up.
- Writing to a cell while the server-side table is unavailable is refused instead of silently keeping the contents in memory only.
- Missing translation keys were added and previously hardcoded tooltips are now translatable.

### Compatibility

- **AE2 MEGA Things**: the command help text and the tooltip call path are kept byte-for-byte compatible with the mixins MEGA patches into this mod. Verified against MEGA Things 1.0.8 on 1.20.1 (full joint runtime test) and 2.0.4 on 1.21.1 (symbol and injection-point contract).
- Works with the AE2 versions listed above and with anything else the upstream release worked with.

## Install

1. Install the matching Forge / NeoForge version and AE2 ([Applied Energistics 2](https://www.curseforge.com/minecraft/mc-mods/ae2)), plus GuideME if your loader needs it.
2. Remove any older AE2 Things jar — only one mod with id `ae2things` may be loaded.
3. Drop the matching jar into `mods/`. Both client and server.

## Credits and license

- Original mod by **ProjectET** (Fabric) and **Technici4n** (Forge / NeoForge port).
- This fork is maintained by **MerlinKitsune**; it is not affiliated with or endorsed by the original authors.
- MIT license. Source, issue tracker and full changelog: [github.com/merlin-kitsune/AE2Things-Reforged](https://github.com/merlin-kitsune/AE2Things-Reforged).

---

## 中文简介

**AE2Things Reforged** — [AE2 Things](https://www.curseforge.com/minecraft/mc-mods/ae2-things) 的优化分支，完整保留原模组的全部功能（DISK 磁盘、驱动器、分区、模糊卡/反向卡、`/ae2things` 命令），并大幅降低大网络下的额外开销。

- **版本对应**：1.21.1 NeoForge 用 **1.4.5**（需 AE2 19.2.18+、Java 21）；1.20.1 Forge 用 **1.3.1**（需 AE2 15.4.12+、Java 17）。
- **性能**：无类型上限所依赖的侧表改为增量写、按键缓存序列化结果、读路径只读、加载不再标脏、渲染不再每帧构造 inventory。
- **修复**：中键克隆与恢复命令不再让两个磁盘共用同一份数据、长度不一致不再崩、坏条目不再被静默抹掉、模拟插入不再铸造 UUID、拆盘清理侧表、服务端表不可用时拒绝写入。
- **兼容**：与 AE2 MEGA Things 保持二进制级兼容（1.20.1 已实机联合启动验证，1.21.1 已逐条核对符号与注入点）。
- **注意**：这是同一个模组的替代版，不能与原 AE2 Things 同时安装（modId 相同），请直接替换旧 jar。

源码、问题反馈与完整更新记录：[GitHub](https://github.com/merlin-kitsune/AE2Things-Reforged)。
