# UPSTREAM-AUDIT — AE2Things 1.20.1 (1.2.1) 行为差异清单 + 服务端性能/恶性 bug 审计

审计对象：上游 1.20.1 Forge 发行版 **AE2Things 1.2.1**（Technici4n/AE2Things-Forge tag 1.2.1）与我们 fork 当前 1.21.1 主线（F:/MCProject/ae2things-reforged @ master）。
用途：为「把本 fork 反向移植到 MC 1.20.1 / Forge 47.x / AE2 15.4.12」提供逐条判据；同时对抗性地找上游遗留、且**我们仍然带着**的 bug。

作者：teammate upstream-audit（independent auditor），共享任务 task-2。
证据约定：所有结论给 文件:行。凡无法从源码/jar 字节码 settle 的，写 **UNKNOWN**，不猜。

---

## 0. 摘要（中文）

1. 上游 1.20.1 有 **2 个可实际利用的复制/丢数据缺陷**：创意中键克隆共用同一个 DataStorage 实例（mixin 把 getOrCreateDisk(原UUID) 的**引用**塞给新 UUID），以及 /ae2things spawnDrive <UUID> 能把**任何** UUID（包括一只在线磁盘）再实体化成第二只磁盘。两者都是「两只盘各持一份内存 map、却写同一份存档列表」⇒ 后写覆盖 + 双向提取复制。**我们的 1.21.1 只修了前者（clone 深拷贝），后者仍然存在。**
2. 上游 loadCellItems 在 stackAmounts.length != stackKeys.size() 时**不会崩**：1.20.1 的 ListTag.getCompound(i) 越界返回空 CompoundTag（已用字节码核实），于是 AEKey.fromTagGeneric 返回 null → 一条 warn → saveChanges() 把尾部条目**永久截断**。我们改成 Math.min()，但仍语义上是「静默丢尾部」。
3. 性能主因（1.20.1 上同样成立）：persist() 对**盘上每个 key** 跑一次 toTagGeneric()；而 AppEngCellInventory.getStackInSlot / insertItem / setHandler **每次访问都先 persist(slot)**，DriveBlockEntity 内容变化又经 DriveWatcher → blinkCell → updateVisualStateIfNeeded → updateClientSideState → getCellItem 再读一次槽位 ⇒ **插入一个物品 = 全盘所有 key 重新编码一次**。我们已用「按 key 缓存 tag + 同 key 集合原地改 amounts + delta 计数器」压掉，移植必须带过去。
4. DISKCellInventory 的构造路径（initData → getDiskStorage → StorageManager.getOrCreateDisk）在**纯读**场景往 SavedData 插幽灵条目并 setDirty()；两个 StorageManager 构造器（含 readNbt 那个）也都 setDirty() ⇒ 每次存档全量重写 disk_manager。**我们没修。**
5. 客户端渲染路径（DISKDrive.getColor）上游把**客户端静态 dummy manager** 当真用，按看到的每个磁盘 UUID 无限插条目；单人游戏里与服务端线程并发写同一个 HashMap。我们传 null manager，已修。
6. 1.20.1 移植必须**回退 NBT 键名**：diskuuid（不是 disk_id）、ic（不是 item_count）、fuzzy 用物品 NBT "FuzzyMode"。这些是我们 1.21.1 上游基座（4f2503c）自己改的名，不是本 fork 改的。
7. AE2 19.2.18 **删掉了 ICellGuiHandler / StorageCells.addCellGuiHandler**（已在 jar 里 javap 核实），所以 1.21.1 少一个 GUI handler 是版本适应、不是我们删的；但 1.20.1（AE2 15.4.12）**必须加回来**，否则驱动槽里的 DISK 打不开界面。

---

## 1. 参照物与工具

| 参照 | 路径 | 说明 |
|---|---|---|
| 上游 1.20.1 源码 | F:/MCProject/_ref/ae2things-1.20.1 | tag 1.2.1，真实 1.20.1 Forge 发行版，14 个 java 文件 |
| AE2 15.4.12 源码 | F:/MCProject/_ref/ae2-forge-15.4.12 | tag forge/v15.4.12，HEAD 5a544ce4a |
| AE2 15.4.12 真实 Forge jar | F:/MCProject/_ref/libs/ae2-forge-real-15.4.12.jar | javap 用这个（另一个 ae2-forge-15.4.12.jar 是 Fabric 构建，**别用**） |
| AE2 19.2.18 jar | ~/.gradle/caches/modules-2/files-2.1/maven.modrinth/ae2/19.2.18/bcd04bc7.../ae2-19.2.18.jar | 只用于对照「19 删了什么」 |
| MC 1.20.1 merged jar | ~/.gradle/caches/fabric-loom/.../forge-1.20.1-47.3.0-minecraft-merged-1.20.1-loom...jar | 用于 ListTag / AbstractContainerMenu.doClick 字节码核实 |
| javap | C:/Program Files/Zulu/zulu-21/bin/javap.exe | |
| 本 fork | F:/MCProject/ae2things-reforged（1.21.1 / NeoForge 21.1.248 / AE2 19.2.18） | |
| fork 自己的上游基座 | 4f2503c（tag 1.4.2-beta，1.21.1） | 用于区分「上游改的」与「我们改的」 |

**重要基线事实**：git diff 4f2503c..HEAD -- src 显示 "diskuuid"→"disk_id"、"ic"→"item_count" 这些键名**在 4f2503c 就已经是新的**（Technici4n 自己做 1.20.1→1.21.1 时改的），不是本 fork 的改动。本 fork 对 DISKCellInventory 的 diff 是 101 行、DataStorage 173 行、StorageManager 8 行。

上游 1.2.1 的实际构建目标（F:/MCProject/_ref/ae2things-1.20.1/gradle.properties）：minecraft_version=1.20.1、forge_version=47.0.5、**ae2_version=15.0.3-alpha**、jei_version=15.0.0.12，ForgeGradle 6.0.+，mappings(official, 1.20.1)。
⇒ **上游是对着 15.0.3-alpha 编的**，而我们要对 15.4.12 → 见第 7 节逐条 API 结论。

---

## 2. Part A — 行为差异清单（上游 1.2.1 / 1.20.1 vs 我们 1.21.1）

分类：**[P]** 我们的性能改造 / **[B]** 我们的 bug 修复 / **[V]** AE2-19 或 MC-1.21 强制的版本适应（不是我们的选择）/ **[D]** 我们刻意的改动（元数据、i18n、资源、许可）。

### A.1 [P] 性能改造（本 fork 的存在理由）

| # | 位置 | 上游 1.20.1 | 我们 1.21.1 | 为什么 |
|---|---|---|---|---|
| P1 | DISKCellInventory.persist | 每次 flush 对每个 key 调 toTagGeneric()（DISKCellInventory.java:167-181） | 先 diskStorage.tryUpdateAmounts(storedAmounts) 原地改 amounts，失败才 rebuildFrom(...) 重编码（DISKCellInventory.java:191-204） | 绝大多数变化只改「已存在 key 的数量」，键集合不变 ⇒ 不必重编码 |
| P2 | DataStorage.getOrEncodeKeyTag | 无 | Map<AEKey,CompoundTag> keyTags 按 key 缓存（DataStorage.java:37, 94-105） | AEKey 按值相等 ⇒ 同 key 的 tag 必然相同，语义安全；上界 = 盘上已有 key 数 |
| P3 | DataStorage.copy | 无（上游连 clone 都没有） | DataStorage.java:61-71，独立副本（list/amounts/itemCount 不共享，key tag 共享） | 给创意克隆用，消灭 F1 的别名 |
| P4 | DISKCellInventory.saveChanges(long) | 每次变更 O(n) 重算 storedItemCount（:306-321） | 常见路径只加 delta（:348-353），全量重算只在 loadCellItems 修复分支 | 每次插入/提取遍历整张盘是纯浪费 |
| P5 | persist 的 "ic" 写入 | 无条件 i.getOrCreateTag().putLong("ic", itemCount)（:192） | if (i.getOrDefault(DATA_DISK_ITEM_COUNT,0L) != itemCount) i.set(...)（:212-215） | 值没变还写会不停搅动 data component patch / 物品同步 |
| P6 | StorageManager 取得方式 | 静态 AE2Things.STORAGE_INSTANCE，由 AE2Things.worldTick 每 tick 每维度重设（AE2Things.java:33,85-89；DISKCellInventory.java:302-304） | 静态字段 + ServerStartedEvent/ServerStoppedEvent，AE2Things.currentStorageManager()（AE2Things.java:56-60,107-117,128-131），并**显式传进** cell（DISKCellInventory.java:47-48,57-70,225-241） | 去掉每 tick × 每维度的 getDataStorage().computeIfAbsent；停机不残留旧 server 的 SavedData |
| P7 | StorageManager.modifyDisk | 直接改字段（:72-81） | 列表身份变化时 invalidateKeyIndex()（StorageManager.java:83-97） | 索引还指旧槽位时，下一次原地 amounts 写入会写错槽（P1 的正确性前提） |
| P8 | StorageManager.updateDisk | 裸 put（:51-54） | DataStorage.EMPTY 哨兵直接抛 IllegalArgumentException（StorageManager.java:59-65） | 防共享静态哨兵被写脏 |
| P9 | loadCellItems | 循环到 amounts.length，越界 getCompound 静默返空（:286-288） | Math.min(amounts.length, tags.size()) + resetKeyIndex()/indexKey() 重建索引（DISKCellInventory.java:303-325） | 1.20.1 的不越界行为导致「尾部永久截断」（F3）；我们至少显式化并维护索引 |

### A.2 [B] bug 修复

| # | 位置 | 上游 1.20.1 | 我们 1.21.1 | 为什么 |
|---|---|---|---|---|
| B1 | persist 空盘分支 | storedItemCount==0 分支执行完 return **没置 isPersisted=true**（:157-165） | 置 true 再 return（:170-179） | 否则这只盘永远"脏"，每次被访问都重跑分支 |
| B2 | insert | 没有 amount<=0 早退（:361-370） | amount<=0 直接 return 0（:409-412） | 钳到 0 后仍 put+saveChanges = 无意义脏化 |
| B3 | extract | 没有 amount<=0 早退（:376-400） | amount<=0 直接 return 0（:423-427） | 同上 |
| B4 | IDISKCellItem.isBlackListed | 无检查强转 (AEItemKey) requestedAddition + 不判空 getCellInventory(...).getUsedBytes()（:40-46） | instanceof 模式匹配 + inventory != null（IDISKCellItem.java:40-47） | 可 NPE |
| B5 | CursedInternalSlotMixin | 新 UUID **共用原盘的 DataStorage 引用**（:32-48） | 走 IDISKCellItem.clone → DISKDrive.clone → DataStorage.copy()（mixin:27-37；DISKDrive.java:80-104） | 别名 = 后写覆盖 = 丢数据/复制，见 F1 |
| B6 | DISKDrive.getColor | getCellInventory(stack,null) ⇒ 客户端 dummy manager 被真用（:151-161） | createInventory(stack, null, null)，注释"不要碰服务端 storage manager"，并把 alpha 字节按位或进 0xFF000000 与 color（DISKDrive.java:176-187） | 见 F6；1.20.1 tint 是纯 RGB，移植应**去掉 alpha 字节** |
| B7 | DISKCellHandler | 走静态 STORAGE_INSTANCE | 传 AE2Things.currentStorageManager()（DISKCellHandler.java:29）；tooltip 路径显式传 null（:34） | tooltip/渲染只需物品上的信息 |
| B8 | Command | 直接用静态实例 | 先判 currentStorageManager()==null（Command.java:63-65） | 服务器未启动会 NPE |

### A.3 [V] AE2-19 / MC-1.21 强制的版本适应（不是我们的选择，但移植要反着做）

| # | 位置 | 上游 1.20.1 | 我们 1.21.1 | 移植动作 |
|---|---|---|---|---|
| V1 | 磁盘身份 | 物品 NBT diskuuid（Constants.java:5）+ ic（DISKCellInventory.java:41） | data component DATA_DISK_ID("disk_id") / DATA_DISK_ITEM_COUNT("disk_item_count")（AE2Things.java:44-50） | **回退 NBT，键名必须 diskuuid / ic**，否则 1.2.1 存档里所有磁盘变空气 |
| V2 | fuzzy | 物品 NBT 字符串 "FuzzyMode"（DISKDrive.java:75-90） | component DATA_FUZZY_MODE("fuzzy_mode"，codec)（AE2Things.java:51-54；DISKDrive.java:107-114） | 回退 NBT，键名保持 "FuzzyMode" |
| V3 | StorageManager 内部 UUID 键 | "diskuuid"（StorageManager.java:33,45 用 Constants.DISKUUID） | private static final String DISKUUID = "disk_id"（StorageManager.java:19） | **改回 "diskuuid"**，否则 1.2.1 存档读不到任何盘内容 |
| V4 | DataStorage 内部键 | 复用 DISKCellInventory 常量 = keys / amts / **ic**（DataStorage.java:29-46） | 私有常量 keys / amts / **item_count**（DataStorage.java:73-88） | ic 必须还原 |
| V5 | AEKey 反序列化 | AEKey.fromTagGeneric(CompoundTag) 单参 | fromTagGeneric(HolderLookup.Provider, CompoundTag) | 用单参；toTagGeneric() 也不带参 |
| V6 | SavedData#save | save(CompoundTag) | save(CompoundTag, HolderLookup.Provider) | 用 1.20.1 签名 |
| V7 | getAvailableStacks | void getAvailableStacks(KeyCounter out)（:265-270） | 同 | 无变化（不是 1.21 才有的差异） |
| V8 | 细胞 GUI handler | gui/cell/DISKItemCellGuiHandler.java（29 行），commonSetup 里 StorageCells.addCellGuiHandler(new DISKItemCellGuiHandler())（AE2Things.java:60-61） | **无**（AE2 19.2.18 jar 里根本没有 ICellGuiHandler / addCellGuiHandler，已 javap：StorageCells 只剩 addCellHandler/isCellHandled/getHandler/getCellInventory） | **必须恢复**，否则驱动/ME 箱子里的 DISK 右键打不开界面 |
| V9 | ICellHandler | isCell + getCellInventory（15.4.12 也只这两个） | 同 | 无差异 |
| V10 | StorageCell | getStatus/getIdleDrain/canFitInsideCell/persist；**没有** getClientStatus；getDescription 属 MEStorage | 同（19.2.18 javap 一致） | getClientStatus 是自有方法；getDescription 是 MEStorage 抽象方法实现 |
| V11 | ICellWorkbenchItem | 无 clone() | 无（我们的 clone 是**自己加的接口方法**） | 照搬我们的 IDISKCellItem.clone |
| V12 | 注册 API | ForgeRegistries.ITEMS 的 DeferredRegister + RegistryObject（AE2Things.java:35；AETItems.java） | DeferredRegister.Items + DeferredItem | 用 Forge 写法 |
| V13 | 事件/生命周期 | FMLJavaModLoadingContext.get().getModEventBus()、MinecraftForge.EVENT_BUS.addListener(Command::commandRegister)、DistExecutor.unsafeRunWhenOn(Dist.CLIENT,...)、forge 的 RegisterCommandsEvent / RegisterColorHandlersEvent.Item、**TickEvent 里刷静态**（AE2Things.java:42-56,85-89） | IEventBus 注入、ServerStartedEvent/ServerStoppedEvent、neoforge 的同名事件 | 用 Forge 事件；但**不要**回退 tick 刷静态（见 F15） |
| V14 | CellConfig | CellConfig.create(getKeyType().filter(), is)（DISKDrive.java:71） | CellConfig.create(Set.of(getKeyType()), is)（DISKDrive.java:76） | 用 15.4.12 签名 |
| V15 | 驱动槽模型 | models/model/drive/cells/disk_1k.json：parent ae2:block/drive/drive_cell，textures.cell = ae2things:model/drive/cells/disk_1k | parent ae2:block/drive/cells/1k_item_cell + 共享纹理 ae2things:block/drive/drive_cells | 回退 1.20.1 的 parent + 每尺寸 PNG + assets/minecraft/atlases/blocks.json（见 A.4） |
| V16 | 配方 JSON | 1.20.1："result":{"item":...}，路径 data/ae2things/recipes/cells/*.json | 1.21：result:{count,id} + category，路径 data/ae2things/recipe/*（datagen 产出） | 回退 1.20.1 格式与路径 |
| V17 | 标签 | forge:dusts/redstone、minecraft:netherite_ingot、minecraft:amethyst_shard | c:dusts/redstone、c:ingots/netherite、c:gems/amethyst | 回退 forge 标签 + 裸物品（1.20.1 无 c: 通用标签保证） |
| V18 | 配方文件名 | disk_drive_1k_storage.json | disk_drive_1k_with_housing.json | 回退文件名（配方 id 变了会影响数据包覆盖与 FTB 任务引用） |
| V19 | 数据生成 | 无 datagen（runData --existing src/main/resources） | data/AE2ThingsDataGenerator + CraftingRecipeProvider，产物进 src/generated/resources | 1.20.1 可沿用 datagen，但必须产出 1.20.1 格式与文件名；否则手写 JSON |
| V20 | mixin 配置 | 含 "refmap":"mixins.ae2things.refmap.json"，compatibilityLevel:"JAVA_8" | 无 refmap，JAVA_21 | 1.20.1 Forge 需要 **refmap**（SRG 重映射）+ JAVA_17 |
| V21 | pack.mcmeta | pack_format: 9（1.20.1 应为 15） | 也写 9（1.21 应为 34） | 两侧都是错值；对 mod 资源无功能影响（只影响资源包兼容判定），可顺手改 15 |

### A.4 [D] 我们刻意的改动 / 元数据 / i18n / 资源

| # | 位置 | 上游 1.20.1 | 我们 1.21.1 | 备注 |
|---|---|---|---|---|
| D1 | 元数据 | META-INF/mods.toml：modLoader="javafml"、loaderVersion="[40,)"、version="${version}"、displayURL=CurseForge、authors 两人、依赖 forge [43.1.24,) / minecraft [1.20.1,) / ae2 [15.0.0, 16.0.0) | neoforge.mods.toml：loaderVersion="[2,)"、version="${file.jarVersion}"、displayURL=GitHub Reforged、authors 加 MerlinKitsune、[[mixins]] config=、依赖 neoforge [21.1.248,) / minecraft [1.21.1,) / ae2 [19.2.18, 20.0.0) | 1.20.1 写成 mods.toml 形状：loaderVersion [47,)、version ${file.jarVersion}、ae2 [15.4.12, 16.0.0) |
| D2 | 物品描述文案 | 硬编码英文 "Deep Item Storage disK - Storage for dummies"（DISKDrive.java:145-149）、"Disk UUID: "（DISKCellHandler.java:31-55） | Component.translatable("text.ae2things.disk_drive_description") / text.ae2things.disk_uuid，5 个 lang 各 +2 键（DISKDrive.java:171；DISKCellHandler.java:40） | 移植建议**保留我们的可翻译实现**（纯改进，不破存档） |
| D3 | 命令帮助文本 | 硬编码英文（Command.java:104-111） | 同样硬编码，**刻意保留**（Command.java:49-58） | AE2MEGAThings 用 @ModifyConstant(method="lambda$help$3", ordinal=0) 改这个字面量，翻译会输出原始键 |
| D4 | 模型/纹理 | textures/model/drive/cells/disk_{1k,4k,16k,64k,256k}.png + assets/minecraft/atlases/blocks.json（5 条 single 源） | textures/block/drive/drive_cells.png（单张共享）+ 无 atlases json | 移植必须取回上游那套（V15） |
| D5 | lang 文件 | en_us / pt_br / ru_ru / uk_ua / zh_cn（5 个） | 同 5 个 + 2 个新键 | 键集只差我们新增的 2 个（已 diff 核实） |
| D6 | 许可/署名 | license="MIT"、LICENSE、authors 两人 | MIT 保留 + credits 注明 fork | 移植沿用 |

---

## 3. Part B — 上游 1.20.1 对抗性审计（逐条）

严重度：**S1** 丢数据/复制/存档崩溃 · **S2** 静默数据错乱/客户端可见错误 · **S3** 性能 · **S4** 低危/潜在。
每条给：上游证据 → 机制 → 「我们 1.21.1 修了吗」→ 「1.20.1 移植要做什么」。

### F1 — 创意中键克隆让两只盘**共用同一个 DataStorage 引用**（S1，可复制可丢数据）
**上游证据**：mixin/CursedInternalSlotMixin.java:32-48
@Inject(method="doClick", at=@At(INVOKE, target="...ItemStack.copyWithCount (I)..."), slice=@Slice(from=@At(INVOKE, target="...Slot.hasItem()Z", ordinal=1)), cancellable=true)；体内：DataStorage storage = AE2Things.STORAGE_INSTANCE.getOrCreateDisk(i.getItem().getOrCreateTag().getUUID("diskuuid")); → putUUID("diskuuid", UUID.randomUUID()) → putLong("ic", storage.itemCount) → **AE2Things.STORAGE_INSTANCE.updateDisk(新UUID, storage);** → setCarried(newStack) → ci.cancel()。
**机制**：getOrCreateDisk() 返回 map 里那个**可变实例**；updateDisk(新UUID, storage) 把同一实例挂到第二个 UUID 下。此后两只盘的 DISKCellInventory 各自持**独立** storedAmounts map（:48-53），但 persist() 用自己那份 map **覆盖**共享实例的 stackKeys/stackAmounts/itemCount（:183-187；StorageManager.modifyDisk:72-81）⇒ 最后落盘的盘抹掉另一只盘的全部内容。
可复现：A 装石头×100 → 中键克隆得 B（共享）→ 往 A 塞泥土 → 从 B 抽石头（B 的 map 仍 {石头:100}，抽完 persist 把共享列表重写成"空"）⇒ **A 里刚塞的泥土消失**；双向抽取就是**复制**。
另：doClick 在**客户端**也执行（1.20.1 MultiPlayerGameMode.handleInventoryMouseClick 在发包前先调 AbstractContainerMenu.clicked，调用点偏移 119，已字节码核实），所以 MP 里客户端会改客户端 dummy 静态 manager（无害但脏），SP 里是**渲染线程改服务端正在用的 HashMap**。
**我们 1.21.1**：已修 —— mixin 体改成 diskCellItem.clone(stack)（mixin:28-37），DISKDrive.clone 给新 id + DataStorage.copy()（DISKDrive.java:80-104），不碰 manager。
**移植动作**：**原样搬我们的 mixin 体与 clone()，绝不搬上游那份**。1.20.1 版 clone() 要写 NBT：新 diskuuid、ic = storage.itemCount，用 stack.copy() 避免污染源 stack。

### F2 — /ae2things spawnDrive <UUID> 允许把**任何** UUID 再实体化（S1，复制）
**上游证据**：command/Command.java:63-84：if (!AE2Things.STORAGE_INSTANCE.hasUUID(uuid)) { error; return; } 然后构造 DISK_DRIVE_256K，NBT {diskuuid: uuid, ic: storage.itemCount}，player.addItem(...)。
**机制**：没有任何「该 UUID 是否已被某只实体盘持有」的检查。对一只**在线**磁盘执行该命令就得到第二只盘，命中与 F1 **完全相同**的别名-覆盖/双向复制路径。这是"官方支持"的复制入口，不需要作弊器。
**我们 1.21.1**：**没修**（Command.java 只用 currentStorageManager() 判空，逻辑同上游，仅换 component）。
**移植动作**：至少（a）在 spawnDrive 里拒绝「UUID 已挂载/正在使用」，（b）根治靠把 persist 从「整体覆盖」改成「写入时合并」或给每条记录加写序列号（发现别人在我之后写过就丢弃本地 map 重载）。**必须在文档里保留为已知缺陷，别当成已修。**

### F3 — stackKeys / stackAmounts 长度不一致时**静默永久截断尾部**（S1）
**上游证据**：DISKCellInventory.java:281-299 —— if (amounts.length != tags.size()) AELog.warn(...) 然后 **for (int i = 0; i < amounts.length; i++)** 里 tags.getCompound(i)。
**已核实的 1.20.1 行为**：ListTag.getCompound(int) 有边界检查（字节码 iflt / if_icmpge → new CompoundTag()），**越界不抛异常，返回空 tag**；ListTag.get(int) 才是裸 list.get(i)（会抛）。
**机制**：越界 → AEKey.fromTagGeneric(空tag) 返回 null（并 warn Cannot deserialize generic key from %s because key '#c' is missing.）→ corruptedTag=true → saveChanges() → persist() 只写回**抢救下来的那些对**（:167-187）⇒ 尾部条目**从此消失**，玩家只看到一条 warn。若 amounts 比 tags 短，多出来的 tag 被忽略并无条件重写。
**我们 1.21.1**：entries = Math.min(amounts.length, tags.size())（:310）+ 注释「超出较短列表的部分不可读，必须丢弃而不是崩或静默保留」，然后 invalidateKeyIndex(); saveChanges()（:327-331）⇒ 不越界、索引自洽，但**语义仍是丢弃尾部**。
**移植动作**：搬我们的写法，并加两件事：(1) warn 带被丢弃条数；(2) 丢弃时别自动 persist 覆盖原始数据（标记只读 / 保留原 tag 供人工抢救），至少让管理员在日志里看得见「丢了 N 条」。

### F4 — 空盘分支 return 前没置 isPersisted = true（S3/S2）
**上游证据**：DISKCellInventory.java:157-165。
**机制**：盘被抽空后 isPersisted 永远 false ⇒ 之后每次被 AE2 触碰（AppEngCellInventory.persist(slot)）都重跑一次分支；一旦 hasDiskUUID 已 false 就是空转，但若期间又有路径重设 UUID，会反复 removeDisk + initData()。
**我们 1.21.1**：已修（:170-179 置 true 后 return）。
**移植动作**：搬我们的。

### F5 — **只读路径**创建磁盘条目并 setDirty；StorageManager 构造即脏（S3，存档膨胀）
**上游证据**：
- getDiskStorage()（:83-88）= getStorageInstance().getOrCreateDisk(getDiskUUID())；getOrCreateDisk（StorageManager.java:65-70）缺失时 updateDisk(uuid, new DataStorage()) → setDirty()（:51-54）。
- getDiskStorage() 被 initData()（:90-99）调用，而 initData 在**构造函数**（:55-64）里就跑 ⇒ **每 new 一个 DISKCellInventory 就可能插入幽灵条目并把存档标脏**。创建点在 AE2 侧极多：DriveBlockEntity:361、ChestBlockEntity:193、IOPortBlockEntity:285、PortableCellMenuHost:63、CellWorkbenchMenu:210、CellPartitionSlot:69、BasicStorageCell:165/201、ColorApplicatorItem（5 处）、MatterCannonItem:140、BasicCellInventory:401 …… 以及**我们自己的** tooltip/渲染路径。
- StorageManager 两个构造器都 setDirty()（:16-24，**包括 readNbt 那个**）⇒ 世界一加载就把 disk_manager 标脏 ⇒ 每次自动存档全量重写整个列表（含所有 key 的 NBT）。
**我们 1.21.1**：**部分**。getDiskStorage()（:89-95）在 getDiskUUID()!=null && storageManager!=null 时**仍然** getOrCreateDisk；StorageManager 构造器仍然 setDirty（:24-32）。⇒ **未修**。
**移植动作**：加 **@Nullable getDisk(UUID)（只读、不插入、不 setDirty）**，getDiskStorage 用它；只有真正写（modifyDisk/updateDisk/insert 里那次 getOrCreateDisk）才创建。readNbt 构造器去掉 setDirty（加载不算修改）。initData() 里 getDiskStorage 被调两次（:92,94），合成一次。

### F6 — 客户端渲染路径把**客户端 dummy manager** 当真用（S2/S3/线程安全）
**上游证据**：DISKDrive.getColor（:151-161）→ DISKCellHandler.getCellInventory(stack, null) → createInventory(stack, null) → 构造 → initData → getDiskStorage → AE2Things.STORAGE_INSTANCE。该静态在类初始化时就是 new StorageManager()（AE2Things.java:33），**只有** worldTick（:85-89，side.isServer() 才跑）会换成真的。
**机制**：MP 客户端每渲染一只带 UUID 的盘就往这个本地 HashMap 插一个空 DataStorage（按 UUID 分），永不清理 ⇒ 见过的盘越多客户端内存越大；每次还 setDirty。SP 里 getColor 在**渲染线程**跑、worldTick 在服务端线程跑，两者操作同一个非并发 HashMap 且无同步 ⇒ 理论上的结构破坏。
另：hasDiskUUID() 用 i.getOrCreateTag()（:218-220），即**只读查询里创建物品 NBT**，渲染路径每次调用都可能碰物品 NBT。
**我们 1.21.1**：已修（getColor 传 null manager；hasDiskUUID 用 i.has(component)）。
**移植动作**：保持我们的做法；hasDiskUUID 改成不创建 NBT（i.hasTag() && i.getTag().contains("diskuuid")，**不要 getOrCreateTag**）。

### F7 — LED 状态只跟"上次 persist 写进物品的 ic"同步，且 persist 无条件重写 ic（S2）
**上游证据**：getClientStatus()（:136-144）→ getNbtItemCount()（:410-415）= hasDiskUUID() ? i.getTag().getLong("ic") : 0；persist() 里 i.getOrCreateTag().putLong("ic", itemCount)（:192）无条件写。
**机制**：内容变化后 ic 只在 persist 落地那刻才更新；而 AE2 DriveBlockEntity.updateClientSideState() 用 **cellItem != clientSideCellItems[x]（引用比较）** 判断是否 markForUpdate —— AppEngInternalInventory.getStackInSlot 返回同一个 ItemStack 实例，NBT 变了引用没变 ⇒ **不同步给客户端**。于是驱动上的 LED/计数可能停在旧值（直到物品被换下/区块重发）。同时无条件 putLong 在 1.20.1 没有 component 去重，物品 NBT 每次都被改写。
**我们 1.21.1**：只修了「无意义写入」（:212-215），**LED 同步问题没修**（同一套 AE2 引用比较逻辑）。
**移植动作**：保留守卫式写入（值不变不写）；LED 同步属 AE2 核心行为，建议**不改**，但记一条「盘中内容变化后驱动 LED 可能迟滞」，避免被当成移植 bug 反复排查。

### F8 — 每次内容变化都 O(n) 重算 storedItemCount（S3）
**上游证据**：saveChanges()（:306-321）storedItems = storedAmounts.size(); for (var a : storedAmounts.values()) storedItemCount += a; —— 每次 insert/extract 全表遍历（n = 键种类数，DISK 无类型上限，256k 盘最多 256000 种）。
**我们 1.21.1**：saveChanges(long delta)（:348-353）只加 delta。
**移植动作**：搬我们的。

### F9 — **一次插入 = 全盘 key 重新编码**（S3，最主要性能问题）
**上游证据**：persist()（:167-181）对 storedAmounts 里每个 entry 调 entry.getKey().toTagGeneric() 塞进 ListTag。
触发链（AE2 15.4.12，全部已核对）：
- AppEngCellInventory.getStackInSlot / insertItem / setItemDirect / setHandler **每次**先 persist(slot)（AppEngCellInventory.java:34-76）⇒ 该 cell 若 isPersisted==false 就全量重编码；
- DriveBlockEntity.updateStateForSlot 为每槽建 new DriveWatcher(cell, () -> blinkCell(slot))（:355-372），内容变化 → blinkCell（:410-412）→ updateVisualStateIfNeeded（:274）→ updateClientSideState（:281-300）→ getCellItem(x) = inv.getStackInSlot(x) ⇒ **再触发一次 persist(slot)**；
- StorageService.onServerEndTick → updateCachedStacks → storage.getAvailableStacks(...)（StorageService.java:88-101，有兴趣订阅者时每 tick 跑）→ NetworkStorage.getAvailableStacks（:204-218）→ 各挂载 cell 的 getAvailableStacks（我们/上游实现都只遍历 map，便宜）。
⇒ 导入总线每 tick 往一只 2 万 key 的盘插一个物品 = **每 tick 2 万次 toTagGeneric()（含 DFU codec、组件补丁序列化）**。现场 profile 实测 DISKCellInventory.persist 占 911,120 ms 窗口的 **64,908 ms = 6.9%**（AGENTS.md §4 引用 perf-2026-10-06/FIX-PLANS-2026-10-06.md §10）。
**我们 1.21.1**：P1/P2 已修。
**移植动作**：搬 P1/P2/P5。这是移植首要目标；单测应覆盖「同 key 集合时 tryUpdateAmounts 返回 >=0 且不重编码」。

### F10 — storedItems = (short) storedAmounts.size() 截断（S2，可达）
**上游证据**：persist :189（字段声明是 int，:49）；saveChanges() :308 却直接赋 size() —— 两处语义不一致。
**机制**：DISK 无类型上限且 1 物品 = 1 字节 ⇒ 256k 盘可容纳 > 32767 种不同物品；超过后 persist 把 storedItems 写成负数，getStoredItemTypes()（:421-423）返回负值 ⇒ AE2 tooltip/GUI 类型数错乱。getStatus()/getFreeBytes() 用 storedItemCount，所以**不导致**容量计算错。
**我们 1.21.1**：已修（persist :209 直接 storedAmounts.size()，无强转）。
**移植动作**：搬我们的；顺便把 storedItems 声明为 long 或至少别 cast。

### F11 — StorageManager.readNbt 允许 null UUID 键 ⇒ 世界存档 NPE（S1，存档卡死）
**上游证据**：readNbt（:41-49）disks.put(disk.getUUID("diskuuid"), ...)；CompoundTag.getUUID 在键缺失/非法时返回 **null**（1.20.1），HashMap 接受 null 键；随后 save()（:27-39）disk.putUUID(Constants.DISKUUID, entry.getKey()) → NbtUtils.createUUID(null) → **NPE**（已核字节码：putUUID 直接取 UUID 位）⇒ 保存世界时崩，且每次保存都崩 ⇒ **存档无法保存**。
**可达性**：需要 disk_manager.dat 里出现没有合法 diskuuid 的条目（外部损坏、手改，或任何把 null 塞进去的新代码路径）。上游 persist 流程走不到（空盘分支先 removeDisk），但这是**结构性隐患**：任何一次 updateDisk(null, ...) 就会炸存档。
**我们 1.21.1**：**没修**（StorageManager.java:49-57 同样的 disks.put(disk.getUUID(DISKUUID), ...)；:35-47 的 save 同样 putUUID）。
**移植动作**：readNbt 判 null 跳过并 AELog.error（宁可丢一条已损坏的盘记录，也不要卡死存档）；save 对 null 键防御性跳过。

### F12 — IDISKCellItem.isBlackListed 无检查强转 + 未判空（S2，NPE）
**上游证据**：:40-46 ((AEItemKey) requestedAddition).getItem() instanceof IBasicCellItem 然后 BasicCellHandler.INSTANCE.getCellInventory(...).getUsedBytes() > 0。
**机制**：getCellInventory 返回 null 时 NPE；强转在 keyType.contains() 之后通常安全（AEKeyType.items().filter() 只放 AEItemKey），但 isBlackListed 也可能被配置界面路径调用。这是"防嵌套存储元件"的黑名单判断，任何异常都会让整次 insert 失败。
**我们 1.21.1**：已修（IDISKCellItem.java:40-47）。
**移植动作**：搬我们的（15.4.12 里 BasicCellHandler 在 appeng.me.cells.BasicCellHandler）。

### F13 — UUID 与磁盘条目在 **SIMULATE** 模式下就被创建（S2，幽灵条目 + 双端 UUID 不一致）
**上游证据**：insert :354-358（if (!hasDiskUUID()) { putUUID(random); getOrCreateDisk(...); loadCellItems(); }）在 :361-365 钳制与 :367 的 mode == MODULATE 判断**之前**。
**机制**：AE2 在网络存储/导入总线上大量用 Actionable.SIMULATE 探容量（先 SIMULATE 找目标再 MODULATE）。于是**一次"只是问问能不能放"的探测就**：给物品 NBT 写一个 diskuuid、往 StorageManager 插一个条目并 setDirty。菜单位置的模拟插入在**客户端也跑**，客户端随机 UUID 与服务端不同 ⇒ 两侧短暂不一致（同步后以服务端为准，通常自愈）。另：钳到 0 时上游仍 return 0，但 **UUID 已写进去**，产生"有 UUID、无内容、ic 缺失"的盘（空盘 persist 会自愈）。
**我们 1.21.1**：**没修**（DISKCellInventory.java:396-400 同样在 MODULATE 判断 :414 之前 i.set(DATA_DISK_ID, UUID.randomUUID()) + getOrCreateDisk）。
**移植动作**：把「创建 UUID + getOrCreateDisk + loadCellItems」整块移进 if (mode == Actionable.MODULATE)（SIMULATE 时无 UUID ⇒ getCellItems 返回空 map、getLong=0、remaining=total，语义完全正确）。**这条我们 1.21.1 也该同步修。**

### F14 — 拆解驱动（housing 回收）不 removeDisk（S3，存档永久膨胀）
**上游证据**：DISKDrive.use/onItemUseFirst → disassembleDrive（:106-135）：只在「盘为空」（inv.getAvailableStacks().isEmpty()）时允许拆解，随后 setItem(EMPTY) 并掉落 housing/升级卡/核心 —— **从不调用 StorageManager.removeDisk(uuid)**。
**机制**：空盘 persist 会 removeDisk，但拆解路径操作的是**玩家手上的物品**：若该盘"刚抽空、还没被任何路径 persist"就拿出来拆，则 storedItemCount==0 且条目仍在 ⇒ **条目永久残留**（含它最后一次的完整 key 列表）。反复拆装 ⇒ 存档里堆死人条目。
**我们 1.21.1**：**没修**（DISKDrive.java:130-159 同逻辑）。
**移植动作**：拆解时若有 UUID 就 storageManager.removeDisk(uuid)（盘要销毁，删条目零风险），并清掉物品上的 UUID tag 再 setItem(EMPTY)。

### F15 — AE2Things.worldTick 每 tick × 每维度重设静态 manager；停机不清（S3/S2）
**上游证据**：AE2Things.java:85-89 if (event.phase == TickEvent.Phase.START && event.side.isServer()) STORAGE_INSTANCE = StorageManager.getInstance(event.level.getServer());；getInstance（StorageManager.java:83-87）每次 server.getLevel(OVERWORLD).getDataStorage().computeIfAbsent(...)。
**机制**：(1) 20×N/s 的无效工作；(2) 停机/换存档后静态**仍指向旧 server 的 SavedData**，在下一个 tick 之前任何 DISKCellInventory 构造都会读写**上一个世界**的存储（单人游戏切存档时是真实的跨存档污染通道）。
**我们 1.21.1**：已修（onServerStarted/onServerStopped，只在同一 server 实例上清空，AE2Things.java:107-117）。
**移植动作**：用 Forge 的 ServerStartedEvent/ServerStoppedEvent 做同样的事，不要搬 tick 版。

### F16 — getDescription() 返回 null（S4，潜在 NPE）
**上游证据**：DISKCellInventory.java:197-200 返回 null，而 MEStorage 声明 Component getDescription()（15.4.12 MEStorage.java:101；19.2.18 亦然）。
**机制**：任何对「挂载中的 cell」调 getDescription().getString() 的 AE2 UI 都会 NPE。15.4.12 里没找到把挂载 cell 的 description 送去渲染的调用点（只找到 CompositeStorage / DelegatingMEInventory / ExternalStorageFacade / NetworkStorage / NullInventory 自己的实现）⇒ 现实不可达，但是定时炸弹。
**我们 1.21.1**：同样返回 null（:220-223）。
**移植动作**：低优先；建议返回 Component.translatable("text.ae2things.disk_description") 或 AEKeyType.items().getDescription()。

### F17 — StorageManager.disks 是非同步 HashMap，历史上被两个线程碰（S4）
**上游证据**：StorageManager.java:11-12 private final Map<UUID, DataStorage> disks;（HashMap）。
**机制**：F6 的客户端渲染路径 + worldTick 的服务端写入，在单人游戏里就是跨线程。
**我们 1.21.1**：类型没变，但客户端路径已移除 ⇒ 实际不再跨线程（除非第三方 mod 在别的线程构造 cell inventory）。
**移植动作**：保持 HashMap，但**严禁**从客户端代码调用带 manager 的 createInventory；若要更稳可换 ConcurrentHashMap（开销可忽略）。

### F18 — 嵌套存储元件检查会为"在别处的盘"造幽灵条目（S3，我们自己的 wart）
**上游证据**：insert :346-352 createInventory(itemKey.toStack(), null) + isCellEmpty(...)。
**机制**：为判断"要塞进去的东西本身是不是一只有内容的盘"，会构造**临时** DISKCellInventory 并读内容；构造路径 initData → getDiskStorage → getOrCreateDisk ⇒ 为那只盘的 UUID 在服务端 StorageManager 插条目（比如存档损坏后条目丢失）并 setDirty。上游在这里传 null（走静态 = 服务端 manager，同样命中）；**我们 1.21.1 显式把 storageManager 传了进去（:390），命中面一样**。
**移植动作**：不要简单改成 null —— 没有 manager 时 loadCellItems 直接 return，会把"有内容的盘"误判为空，**反而变成真实的盘套盘复制**。正确做法：给 DISKCellInventory 加 readOnly 标志，或让这段改用只读 storageManager.getDisk(uuid) 判断。⚠️ 同样是我们 1.21.1 的待办。

### F19 — 跨维度查找：**不是 bug**（已核实，别"修"）
ServerLevel.getDataStorage() → getChunkSource().getDataStorage() → ServerChunkCache 自己的 DimensionDataStorage ⇒ 1.20.1 的 SavedData **确实每维度一份**；上游硬编码 server.getLevel(ServerLevel.OVERWORLD).getDataStorage() 正是拿"唯一那份、与维度无关"的存取点 ⇒ **主世界 = 唯一真源，任何维度的盘都能读到**。移植**必须保持**这种写法，**不要**改成 level.getDataStorage()（那会让下界/末地的盘读不到主世界的表，表现为"盘里的东西凭空消失"）。

### F20 — extract 不做 keyType.contains 判断（S4，无害）
:376-400 与 insert 不对称，但 getCellItems().getLong(what) 对不存在的 key 返回 0 ⇒ 返回 0，无副作用。移植可保持。

---

## 4. Part C — 我们 1.21.1 **仍然带着**的上游缺陷（移植前先自己修）

| # | 缺陷 | 我们的证据 | 建议 |
|---|---|---|---|
| C1 = F2 | spawnDrive 可实体化在线盘 UUID ⇒ 复制/覆盖 | Command.java（同上游逻辑，仅换 component） | 见 F2；至少加警示文案 + 文档记录；根治靠 persist 合并语义 |
| C2 = F5 | 只读路径 getOrCreateDisk + setDirty；StorageManager 构造即脏 | DISKCellInventory.java:89-95；StorageManager.java:24-32 | 加只读 getDisk(UUID)；构造器去 setDirty |
| C3 = F11 | null UUID 键 ⇒ 存档 save NPE | StorageManager.java:49-57 / 35-47 | 两侧都判 null |
| C4 = F13 | SIMULATE 里创建 UUID/条目 | DISKCellInventory.java:396-400 | 移进 MODULATE 分支 |
| C5 = F14 | 拆解不 removeDisk | DISKDrive.java:130-159 | 拆解时删条目 |
| C6 = F3 | 长度不一致时丢弃尾部（有 warn，但等于丢数据） | DISKCellInventory.java:303-331 | 保留 min() 写法 + 未读条目计数日志；考虑不自动覆盖 |
| C7 = F16 | getDescription 返回 null | DISKCellInventory.java:220-223 | 低优先 |
| C8 = F18 | 嵌套检查用真 manager ⇒ 幽灵条目 | DISKCellInventory.java:390 | 引入 readOnly / 只读 getter |
| C9 | **storageManager == null 时 MODULATE 插入会静默丢数据**：insert 只在 storageManager != null 时创建 UUID（:396），于是 map 里放了内容、markChanged() → persist() 见 manager 为 null 直接 isPersisted = true; return;（:164-168）⇒ 内容只活在这个实例的内存里，实例一死全丢，物品上也没有 UUID 让下一次实例恢复 | DISKCellInventory.java:164-168, 396-401；markChanged :355-363 | manager==null 时应**拒绝** MODULATE 插入（return 0），只允许 SIMULATE/读取。可达性 **UNKNOWN**（正常流程里管理器应在 ServerStartedEvent 后一直非 null；但客户端菜单预测插入、世界加载早期构造的 cell 都可能落到这里） |
| C10 | clone() 在 manager==null（客户端 MP）时 remove(DATA_DISK_ITEM_COUNT)，克隆出的物品暂时没有计数组件 | DISKDrive.java:80-104 | 1.20.1 同理：客户端克隆只能拿"物品上已有的 ic"，不要试图读服务端存储 |

> C1 / C9 属于**必答项**：如果 1.20.1 移植只是「把我们的 101 行 diff 搬过去」，这两条会一起搬过去，成为新存档的隐患。

---

## 5. Part D — 1.20.1 移植清单（本审计直接推出的硬要求）

**数据格式（不可协商）**
1. 物品 NBT 键：diskuuid（UUID）、ic（long）、FuzzyMode（String）；磁盘表条目键：diskuuid / diskdata；DataStorage 内部键：keys / amts / ic；表名 disk_manager / 列表名 disklist。
2. 必须做**字节级黄金回归**：用 1.2.1 生成的 disk_manager.dat + 磁盘物品 NBT 作样本，新旧实现 toNbt/fromNbt 互读一致（空盘、单 key、多 key、含组件补丁的物品、流体 key 各一组）。
3. DataStorage.EMPTY 语义保留（我们加的 updateDisk 抛错与 tryUpdateAmounts 短路依赖它）。

**功能（1.20.1 特有）**
4. 恢复 gui/cell/DISKItemCellGuiHandler（15.4.12 有 ICellGuiHandler / addCellGuiHandler，19.2.18 已删）。
5. 恢复上游资源集：模型 parent ae2:block/drive/drive_cell + 每尺寸纹理 + assets/minecraft/atlases/blocks.json；StorageCellModels.registerModel 仍在 commonSetup（15.4.12 StorageCellModels.java:71）。
6. 配方用 1.20.1 格式与文件名（recipes/cells/disk_drive_1k.json、disk_drive_1k_storage.json、disk_housing.json），标签 forge:dusts/redstone + 裸 minecraft:netherite_ingot / amethyst_shard。
7. mods.toml：modLoader="javafml"、loaderVersion="[47,)"、ae2 [15.4.12, 16.0.0) ordering AFTER、[[mixins]] config=、version="${file.jarVersion}"。
8. mixin json 需 "refmap":"mixins.ae2things.refmap.json" + "compatibilityLevel":"JAVA_17"。
9. 事件面全部换 Forge：FMLJavaModLoadingContext、forge 的 RegisterCommandsEvent、forge.client 的 RegisterColorHandlersEvent.Item、server 生命周期事件（Started/Stopped）、DistExecutor.unsafeRunWhenOn。
10. getColor 返回值**去掉 alpha 字节**（1.20.1 item tint 是 24 位 RGB）。

**性能（移植的主要目的）**
11. 搬 P1/P2/P4/P5/P7：keyTags 缓存、tryUpdateAmounts、saveChanges(delta)、ic 变化才写、modifyDisk 失效索引。
12. 搬 P6/F15：事件驱动 manager，不要 tick 静态；DISKCellInventory 显式持 @Nullable manager。
13. **额外修 C2**：只读 getDisk(UUID)；StorageManager 加载时不标脏 —— 否则「每次自动存档重写整张表」会吃掉 11 的收益。

**审计要求**
14. 修 C1/C4/C5/C8/C9，不要把上游缺陷一起搬进新存档。
15. 加回归断言：insert(SIMULATE) **不得**改变物品 NBT / StorageManager（大小与 dirty 状态）。

---

## 6. Part E — UNKNOWN / 无法从源码判定

| # | 问题 | 为什么没有结论 |
|---|---|---|
| U1 | Forge 47.1.x 是否提供 ServerStartedEvent / ServerStoppedEvent 这两个类名（我们 1.21.1 用的是 NeoForge 的） | 本次只核了 1.20.1 的 MC/Forge merged jar 里的 MC 类。等价物大概率是 net.minecraftforge.event.server.ServerStartedEvent / ServerStoppedEvent（Forge 的 event.server 包一直有），移植时用 javap 核一下即可 |
| U2 | isStorageCell(stack) / storableInStorageCell() 的完整语义版图（上游 IDISKCellItem 默认 false ⇒ isStorageCell(AEItemKey) 返回 true ⇒ 阻止非空盘套盘） | 只看了 AE2Things 侧；AE2 侧所有 storableInStorageCell 实现没穷举 |
| U3 | F7 的 LED 迟滞在实际游玩中的可见程度 | 需要实机；引用比较已核实，但 AE2 可能还有别的 markForUpdate 触发点（IStorageProvider.requestUpdate、onChangeInventory 等） |
| U4 | C9（manager==null 时插入）在真实流程中的可达性 | 取决于世界加载 / 菜单预测插入时序；静态阅读无法证明可达或不可达 |
| U5 | pack_format: 9 对 mod 自带资源包的实际影响 | MC 只在资源包列表做兼容判定，mod 资源不经过该判定；未实测 |
| U6 | 上游 1.2.1 是否确实能在 Forge 47.0.5 上跑（15.0.3-alpha 是否只是能编过的最低版本） | 需要实机；发行 jar AE2-Things-1.2.1.jar 在 _ref/libs，但在 15.4.12 上是否零调整见第 7 节 |

---

## 7. Part F — AE2 15.4.12 相对上游构建目标（15.0.3-alpha）的 API 差异（逐条已验证）

| API | 15.4.12 结论 | 对移植的影响 |
|---|---|---|
| AEKey.fromTagGeneric(CompoundTag) | **单参静态**（AEKey.java:43），缺 #c/channel 时 warn 并返回 null | 直接用，不需要 registries |
| AEKey.toTagGeneric() | public final，**无参**（:105） | 直接用 |
| StorageCell | getStatus/getIdleDrain/canFitInsideCell/persist（无 getClientStatus） | getClientStatus 保持普通方法 |
| MEStorage.getDescription() | 抽象方法（MEStorage.java:101） | DISKCellInventory 必须实现（返回 null = 上游行为，见 F16） |
| ICellHandler | 仅 isCell + getCellInventory | 无 addCellInformationToTooltip；tooltip 继续走 DISKDrive.appendHoverText |
| ICellGuiHandler | **存在**：isSpecializedFor + openChestGui(Player, IChestOrDrive, ICellHandler, ItemStack) | 恢复 V8 |
| StorageCells.addCellGuiHandler | **存在**（:75） | 同上 |
| StorageCellModels.registerModel(ItemLike, ResourceLocation) | **存在**（:71，synchronized） | 保持 commonSetup 里注册 |
| CellState | EMPTY 0x00FF00 / NOT_EMPTY 0x00AAFF / TYPES_FULL 0xFFAA00 / FULL 0xFF0000，getStateColor() :67 | getColor 里不 OR alpha（1.20.1） |
| getAvailableStacks(KeyCounter out) | 与上游一致 | 无变化 |
| ICellWorkbenchItem | 无 clone() | 我们的 clone 是自有接口方法，移植保留 |

---

## 8. 附：关键调用链（便于复现）

1. **驱动槽内容变化 → 全盘重编码**：DriveBlockEntity.updateStateForSlot（:355-372，每槽 new DriveWatcher(cell, () -> blinkCell(slot))）→ blinkCell（:410-412）→ updateVisualStateIfNeeded（:274）→ updateClientSideState（:281-300，getCellItem）→ AppEngCellInventory.getStackInSlot（:55-58，先 persist(slot)）→ DISKCellInventory.persist（:151-195）。
2. **网络存储缓存刷新**：StorageService.onServerEndTick（:88-96）→ updateCachedStacks（:97-101）→ NetworkStorage.getAvailableStacks（:204-218）→ 各挂载 cell 的 getAvailableStacks（DISKCellInventory:265-270）。
3. **创意中键克隆**：MultiPlayerGameMode.handleInventoryMouseClick（客户端，偏移 119 调 AbstractContainerMenu.clicked）→ AbstractContainerMenu.doClick（CLONE 分支：instabuild @1387、hasItem @1427、copyWithCount @1448、setCarried @1451）→ mixin → StorageManager.getOrCreateDisk。
4. **存档**：ServerLevel.getDataStorage()（每维度一份）→ 我们/上游都硬编码 getLevel(OVERWORLD) → StorageManager.save/readNbt（disklist 列表，每项 {diskuuid, diskdata}）。

---

*本文件只读上游与 AE2/MC 参照树；未修改任何参照树，未运行改变仓库状态的 git 命令。*
