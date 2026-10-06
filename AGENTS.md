# AGENTS.md — AE2Thing Reforged（F:\MCProject\ae2things-reforged）

## 0. 这个项目是什么
- 本项目是 **AE2Things-Forge 的 fork**，用于修复其 DISK 存储元件在服务端的**序列化/落盘开销**，并作为上游停更后的长期替代。
- 来源：`https://github.com/Technici4n/AE2Things-Forge`（clone 于 2026-10-06，含完整历史 87 commits；HEAD = tag `1.4.2-beta` = `4f2503c`，与线上在用 jar 同版本）。
- 上游源头：Fabric 版由 ProjectET 编写，Technici4n 移植到 NeoForge。
- 目标使用方：整合包 `F:\MCProject\merlin_aeronautics_bundled`（狐の航空学 Voxy Edition）；生产服 192.168.31.5 当前加载 `AE2-Things-1.4.2-beta.jar`。
- 目标产物：**drop-in 替换 jar**（同 modId、同存档格式、同方块/物品/组件 id）。

## 1. 硬约束（违反即失败）
1. **必须保留 `modId="ae2things"`**（`src/main/resources/META-INF/neoforge.mods.toml`）；显示名可改为 "AE2 Things Reforged"。改 modId 会让存档内的方块/物品/组件全部失效。
2. **必须保持存档格式完全兼容**：
   - `io.github.projectet.ae2things.util.StorageManager` 侧表中的 `DataStorage`（`stackKeys: ListTag` + `stackAmounts: long[]`，两者索引严格对齐）；
   - 物品组件 `io.github.projectet.ae2things.AE2Things.DATA_DISK_ID` / `DATA_DISK_ITEM_COUNT`；
   - 磁盘 UUID 标识与 `removeDisk`/`getDisk`/`initData` 语义。
   改格式 = 现有磁盘丢数据 ⇒ 必须先做**字节级黄金样本回归**（§4 验收）。
3. **禁止连接或修改生产服务器 192.168.31.5**（本项目不持有任何服务器凭据）。一切验证在本地/离线副本进行；确需线上验证时由整合包主会话按纪律执行（先 `F:\MCProject\merlin_aeronautics_bundled\_ops\check_players.py` 查在线人数，再通知用户批准）。
4. **许可证与署名**：上游 `license="MIT"`（见 `LICENSE`）。保留 `LICENSE`；`authors` 保留 `ProjectET, Technici4n`，在 `credits` 注明本 fork 与目标整合包；不得冒充上游发布。
5. **不要向上游推送**，并**移除/改写 CurseForge 发布块**：`build.gradle` 末尾的 `curseforge { project { id = "609977" ... } }` 属上游作者的 CurseForge 项目，保留会导致误发布。

## 2. 版本契约与构建
| 项 | 当前仓库值 | 目标（整合包线上） |
|---|---|---|
| Minecraft | 1.21.1（`gradle.properties: minecraft_version`） | 1.21.1 |
| NeoForge | `21.1.13`（`neoforge_version`） | **21.1.250**（需对齐） |
| AE2 | `19.0.20-beta`（`ae2_version`，来自 maven `modmaven.dev`） | **19.2.18**（需对齐） |
| EMI（仅本地运行） | `1.1.12+1.21`（`emi_version`） | — |
| Parchment | 1.21 / `2024.07.28` | — |
| Java | toolchain 21（本机 `C:\Program Files\Zulu\zulu-21`） | 21 |

- 版本号来源：环境变量 `AE2THINGS_VERSION`（`build.gradle: version = System.getenv("AE2THINGS_VERSION") ?: "0.0.0"`），经 jar manifest `Implementation-Version` 注入 toml 的 `${file.jarVersion}`。
- 构建：`$env:AE2THINGS_VERSION='1.4.3-reforged'; .\gradlew.bat build` → `build/libs/AE2-Things-<version>.jar`
- 代码风格：`.\gradlew.bat spotlessApply`（配置 `codeformat/codeformat.xml`、`codeformat/ae2.importorder`，即 AE2 的格式化规范）；提交前跑 `spotlessCheck`。
- 数据生成：`.\gradlew.bat runData`（`--mod ae2things --all`，输出 `src/generated/resources/`，该目录同时被登记为 resource srcDir）。
- 运行：`.\gradlew.bat runClient` / `runServer`。
- 构建插件：`net.neoforged.moddev 2.0.15-beta`、`spotless 6.25.0`、`cursegradle 1.4.0`（建议移除）。
- `.github/workflows/build.yml`、`publish.yml` 为上游 CI，fork 后需改写或停用。

## 3. 源码结构（15 个 Java 文件）
```
src/main/java/io/github/projectet/ae2things/
  AE2Things.java                     主类：注册物品与数据组件（DATA_DISK_ID、DATA_DISK_ITEM_COUNT）
  client/AE2ThingsClient.java
  command/Command.java
  data/AE2ThingsDataGenerator.java, data/CraftingRecipeProvider.java
  item/AETItems.java, item/DISKDrive.java          DISK 驱动方块
  mixin/CursedInternalSlotMixin.java               （已注册于 mixins.ae2things.json）
  storage/DISKCellHandler.java, storage/DISKCellInventory.java, storage/IDISKCellItem.java
  util/Constants.java, util/DataStorage.java, util/StorageManager.java
src/main/resources/{META-INF/neoforge.mods.toml, mixins.ae2things.json, pack.mcmeta, icon.png, assets/ae2things/**}
src/generated/resources/data/ae2things/{advancement/**, recipe/**}
```
依赖声明（toml）：`neoforge [21.1.0,)`、`minecraft [1.21.1,)`、`ae2 [19.0.0-beta, 20.0.0)`（AFTER，BOTH）。

## 4. 要修的问题（本 fork 的存在理由）
现场取证（整合包侧稳态 spark profile，窗口 911,120 ms；完整分析见 `F:\MCProject\merlin_aeronautics_bundled\perf-2026-10-06\FIX-PLANS-2026-10-06.md` §10）：
- `DISKCellInventory.persist()`：`if (isPersisted || storageManager == null) return;` 之后遍历 `storedAmounts`，对**每个 key** 调 `AEKey.toTagGeneric()`（DFU codec，含 `DataComponentPatch`）填 `ListTag`，再写 `StorageManager`；而 `saveChanges()` 每次内容变更都会 `isPersisted = false` 并把保存转交 `ISaveProvider.saveChanges()` ⇒ **高频全量重编码**。
- 调用链：`DriveBlockEntity.updateStateForSlot → blinkCell → updateVisualStateIfNeeded → updateClientSideState → getCellItem → AppEngCellInventory.getStackInSlot → persist`；以及 `StorageService.onServerEndTick → updateCachedStacks → NetworkStorage.getAvailableStacks → DISKCellInventory.getAvailableStacks`。
- 实测占比：`DISKCellInventory.persist` **64,908 ms / 911,120 ms（6.9%）**；`getAvailableStacks` 路径另 10,332 ms。

修复方向（按收益/风险排序）：
1. **按 key 缓存序列化结果**（`Map<AEKey, CompoundTag>`）：`AEKey` 已含物品组件补丁，key 相等 ⇒ NBT 必然相同 ⇒ 语义安全、不丢数据；内存上界 = 该盘已有 key 数（本就在 `storedAmounts` 中）。
2. **增量 persist**：只重编码/重写变化的 key，保留 `stackKeys` + `stackAmounts` 并行结构（索引必须严格对齐；`loadCellItems()` 已对长度不一致打 `Loading storage cell with mismatched amounts/tags: %d != %d` 告警）。
3. **写盘合批**：同一 tick/N ms 内最多真正落盘一次，其余只置 dirty；**必须在世界存档/区块卸载/元件取出时强制 flush**，数据丢失窗口不得超过 1 tick。
4. 消除 `DATA_DISK_ITEM_COUNT` 组件在可视/闪灯路径上的无意义抖动。

只读对照样本（**勿修改**）：`E:\merlin-serverfile-v1.0-openbeta\mods\AE2-Things-1.4.2-beta.jar`
反编译：`C:\Program Files\Zulu\zulu-21\bin\javap.exe -p -c -cp <jar> io.github.projectet.ae2things.storage.DISKCellInventory`

### 验收标准（缺一不可）
- 与上游 1.4.2-beta 的 `DataStorage` 序列化结果**逐字节一致**（空盘、单 key、多 key、含组件补丁的物品、流体 key 各一组）。
- 旧存档直接可用；新旧 jar 可来回替换而不丢数据。
- persist 路径 CPU 占比显著下降（用 `perf-2026-10-06\steady.py` 在同一 profile 口径对比）。
- `spotlessCheck` 通过。

## 5. 协作与交付规范
- 首次进入：`git remote rename origin upstream` → `git remote add origin <你的 fork 地址>`；日常提交到自己的 fork，**不推 upstream**。
- 提交信息说明"为什么"；不要提交 `build/`、`.gradle/`、`run/`（`.gitignore` 已覆盖）。
- 本项目**只做开发**；接入整合包（三端 `mods` 替换、发布包 `E:\merlin-serverfile-v1.0-openbeta`、线上服务器）由整合包主会话按生产纪律执行。
- 相关项目与资料：`F:\MCProject\merlin_modpack_tweaks`（整合包专用 mixin 模组，当前用 mixin 临时压制同一问题）、技能 `merlin-pack-handbook`、`F:\MCProject\merlin_aeronautics_bundled\PENDING-2026-10-06.md` 的 P7。

## 6. 当前状态（2026-10-06）
- 已 clone 完整历史（87 commits），HEAD `master` @ `4f2503c`（tag `1.4.2-beta`），**尚未做任何代码改动**。
- 待办：对齐 NeoForge/AE2 版本 → 移除/改写下 CurseForge 发布与上游 CI → 实现 §4 修复 → 跑 §4 验收。
