# AE2Things Reforged

A reworked fork of [AE2 Things](https://github.com/Technici4n/AE2Things-Forge) that keeps every feature of the
original mod, targets the current AE2 API on each Minecraft version, and removes a large amount of the extra
performance overhead that large networks used to cause.

*DISK = Deep Item Storage disK.* A storage cell with **no type limit**: one item costs one byte.

## Download

| Minecraft | Loader | Mod version | Requires | Jar |
|---|---|---|---|---|
| 1.21.1 | NeoForge 21.1.248+ | **1.4.4** | AE2 19.2.18+ (below 20) | `AE2-Things-1.4.4.jar` |
| 1.20.1 | Forge 47.1.47+ | **1.3.0** | AE2 15.4.12+ (below 16) | `AE2-Things-1.3.0.jar` |

Both lines keep `modId=ae2things`, the same block/item ids and the same save format as the upstream release of
their own Minecraft version, so they are drop-in replacements — existing worlds keep working.

Java 21 is required on 1.21.1, Java 17 on 1.20.1.

## What the fork changes

### Performance

The DISK cell keeps its contents in a side table (`world/data/disk_manager.dat`) instead of in the item, which is
what makes "no type limit" possible. Upstream rewrote that table on every single content change, and in a large
network that turned into the single biggest cost of the mod.

- A key is serialized once and the result is reused, instead of re-running the DFU codecs for every stored key on
  every persist.
- Persisting a disk whose contents only changed in amount updates the stored amounts in place, instead of rebuilding
  and re-encoding the whole key list.
- The item count is tracked with a known delta instead of being recomputed from every stored key on each change.
- Reading a disk no longer creates (and dirties) a table entry: lookups on the read path are now read-only, so a disk
  that is merely displayed or scanned does not touch the save file.
- Loading the table no longer marks it dirty, so the first autosave after a world load does not rewrite the whole
  table.
- Rendering no longer builds a full cell inventory per frame: item tint and tooltip read the cell state straight
  from the stack's NBT.

### Bug fixes

- Cloning a disk copies its contents, so the clone no longer shares (and overwrites) the contents of the original.
  `/ae2things recover` likewise mints a fresh id and copies the entry instead of handing out a second item on the
  same id.
- A disk whose stored amount list and key list have mismatched lengths no longer crashes or silently drops data.
- A table entry that cannot be read (missing/malformed id, undecodable payload) is kept verbatim and written back on
  the next save instead of being erased, and a single bad entry no longer wipes the rest of the table.
- A cell that is only being capacity-probed (`SIMULATE`) no longer mints a disk id or writes item NBT.
- Disassembling a disk removes its table entry, so empty entries no longer accumulate.
- Writing to a cell while the server-side table is unavailable is refused instead of silently keeping the contents
  in memory only.
- Missing translation keys were added, and the previously hardcoded tooltips are now translatable.

### Compatibility

The command help text and the tooltip call path are kept byte-for-byte compatible with
[AE2 MEGA Things](https://github.com/Lapis256/AE2MEGAThings), which patches this mod through mixins.
Verified against AE2 MEGA Things **1.0.8** (1.20.1, full runtime test with the mod installed) and **2.0.4**
(1.21.1, symbol and injection-point contract).

## Usage

- Craft a DISK housing and combine it with a cell component, or craft the drive directly.
- Put the disk into an **ME Drive** (or an **ME Chest** to read the disk on its own).
- Partition it in a Cell Workbench, add a Fuzzy Card or an Inverter Card as usual.
- `/ae2things getuuid` prints the id of the disk in your hand.
- `/ae2things recover <uuid> [DISK]` spawns a drive for that id, copying the disk (operator only).

## Building

This repository holds two independent builds; the root project is the 1.21.1 NeoForge line, and
`forge-1.20.1/` is the 1.20.1 Forge line with its own wrapper and settings.

```bash
# 1.21.1 / NeoForge
./gradlew build                    # -> build/libs/AE2-Things-<mod_version>.jar

# 1.20.1 / Forge
cd forge-1.20.1 && ./gradlew build  # -> build/libs/AE2-Things-<mod_version>.jar
```

The version of each line comes from its own `gradle.properties` (`mod_version`); override it with
`-Pmod_version=`.

## Credits

- Original mod by **ProjectET** (Fabric) and **Technici4n** (Forge / NeoForge port).
- This fork is maintained by **MerlinKitsune**; it is not affiliated with or endorsed by the original authors.
- Licensed under the MIT license, see [LICENSE](LICENSE).

---

## 中文简介

AE2 Things Reforged 是 [AE2 Things](https://github.com/Technici4n/AE2Things-Forge) 的优化分支，完整保留原模组的
全部功能（DISK 磁盘、驱动器、分区、模糊卡/反向卡、`/ae2things` 命令），并大幅降低大网络下的额外开销：

- **性能**：把「无类型上限」所依赖的侧表做成了增量写、按键缓存序列化结果、读路径只读、加载不再标脏、渲染不再每帧构
  造 inventory。
- **修复**：中键克隆/恢复命令不再让两个磁盘共用一份数据、长度不一致不再崩、坏条目不再被静默抹掉、模拟插入不再铸造
  UUID、拆盘清理侧表、服务端表不可用时拒绝写入。
- **兼容**：与 AE2 MEGA Things 保持二进制级兼容（1.20.1 已实机联合启动验证，1.21.1 已逐条核对符号与注入点）。

版本对应：**1.21.1 NeoForge = 1.4.4**（需 AE2 19.2.18+、Java 21），**1.20.1 Forge = 1.3.0**（需 AE2 15.4.12+、
Java 17）。同 modId、同存档格式，可直接替换上游版本。
