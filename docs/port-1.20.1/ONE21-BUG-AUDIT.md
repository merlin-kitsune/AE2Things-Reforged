# ONE21-BUG-AUDIT — 1.21.1 主线（`src/`）是否带着 UPSTREAM-AUDIT Part C 那批缺陷

审计对象：**根目录 1.21.1 线**（`src/main/java/io/github/projectet/ae2things/`，已发布 1.4.3）。
对照物：1.20.1 移植线（`forge-1.20.1/src/main/java/io/github/projectet/ae2things/`，1.3.0）、MC 1.21.1 反编译源码、AE2 19.2.18 字节码。
审计方式：只读源码 + 字节码取证；**本审计未修改任何源码**。

## 0. 结论速览

| item | 1.21.1 verdict | needs fix? | evidence |
|---|---|---|---|
| C2 只读路径 `getOrCreateDisk` | **PRESENT** | **YES** | `src/main/java/io/github/projectet/ae2things/storage/DISKCellInventory.java:89-95`（`getDiskStorage()` 就是 `getOrCreateDisk`）；`src/main/java/io/github/projectet/ae2things/util/StorageManager.java` 全类无 `getDisk(UUID)` |
| C2b 构造即 `setDirty` | **PRESENT** | **YES** | `src/main/java/io/github/projectet/ae2things/util/StorageManager.java:24-27`、`:29-32`（两个构造器都 `setDirty()`）；`readNbt` 走的就是 `:29-32`（`StorageManager.java:56`） |
| C3 `readNbt` 无 null 检查 | **DIFFERENT** | **YES（修复形状要换；1.20.1 的 `== null` 判空是死代码）** | `src/main/java/io/github/projectet/ae2things/util/StorageManager.java:54` 仍无判空；1.21.1 的 `CompoundTag#getUUID` 缺键**抛 NPE** 而非返回 null（`net/minecraft/nbt/NbtUtils.java:120-133`，1.20.1 字节码同形）⇒ 失败模式由「save 崩」变成「load 静默清空整表」（`DimensionDataStorage.java:65-78`+`:43-52`）；正解用 `CompoundTag#hasUUID`（`:259-262`） |
| C4 SIMULATE 里铸 UUID | **PRESENT** | **YES（最高优先）** | `src/main/java/io/github/projectet/ae2things/storage/DISKCellInventory.java:396-400` 在 `:414` 的 `Actionable.MODULATE` 判断**之前** |
| C5 拆解不 `removeDisk` | **PRESENT（面已收窄）** | **YES（低成本，1.20.1 已修）** | `src/main/java/io/github/projectet/ae2things/item/DISKDrive.java:130-159` 拆解仍不 `removeDisk`；但「取空」这条主要来源已被 `src/main/java/io/github/projectet/ae2things/storage/DISKCellInventory.java:170-179` 的 `removeDisk` 兜住，残余只涉及 C2/C4 造出的空条目 |
| C9 `storageManager == null` 时 MODULATE 静默丢数据 | **PRESENT** | **YES（2 行）** | `DISKCellInventory.java:396`（只在 manager 非 null 时铸 UUID）+ `:414-417`（MODULATE 照样写内存）+ `:164-168`（persist 直接丢弃） |
| F2 `/recover` 不查在线占用 | **PRESENT** | **YES（设计级；1.20.1 也没修）** | `src/main/java/io/github/projectet/ae2things/command/Command.java:65-71` 只有 `hasUUID` |

**一句话**：7 条里 6 条在 1.21.1 上仍然需要处理（C2 / C2b / C4 / C5 / C9 / F2），其中 C5 的「空条目永久累积」已被本 fork 自己的 `persist()` 空盘分支堵掉大半、只剩拆解这一处；C3 形态变了——从「存档 save 崩溃」退化成更坏的「load 静默清空整张磁盘表」。没有一条是 ABSENT。

---

## 1. 方法与取证边界

- 1.21.1 源码：逐行读 `StorageManager.java`(119 行)、`DataStorage.java`(215 行)、`DISKCellInventory.java`(480 行)、`DISKDrive.java`(188 行)、`DISKCellHandler.java`(58 行)、`Command.java`(110 行)、`AE2Things.java`(132 行)、`CursedInternalSlotMixin.java`(41 行)。全文下方引用均有行号。
- MC 1.21.1 侧类来自本工程反编译产物：`C:/Users/xmace/.gradle/caches/ng_execute/cd3b954f8b80e4b261fade7160774bd2ec5a7eade7a6deb652bda72297b507c8/transformed/`（`CompoundTag` / `NbtUtils` / `UUIDUtil` / `DimensionDataStorage` / `SavedData`）。
- AE2 侧：`ae2-19.2.18.jar`（`C:/Users/xmace/.gradle/caches/modules-2/files-2.1/maven.modrinth/ae2/19.2.18/bcd04bc7f181b2ffa314625f284c8eca6fee7fe4/ae2-19.2.18.jar`）用 `javap -p -c` 读方法体，验证 SIMULATE 是否真的会打到本 mod 的 cell 上。**没有 AE2 源码可读**，只有字节码。
- 1.20.1 移植线只用于「修好之后长什么样」的比对，**判定永远针对 1.21.1**。
- 实机未跑（本审计是静态取证）：凡涉及「玩家操作序列是否真能走到」的结论都标了可达性等级，其中无法从源码判定的记入 §4 UNKNOWN。

---

## 2. 逐条判定

### C2 — 只读路径用 `getOrCreateDisk`：**PRESENT，需要修**

**证据（1.21.1）**

```java
// src/main/java/io/github/projectet/ae2things/storage/DISKCellInventory.java:89-95
private DataStorage getDiskStorage() {
    if (getDiskUUID() != null && storageManager != null) {
        return storageManager.getOrCreateDisk(getDiskUUID());
    } else {
        return DataStorage.EMPTY;
    }
}
```

```java
// src/main/java/io/github/projectet/ae2things/util/StorageManager.java:76-81
public DataStorage getOrCreateDisk(UUID uuid) {
    if (!this.disks.containsKey(uuid)) {
        this.updateDisk(uuid, new DataStorage());   // :59-65 里 put + setDirty()
    }
    return this.disks.get(uuid);
}
```

- `StorageManager` 的全部公开方法只有 `updateDisk / removeDisk / hasUUID / getOrCreateDisk / modifyDisk / getInstance / getRegistries`（`StorageManager.java:59-118`）——**不存在只读的 `getDisk(UUID)`**。
- `getDiskStorage()` 的调用点：构造器 `DISKCellInventory.java:67` → `initData()`（`:97-106`，且 `:99`、`:100` **各调一次**，共 2 次）；`loadCellItems()`（`:300`）；`persist()`（`:181`，写路径，合理）。
- 因此：只要「物品带 UUID、但表里没有该条目」，**任何一次 cell 实例化**（放进驱动、打开菜单、被网络缓存扫描）都会凭空建一条空 `DataStorage` 并 `setDirty()`。
- 附带浪费：`initData()` 为读两个字段调了 2 次 `getDiskStorage()`（`:99-100`），即 2 次 HashMap 查找/可能的创建。

**对比 1.20.1（已修）**：`forge-1.20.1/.../util/StorageManager.java:75-82` 有只读 `getDisk(UUID)`（缺失时返回 `DataStorage.EMPTY`，不插入、不置脏）；`forge-1.20.1/.../storage/DISKCellInventory.java:101-112` 的 `getDiskStorage()` 就是 `getDisk(...)`，javadoc 写明「Creating an entry is left to persist() and to modifying inserts」。

**风险与可达性**：存档膨胀 / 无谓置脏 —— 等级 **S3**，可达性 **中**。触发前提是「物品 UUID 有、表里没有」：
1. C3 触发后整表被清空，此后每只盘都会被重新建空条目（数据已丢，条目只是“墓碑”）；
2. F2 产出 / 手工复制出同 UUID 物品；
3. 手工编辑 NBT、或跨线/跨版本存档（1.21.1 条目键是 `disk_id`，1.20.1 线是 `diskuuid`）。
正常单机流程里条目一旦建立就不会再缺（`persist()` 空盘分支 `:170-179` 会把条目和组件一起删），所以**不是每 tick 都在写**，但它把「读操作会改存档」这个不变式彻底破坏，也让 C2b 的收益随时被抹掉。

**需要的修复形状**（与 1.20.1 同形）：
1. `StorageManager` 加 `public DataStorage getDisk(UUID)`：`var d = disks.get(uuid); return d != null ? d : DataStorage.EMPTY;`（不得插入、不得 `setDirty`）；
2. `getDiskStorage()` 改用它；`initData()` 只取一次引用（顺手修 `:99-100` 双调用）；
3. 建条目只允许发生在 `persist()`（`:181`/`:199`/`:201`）与 `insert()` 的 MODULATE 分支（见 C4）。

**验收断言**：`getDiskStorage()` 在条目缺失时**不得**改变 `StorageManager.disks` 大小与 `isDirty()`；用一个「UUID 存在、条目缺失」的假盘做 `createInventory` + `getAvailableStacks()`，前后 `disks.size()` 与 `isDirty()` 均不变。

---

### C2b — 构造即 `setDirty`（含 `readNbt` 工厂路径）：**PRESENT，需要修**

**证据（1.21.1）**

```java
// src/main/java/io/github/projectet/ae2things/util/StorageManager.java:24-32
public StorageManager() {
    this.disks = new HashMap<>();
    this.setDirty();
}

private StorageManager(Map<UUID, DataStorage> disks) {
    this.disks = disks;
    this.setDirty();
}
```

```java
// src/main/java/io/github/projectet/ae2things/util/StorageManager.java:49-57（readNbt 的收尾）
}
return new StorageManager(disks);   // :56 —— 走的是上面那个 private 构造器 ⇒ 读盘即脏
```

机制（MC 侧）：`SavedData.save(...)` 只在 `isDirty()` 时落盘（`net/minecraft/world/level/saveddata/SavedData.java:36-55`），写完 `setDirty(false)`。所以：
- **每次世界加载** → `readNbt` → `setDirty()` → 下一次自动存档**必然**整表重写一次（无论有没有内容变化）；
- 之后任何一次真实写入（`updateDisk`/`modifyDisk`/`removeDisk`）同样把**整张表**置脏 ⇒ 下一次自动存档重写全部磁盘的全部 key（这是 `SavedData` 单 blob 的固有代价，不是本 mod 引入的）。
- 叠加 C2/C4 的假写入后，“每次自动存档都写整表”就接近事实；**单看 C2b，准确说法是“每次加载后必写一次 + 真实改动后每次写全表”**。

**对比 1.20.1（已修）**：`forge-1.20.1/.../util/StorageManager.java:16-19` 公开构造器保留 `setDirty()`，`:21-25` 的 private 构造器**去掉了**它，注释原文："The data was just read from disk, so it does not need to be written back until something actually changes. Marking it dirty here made every world save rewrite the whole side table."

**风险与可达性**：仅在**性能/存档体积**（S3），但 **100% 可达**、零条件——每次进服/进单人世界都会命中一次。对一台长期挂机、磁盘多的大服：每次重启后的第一次自动存档都要把整张磁盘表压一遍写一遍。

**需要的修复形状**：private 构造器去掉 `setDirty()`（一行）。公开构造器保留（新建的空表确实需要落盘）。**必须与 C2 一起修**，否则只修 C2b 没意义：C2 的 `getOrCreateDisk` 会立刻把脏标志重新拉起来。

**验收断言**：加载一个非空 `disk_manager.dat` 后不做任何操作，跑一次 `save()` —— 文件 mtime/内容不变、`isDirty()` 为 false。

---

### C3 — `readNbt` 里 `disks.put(getUUID(...), ...)` 无判空：**DIFFERENT（结构还在，但失败模式换了）**

**证据（1.21.1）**

```java
// src/main/java/io/github/projectet/ae2things/util/StorageManager.java:49-56
public static StorageManager readNbt(CompoundTag nbt, HolderLookup.Provider registries) {
    ...
    for (...) {
        CompoundTag disk = ...;
        disks.put(disk.getUUID(DISKUUID), DataStorage.fromNbt(disk.getCompound(Constants.DISKDATA)));  // :54 仍然不判空
    }
    return new StorageManager(disks);                                                              // :56
}
```

**为什么「null 键」在 1.21.1 写不进去**（这就是 DIFFERENT 的原因）：

```java
// net/minecraft/nbt/CompoundTag.java:251-257
public void putUUID(String key, UUID value) { this.tags.put(key, NbtUtils.createUUID(value)); }
public UUID getUUID(String key) { return NbtUtils.loadUUID(this.get(key)); }

// net/minecraft/nbt/NbtUtils.java:120-133
public static UUID loadUUID(Tag tag) {
    if (tag.getType() != IntArrayTag.TYPE) throw new IllegalArgumentException("Expected UUID-Tag to be of type ...");
    int[] aint = ...; if (aint.length != 4) throw new IllegalArgumentException("Expected UUID-Array to be of length 4 ...");
}
```

缺键时 `this.get(key)` 返回 `null` → `tag.getType()` 直接 **NPE**；类型/长度不对 → **IAE**。`loadUUID` 既不接受 null 也不返回 null。所以 `:54` 不会产生 null 键，但会**抛**。

**于是失败路径变成这样（更坏）**：

```java
// net/minecraft/world/level/storage/DimensionDataStorage.java:65-78
private <T extends SavedData> T readSavedData(...) {
    try {
        ... return reader.apply(compoundtag.getCompound("data"), this.registries);   // :71 我们的 readNbt 在这里抛
    } catch (Exception exception) {
        LOGGER.error("Error loading saved data: {}", filename, exception);          // :74 只打一行日志
    }
    return null;                                                                    // :77
}

// :43-52
public <T extends SavedData> T computeIfAbsent(SavedData.Factory<T> factory, String name) {
    T t = this.get(factory, name);            // 读失败 ⇒ null（且被缓存成 null，:54-63）
    if (t != null) return t;
    T t1 = (T)factory.constructor().get();    // ⇒ 直接 new 一个空白 StorageManager
    this.set(name, t1);
    return t1;
}
```

后果链：**任意一条**条目缺 `disk_id`（或类型错）→ `readNbt` 抛 → 被吞 → 返回**空的**新 `StorageManager`（而且它被 `setDirty()` 置脏）→ 下一次自动存档把 `disk_manager.dat` 覆盖成空表 ⇒ **所有磁盘的侧表数据静默消失**（物品上的 UUID 还在，于是每只盘都被 C2 重建为空条目，看起来就是“所有盘都变空了”）。

**对比 1.20.1（改了写法，但那个判空其实是死代码 —— 本审计推翻了移植线自己的说明）**：`forge-1.20.1/.../util/StorageManager.java:47-52` 写的是

```java
var diskId = disk.getUUID(Constants.DISKUUID);
if (diskId == null) {                     // ← 永远不会为 true
    continue;
}
```

注释说「Saving a null key would throw on the next world save…dropping it is the only safe option」。但在 **MC 1.20.1 里 `CompoundTag#getUUID` 同样不可能返回 null**，本审计用字节码证过（`forge-1.20.1/build/moddev/artifacts/forge-1.20.1-47.1.47-merged.jar`）：

```
public java.util.UUID getUUID(java.lang.String);
   0: aload_0 / 1: aload_1
   2: invokevirtual get:(Ljava/lang/String;)Lnet/minecraft/nbt/Tag;
   5: invokestatic  NbtUtils.loadUUID:(Lnet/minecraft/nbt/Tag;)Ljava/util/UUID;    // 直接把可能为 null 的 Tag 传进去

public static java.util.UUID loadUUID(net.minecraft.nbt.Tag);
   0: aload_0
   1: invokeinterface Tag.getType:()Lnet/minecraft/nbt/TagType;                   // ← 缺键时这里就是 NPE
   6: getstatic IntArrayTag.TYPE / 9: if_acmpeq 44
  12: new java/lang/IllegalArgumentException … 43: athrow                          // 类型不对就抛
```

⇒ 缺键时 1.20.1 也是 **NPE**，`diskId == null` 这一支进不去，`readNbt` 照样抛、照样被 `DimensionDataStorage` 吞掉、照样整表清空。**所以 C3 在 1.20.1 线其实也没真正修好**，只是把「save 期崩」的形态换了个位置；1.21.1 不必照抄这个判空。

**风险与可达性**：数据丢失（全表），S1。1.21.1 自己的 save/load 循环**不会**产出缺 `disk_id` 的条目（`save()` `:40` 每条都 `putUUID`），所以可达性 **低但非零**：手改 NBT、第三方工具、跨线/跨版本存档（1.20.1 线写入的键是 `diskuuid`，1.21.1 读的是 `disk_id` ⇒ 把 1.20.1 存档直接拿到 1.21.1 上跑，**每一条都缺键 ⇒ 整表清空**）、`DataStorage.toNbt()` 将来写坏、或任何新写入方漏键。触发概率低、后果是「所有盘看起来都空了」，且**发生时不会有崩服提示**，只有一行 `Error loading saved data` 日志 ⇒ 运维极难追溯。

**需要的修复形状（两侧都适用，别照抄 1.20.1 的 `== null`）**：用 MC 自带的 `hasUUID` 做前置判定——它的语义恰好就是「键存在 且 是 IntArrayTag 且 长度 4」，一条表达式挡掉 NPE 与 IAE：

```java
// net/minecraft/nbt/CompoundTag.java:259-262（1.21.1，与 1.20.1 同名同语义）
public boolean hasUUID(String key) {
    Tag tag = this.get(key);
    return tag != null && tag.getType() == IntArrayTag.TYPE && ((IntArrayTag)tag).getAsIntArray().length == 4;
}

// src/main/java/io/github/projectet/ae2things/util/StorageManager.java:54 处改成
if (!disk.hasUUID(DISKUUID)) {
    LOGGER.warn("Dropping unreadable disk entry (missing/invalid diskuuid)");
    continue;
}
disks.put(disk.getUUID(DISKUUID), DataStorage.fromNbt(disk.getCompound(Constants.DISKDATA)));
```

更强的做法（建议同时做）：把整个 per-entry 体包进 `try/catch (RuntimeException e) { LOGGER.error(...); continue; }`，让**单条坏数据**（`diskddata` 里 `stackKeys`/`stackAmounts` 对不上、`toNbt` 结构异常等）也不牵连整表。1.21.1 线尤其需要，因为这里一次失败的代价是**整张表**。

**验收断言**：构造一个含两条目的 `disk_manager` NBT：一条完整、一条**删掉 `disk_id`**。加载后必须：① 不抛异常出界（日志允许 warn）；② 好条目仍在；③ 下一次 `save()` 之后好条目仍在。当前代码在第 ① 步就会整表清空。

---

### C4 — SIMULATE 阶段就铸 UUID / 建条目：**PRESENT，需要修（最高优先）**

**证据（1.21.1）**

```java
// src/main/java/io/github/projectet/ae2things/storage/DISKCellInventory.java:396-417
if (storageManager != null && !hasDiskUUID()) {
    i.set(AE2Things.DATA_DISK_ID, UUID.randomUUID());   // :397 写物品组件
    storageManager.getOrCreateDisk(getDiskUUID());      // :398 建条目 + setDirty
    loadCellItems();                                    // :399
}

var currentAmount = this.getCellItems().getLong(what);  // :402
...
if (amount <= 0) {
    return 0;
}

if (mode == Actionable.MODULATE) {                      // :414 这才是真正的写入判断
    getCellItems().put(what, currentAmount + amount);
    this.saveChanges(amount);
}
```

`Actionable` 的判断在 `:414`，铸 UUID/建条目在 `:396-400` —— 即**任何 SIMULATE 调用都会改物品组件、都会改侧表、都会把存档置脏**。注意 `:409-412` 的 `amount <= 0` 提前返回发生在 `:396-400` **之后**：连「0 容量探测」也会先铸 UUID（`getRemainingItemCount()` 为 0 时，`:402` 之后立刻返回 0，但 UUID 已经写进物品了）。

**SIMULATE 真的会打到这里吗——字节码取证（AE2 19.2.18）**：
- `appeng.parts.automation.StorageImportStrategy`：`139: getstatic Actionable.SIMULATE` → `148: invokeinterface MEStorage.insert(...)`；之后 `163/198: getstatic Actionable.MODULATE` → `207/248: ...insert(...)`。即 AE2 **自己**就是「先 SIMULATE 探容量、再 MODULATE 真插」。
- `appeng.me.storage.NetworkStorage.insert(...)`：`138` 与 `230` 两处 `invokeinterface MEStorage.insert(...)`，把同一个 `Actionable` 透传给已挂载的 storage。
- `appeng.me.storage.DriveWatcher extends appeng.me.storage.MEInventoryHandler`，`MEInventoryHandler.insert(...)` 直接委派下游。
⇒ 链路闭合：**导入总线/接口等自动化 → 网络 SIMULATE 探测 → 驱动槽 watcher → `DISKCellInventory.insert(SIMULATE)`**。所以这不是「理论上可能」，是每次自动化往插着空盘的驱动里灌东西时都会发生。

**后果**：
1. 空盘被「摸」一下就带上 UUID 组件（物品不再是干净的空盘；`getuuid` 立刻能读到值）；
2. 侧表多一条空 `DataStorage` 条目 + 存档置脏（与 C2/C5 连锁：条目永不回收）；
3. 单人/客户端：见下。

**客户端那条分支**：`AE2Things.currentStorageManager()` 返回的是**进程级静态字段**（`src/main/java/io/github/projectet/ae2things/AE2Things.java:56-60`、`:128-131`），单人游戏里客户端与服务端同 JVM/同 classloader ⇒ **客户端线程看到的就是服务端那个真 manager（非 null）**。于是只要客户端出现一次对 DISK cell 的 `insert`（菜单预测/拖拽预览），就会在服务端侧表里建条目、置脏，并在客户端自己的 stack 副本上写一个随机 UUID；而服务端随后 MODULATE 时又会铸**另一个**随机 UUID ⇒ 双端不一致 + 两条幽灵条目。
**但**「客户端是否真的会对 DISK cell 调 `insert`」本次**没能取证**（AE2 19.2.18 无源码，客户端菜单路径靠字节码反推成本过高）——记入 §4 UNKNOWN。不依赖这一条的结论是：**服务端 SIMULATE 已经足够触发 C4**。

**对比 1.20.1（已修）**：`forge-1.20.1/.../storage/DISKCellInventory.java:454-460` 把整段移进了 MODULATE 分支：`if (mode == Actionable.MODULATE && storageManager != null && !hasDiskUUID()) { setDiskUUID(i, UUID.randomUUID()); ... loadCellItems(); }`。

**需要的修复形状**：把 `:396-400` 整段下移到 `:414` 的 MODULATE 分支内（1.20.1 形状），并且**放在 `amount <= 0` 提前返回之后**——即只在实际会写入时才铸 UUID。

**验收断言**（UPSTREAM-AUDIT Part D 第 15 条已提出，1.21.1 尚未满足）：对一只空盘（无 UUID）调 `insert(key, n, Actionable.SIMULATE, null)` 之后，① 物品 NBT/组件不变；② `storageManager.disks` 大小不变；③ `isDirty()` 不变。当前代码三项全破。

---

### C5 — 拆解/取空不 `removeDisk`：**PRESENT（面已被收窄，但拆解这一处仍在）**

**证据（1.21.1）**

```java
// src/main/java/io/github/projectet/ae2things/storage/DISKCellInventory.java:170-179
if (storedItemCount == 0) {
    if (hasDiskUUID()) {
        storageManager.removeDisk(getDiskUUID());          // :172 条目回收
        i.remove(AE2Things.DATA_DISK_ID);                  // :173 组件一起摘
        i.remove(AE2Things.DATA_DISK_ITEM_COUNT);          // :174
        initData();
    }
    this.isPersisted = true;
    return;
}
```

上游「取空后条目永久累积」的主要来源是**取空**：每次 `extract` 走 `saveChanges(delta)`（`:348-353`）→ `markChanged()`（`:355-363`）→ `persist()`，而 `persist()` 在 `storedItemCount == 0` 时已经 `removeDisk` 并摘掉组件。**这条路径 1.21.1 已经不漏了。**

剩下的漏点在拆解：

```java
// src/main/java/io/github/projectet/ae2things/item/DISKDrive.java:130-141
private boolean disassembleDrive(final ItemStack stack, final Level level, final Player player) {
    if (InteractionUtil.isInAlternateUseMode(player)) {
        if (level.isClientSide()) return false;
        var inv = getCellInventory(stack, null);          // :137 用真 manager（服务端）
        if (inv != null && playerInventory.getSelected() == stack) {
            var list = inv.getAvailableStacks();
            if (list.isEmpty()) {                          // :140 只判空
                playerInventory.setItem(playerInventory.selected, ItemStack.EMPTY);   // :141 物品直接销毁
                ... 只返还 core / upgrades / housing，**从不 removeDisk**
```

- 由于 `:140` 已要求空盘，拆解时调 `removeDisk` 是**安全**的；不调的代价只是「表里可能留着一条空条目」。
- 而「空盘 + 表里有条目」这个状态在 1.21.1 里只能由 C2/C4 造出来（正常取空会连条目一起删），所以 **C5 的残余面是 C2/C4 的下游**。

**对比 1.20.1（已修，而且是主动修的）**：移植线在拆解里补了 6 行 + 一句解释：

```java
// forge-1.20.1/src/main/java/io/github/projectet/ae2things/item/DISKDrive.java:122-128
if (list.isEmpty()) {
    // The cell item is about to be destroyed, so its side-table entry would otherwise linger forever.
    var diskId = DISKCellInventory.getDiskUUID(stack);
    var manager = AE2Things.STORAGE_INSTANCE;
    if (diskId != null && manager != null && manager.hasUUID(diskId)) {
        manager.removeDisk(diskId);
    }
```

（注：1.20.1 用静态 `AE2Things.STORAGE_INSTANCE`；1.21.1 对应的是 `AE2Things.currentStorageManager()`。1.20.1 的 `DISKCellInventory.getDiskUUID(ItemStack)` 是静态 NBT 版本，1.21.1 是实例方法 `getDiskUUID()`（`DISKCellInventory.java:254-257`）／静态 `hasDiskUUID(ItemStack)`（`:247-252`），改写时按 1.21.1 的 API 取。）

**判定与建议**：verdict = **PRESENT**（brief 描述的行为「拆解从不下发 `removeDisk`」逐字成立，`DISKDrive.java:130-159` 里没有 `removeDisk`）；`needs fix?` = **YES，低成本**——理由不是「现在泄漏很严重」（取空已回收、残余只经 C2/C4 产生），而是：① 1.20.1 已经修了，两条线不该在这里分叉；② 6 行、零风险（`:140` 已判定空盘，删条目不丢内容）；③ 修完 C4 后它成为唯一剩余的「条目只增不减」入口，留着只会让以后的 bug 报告重新指向这里。

---

### C9 — `storageManager == null` 时 MODULATE 插入静默丢数据：**PRESENT，需要修**

**证据（1.21.1）**

```java
// :396 —— 没有 manager 就不铸 UUID
if (storageManager != null && !hasDiskUUID()) { ... }

// :414-417 —— 但 MODULATE 照样写内存
if (mode == Actionable.MODULATE) {
    getCellItems().put(what, currentAmount + amount);
    this.saveChanges(amount);      // :348-353 → markChanged
}

// :355-363
private void markChanged() {
    this.isPersisted = false;
    if (this.container != null) { this.container.saveChanges(); }
    else { this.persist(); }       // → :164-168
}

// :164-168
if (storageManager == null) {
    // Without a storage manager there is nothing to write to, so retrying on every change only wastes CPU.
    this.isPersisted = true;
    return;                        // 内容只在 this.storedAmounts 里
}
```

内容的权威副本是内存 map `storedAmounts`（`:279-286`）；物品上既没有 UUID（`:396` 被跳过），也没有其它落盘途径 ⇒ 这个实例一销毁，内容**彻底消失且不可恢复**（下一次实例 `loadCellItems()` 从 `DataStorage.EMPTY` 读到空）。

**对比 1.20.1（已修）**：`forge-1.20.1/.../storage/DISKCellInventory.java:427-432`：`if (storageManager == null && mode == Actionable.MODULATE) { return 0; }`，注释「…real insert has to be refused instead of silently eating the items… Simulations are still answered, they are what the client uses to predict the result of a click.」

**风险与可达性**：静默数据丢失（S1），但可达性 **低/UNKNOWN**：
- 服务端：manager 在 `ServerStartedEvent` 时设置（`AE2Things.java:107-110`），AE2 的存储服务此后才工作 ⇒ 正常时序里拿不到 null；
- 单人：同一个静态字段，非 null；
- MP 客户端：`currentStorageManager()` 返回 null（客户端没有 ServerStartedEvent），此时若客户端真发生 MODULATE 插入，丢的是客户端预测副本——**不落到真实物品**，但用户看到「放进去又消失」的错乱。
⇒ 定性为**防御性正确性修复**，成本 2 行，收益是彻底关掉一个「静默吞物品」的分支。**建议修**（1.20.1 已修，两侧保持一致也降低后续漂移风险）。

**验收断言**：`createInventory(stack, null, null)`（显式 null manager）→ `insert(key, 1, Actionable.MODULATE, null)` 必须返回 0，且 `getAvailableStacks()` 仍为空；同一实例 `insert(key, 1, Actionable.SIMULATE, null)` 仍应正常返回可插数量（保留客户端的预测能力）。

---

### F2 — `/ae2things recover <uuid>` 不检查该盘是否已被占用：**PRESENT，需要修（至少要在命令层面承认这个洞）**

**证据（1.21.1）**

```java
// src/main/java/io/github/projectet/ae2things/command/Command.java:63-71
var storageManager = AE2Things.currentStorageManager();

if (storageManager != null && storageManager.hasUUID(uuid)) {      // :65 只有“表里有这个 UUID”
    ItemStack stack = new ItemStack(AETItems.DISK_DRIVE_256K.get());
    stack.set(AE2Things.DATA_DISK_ID, uuid);                       // :68
    stack.set(AE2Things.DATA_DISK_ITEM_COUNT, storageManager.getOrCreateDisk(uuid).itemCount);
    player.addItem(stack);                                         // :71 直接给玩家一份新盘
```

- 全文没有任何「该 UUID 是否已被某个活着的 `DISKCellInventory` / 某个已存在的 `ItemStack` 持有」的判断；`recoverArg` 只要求权限 2（`:40`）。
- 于是「盘还在驱动里」时执行一次 `recover`，就得到**两个 ItemStack 共用同一 UUID ⇒ 共用同一条 `DataStorage`**。两条 cell 各自 `persist()`：`tryUpdateAmounts` 原地改同一份 `DataStorage`，`updateDisk` 整条覆盖，`pruneKeyTags` 还会按各自的 map 裁缓存 ⇒ 交错写入时**内容互相覆盖（丢东西）**，而外观上是「复制了一份盘」。
- 另一条路径：`/recover` 还会顺手 `getOrCreateDisk(uuid)`（`:69`）——但该分支要求 `hasUUID` 为真，所以不会凭空建条目，无 C2 副作用。

**对比 1.20.1**：**没修**。`forge-1.20.1/.../command/Command.java:67` 同样是 `if (storageManager != null && storageManager.hasUUID(uuid)) {`。两侧同病。

**怎么修**（本 mod 目前没有 UUID 占用表，所以没有“严格在线检查”可用）：
1. **最小成本（建议先做）**：命令层加「玩家背包里是否已有同 UUID 物品」的检查 + 在成功/失败消息里明确写出「这是救援工具，盘仍在使用时执行会造成数据覆盖」；文档/`help()` 同步改（注意 `Command.java:49-58` 的英文帮助文本是**刻意硬编码**的，AE2MEGAThings 的 `MixinAE2ThingsCommand` 用 `@ModifyConstant` 改那个字面量，别翻译它）。
2. **结构性**：给 `DISKCellInventory` 加轻量登记（构造时 `register(uuid, this)`、失效/`removeDisk` 时注销，引用计数或弱引用集合），`recover` 前查登记表；顺带能给「同 UUID 双实例」加一条 warn。成本中等，但这是唯一能真正拦住 F2 的形状。
3. 不建议：靠 `hasUUID` 之外的 `disks` 内容判断「是否还在用」——条目在盘不在驱动里时也一直存在，判不出来。

**建议定性**：**设计级缺陷 + 管理员误用风险**（不是普通玩家可触发，需要 OP/权限 2）。修复优先级低于 C4/C2b/C2/C3，但**不能继续当不存在**：1.20.1 与 1.21.1 两条线都要写进文档。

---

## 3. 交叉影响与建议修复顺序

```
C4（SIMULATE 不铸 UUID）   ── 先修：它是幽灵条目/物品被改写的主源头
   ↓ 修完后 C5 的残余面≈0
C2（只读 getDisk + getDiskStorage 改只读）── 与 C4 配套：读不再造条目
   ↓ 修完后 C2b 才有意义
C2b（private ctor 不 setDirty）── 一行，收益最确定（每次加载少一次整表重写）
   ↓
C3（readNbt 逐条判空 + 逐条 try/catch）── 防「整表静默清空」的数据丢失
   ↓
C9（manager==null 时拒绝 MODULATE）── 2 行防御
   ↓
C5（拆解补 removeDisk）── 6 行，与 1.20.1 对齐
   ↓
F2（recover 占用检查 / 文档定性）── 设计级，最后做，但必须记录
```

注意两条**顺序耦合**：
- 只修 C2b 不修 C2 ⇒ `getOrCreateDisk` 会立刻把脏标志重新拉起，收益被吃掉；
- 只修 C4 不修 C2 ⇒ 读路径仍会为「UUID 有、条目无」的盘建条目（C3 触发后、或 `/recover` 之后）；
- 修 C2 时**不要**顺手把 `persist()` 的 `:181` 改成只读：那是写路径，条目必须在那里（或 `insert` 的 MODULATE 分支）被创建。

---

## 4. UNKNOWN（静态阅读无法定论，需要实机/字节码进一步取证）

| # | 问题 | 为什么没有结论 |
|---|---|---|
| U1 | **客户端**是否会真的对 DISK cell 调 `insert`（决定 C4 的「客户端随机 UUID 双端不一致」是否成立） | AE2 19.2.18 无源码；要证明得反编译客户端菜单/拖拽预览路径。**C4 的主体结论不依赖此条**（服务端 SIMULATE 已由 `StorageImportStrategy` 字节码证明） |
| U2 | C9 在真实流程里的可达性（服务端启动时序） | 取决于世界加载与 AE2 存储服务的启动先后；静态阅读只能证明「服务端通常拿不到 null」 |
| U3 | C2 的「UUID 有、条目无」在正常游玩中出现的真实频率 | 需要实机统计（尤其 C3 触发后、以及跨线存档场景） |
| U4 | `readNbt` 里 `DataStorage.fromNbt` 是否还可能抛 | 已核：`DataStorage.java:83-88` 全部走 `getList`/`getLongArray`/`getLong`，缺失返回空值，不抛 ⇒ 现行实现下 readNbt 唯一的抛出点是 `getUUID` |

---

## 5. 复现命令（只读）

```bash
# 1) 确认 1.21.1 的 StorageManager 没有只读 getDisk、两个构造器都 setDirty
grep -n 'setDirty\|getOrCreateDisk\|public DataStorage\|private StorageManager' src/main/java/io/github/projectet/ae2things/util/StorageManager.java

# 2) 确认 readNbt 无判空
sed -n '49,57p' src/main/java/io/github/projectet/ae2things/util/StorageManager.java

# 3) 确认 C4 的铸 UUID 在 MODULATE 判断之前
sed -n '396,417p' src/main/java/io/github/projectet/ae2things/storage/DISKCellInventory.java

# 4) 确认 SIMULATE 会打到 cell 上（AE2 19.2.18 字节码）
javap -p -c -cp . appeng.parts.automation.StorageImportStrategy | grep -E 'SIMULATE|MEStorage.insert'
javap -p -c -cp . appeng.me.storage.NetworkStorage         | grep -E 'MEStorage.insert'

# 5) 确认 MC 1.21.1 的 getUUID 对缺键抛异常（而非返回 null）
sed -n '110,135p' <transformed>/net/minecraft/nbt/NbtUtils.java

# 5b) 确认 1.20.1 同样抛（⇒ 移植线的 diskId == null 是死代码）
cd forge-1.20.1/build/moddev/artifacts && "$JAVA_HOME/bin/javap" -p -c \
  -cp forge-1.20.1-47.1.47-merged.jar net.minecraft.nbt.NbtUtils | grep -A12 'public static java.util.UUID loadUUID'
# 输出第 1、2 条指令即 aload_0 / invokeinterface Tag.getType()：缺键（Tag=null）当场 NPE

# 6) 确认 readSavedData 吞异常后 computeIfAbsent 会用空白实例顶替
sed -n '43,78p' <transformed>/net/minecraft/world/level/storage/DimensionDataStorage.java
```

---

## 附：本审计**没有**做的事

- 没有修改任何源码（含 1.21.1 与 1.20.1 两条线）；工作区唯一的写入是本文件。
- 没有跑构建、没有跑实机、没有跑 `GoldenSampleDump`。
- 没有实机验证 C3 的跨线场景（1.20.1 存档 → 1.21.1），该场景结论来自读写键名比对（`diskuuid` vs `disk_id`）与 MC 字节码，不是实测。
- 没有把 Part C 的其它条目（C1/C6/C7/C8/C10）纳入判定——那批在 1.21.1 上的现状见 `docs/port-1.20.1/UPSTREAM-AUDIT.md` §4；本文件只回答 C2/C2b/C3/C4/C5/C9/F2 七条。
