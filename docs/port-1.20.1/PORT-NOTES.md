# AE2 Things Reforged — 1.20.1 Forge 线移植说明

> 本文件记录 1.20.1 Forge 线的移植决策、与 1.21.1 线的差异、以及本轮对上游 1.20.1 源代码做的性能/恶性 bug 审计与处置。
> 配套文档：`AE2-API-MAP.md`（AE2 15.4.12 API 差异）、`UPSTREAM-AUDIT.md`（上游 F1–F20 / C1–C10 全量审计）、`MEGA-COMPAT.md`（AE2 MEGA Things 二进制契约）。

## 1. 目标与状态

| 项 | 状态 |
|---|---|
| 目标 | 把已优化的 1.21.1 NeoForge 线完整移植到 1.20.1 Forge，行为对齐、GUI 用 1.20.1 老素材 |
| AE2 | **15.4.12**（1.20.1 Forge 最新），由 `gradle.properties: ae2_version_id=QRpVsqDI` 锁定（Modrinth 上 15.4.12 的**默认文件是 Fabric jar**，必须用版本 id 才能拿到 Forge jar） |
| Forge API | **47.1.47**（AE2 15.4.12 自身编译所用版本），对用户声明下限 47.1.3 |
| 版本号 | **1.3.0**（jar 名 `AE2-Things-1.3.0.jar`，manifest `Implementation-Version: 1.3.0`） |
| 构建 | 通过（`spotlessCheck` + `build`），103 entries，80,552 B，sha256 `f766e83dbc4010681530389ab6db37aa95c561fc5e494fba7db8c3a566c3868f` |
| 开发服启动 | 通过：mod 容器创建、mixin 注入 `AbstractContainerMenu` 成功、数据包加载无报错 |
| 提交 | 本地四次提交：`06d4b40`（移植）/ `613bd18`（persist 铸锚补漏）/ `f27a496`（第二轮复核修复批次，见 §13）/ 本轮裁决落地（见 §14）；**未推送**，等实机指令 |

## 2. 目录与构建

仓库根目录仍是 1.21.1 NeoForge 单工程；1.20.1 线是**自包含**子构建，不与根工程共享 `settings.gradle`：

```
F:/MCProject/ae2things-reforged/
  build.gradle, gradle.properties, src/main/java/...        # 1.21.1 NeoForge 线（本轮未改动）
  codeformat/                                               # 两条线共用的格式配置
  forge-1.20.1/
    gradle/wrapper, gradlew, settings.gradle                # 独立构建
    gradle.properties                                       # 版本矩阵（见 §3）
    build.gradle                                            # MDG legacyforge 2.0.144
    src/main/java/io/github/projectet/ae2things/**          # 移植后的源码
    src/main/resources/**                                   # 资源 + 静态配方/进度
    src/main/templates/META-INF/mods.toml                   # ProcessResources 展开
```

```powershell
cd F:/MCProject/ae2things-reforged/forge-1.20.1
.\gradlew build                 # 产物 build/libs/AE2-Things-1.3.0.jar
.\gradlew runServer             # 开发服（run/ 目录）
.\gradlew runClient             # 开发客户端
```

构建要点（踩过的坑都记在这里）：

1. **Forge 1.20.1 的 `loaderVersion` 只能写语言提供者的主系列**（`[47,48)`）。写 `[47.1.47,48)` 会被 FML 判为 `Missing language javafml version ... found 47` 而直接拒绝加载本模组；精确下限放在 `modId=forge` 的 mandatory 依赖上。
2. `mods.toml` 模板是 UTF-8，`ProcessResources` 必须 `filteringCharset = 'UTF-8'`，否则按 JVM 默认编码读入会让注释出现乱码。
3. mixin 配置文件名必须三处一致：`src/main/resources/mixins.ae2things.json`、jar manifest 的 `MixinConfigs`、dev 运行参数 `-Dmixin.config`。**manifest 写错不会报错**，只会让 mixin 静默不加载。
4. spotless 的 `format('json') { prettier() }` 需要联网下载 npm + prettier，本机必然 `TimeoutException`；已从本线移除，只保留 Java spotless（格式仍用仓库根 `codeformat/`）。
5. 管道 `| tail` 会吞掉 gradle 退出码：一律 `> build/build.log 2>&1; echo EXIT=$?`。

## 3. 版本矩阵

| 项 | 值 |
|---|---|
| Minecraft | 1.20.1（声明 `[1.20.1,1.20.2)`） |
| Forge | 编译 1.20.1-47.1.47；声明 `[47.1.47,48)` |
| AE2 | 15.4.12（声明 `[15.4.12,16.0.0)`） |
| GuideME | 20.1.15（AE2 的 mandatory 前置，不内嵌；本模组不引用其 API，故 `modRuntimeOnly`） |
| Parchment | 1.20.1 / 2023.09.03 |
| Java | toolchain 17（`C:\Users\xmace\jdks\jdk-17.0.20.1+1`） |
| Gradle / 插件 | Gradle 9.5.1；`net.neoforged.moddev.legacyforge` 2.0.144；spotless 6.25.0 |

## 4. 与 1.21.1 线的差异矩阵

| 维度 | 1.21.1 NeoForge 线 | 1.20.1 Forge 线 | 原因 |
|---|---|---|---|
| 元数据 | `META-INF/neoforge.mods.toml`（直接写死版本） | `src/main/templates/META-INF/mods.toml` + `generateModMetadata` 展开 | 1.20.1 无 `${file.jarVersion}` 之类替换 |
| 依赖声明 | `type=required` + `versionRange` | `mandatory=true` + `ordering`/`side` | 1.20.1 的 toml 语法 |
| 物品注册 | `DeferredItem` | `DeferredRegister<Item>` + `RegistryObject<Item>` | 1.20.1 无 `DeferredItem`；MEGA 也要求字段类型是 `RegistryObject` |
| 盘 ID / 数量 / 模糊模式 | 数据组件 `ae2things:disk_id` / `disk_item_count` / `disk_fuzzy_mode` | NBT：`diskuuid`(UUID) / `ic`(long) / `fm`(String) | 1.20.1 无数据组件 API；且这三个是 **MEGA 的 inline 常量契约** |
| 存储管理器获取 | `ServerStartedEvent`/`ServerStoppedEvent` | 同事件，另保留 `public static StorageManager STORAGE_INSTANCE` 字段 | MEGA 用 `getstatic AE2Things.STORAGE_INSTANCE` 读它 |
| 配方 / 进度 | datagen（`RecipeOutput`） | 静态 JSON：`data/ae2things/recipes/*.json`、`data/ae2things/advancements/recipes/misc/*.json` | 1.20.1 无 `RecipeOutput`；目录名是复数 |
| 资源包格式 | `pack_format 9` | `pack_format 15` | 1.20.1 |
| GUI / 贴图素材 | AE2 19 的新素材 | **沿用上游 1.20.1 老素材**（`textures/model/drive/cells/*.png`、`assets/minecraft/atlases/blocks.json`）；sha1 与 `_ref/ae2things-1.20.1` 逐文件一致 | AE2 在 1.21.1 重绘了全部 GUI 资产，新素材放进 1.20.1 会与 AE2 15 的界面风格不符 |
| 磁盘单元 GUI | 1.21.1 线**丢失**了 `DISKItemCellGuiHandler` | 恢复：`StorageCells.addCellGuiHandler(new DISKItemCellGuiHandler())` | 上游 1.20.1 有此功能（点开容器里的磁盘 → 直接开 `MEStorageMenu`），属功能对齐 |
| Mixin | NeoForge 的 mixin 配置块 | `mixins.ae2things.json` + `compatibilityLevel JAVA_17` + **refmap**（`ae2things.refmap.json`）+ manifest `MixinConfigs` | 1.20.1 生产环境是 SRG 名，`@Inject` 选择器必须靠 refmap 翻译 |
| `IDISKCellItem.clone` | 有（创意中键复制用） | **不设 abstract clone**，改由 mixin 内部直接调用 `DataStorage.copy()` | AE2 15 没有“缓存副本”语义；MEGA 1.20.1 的 `AbstractDISKDrive` 不实现 clone，声明成 abstract 会 `AbstractMethodError` |

## 5. 上游 1.20.1 审计结论与处置

审计对象 = 上游 1.20.1 发布线（tag `1.2.1` = `acb451c`，即 CurseForge 上的 `AE2-Things-1.2.1.jar`）与我们 1.21.1 fork 的逐文件对照。全量见 `UPSTREAM-AUDIT.md`，此处只列“影响性能或会造成恶性后果”的处置：

| 编号 | 问题 | 上游 1.20.1 | 我们的 1.20.1 移植版 | 处置 |
|---|---|---|---|---|
| F1 | 创意模式中键复制整个盘时，两份盘**共享同一个 `DataStorage` 引用**（同一 UUID 被两个实例持有），可无限复制内容 | 存在 | 已修（1.21.1 线已带入 `DataStorage.copy()`） | 保持 |
| C2 | 读路径（构造 cell inventory / 工具提示 / 驱动器灯）走 `getOrCreateDisk()`，**每次读都可能新建条目并 `setDirty()`** ⇒ 每次自动存档重写整张侧表 | 存在 | 已修：新增只读 `StorageManager.getDisk(UUID)`，读路径全部改用它；只有写入才创建条目 | 修 |
| C3 | `readNbt()` 用 `disk.getUUID(diskuuid)` 作 key。**注意：`CompoundTag#getUUID` 在缺键时是抛 NPE 而不是返回 null**（`NbtUtils.loadUUID(Tag)` 第一条指令就 `Tag.getType()`；1.20.1 merged jar `javap` 实测），所以真实失败形态是：`readNbt` 抛异常 → 维度存储丢掉整份 `SavedData` → 下次存档把**所有盘的侧表清空**（不崩服，只留一行 `Error loading saved data`） | 存在 | 已修（两轮）：第一版写 `if (diskId == null) continue` 是**死代码**（永远进不去）；现行版本改成 `disk.hasUUID(DISKUUID)` 前置判断 + **每条目 try/catch**，单条坏数据不再牵连整表 | 修 |
| C4 | 首次插入时 UUID 与侧表条目在 **SIMULATE 阶段**就创建 ⇒ 仅“容量探测”也会写物品 NBT、弄脏存档；客户端菜单预测还会各自铸造不同 UUID | 存在 | 已修：只在 `MODULATE` 创建 | 修 |
| C5 | 拆解磁盘（潜行右键）只掉落外壳/核心/升级，**不调用 `removeDisk`** ⇒ 侧表里永久残留空条目 | 存在 | 已修：拆解时按 UUID 移除条目（先 `hasUUID` 判断，避免无谓 `setDirty`） | 修 |
| C9 | 在没有 `StorageManager` 的实例上执行 `MODULATE` 插入：不建 UUID，`persist()` 直接标记已保存并返回 ⇒ 物品只活在该实例里 | 存在 | 已修：`storageManager == null` 时拒绝 `MODULATE` 插入（`SIMULATE` 仍正常应答，保留客户端预测） | 修 |
| C2b | `StorageManager` 的读档构造器也 `setDirty()` ⇒ 读档即标脏 | 存在 | 已修：读档构造器不再标脏 | 修 |
| F2 | `/ae2things recover <uuid>` 只查 `hasUUID`，**不查该盘是否已被在线实例持有** ⇒ 对在线盘生成第二份引用 = 复制通道 | 存在 | **未修**（需要“在线盘登记表”，属新增机制） | 待裁决 |
| F3 | `amounts`/`tags` 长度不一致时，1.20.1 的 `ListTag.getCompound(i)` 越界**返回空 tag 而不抛异常** ⇒ `fromTagGeneric` 返回 null ⇒ 一次 warn 后 `saveChanges()` **永久截断尾部** | 存在 | 沿用 1.21.1 的 `Math.min(length, size)`：索引一致安全、损坏尾部丢弃并重写（同时打 `Loading storage cell with mismatched amounts/tags` 警告） | 保持 |
| F19 | 侧表必须挂在**主世界**的 `DataStorage` 上（`server.getLevel(OVERWORLD)`）；改成 `level.getDataStorage()` 会让下界/末地的盘“丢内容” | 上游写法正确 | 保持上游写法（**不要**“优化”成 level 相对） | 保持 |

> C2/C2b/C3/C4/C5/C9 / F2 目前**只在 1.20.1 线修复**。1.21.1 线是否需要同样修复已独立复核，结论见 §10 与 `ONE21-BUG-AUDIT.md`：**7 条里 6 条在 1.21.1 上真实存在**（无一条 ABSENT），建议回移；C3 在 1.21.1 上的形态与 1.20.1 相同（`getUUID` 抛 NPE → 整表被清空），且最现实的可达路径是**跨线存档**（1.20.1 写 `diskuuid`、1.21.1 读 `disk_id`）。是否回移等用户裁决。

## 6. AE2 MEGA Things 兼容

目标：`AE2MEGAThings-1.20.1-1.0.8.jar`（CurseForge 6203824；≤1.0.5 的 1.20.1 构建是非 reobf 的 dev jar，不能作基准）。

它通过**符号表**（而不是版本号）与 AE2 Things 绑定；版本门槛只写了 `modId=ae2things` 的 `[1.2,)`，所以哪怕我们符号缺失也照样“通过依赖检查”并在运行期炸。逐项核验（在本轮产物 `AE2-Things-1.3.0.jar` 上用 `javap` 实测）：

| MEGA 需要的符号 | 实测 |
|---|---|
| `AE2Things.STORAGE_INSTANCE : StorageManager`（getstatic） | 有 |
| `DISKCellInventory.<init>(IDISKCellItem, ItemStack, ISaveProvider)`（**三参**） | 有（四参版本保留给内部） |
| `DISKCellInventory.getStoredItemCount()J` / `getNbtItemCount()J` | 有 |
| `DISKCellHandler.INSTANCE`；`getCellInventory(ItemStack, ISaveProvider)` 返回 **`DISKCellInventory`** | 有（两个协变重载都在） |
| `AETItems.DISK_DRIVE_256K : RegistryObject<Item>` | 有 |
| `StorageManager.hasUUID(UUID)Z` / `getOrCreateDisk(UUID)DataStorage` / `DataStorage.itemCount:J` | 有 |
| 常量 `ae2things` / `diskuuid` / `ic` | 未改名 |
| `Command.lambda$help$3` 内含单个含 `<UUID>` 的字面量（MEGA 用 `@ModifyConstant(ordinal=0)` 替换成 `<UUID> [DISK]`） | 有：`ldc /ae2things recover <UUID> - Spawns a drive with the given UUID, if it doesn't exist, does not spawn any item.` |
| `insert()` 中**恰好一处** `IDISKCellItem.isBlackListed(ItemStack, AEKey)Z` 调用（MEGA 的 `@ModifyExpressionValue` 依赖 `defaultRequire=1`） | 有，计数 = 1（黑名单判断不得抽成 helper） |
| 命令根 `ae2things`、子命令 `recover`/`getuuid` 的 id | 原样 |
| lang key `command.ae2things.recover_success`（两个 %s）/ `recover_fail`（一个 %s） | 5 个语言文件里都有 |

结论：**静态契约全部满足，无“不兼容”项**。运行期实测结论见 §7.1（真实 Forge 服务端联调，通过）。

## 7. 验证记录

- 构建：`./gradlew spotlessApply build` → BUILD SUCCESSFUL；`build/libs/AE2-Things-1.3.0.jar`，80,246 B，103 entries，sha256 `8598f55ee562e2c61cd3afd9d1845c908c8086c5335d5789d39d17eb54e357dc`（含 `mixins.ae2things.json`、`ae2things.refmap.json`、11 配方 + 11 进度、全部素材、无 dev 类）。早一轮未含 C3 修复的产物是 80,028 B / sha256 `73dcb144…`，已被取代。
- C3 修复后复跑联调服务端（7 个 jar）：`Done (1.475s)!`，日志内除 megacells 自身的 loot table 噪声外无异常。
- manifest：`MixinConfigs: mixins.ae2things.json`、`Implementation-Version: 1.3.0`。
- refmap（生产 SRG）：`doClick → m_150430_`、`Slot.hasItem()Z → m_6657_`、`ItemStack.copyWithCount(I) → m_255036_`。
- 开发服：`Mixing CursedInternalSlotMixin from mixins.ae2things.json into net.minecraft.world.inventory.AbstractContainerMenu`；`Creating FMLModContainer instance for io.github.projectet.ae2things.AE2Things`；`Done (1.188s)!`，无异常。dev 下 `Reference map ae2things.refmap.json ... could not be read` 是可忽略的提示（refmap 在 build/mixin，dev 用命名映射）。
- 数据包：`Loaded 1690 advancements` 恰好 = 原版 1271 + AE2 408 + 本模组 11，说明 11 个进度 JSON 被正确解析。
- 配方（**实测，不是推断**）：临时在 `AE2Things.onServerStarted` 里打印 `RecipeManager` 内容后测得 `crafting recipes=1181`，且 `ae2things:disk_housing`、`ae2things:disk_drive_1k`、`ae2things:disk_drive_256k_with_housing` 三个 id 全部 `present=true`；探针（额外加一个配方）与对照实验均已回收，源码里无残留。
- 注意：dev 服的 `Loaded 12 recipes` 这行日志**不代表实际配方数**（同一刻 `getAllRecipesFor(CRAFTING)` 为 1181），属于 Forge 1.20.1 dev 环境的日志口径问题，不必据此判断配方是否生效。
- 素材来源：`textures/item/disk_drive_1k.png`、`textures/model/drive/cells/disk_1k.png`、`assets/minecraft/atlases/blocks.json` 三者 sha1 与 `_ref/ae2things-1.20.1` 完全一致。

### 7.1 AE2 MEGA Things 运行时联调（真 Forge 服务端实测）

为什么不用 dev 环境：MDG dev 跑的是命名映射，而 MEGA 是按 SRG 重映射的发布 jar，dev 里出现 `NoSuchMethodError` 无法区分“真契约破损”与“映射差异”。所以另起一个真实 `1.20.1-47.1.47` 专用服务端：`F:/MCProject/_ref/runtime-forge-mega`（Forge 官方 installer 装出的 `libraries/net/minecraftforge/forge/1.20.1-47.1.47/win_args.txt`，`--launchTarget forgeserver`）。

最终联调配置（7 个 jar，稳定启动 `Done (1.551s)!`）：

| jar | 说明 |
|---|---|
| `ae2-forge-real-15.4.12.jar` | AE2 Forge 15.4.12（Modrinth XxWD5pD3 / QRpVsqDI，sha1 `4e819846…`） |
| `guideme-20.1.15.jar` | AE2 硬前置 |
| `AE2-Things-1.3.0.jar` | 本轮产物 |
| `AE2MEGAThings-1.20.1-1.0.8.jar` | 被测对象 |
| `kotlinforforge-4.12.0-all.jar` | MEGA 硬前置 `[4.11.0,)` |
| `megacells-forge-2.4.6-1.20.1.jar` | MEGA 硬前置 `[2.1,)` |
| `cloth-config-11.1.136-forge.jar` | megacells 硬前置 `*` |

实测证据：
1. 启动无异常，`Done (1.551s)!`；整份日志里 `NoSuchFieldError` / `NoSuchMethodError` / `AbstractMethodError` / `ClassNotFoundException` / `NoClassDefFoundError` / `InjectionError` / `MixinApplyError` / `IllegalAccessError` 计数全为 0。
2. `-Dmixin.debug.verbose=true` 下三条 mixin 全部应用：
   - `Mixing CursedInternalSlotMixin from mixins.ae2things.json into net.minecraft.world.inventory.AbstractContainerMenu`（我们的）
   - `Mixing MixinAE2ThingsCommand from ae2_mega_things.mixins.json into io.github.projectet.ae2things.command.Command`
   - `Mixing MixinDISKCellInventory from ae2_mega_things.mixins.json into io.github.projectet.ae2things.storage.DISKCellInventory`
3. **行为级**验证（RCON 执行 `ae2things help`，服务端控制台来源）：输出为
   `/ae2things recover <UUID> [DISK] - Spawns a drive with the given UUID, if it doesn't exist, does not spawn any item. You can change the disk to be spawned by the given DISK.`
   —— `[DISK]` 与尾句是 MEGA 的 `@ModifyConstant(lambda$help$3)` 改写结果，证明它确实命中我们 `help()` 里那个字面量、且 javac lambda 序号如 MEGA 预期。
4. 存档链路：无玩家环境下用 `data modify block 0 -60 0 inv.item0 set value {id:"ae2things:disk_drive_1k",Count:1b,tag:{diskuuid:[I;1,2,3,4]}}` 把盘插进 `ae2:drive`（本模组不注册方块；`/item replace` 对 AE2 的 BE 无效），BE 反序列化即构造 `DISKCellInventory` 并触发 MEGA 的 `MixinDISKCellInventory`；随后 `world/data/disk_manager.dat` 被创建（解压 46 B，键 `data`/`disklist`/`DataVersion=3465`，盘表为空——因为从未插入过物品，`getOrCreateDisk` 未触发，符合预期），RCON `stop` 干净退出。

联调中发现的环境怪象（**与我们的模组无关**，记录以免以后误判）：在手装的这台服务端上，若 `mods/` 只剩 6 个 mod，会在 AE2 自己的 `MysteriousCubeBlock.<clinit>` 抛 `IllegalAccessError: ... Blocks.m_50805_`（即 AE2 自带 AT 未生效）；7 个 mod 则稳定正常。已排除“我们的 jar 导致的”：**移除 megacells（而非我们的 jar）同样复现**；加一个空 jar、把 AE2 的 jar 改名也都不影响（仍失败）；同一配置重复跑 3/3 稳定（A 有我们 jar → 成功 3 次，B 无我们 jar → 失败 2 次）。判断是这套手工拼装服务端在 mod 数较少时的 AT 扫描怪癖，与真实整合包无关。

另外，日志里 `megacells` 自身的数据噪声不属于我们：`Couldn't parse element loot_tables:megacells:blocks/mega_emc_interface` 与大量 `Skipping loading recipe megacells:… as it's serializer returned null`（可选联动序列化器）。

结论：**MEGA Things 1.20.1-1.0.8 与我们的 1.3.0 运行期兼容，无缺失符号、无 mixin 冲突、命令改写与盘数据链路均正常。**

## 8. 未决 / 需用户裁决

1. F2（`/ae2things recover` 可对在线盘造出第二个引用）：要不要加“在线盘登记表”堵死？需要一份“哪些 UUID 正被某个 cell inventory 持有”的弱引用表，属新增机制。
2. C2/C3/C4/C5/C9 只在 1.20.1 线修了；是否回移到 1.21.1 线（尤其 C3 的坏档 NPE）。
3. 1.21.1 线是否也补回 `DISKItemCellGuiHandler`（1.20.1 线已恢复）。
4. ~~版本号由环境变量决定~~ **已按用户裁决改为每线独立版本号**，详见 §11。

## 9. F2 详解：`/ae2things recover <uuid>` 的复制通道（给裁决用的完整说明）

### 9.1 现状与调用链

- `Command.spawnDrive()`（`forge-1.20.1/.../command/Command.java:62-83`；1.21.1 同形）：拿到 UUID → `storageManager.hasUUID(uuid)` 为真就 `new ItemStack(DISK_DRIVE_256K)` + `setDiskUUID(stack, uuid)` + `setNbtItemCount(...)` + `player.addItem(stack)`。**全程不知道、也不问“这个 UUID 现在是否已经有一份盘在用着”**。
- 权限：`recover` 需要 `hasPermission(2)`（`Command.java:42`），也就是**只有 op / 单人存档能触发**；`getuuid`（`Command.java:85-102`）无权限门槛，只要手上拿着盘就能读到 UUID。
- 盘与数据的关系：任何 `DISKCellInventory` 都通过 `StorageManager.getOrCreateDisk(uuid)` 拿 `DataStorage`；同一个 UUID **永远是同一个 `DataStorage` 对象**（manager 里的 `Map<UUID, DataStorage>` 只存一份）。

### 9.2 为什么这会造成复制 / 丢数据（不是理论推演，是写回语义决定的）

两个盘实例 = 两份 `DISKCellInventory`，各自持有一份 `storedAmounts` 快照，但写回时都写进**同一个** `DataStorage`：

1. 设原盘内容是 `{泥土:100}`。op 执行 recover ⇒ 手上多出第二块盘，内容也是 `{泥土:100}`（共享同一条侧表记录）。
2. A 抽出 50 泥土 ⇒ A 快照 `{泥土:50}` ⇒ `persist()` 写回侧表 `{泥土:50}`。
3. B 从未被碰过，快照仍是 `{泥土:100}`；B 只要发生一次写（插入/抽出任意物品）就会 `persist()`。若 B 的 key 集合与侧表一致，`tryUpdateAmounts` **原地覆盖** ⇒ 侧表变回 `{泥土:100}`：**凭空多出 50 泥土**。
4. 反向操作（A 插入 50 后 B 写回旧值）⇒ **50 泥土凭空消失**。

所以 F2 的后果是**双向的**：既能刷物品，也能吃物品；而且两边都不报错、不崩服，玩家只会看到“我的盘内容不对”。

### 9.3 现实可达路径（按威胁从高到低）

1. **op 误操作（最常见，也最危险）**：想帮玩家“找回”一块盘，但原盘其实还在某个驱动器/背包/末影箱里 ⇒ 静默复制或吞掉内容。恢复功能本身就注定会被这么用。
2. **单人存档**：玩家自己就是 op，随手一试就可能踩到。
3. **恶意 op / 被攻陷的 op 账号**：无限刷物品（但能做到这点的 op 本来也能 `/give`，威胁增量有限）。
4. **普通玩家**：`recover` 有权限门槛，**拿不到**；`getuuid` 只泄露自己的盘 UUID，本身无害。

### 9.4 三种处置方案（含成本与风险）

| 方案 | 做法 | 成本 | 覆盖度 | 风险 |
|---|---|---|---|---|
| A 在线盘登记表（推荐，最彻底） | 在 `StorageManager` 侧加 `Map<UUID, WeakReference<DISKCellInventory>> LIVE`；`DISKCellInventory` 构造/首次加载时登记，`recover` 若查到**仍存活且其容器仍有效**的实例则拒绝并给出提示；`onServerStopped` 清表 | 约 40–60 行，1 个新静态表 + 2 处登记 + 1 处判定 | 覆盖所有形态（背包/驱动器/箱子/其他维度/正在打开的 GUI） | 弱引用需要正确清理；误判（实例还在但已空壳）会导致“无法恢复”假阳性，需要提示文案引导玩家先取出 |
| B 背包快照检查（便宜，不彻底） | `recover` 时扫所有在线玩家背包 + 打开的菜单，看有没有同 UUID 的盘 | 约 15–20 行 | 只覆盖“盘在人身上/在打开的 GUI 里”，**漏**驱动器/箱子/末影箱 | 无状态，零清理负担 |
| C 只写文档（什么都不改） | 保留上游行为，在 README/命令帮助里写明“仅用于盘已彻底丢失时” | 0 | 无 | 依赖玩家自律，事故照旧 |

我的建议：**A**。理由是 `recover` 一旦被误用就是静默数据事故，而 A 是唯一能真正挡住的形式，且不改变存档格式、不影响 MEGA 的符号契约（`hasUUID`/`getOrCreateDisk` 签名不动，只是新增一个静态表 + 判定）。如果只想低成本止血，退一步选 B。

> 注意：这条与 C3 不同，**F2 在 1.21.1 与 1.20.1 两条线上完全一样**，修就得两条线一起修（或明确只保护 1.20.1 线）。

## 10. 1.21.1 线缺陷复核（用户裁决第 3 条的执行结果）

独立复核（`docs/port-1.20.1/ONE21-BUG-AUDIT.md`，34 KB，全部带 `文件:行` 证据）结论：**7 条里 6 条在 1.21.1 上真实存在，无一条 ABSENT**，即 1.21.1 线确实需要同一批修复：

| 项 | 1.21.1 判定 | 证据要点 | 建议 |
|---|---|---|---|
| C4 SIMULATE 铸 UUID | PRESENT（最优先） | `DISKCellInventory.java:396-400` 在 `:414` 的 `MODULATE` 判断之前就 `set(DATA_DISK_ID, randomUUID())` + `getOrCreateDisk` + `loadCellItems()` | 必须修 |
| C2 读路径 getOrCreateDisk | PRESENT | `DISKCellInventory.java:89-95`；1.21.1 的 `StorageManager` **没有** `getDisk(UUID)` | 修 |
| C2b 构造/读档即 setDirty | PRESENT | `StorageManager.java:26` 与 `:31` | 修 |
| C3 readNbt 无前置判断 | **DIFFERENT**（形态变了） | `:54` 无判空；1.21.1 `CompoundTag#getUUID` 缺键**抛 NPE** ⇒ `DimensionDataStorage` 吞异常 → 空白 manager 顶替 → 下次存档清空整表 | 修（**必须用 `hasUUID()`，别照抄 1.20.1 早期的 null 判断**） |
| C5 拆解不 removeDisk | PRESENT（面已收窄） | `DISKDrive.java:130-159`；取空那条主来源已被 `persist()` 兜住 | 修（约 6 行） |
| C9 manager==null 静默丢数据 | PRESENT | `:396` 不铸 UUID + `:414-417` 照样写内存 + `:164-168` 早退 | 修（约 2 行） |
| F2 recover 不查在线占用 | PRESENT | `Command.java:65-71` 只有 `hasUUID` | 设计级，见 §9 |

三条耦合（照此顺序改最省事）：C4 → C2 → C2b → C3 → C9 → C5 → F2；且**修 C2 时不要把 `persist():181` 改成只读**——那是写路径。

## 11. 版本号：每线独立（用户裁决第 5 条）

- 环境变量 `AE2THINGS_VERSION` **已移除**，全仓再无引用。
- 1.21.1 线：根 `gradle.properties` 的 `mod_version=1.4.3`（与线上在用的 1.4.3 对齐），`build.gradle: version = mod_version`；CI 发版时用 `./gradlew build -Pmod_version=<tag>` 覆盖（`publish.yml` 已改）。
- 1.20.1 线：`forge-1.20.1/gradle.properties` 的 `mod_version=1.3.0`（初版号），`forge-1.20.1/build.gradle: version = mod_version`。
- 两条线各自升号，互不牵连；产物名分别是 `AE2-Things-1.4.3.jar` / `AE2-Things-1.3.0.jar`，`Implementation-Version` 与目录名一致（均已实测）。

## 12. 独立复核（read-only reviewer，2026-11-05）

对移植版的持久化修复做了独立复核：只读源码 + 对 merged jar 与编译产物 `javap -p -c` 逐条取证，不采信主会话自述。四个判据与结论：

| 判据 | 结论 | 关键证据 |
|---|---|---|
| Q1 `readNbt` 不丢数据、不抛 | **PASS** | `StorageManager.java:52-56` 先 `hasUUID` 守卫再 `continue`；`:60-64` 的 `try/catch(Exception)` 字节码异常表 `from 73 to 97 target 100` 覆盖 `getUUID`+`getCompound`+`fromNbt`+`put` 全段；`hasUUID` 实测语义 = `get(key)` 非空 + `Tag.getType()==IntArrayTag.TYPE` + 长度 4，三种坏法都只返回 false。每条合法 entry 必 `put`。 |
| Q2 读路径不再弄脏存档 | **PASS**（一条残留写入，仅坏数据可达） | 读路径全部经 `DISKCellInventory.java:108 storageManager.getDisk(diskId)`（`StorageManager.java:90-93` 只 `get`、不 `setDirty`）；写路径仍用 `getOrCreateDisk`/`updateDisk`/`modifyDisk`/`removeDisk`（`:209/:222/:224/:229/:191`）。残留：损坏数据的 `loadCellItems` 自修复会 `removeDisk`（`:378-382` → `:189-197`），与 upstream 一致。 |
| Q3 `insert()` 门禁顺序 | **PASS** | 源码顺序 `:423` 参数 → `:427` manager==null 拒绝 → `:434/:438` 分区/黑名单 → `:456-460` 铸 UUID → `:474-477` 才 put+saveChanges；字节码偏移序列 `20/28` 门禁在最前，`147 UUID.randomUUID`、`222 put`、`230 saveChanges` 在其后 ⇒ SIMULATE 探针零副作用。 |
| Q4 存档格式 | **PASS**（对 upstream 1.20.1 全等）/ 与 1.21.1 线不同键名 | 逐键核对：`disk_manager` / `disklist` / `diskuuid`(IntArray4) / `diskdata` / `keys` / `amts` / `ic`(仅非 0 时写) 与 upstream 1.20.1 完全一致，`diskuuid` 与 `diskdata` 同级。 |

### 12.1 关于「与 1.21.1 线键名不同」的定性（不修）
- 1.21.1 线用 `disk_id` / `item_count`，1.20.1 线用 `diskuuid` / `ic`。**这不是本移植的偏差**：上游 1.21.1 自己就把键名改成了 `disk_id`/`item_count`（`_ref/ae2things-forge-upstream/src/main/java/io/github/projectet/ae2things/util/StorageManager.java:19`、`DataStorage.java:11`），而 1.20.1 上游（tag `1.2.1`）一直是 `diskuuid`/`ic`。
- 因此 1.20.1 线**必须**用 `diskuuid`/`ic`：这既是 1.2.1 存档能直接读的唯一前提，也是本项目 AGENTS §1.2「存档格式完全兼容」的落点。
- 后果边界：把 1.21.1 世界的 `world/data/disk_manager.dat` 手工搬到 1.20.1，条目会因缺 `diskuuid` 被丢弃（有 warn 日志），反向则 1.21.1 读 1.20.1 的键会 NPE。1.20.1 客户端本来就无法加载 1.21.1 世界，所以这条只在手工搬文件时成立，属**预期行为**。

### 12.2 复核发现并已修复的缺陷（P2）
- 现象：物品 NBT 里 `diskuuid` 存在但值不是 4-int UUID（手改、或被别的版本写坏）时，`hasDiskUUID()` 只判 `contains` 为真 ⇒ `insert` 不铸新 id，`getDiskUUID()` 返回 null ⇒ `persist()` 直接 `isPersisted = true; return;` ⇒ 内容只活在内存里，卸载即丢。
- 修复：`DISKCellInventory.persist()` 在 `diskId == null && storedItemCount > 0` 时**就地铸一个 UUID 并写回物品**，再继续正常落盘（不再静默返回）。
- 未改 `insert`（MEGA 的 `@ModifyExpressionValue` 依赖 `insert` 里恰好一处 `isBlackListed` invokeinterface，动它风险更大）。
- 重新构建：`AE2-Things-1.3.0.jar` 80,250 B / 103 entries / sha256 `2881b38b9ada9e136f260adf2a94d869698e75da487fa080ad7ecf6b511768df`；联调服务端复跑 `Done (1.259s)!`，NoSuchMethod/NoSuchField/AbstractMethod/ClassNotFound/MixinApplyError 计数全 0。

### 12.3 复核保留但本次不改的项（P3/P4，均继承自上游）
- P3：损坏数据触发的 `loadCellItems` 自修复会在「读」路径写盘（`DISKDrive.java:119-121` 入口），与 upstream 1.20.1 同形；要彻底消除需给 `DISKCellInventory` 加只读标志，属独立改动。
- P4：`IDISKCellItem.java:100` 克隆时对源 id 用 `getOrCreateDisk`，源盘无记录时会顺手建一条空记录（写路径，影响可忽略）。

## 13. 第二轮独立复核（3 major / 6 minor / 3 nit）的处置

第二轮 read-only 复核（同一次 `code_review` 派生的独立 reader，全部结论带 `文件:行` 与 javap 证据）判定**无 blocker**，但列出 3 条 major / 6 条 minor / 3 条 nit。逐条处置如下（P1 = major，P2 = minor，P3 = nit）：

| # | 复核结论 | 处置 | 落点 |
|---|---|---|---|
| P1-1 | 读档时丢弃的坏条目，会在下一次无关写入时被永久抹掉（`save()` 只序列化内存表） | **已修**：不可读条目原样保留并在 `save()` 末尾追加回去，文件不再丢字节 | `util/StorageManager.java`（`unreadable` 字段 + `save()` 的 `diskList.addAll(unreadable)` + `readNbt` 两条丢弃路径改为 `unreadable.add(disk)`，日志措辞由 Dropping 改为 Keeping…unreadable） |
| P1-2 | CI 从不构建这条线；打 1.20.1 的 tag 会发布出一个 1.21.1 的 jar | **已修**：`build.yml` 拆成 NeoForge 1.21.1 / Forge 1.20.1 两个 job（后者 `working-directory: forge-1.20.1`、JDK 17）；`publish.yml` 按 tag 前缀门控（`forge-<ver>` 走 Forge 线，纯 `<ver>` 或 `neoforge-<ver>` 走 1.21.1 线），上传名统一 `AE2-Things-<ver>.jar` | `.github/workflows/build.yml`、`.github/workflows/publish.yml` |
| P1-3 | `/ae2things recover` 能让两个活动盘共用一个 UUID（后写覆盖先写；空盘分支还会 `removeDisk` 并把另一张盘留在「有 UUID 无条目」） | **待裁决**：即 §9 的 F2，此处复核补了第四种方案（换新 UUID + 复制条目），见 13.2 | — |
| P2-4 | `mixins.ae2things.json` 的 `"required": false`：注入失败只记日志，创造模式中键复制守护会静默消失 | **待裁决**（改成 `true` = 注入失败即启动失败，利弊见 13.2） | `src/main/resources/mixins.ae2things.json:2` |
| P2-5 | `DISKCellInventory` 在构造期把 manager 存进 `final` 字段；`initServer()` 阶段加载的区块（出生点驱动器）会拿到 null manager 并显示为空 | **待裁决**：复核已验证会自愈（`onReady`/`mountInventories`/`onChangeInventory` → `updateState()` 重建），惰性兜底属加固 | `storage/DISKCellInventory.java:64-67`、`:105-112` |
| P2-6 | `extract()` 缺 `insert()` 那样的 `storageManager == null` 守卫（今日不可达，纯不对称） | **已修**：MODULATE 且无 manager 时返回 0 | `storage/DISKCellInventory.java` `extract()` 开头 |
| P2-7 | `DataStorage.copy()` 共享每个 key 的 `CompoundTag`（靠「没人原地改 tag」的隐含不变量） | **已修**：逐条 `getCompound(i).copy()` 深拷贝，javadoc 同步改写 | `util/DataStorage.java` `copy()` |
| P2-8 | 铸 UUID 发生在 `amount <= 0` 裁剪之前，可能留下空条目（今日不可达） | **未改**：动 `insert()` 的语句顺序会触碰 MEGA 的注入点，收益为 0、风险非 0 | `storage/DISKCellInventory.java:457-475` |
| P2-9 | `getColor` 与 tooltip 每次渲染都 new 一整个 `DISKCellInventory`（含 `CellConfig.create` 读 NBT） | **部分已修**：`getColor` 改走静态 `DISKCellInventory.getClientStatus(ItemStack)` + `getTotalBytes(ItemStack)`，路径上不再建 inventory；**tooltip 保留虚调用**，因为 MEGA 的工具提示依赖它（契约 R7） | `item/DISKDrive.java:164-175`、`storage/DISKCellInventory.java` |
| P3-10 | `help()` 的 lambda 编号是 MEGA 的二进制契约，源码里没写 | **已修**：`help()` 上加 javadoc 明示 `lambda$help$3` 与 `<UUID>` 字面量不可动 | `command/Command.java` |
| P3-11 | `recover` 走 `getOrCreateDisk` 而入口已由 `hasUUID` 把关 | **已修**：改 `getDisk(uuid)`（不建条目） | `command/Command.java:67-73` |
| P3-12 | 本文档 §1 过期（写着未提交 / 80,028 B） | **已修**：§1 已更新为本轮实际状态 | 本文件 §1 |

### 13.1 本轮的实测证据

- **P1-1 的行为自测**（开发服，临时钩子，已删除）：构造 `disklist` = 1 条合法条目 + 1 条缺 `diskuuid` 的条目，`readNbt` → `save` 往返后输出 `[AE2T-DEBUG] retained entries=2 marker=keep-me uuidPresent=true`，同时日志有 `Keeping disk entry 1 from disk_manager unreadable: no usable diskuuid tag`。即坏条目被保留、好条目照常可读。
- 自测代码删除后重建，jar 与自测前**逐字节相同**（`spotlessApply build` 后 80,454 B / 103 entries / sha256 `27488cd19…`），源码 `grep AE2T-DEBUG` 为空。
- **联调服务端（7 个 jar：AE2 15.4.12 + GuideME + 本模组 + AE2 MEGA Things 1.0.8 + KotlinForForge + MegaCells + Cloth Config）**：`Done (1.282s)!`；RCON 执行 `ae2things`（裸命令即帮助）输出仍带 MEGA 改写的 `/ae2things recover <UUID> [DISK] … You can change the disk to be spawned by the given DISK.` ⇒ `@ModifyConstant(lambda$help$3, ordinal 0)` 契约在本轮改动后依然成立；`stop` 干净退出，全日志 `NoSuchMethod/NoSuchField/AbstractMethod/ClassNotFound/InjectionError` 计数为 0，唯一 ERROR 是 megacells 自己的 `loot_tables:megacells:blocks/mega_emc_interface` 解析失败（与本模组无关）。
- **未验证项**：两个 workflow 的 YAML 没有在 CI 实跑过（本机无 yaml 解析器，也不想靠 `npx` 联网装）；首次推送后需要看一眼 Actions 是否按预期分 job。

### 13.2 三个待裁决项（已于 2026-11-05 裁决，处置见 §14）

1. **P1-3 / F2：`recover` 的语义怎么定**。复核给出第四种方案 **D：换新 UUID + 复制条目**（照 `IDISKCellItem.cloneDisk` 的写法：`copy()` 原条目 → `updateDisk(newId, copied)` → 写回物品 NBT，并在成功消息里报新 id）。
   - D 的好处：从机制上**不可能**出现两盘共用一个 UUID，改动约 8 行，不需要登记表，也不会漏掉驱动器/箱子里的盘。
   - D 的代价：`recover` 从「找回那张盘」变成「复制一张新盘」——原件还在时这就是一次物品复制（op only）。
   - A（在线盘登记表 + 拒绝）仍是「不改语义、只挡误用」的唯一方案，代价 40–60 行 + 弱引用清理。
   - 我的建议：**D**。理由是 oplist 本来就能用 `/give`、创造模式拿任何东西，「op 能复制」不是新增能力，而「静默清空一张真盘」是真正的事故。最终以你的裁决为准，两条线要一起定。
2. **P2-4：mixin 的 `required`**。`true` = 注入点一旦解析失败就启动失败（吵闹但安全）；`false` = 只记一行日志，创造模式中键复制失去守护（静默出双盘）。1.21.1 线当前也是 `false`，要改就两条线一起改。我的建议：**改成 true**，注入目标已验证稳定（`@Slice` 命中 bci 1428 的 `hasItem`，其后唯一 `copyWithCount` 在 1449）。
3. **P2-5：manager 惰性解析**。现在依赖 AE2 的 `updateState()` 在首 tick 自愈；加固做法是让 `currentStorageManager()` 在没有实例但有运行中服务端时惰性初始化。要改动 3 个文件，实机验证成本较高。我的建议：**先不动**，把「重启后出生点驱动器里的盘是否正常显示」列进实机验收清单，出问题再加固。

## 14. 裁决落地（2026-11-05）

用户裁决原文（m01251）：**「裁决：1. D；2. 改true；3.等实机验证再决定。另外共享修复和DISKItemCellGuiHandler回灌到1.21.1」**

| 裁决项 | 结论 | 落地 |
|---|---|---|
| P1-3 / F2 `recover` 语义 | **D：换新 UUID + 复制条目** | 1.20.1：`command/Command.java` `spawnDrive` 改为 `copied = storageManager.getDisk(uuid).copy()` → `newId = UUID.randomUUID()` → `updateDisk(newId, copied)` → 写回物品 `diskuuid` + `ic`，成功消息报**新 id**；1.21.1：同语义，但写的是数据组件 `DATA_DISK_ID` / `DATA_DISK_ITEM_COUNT` |
| P2-4 mixin `required` | **改 `true`** | 两条线的 `mixins.ae2things.json` 均改为 `true`（注入点一旦解析失败即启动失败，不再静默丢掉中键复制守护） |
| P2-5 manager 惰性解析 | **先不动** | 依赖 AE2 `updateState()` 自愈；把「重启后出生点驱动器里的盘是否正常显示」列入实机验收清单 |

补充说明：装了 AE2 MEGA Things 时，Brigadier 的 `addChild` 是合并语义，MEGA 自己的 `recover` executor 会**覆盖**我们的执行器，因此 D 只决定「单独装本模组」时的行为；两条线都要各自实现一份，以免依赖 MEGA 的存在。

### 14.1 本轮 1.20.1 线的实测证据

| 项 | 结果 |
|---|---|
| 构建 | `./gradlew spotlessApply build` → BUILD SUCCESSFUL（11 s） |
| 产物 | `AE2-Things-1.3.0.jar`，103 entries，80,552 B，sha256 `f766e83dbc4010681530389ab6db37aa95c561fc5e494fba7db8c3a566c3868f` |
| 联调服务端（7 jar：AE2 15.4.12 + GuideME 20.1.15 + 本模组 + MEGA 1.0.8 + KotlinForForge 4.12.0 + MegaCells 2.4.6 + Cloth Config 11.1.136） | `Done (1.164s)!` |
| RCON `ae2things`（裸命令 = help） | 输出仍为 MEGA 改写后的 `/ae2things recover <UUID> [DISK] - Spawns a drive with the given UUID, if it doesn't exist, does not spawn any item. You can change the disk to be spawned by the given DISK.` ⇒ `@ModifyConstant(lambda$help$3, ordinal 0)` 在 `required: true` 下依然命中 |
| 全日志错误计数 | `NoSuchMethod / NoSuchField / AbstractMethod / ClassNotFound / InjectionError` = 0；唯一 ERROR 为 megacells 自身的 `loot_tables:megacells:blocks/mega_emc_interface` 解析失败（与本模组无关） |
| 停机 | RCON `stop` 干净退出，`All chunks are saved` / `All dimensions are saved` |

> 注：`/ae2things recover` 本身需要玩家执行（服务端控制台会答 `A player is required to run this command here`），所以 D 的执行路径这一条**只做了编译与契约层验证**，语义由 1.21.1 线的同形自测（§15.2）覆盖。

## 15. 共享修复回灌到 1.21.1 线（版本 1.4.4）

两条线的持久化/渲染修复本来就是同一套问题，本轮把它们回灌到根工程，同时把版本号改为**每线独立**：根 `gradle.properties: mod_version=1.4.4`，1.20.1 线 `mod_version=1.3.0`。

| 修复 | 1.21.1 线的落点 | 与 1.20.1 线的差异 |
|---|---|---|
| C2 读路径不再建条目 | `StorageManager.getDisk(UUID)`（缺省返回 `DataStorage.EMPTY`）；`DISKCellInventory.getDiskStorage()` 改为只读 | 同形 |
| C2b 私有构造不再 `setDirty` | `StorageManager( Map, List )` 私有构造去掉 `setDirty()` | 同形 |
| C3 坏条目不再静默清表 | `readNbt` 先 `hasUUID` 再 `getUUID`，解码异常也捕获；坏条目进 `unreadable` | **失败形态不同**：1.20.1 的 `CompoundTag.getUUID` 缺键返回 null（→ 曾 NPE），1.21.1 是**抛 NPE**，所以两条线都必须先 `hasUUID` |
| P1-1 坏条目保留 | `unreadable` 字段 + `save()` 末尾 `diskList.addAll(unreadable)` | 同形 |
| C4 SIMULATE 不再铸 id | `insert()` 先读现有数量，MODULATE 分支才铸 UUID + `loadCellItems()` | 同形 |
| C9 / P2-6 无 manager 拒绝写 | `insert()` / `extract()` 在 MODULATE 且 `storageManager == null` 时返回 0 | 同形 |
| C5 拆解清条目 | `DISKDrive` 拆解路径先 `removeDisk(diskId)` 再掉零件 | 同形 |
| P2-7 `copy()` 深拷贝 | `DataStorage.copy()` 逐条 `getCompound(i).copy()` | 同形 |
| P2-9 渲染热路径 | `DISKCellInventory.getClientStatus(ItemStack)` / `getTotalBytes(ItemStack)` 静态化，`DISKDrive.getColor` 不再建 inventory | 同形（tooltip 保留虚调用，MEGA 契约） |
| P3-10 / P3-11 | `help()` 的 lambda 契约 javadoc；`recover` 走 `getDisk` | 同形 |

### 15.1 构建期踩到的两个坑（都已修）

1. `build.gradle` 的 `format("json") { prettier() }` 块被**移除**：spotless 6.25 用 `--scripts-prepend-node-path` 启动 npm，本机 npm 直接拒绝（`npm error code EUNKNOWNCONFIG` / `Unknown cli flag: - --scripts-prepend-node-path`），而任务只在**有 JSON 文件变更**时才真正执行 —— 本轮改了 `mixins.ae2things.json` 才第一次炸出 `:spotlessJson FAILED`。1.20.1 线从来没有这个块。
2. `DISKDrive` 拆解处一开始写了 `DISKCellInventory.getDiskUUID(stack)`，但 1.21.1 线的 `getDiskUUID` **没有静态重载**（编译错误：`无法将类 DISKCellInventory中的方法 getDiskUUID应用到给定类型`）→ 改用 `stack.get(AE2Things.DATA_DISK_ID)`。

### 15.2 回灌后的运行时自测（开发服，临时钩子，已删除）

在 `AE2Things.onServerStarted` 里临时挂了一段自测，四件事一次跑完，输出：

```
[AE2T-DEBUG] retentionMarker=true independent=true noMintOnSimulate=true nullManagerRefused=true
```

| 断言 | 含义 |
|---|---|
| `retentionMarker=true` | 构造 `disklist` = 1 条合法 + 1 条缺 `disk_id` 的坏条目，`readNbt` → `save` 往返后坏条目（`marker=keep-me`）仍在 ⇒ P1-1 在 1.21.1 的键名形态下成立 |
| `independent=true` | `getDisk(src).copy()` → `updateDisk(newId, copied)` 后原地改 `copied.stackAmounts[0]`，源条目仍是 9、新条目是 1、两条都在 ⇒ recover-D 的复制独立性成立 |
| `noMintOnSimulate=true` | 对空 256k 盘做 64 个石头的 `SIMULATE` 插入返回 64，且物品上**没有** `DATA_DISK_ID` ⇒ C4 成立 |
| `nullManagerRefused=true` | `storageManager == null` 时 MODULATE 插入返回 0 ⇒ C9 成立 |

自测代码与调用点已删除；重建后 jar 与自测前**逐字节相同**（`grep -rn 'selfTest\|AE2T-DEBUG' src/` 无残留）。

### 15.3 本轮 1.21.1 产物

| 项 | 结果 |
|---|---|
| 版本 | `gradle.properties: mod_version=1.4.4`（根 `build.gradle: version = mod_version`，不再用环境变量 `AE2THINGS_VERSION`） |
| 构建 | `./gradlew spotlessApply build` → BUILD SUCCESSFUL |
| 产物 | `build/libs/AE2-Things-1.4.4.jar`，70,038 B，sha256 `b59d3de408009dc845965b0bd75f4fbf07a0b61451edc4235ebed47ecdd92dd8` |

## 16. `DISKItemCellGuiHandler` 回灌结论：**不需要**（AE2 19 已内置）

用户要求把 `DISKItemCellGuiHandler` 回灌到 1.21.1，但取证结论是该扩展点在 AE2 19.2.18 **已被删除**，且行为已被 AE2 原生接管：

- `javap -p -cp <ae2-19.2.18.jar> appeng.api.storage.StorageCells` 只剩 `addCellHandler` / `isCellHandled` / `getHandler` / `getCellInventory`，**没有** `addCellGuiHandler` / `getGuiHandler`；`appeng/api/storage/cells/ICellGuiHandler.class` **整个不存在**（该目录只有 `ICellHandler / CellState / ISaveProvider / ICellWorkbenchItem / IBasicCellItem / StorageCell`），全 jar 字节扫描 `ICellGuiHandler` / `addCellGuiHandler` / `openChestGui` 命中 0。
- 19.2.18 的链路：`MEChestBlock.useWithoutItem` → `MEChestBlockEntity.openGui(Player)` → `StorageCells.getHandler(cell)` + `BasicCellChestMenu.TYPE`（`extends MEStorageMenu`）→ 直接开 ME 存储界面。我们对任意 `IDISKCellItem` 都返回库存（`DISKCellHandler.getCellInventory` → `DISKCellInventory.createInventory`），所以**点 ME 箱子顶面本来就开 ME 存储界面**。
- 上游为什么非注册不可：1.20.1 默认处理器 `BasicItemCellGuiHandler.isSpecializedFor` 要求 `instanceof IBasicCellItem`，而我们的 `DISKDrive extends Item implements IDISKCellItem` 不是；19 把判据换成了 `ICellHandler`，我们本来就注册了。
- 因此：1.21.1 线**不新建** `DISKItemCellGuiHandler`；1.20.1 线的 `gui/cell/DISKItemCellGuiHandler.java` **保留**（15.4.12 仍然需要它）。
