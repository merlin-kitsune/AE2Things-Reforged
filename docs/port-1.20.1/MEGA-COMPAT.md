# AE2 MEGA Things 1.20.1 — 对我们 1.20.1 Forge 移植的硬依赖契约

- 任务：shared task-3（team: mega-compat）
- 取证日期：2026-11-05
- 对象：**AE2 MEGA Things 1.20.1 线**（Lapis256/AE2MEGAThings，CurseForge project 1150075），最新 1.20.1 版本 **v1.0.8**（CF file 6203824，`AE2MEGAThings-1.20.1-1.0.8.jar`，368871 B）
- 对照物：上游 AE2Things **1.2.1**（1.20.1 最后一个发布版，CF project 609977 file 4616683）+ 本仓 1.21.1 fork（`build/libs/AE2-Things-1.4.3.jar`）
- 全部结论来自 jar 字节码（`javap -p -c`）+ 上游源码 + 实际 mods.toml，未使用的推理在文中标 UNKNOWN。

---

## 0. 结论速览（TL;DR）

MEGA Things 1.20.1 对我们的依赖**极小但极硬**：12 个符号、2 个 mixin 目标、3 个内联常量、5 个 lang key。**没有** data/recipe/tag 层面的耦合。

但**本仓 1.21.1 fork 当前的二进制表面不能满足它**，两处致命：

| # | 现状（我们的 1.21.1 fork） | MEGA Things 1.0.8 期望 | 后果 |
|---|---|---|---|
| A | `AE2Things.STORAGE_INSTANCE` 字段已被删除（改为 `private static` + `currentStorageManager()`） | `getstatic AE2Things.STORAGE_INSTANCE:Lio/github/projectet/ae2things/util/StorageManager;`（2 处） | **NoSuchFieldError**，MEGA Things 命令类初始化即崩 |
| B | `DISKCellInventory` 构造器是 4 参 `(IDISKCellItem, ItemStack, ISaveProvider, StorageManager)` | 3 参 `(IDISKCellItem, ItemStack, ISaveProvider)V`（它在 Kotlin 里 `super(...)` 直接调） | **NoSuchMethodError**，实例化它的盘时崩 |

而它 mods.toml 的版本门写的是 `versionRange="[1.2,)"`——**只到 1.2，挡不住 API 漂移**。所以「版本号 ≥1.2」不是契约，**上面这份符号表才是契约**。

---

## 1. 取证对象与方法

| 用途 | 路径 |
|---|---|
| MEGA Things 1.20.1 发布 jar | `F:/MCProject/_ref/libs/AE2MEGAThings-1.20.1-1.0.8.jar`（解包 `_ref/_tmp/mega_x`，javap 全量转储 `_ref/_tmp/mega_javap.txt`） |
| MEGA Things 源码 | `F:/MCProject/_ref/ae2megathings`（分支 `1.20.1` @ `cd3960c` = tag `v1.0.8`；`origin/1.21.1` = v2.0.4） |
| 上游 AE2Things 1.2.1 jar | `F:/MCProject/_ref/libs/AE2-Things-1.2.1.jar`（解包 `_ref/_tmp/at_x`） |
| 上游 AE2Things 1.2.1 源码 | `F:/MCProject/_ref/ae2things-1.20.1`（tag 1.2.1） |
| 本仓 1.21.1 fork 产物 | `F:/MCProject/ae2things-reforged/build/libs/AE2-Things-1.4.3.jar`（解包 `_ref/_tmp/our_x2`） |
| AE2 15.4.12 源码 | `F:/MCProject/_ref/ae2-forge-15.4.12` |

```bash
JAVAP="C:/Program Files/Zulu/zulu-21/bin/javap.exe"
"$JAVAP" -p -c -cp F:/MCProject/_ref/libs/AE2MEGAThings-1.20.1-1.0.8.jar io.github.lapis256.ae2_mega_things.storage.AE2MTDISKCellInventory
"$JAVAP" -p -c -cp F:/MCProject/_ref/libs/AE2-Things-1.2.1.jar io.github.projectet.ae2things.AE2Things
```

---

## 2. 版本门与依赖声明（它 mods.toml 里对我们的要求）

MEGA Things 1.20.1 jar 内 `META-INF/mods.toml`（从 jar 直接读，非源码）：

```toml
[[dependencies.ae2_mega_things]]
    modId="ae2things"
    mandatory=true
    versionRange="[1.2,)"
    ordering="AFTER"
    side="BOTH"
```

其它相邻依赖（用于判断加载顺序）：`ae2 "[15,)" AFTER`、`megacells "[2.1,)" AFTER`、`kotlinforforge "[4.11.0,)"`、`forge "[47,)"`、`minecraft "[1.20.1,)"`、`appmek "[1.4,)" optional`。

- 版本门生成链：`ae2megathings/build.gradle.kts:167` `ModDep("ae2things", "1.2", ordering = Order.AFTER)` → `buildSrc/src/main/kotlin/Utils.kt:25` 渲染成 `versionRange = "[$version,)"`、`Utils.kt:33` 默认只取 1 段（"1.2"）。
- **含义**：它只要求「我们的 mod 版本字符串 ≥ 1.2」。1.2.1 之后我们把 API 改成 4 参、删字段，版本号仍是 1.4.x ⇒ **门是开着的，崩在运行时**。
- **对我们移植的要求**：
  1. `modId` 必须仍是 **`ae2things`**（否则它 `mandatory=true` 直接拒绝加载，根本轮不到 API 讨论）；
  2. 1.20.1 产物版本号必须 ≥ **1.2**（建议沿用 1.4.3 之类，勿降到 1.0.x）；
  3. `ordering=AFTER` 意味着它**后**注册命令、**后**初始化——命令合并语义依赖这一点（见 §5）。

1.20.1 线自己的 build 版本（供参考，非约束）：`gradle/libs.versions.toml` minecraft 1.20.1 / forge 47.3.7 / ae2 15.2.13 / kotlinForForge 4.11.0 / jdk 17。

---

## 3. 硬依赖符号清单（必须逐字保留：名字 + 描述符 + 返回类型）

以下每一行都来自对 **MEGA Things 1.0.8 全部 class 的 `javap -c` 常量池扫描**（不是源码推断）。

### 3.1 我方类上被直接引用的成员

| 我方符号（精确描述符） | 引用形态 | 出处 |
|---|---|---|
| `AE2Things.STORAGE_INSTANCE:Lio/github/projectet/ae2things/util/StorageManager;` | `getstatic` ×2 | `MEGAThingsCommand`（两处 spawnDrive） |
| `StorageManager.hasUUID:(Ljava/util/UUID;)Z` | `invokevirtual` | `MEGAThingsCommand` |
| `StorageManager.getOrCreateDisk:(Ljava/util/UUID;)Lio/github/projectet/ae2things/util/DataStorage;` | `invokevirtual` | `MEGAThingsCommand` |
| `DataStorage.itemCount:J` | `getfield` | `MEGAThingsCommand` |
| `DISKCellInventory."<init>":(Lio/github/projectet/ae2things/storage/IDISKCellItem;Lnet/minecraft/world/item/ItemStack;Lappeng/api/storage/cells/ISaveProvider;)V` | `invokespecial`（Kotlin `super(...)`） | `AE2MTDISKCellInventory` |
| `DISKCellInventory.getStoredItemCount:()J` | `invokespecial`（super） | `AE2MTDISKCellInventory` |
| `DISKCellInventory.getNbtItemCount:()J` | `invokespecial`（super） | `AE2MTDISKCellInventory` |
| `DISKCellInventory.getAvailableStacks:()Lappeng/api/stacks/KeyCounter;` | `invokevirtual` | `AE2MTDISKCellInventory`（实际解析到 AE2 `MEStorage` default，不在我们的类里，见下） |
| `DISKCellHandler.INSTANCE:Lio/github/projectet/ae2things/storage/DISKCellHandler;` | `getstatic` | `Utils` |
| `DISKCellHandler."<init>":()V` | `invokespecial` | `AE2MTDISKCellHandler`（`object : DISKCellHandler()`） |
| `DISKCellHandler.getCellInventory:(Lnet/minecraft/world/item/ItemStack;Lappeng/api/storage/cells/ISaveProvider;)Lio/github/projectet/ae2things/storage/DISKCellInventory;` | `invokevirtual` | `Utils`、`AE2MTDISKCellHandler` 重写（协变返回，生成 bridge） |
| `IDISKCellItem.getKeyType:()Lappeng/api/stacks/AEKeyType;` | `invokeinterface` | `AE2MTDISKCellInventory` |
| `AETItems.DISK_DRIVE_256K:Lnet/minecraftforge/registries/RegistryObject;` | `getstatic` | `MEGAThingsCommand`（随后 `.get()` + `checkcast IDISKCellItem`） |

**类型级引用**（只在 `@Mixin` 注解 / `instanceof` / `checkcast` 里出现，但同样必须存在且是同一角色）：

- `io/github/projectet/ae2things/command/Command` —— **mixin 目标类**（见 §5）
- `io/github/projectet/ae2things/storage/DISKCellInventory` —— **mixin 目标类** + 父类
- `io/github/projectet/ae2things/storage/IDISKCellItem` —— `instanceof` ×2、方法参数类型
- `io/github/projectet/ae2things/item/DISKDrive` —— `Utils.kt` 里 `is DISKDrive`（`instanceof`）
- `io/github/projectet/ae2things/storage/DISKCellHandler`、`util/StorageManager`、`util/DataStorage`、`item/AETItems`

### 3.2 「解析进我方类」的间接引用（同样硬）

| 引用 | 机制 | 要求 |
|---|---|---|
| `AE2MTDISKCellHandler.addCellInformationToTooltip:(Lnet/minecraft/world/item/ItemStack;Ljava/util/List;)V` | 它**不重写**，只是从 `AbstractDISKDrive`（offset 68 `invokevirtual`）继承调用 | 必须能解析到我们的 `DISKCellHandler.addCellInformationToTooltip(ItemStack, List<Component>)` |
| `AE2MTDISKCellInventory.getAvailableStacks:()Lappeng/api/stacks/KeyCounter;` | 它重写、调 `super` | 解析链经过我们的 `DISKCellInventory` 落到 AE2 15 `MEStorage` 的 `default KeyCounter getAvailableStacks()`（`appeng/api/storage/MEStorage.java:108`）。**我们的类不要自己新增同签名方法**，否则语义被截胡（行为风险，非链接风险） |

### 3.3 显式**不**依赖（已核，勿为它多做防备）

- `DISKCellInventory.createInventory(...)`：**1.20.1 线从未引用**（只有 1.21.1 线的 `MixinDISKCellHandler` 引用 3 参版）。1.20.1 只走 3 参构造器。
- `IDISKCellItem.clone(ItemStack)`：1.20.1 线**没有**这个方法（是 1.21.1 线才加的 `override`）。
- 任何 `data/` `assets/` 资源引用：对 `ae2_mega_things-1.0.8.jar` 的 `data/**` 与源码 `src/main/resources` 搜 `ae2things` = **0 命中**（无 recipe/tag/loot 交叉）。
- 物品注册 id：它不硬编码 `ae2things:disk_drive_*` 字符串；`DISKCellItemArgument` 是遍历 `ForgeRegistries.ITEMS` 找 `is IDISKCellItem`（第 26-32 行）。
- 我们的 GUI handler：1.20.1 线不碰（它自己有 `AE2MTDISKCellGuiHandler`）。
- `StorageManager` / `DataStorage` 的 **NBT 序列化格式**：它不读我们的存档键，只用 `hasUUID` / `getOrCreateDisk` / 内存字段 `itemCount`。

---

## 4. 内联常量（无 fieldref ⇒ 改名不会被链接期发现，只会静默坏）

Kotlin 把 `const val` / `static final String` 内联进它的字节码，因此**没有** `getstatic` 指向我们的常量字段——改名不会报错，只会行为错位：

| 常量 | 值（必须保持） | 它的用法 |
|---|---|---|
| `AE2Things.MOD_ID` | `"ae2things"` | `Commands.literal(AE2Things.MOD_ID)` → 命令根字面量 |
| `Constants.DISKUUID`（1.2.1；我们 fork 里叫 `StorageManager.DISK_ID` 私有） | `"diskuuid"` | `nbt.putUUID("diskuuid", uuid)`（`CompoundTag.m_128362_`）——**写在恢复出来的盘物品上** |
| `DISKCellInventory.ITEM_COUNT_TAG`（1.2.1；fork 里是 `DataStorage.ITEM_COUNT_TAG`，且值已改） | `"ic"` | `nbt.putLong("ic", storage.itemCount)`（`CompoundTag.m_128356_`） |

注意：我方 fork 的 `DataStorage.ITEM_COUNT_TAG` 已从 `"ic"` 改成 `"item_count"`（**侧表** NBT 键，见 §10 R11 讨论）。MEGA Things 写的是**物品栈 tag 上的 "ic"**，不是侧表；1.2.1 的 `persist()` 本来就会把盘物品上的 `"diskuuid"` / `"ic"` 清掉（空盘分支 `CompoundTag.m_128473_`），所以 **"ic" 已废弃、真源是 `diskuuid` + 侧表**。1.20.1 移植必须保住的是 **物品栈 NBT 键 `"diskuuid"`**。

---

## 5. Mixin 契约（2 个，`remap = false`，`injectors.defaultRequire = 1`）

它的 mixin 配置：`ae2_mega_things.mixins.json` → `required=true`、`minVersion=0.8`、`compatibilityLevel=JAVA_17`、`mixins=[MixinAE2ThingsCommand, MixinDISKCellInventory]`、`injectors.defaultRequire=1`、refmap 空（`{"mappings":{},"data":{}}`，因为全 `remap=false`）。

### 5.1 `MixinAE2ThingsCommand`

```java
@Mixin(value = io.github.projectet.ae2things.command.Command.class, remap = false)
public class MixinAE2ThingsCommand {
    @ModifyConstant(method = "lambda$help$3", constant = @Constant(ordinal = 0))
    private static String modifyHelpCommand(String original) {
        return original.replace("<UUID>", "<UUID> [DISK]") + " You can change the disk to be spawned by the given DISK.";
    }
}
```

（源码 `src/main/java/io/github/lapis256/ae2_mega_things/mixin/MixinAE2ThingsCommand.java:9-14`；jar 侧 `javap -v` 确认 `RuntimeInvisible` `@Mixin(value=Lio/github/projectet/ae2things/command/Command;, remap=false)`、`RuntimeVisible @ModifyConstant(method=["lambda$help$3"], constant=[@Constant(ordinal=0)])`，处理器 `ACC_PRIVATE|ACC_STATIC`。）

**对移植的硬要求：**

1. `Command` 类全名与包不变：`io.github.projectet.ae2things.command.Command`。
2. `help()` 里必须存在 **javac 生成的 `lambda$help$3`**，且该方法体内**只有一个字符串常量（ordinal 0）**。
   - 1.2.1 与我们的 fork 现在都满足：`help()` 有 3 个 `sendSuccess(() -> Component.literal(...))`，lambda 计数器类级按源码顺序，于是 `lambda$static$0`(`.requires`)、`lambda$static$1`(`.executes`)、`lambda$help$2`、**`lambda$help$3`**、`lambda$help$4`。
   - 本仓 `src/main/java/io/github/projectet/ae2things/command/Command.java:49-57` 现状：`help()` 三条 `sendSuccess`，第 2 条即 `"/ae2things recover <UUID> - Spawns a drive with the given UUID, if it doesn't exist, does not spawn any item."`。
   - **不要**给 `help()` 加/删/挪任何一条 lambda（会移动计数），不要把它改成方法引用，不要把它 localize（`AGENTS.md` §7 已记录这条刻意保持英文）。
   - `lambda$help$3` 缺失或 ordinal 0 不是字符串 ⇒ mixin `require=1` 失败 ⇒ **InjectionError，游戏起不来**。
3. 文本里必须**含子串 `<UUID>`**：它的处理是 `original.replace("<UUID>", "<UUID> [DISK]")`。若翻译/改写丢掉该占位符 ⇒ 不崩，但输出变成「原文 + 一句解释」的降级形态（RISK 级，非致命）。

### 5.2 `MixinDISKCellInventory`

```java
@Mixin(value = io.github.projectet.ae2things.storage.DISKCellInventory.class, remap = false)
public class MixinDISKCellInventory {
    @ModifyExpressionValue(
        method = "insert",
        at = @At(value = "INVOKE",
            target = "Lio/github/projectet/ae2things/storage/IDISKCellItem;isBlackListed(Lnet/minecraft/world/item/ItemStack;Lappeng/api/stacks/AEKey;)Z"))
    private boolean ae2_mega_things$insertPreventNonEmptyCell(boolean original, @Local(argsOnly = true) AEKey what) { ... }
}
```

（源码 `mixin/MixinDISKCellInventory.java:13-22`。）

**对移植的硬要求：**

1. `DISKCellInventory.insert` 方法名与描述符必须是 1.2.1 原样：
   `insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J`（首参类型 `AEKey` —— `@Local(argsOnly=true) AEKey what` 按**类型**匹配）。
2. 方法体内必须**恰好一处** `invokeinterface io/github/projectet/ae2things/storage/IDISKCellItem.isBlackListed:(Lnet/minecraft/world/item/ItemStack;Lappeng/api/stacks/AEKey;)Z`。
   - `injectors.defaultRequire=1` 决定「至少 1 处」，**0 处 = InjectionError 崩服**；≥2 处会重复套用（不崩但语义重复，别这么干）。
   - 我们 fork 现在**满足**：`javap` 在 `insert` 偏移 47 找到唯一一处；源码 `DISKCellInventory.java:380` `if (this.cellType.isBlackListed(this.i, what))`。
   - **移植时如果把这段判断抽成私有 helper、或提前 return、或改成 `cellType.storableInStorageCell()`，mixin 立刻失效。**
3. `isBlackListed` 必须留在 `IDISKCellItem` 接口上（1.2.1 是 default 方法）。

---

## 6. 命令命名空间：它会把 `recover` **合并**进来（不会顶掉 help/getuuid）

它的注册方式是 `getRoot().addChild(recoverArg)`（`MEGAThingsCommand.init` 字节码：`CommandDispatcher.getRoot()` → `getstatic recoverArg` → `RootCommandNode.addChild`），而 `recoverArg` = `literal("ae2things").then(literal("recover")...)`（静态初始化块，`ldc "ae2things"` / `ldc_w "recover"`）。

Brigadier 的 `CommandNode.addChild` 对同名子节点是**合并**语义（已核 1.1.8 与 1.3.10 两版源码，逻辑一致）：

```java
final CommandNode<S> child = children.get(node.getName());
if (child != null) {                       // merge onto existing
    if (node.getCommand() != null) child.command = node.getCommand();
    for (final CommandNode<S> grandchild : node.getChildren()) child.addChild(grandchild);
} else { children.put(node.getName(), node); ... }
```

推论（这也是为什么它的 `@ModifyConstant` 打在我们 `help` 上是有意义的）：

- `/ae2things` 根节点保留**我们**的 `executes(help)` 与 `getuuid` 子节点；`recover` 子节点被它的版本覆盖（executor 被替换，并挂上它的 `disk` 参数子节点）——这正是上游 1.2.1 + MEGA Things 1.0.8 并存时的既有行为，**不是新风险**。
- 我们 fork `command/Command.java:25-30` 用三次 `dispatcher.getRoot().addChild(...)`（`commandRoot` / `recoverArg` / `copyUUID`，三者都是 `literal(AE2Things.MOD_ID)`），与它的 `addChild` 对称，合并安全。
- **硬要求**：根字面量必须仍是 `"ae2things"`、子字面量必须仍是 `"recover"`；不要改成 `/ae2things-reforged` 之类，否则合并关系消失（变成两套命令，且旧的 `recover` 仍被我们的 2 参版占用）。
- 它额外要求 `AETItems.DISK_DRIVE_256K` 可解析（用作 `/ae2things recover <uuid> <disk>` 的补全根）。

---

## 7. lang key：**用我们的**（它自己的 lang 里没有这些键）

它的代码引用 `Component.translatable("command.ae2things.recover_success" / "command.ae2things.recover_fail")`；而 `ae2_mega_things-1.0.8.jar` 内 `assets/ae2_mega_things/lang/{en_us,ja_jp,ru_ru,zh_cn}.json` **不含任何 `command.*` 键**。这些键来自我们的 `assets/ae2things/lang/en_us.json`（1.2.1 与我们 fork 都逐字带）：

| key | 值（当前英文，勿改占位符顺序/数量） |
|---|---|
| `command.ae2things.recover_success` | `%s has recovered a DISK Drive with the UUID of [%s].` （2 参：player, uuid） |
| `command.ae2things.recover_fail` | `Failed to recover DISK Drive: UUID [%s] does not exist.` （1 参） |
| `command.ae2things.getuuid_success` | （我们 /getuuid 用） |
| `command.ae2things.getuuid_fail_nouuid` | （同上） |
| `command.ae2things.getuuid_fail_notdisk` | （同上） |

**硬要求**：`en_us.json` 必须保留 `command.ae2things.*` 全部 5 个键；`recover_success` 必须是 2 个 `%s`、`recover_fail` 必须是 1 个 `%s`（它的调用点参数个数固定，少一个会抛 `IllegalFormatException`/翻译降级）。

---

## 8. 1.20.1 线 vs 1.21.1 线：它自己的期望差异（移植别照抄 1.21.1）

| 维度 | 1.20.1 线（v1.0.8） | 1.21.1 线（v2.0.4） |
|---|---|---|
| 平台 | Forge 47.3.7 / MC 1.20.1 / AE2 15.2.13 / KFF 4.11.0 / JDK17 | NeoForge 21.1.77 / MC 1.21.1 / AE2(CF 5729094) / KFF 5.6.0 / JDK21 |
| ae2things 依赖声明 | `ModDep("ae2things", "1.2", Order.AFTER)` → `[1.2,)` | 同（`build.gradle.kts:135`），→ `[1.2,)` |
| 对 3 参构造器 | **只用 3 参** `(IDISKCellItem, ItemStack, ISaveProvider)` | 用 **4 参**（多一个 `StorageManager?`），取自 `AE2Things.currentStorageManager()` |
| 对 `STORAGE_INSTANCE` | **直接 getstatic 字段** | 不碰（用 `currentStorageManager()`） |
| mixin 数量 | 2 个（Command + DISKCellInventory） | **3 个**（多 `MixinDISKCellHandler`，因为 fork 的 tooltip 路径绕过了 handler 覆写） |
| mixin 兼容级别 | `JAVA_17` | `JAVA_21` |
| `IDISKCellItem.clone` | 不存在 | `AbstractDISKDrive` 有 `override fun clone(stack: ItemStack): ItemStack`（`AbstractDISKDrive.kt:109`） |
| 注册表 API | `net.minecraftforge.registries.ForgeRegistries` | `net.minecraft.core.registries.BuiltInRegistries` |
| `DISKCellInventory.createInventory` 引用 | 无 | 有（`MixinDISKCellHandler` 的 `@At` 目标，3 参描述符） |

⇒ **对我们的直接含义**：1.20.1 侧只能用「1.2.1 时代的二进制表面」，而 1.21.1 侧只能用「fork 的新表面」。**两条线必须并存**（同一份 `ae2things` modId 下按 MC 版本分 jar），所以正确做法是「1.20.1 jar 恢复 1.2.1 表面（并可按需保留新重载作为**附加**方法），1.21.1 jar 保持现状」，而不是把 fork 的新表面硬推到 1.20.1。

---

## 9. 具体不兼容风险（按严重度）

| # | 风险 | 触发条件 | 症状 | 修法（本仓位置） |
|---|---|---|---|---|
| R1 | **`AE2Things.STORAGE_INSTANCE` 缺失** | 1.20.1 产物沿用 fork 的「私有字段 + `currentStorageManager()`」 | `NoSuchFieldError: io/github/projectet/ae2things/AE2Things.STORAGE_INSTANCE`，MEGA Things 命令类 `<clinit>` 即崩（崩在 mod 初始化，不是进游戏后） | 恢复 `public static StorageManager STORAGE_INSTANCE`（1.2.1 语义：`<clinit>` 里 `new StorageManager()`，并在 server level tick / ServerStarted 时刷成 `StorageManager.getInstance(server)`）。本仓现位置：`AE2Things.java:57/60/107-115/129-131` |
| R2 | **3 参构造器缺失** | 沿用 fork 的 4 参 | `NoSuchMethodError: DISKCellInventory.<init>(IDISKCellItem, ItemStack, ISaveProvider)V`，只在**实例化它的盘**时崩（首次 GUI/插入时） | 加回 3 参重载（内部转调 4 参、`storageManager` 传 `null` 或 `STORAGE_INSTANCE`）。本仓：`DISKCellInventory.java:57-63` 现为 4 参 |
| R3 | **`AETItems.*` 字段类型不是 Forge `RegistryObject`** | 1.20.1 若沿用 NeoForge `DeferredItem`（1.20.1 也没这个类，编译期就会挡住）或写成 `Supplier<Item>` | `getstatic AETItems.DISK_DRIVE_256K:Lnet/minecraftforge/registries/RegistryObject;` 解析失败 → `NoSuchFieldError` | 1.20.1 用 `DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)` 返回的 `RegistryObject<Item>`（这本来就是 1.20.1 唯一写法）。本仓：`AETItems.java:12/19-30` 现为 `DeferredItem` |
| R4 | **`getCellInventory` 返回类型被改成 `StorageCell`/`ICellHandler`** | 移植时「顺手清掉协变返回」 | `NoSuchMethodError`（它 `invokevirtual` 精确描述符带 `DISKCellInventory` 返回） | 源码必须**声明** `public DISKCellInventory getCellInventory(ItemStack, ISaveProvider)`（bridge 由 javac 自动生成）。本仓 `DISKCellHandler.java:28` 现状正确 |
| R5 | **`insert` 里 `isBlackListed` 调用消失** | 重构 insert / 抽 helper | mixin `require=1` 失败 → `InjectionError`，**启动崩溃**（且会带崩同一批 mixin 加载） | 保留 `DISKCellInventory.java:380` 的调用形态与原方法描述符 |
| R6 | **`lambda$help$3` 缺失/移位** | 改 `help()` 的 lambda 数、改方法引用、加/删 `sendSuccess` | 同上 InjectionError | 保持 `Command.java:49-57` 形态；文本含 `<UUID>` |
| R7 | **tooltip 数值走位（行为回归）** | 1.20.1 沿用 fork 把 `addCellInformationToTooltip` 写成 `createInventory(stack, null, null)` 静态调用 | 不崩；MEGA Things 的盘 tooltip 显示**基类**数字（它的盘按 `AEKeyType.amountPerUnit` 折算 `getStoredItemCount()/getNbtItemCount()`），与其 GUI 数字不一致 | 恢复 1.2.1 的**虚调用**：`this.getCellInventory(stack, null)`（本仓 `DISKCellHandler.java:32-35` 现在是 `createInventory(...)` 静态调用）。1.21.1 线用 `MixinDISKCellHandler` 打补丁，1.20.1 线**没有**这个补丁 |
| R8 | **保留 `IDISKCellItem.clone()` 抽象方法** | 1.20.1 移植保留 clone | 它的 `AbstractDISKDrive`(1.0.8) 没实现 clone ⇒ 调到时 `AbstractMethodError`（实例化不炸，用到才炸） | 1.20.1 把 `clone` 从接口与 `DISKDrive` 移除（AE2 15 的 `ICellWorkbenchItem` 本来没有 clone，`CursedInternalSlotMixin` 依赖的 AE2-19 内部槽在 15 也不存在）。本仓：`IDISKCellItem.java:86-89`、`DISKDrive.java:80`、`mixin/CursedInternalSlotMixin.java:33` |
| R9 | **版本门挡不住 API 漂移** | 有人在 1.20.1 上装 fork 版（版本 1.4.x）却按 4 参 API | 安装期无提示，运行期 R1/R2 | 流程性：1.20.1 jar 的版本号别低于 1.2，且**必须**恢复 R1/R2 |
| R10 | **`diskuuid` 用 data component 而非 NBT** | 1.20.1 移植沿用 1.21.1 的 `DATA_DISK_ID` 组件 | 它恢复出来的盘物品上只有 NBT `diskuuid`；若我们只认组件 ⇒ 恢复出的盘**无 UUID/空盘**（静默，不崩） | 1.20.1 只能走 NBT：`ItemStack.getTag().getUUID("diskuuid")`（1.2.1 语义，`Constants.DISKUUID`）。1.20.1 Forge 没有 data components，本来就该这么写 |
| R11 | **侧表 `itemCount` 键已从 `"ic"` 改为 `"item_count"`** | 1.20.1 若沿用 fork 的新键 | 与 MEGA Things **无关**（它不读侧表），但 **1.2.1 存档迁移**会读不到 `ic` ⇒ 若 1.20.1 jar 也改键，旧 1.20.1 存档的 `itemCount` 丢失（只影响 UI 计数，不影响 `stackKeys/stackAmounts`） | 1.20.1 建议**保持 `"ic"`**（1.2.1 原样），fork 的改名只在 1.21.1 线生效；若要统一，必须写 `fromNbt` 兼容读两个键 |
| R12 | **改 modId / 改命令根** | 改 `ae2things` modId，或把 `literal("ae2things")` 改名 | `mandatory=true` 直接拒绝加载（连不上），或命令合并失效 | 保持 `modId="ae2things"` 与根字面量 `"ae2things"` |

**UNKNOWN（明确未settle，不猜）：**

1. 它的 GitHub issues 内容未取得（`api.github.com` 403 rate-limited，`gh` 无 `GH_TOKEN`）；`releases.atom` 可取（见下）。**因此「1.20.1 已知不兼容」只能从 commit 历史看，不能断言 issues 里没有。**
2. 它 1.20.1 线是否**只**针对 AE2Things 1.2.1 测过：CF 上 1.20.1 线最后文件就是 1.2.1（project 609977 file 4616683），无更晚 1.20.1 版本；但「是否曾与 1.2.0 或第三方 fork 共测」无证据。
3. 1.20.1 的 Brigadier 具体版本未直接取自 MC jar（用 1.1.8 与 1.3.10 的源码比对 `addChild` 逻辑一致，均为合并语义；1.20.1 落在两者之间，按同一实现推断，标 UNKNOWN-低风险）。

**1.20.1 线发布历史（`releases.atom` + git log）：**

- v1.0.8（2025-02-17）→ v1.0.7 → v1.0.6（2025-01-30，「Fix to use re-obfuscated jar files」）→ 1.0.5 … v1.0.0。
- 关键 commit：`1615874` 把发布产物从 `tasks.jar` 改成 `tasks.named("reobfJar")` ⇒ **≤1.0.5 的 1.20.1 包是未 reobf 的 dev jar**（拿不到 SRG 名，装进生产环境会因 `m_7373_` 类名缺失直接崩）。我们手上的 1.0.8 有 SRG 名（`m_7373_`/`m_7203_`），确认已 reobf。
  - ⇒ 若整合包/服主用 1.20.1 线，**必须 ≥1.0.6**；兼容测试基线即 1.0.8。
- `3ef2f7a`「Possible fix for #3: Use a less likely failure method to prevent startup crash」= MEGA Cells `StorageTier` 改为传 `Supplier`（延迟初始化），**与 ae2things 无关**。
- `4a05a68`「Fix depends」= 发布页 curseDepends（required: AE2 / AE2 Things / MEGA Cells / KotlinForForge；optional: Applied Mekanistics）。
- `8217b05`「Fix DISK Drive item suggestions in ae2things recover command」= 补全器遍历注册表（正是 §3.3 的 `ForgeRegistries` 扫描）。
- **没有任何 commit 提到与 ae2things 版本/API 的不兼容。**

---

## 10. (a) 1.20.1 移植必须保留的 API 表面（checklist）

**类（全名 + 角色不变）**
- `io.github.projectet.ae2things.AE2Things`（含 `MOD_ID`、`STORAGE_INSTANCE`、`ITEMS`）
- `io.github.projectet.ae2things.item.AETItems`（`DISK_HOUSING`/`DISK_DRIVE_1K|4K|16K|64K|256K` 为 Forge `RegistryObject<Item>`；`DISK_DRIVES`；`init()`/`commonSetup()`）
- `io.github.projectet.ae2things.item.DISKDrive`（`extends Item implements IDISKCellItem, AEToolItem`，`(ItemLike,int,double)` ctor）
- `io.github.projectet.ae2things.storage.IDISKCellItem`（`extends ICellWorkbenchItem`；abstract `getKeyType()`/`getBytes(ItemStack)`/`getIdleDrain()`/`getConfigInventory(ItemStack)`；default `isBlackListed(ItemStack,AEKey)`/`storableInStorageCell()`/`isStorageCell(ItemStack)`/`addCellInformationToTooltip(ItemStack,List)`）——**不加 abstract 成员**
- `io.github.projectet.ae2things.storage.DISKCellInventory`（3 参 ctor、`insert` 形态、`getStoredItemCount`/`getNbtItemCount`、静态 `createInventory(ItemStack,ISaveProvider)` 可选但建议保留）
- `io.github.projectet.ae2things.storage.DISKCellHandler`（`INSTANCE`、public ctor、`isCell`、`getCellInventory` 返回 `DISKCellInventory`、`addCellInformationToTooltip` 走虚调用）
- `io.github.projectet.ae2things.util.StorageManager`（`hasUUID(UUID)`、`getOrCreateDisk(UUID)`、`getInstance(MinecraftServer)`）
- `io.github.projectet.ae2things.util.DataStorage`（public `itemCount` 字段）
- `io.github.projectet.ae2things.command.Command`（`help()` 3 条 lambda、`lambda$help$3` 单串、根字面量 `"ae2things"`、子字面量 `"recover"`/`"getuuid"`）

**字符串契约**
- modId `"ae2things"`；物品 NBT 键 `"diskuuid"`；侧表键沿用 1.2.1（`disk_manager` / `diskdata` / `disklist` / `keys` / `amts` / `ic`）；lang 键 `command.ae2things.*`（5 个，占位符个数/顺序不变）。

**注**：3 参 ctor 与 4 参 ctor **可以并存**——1.20.1 侧保留 3 参（并可保留 4 参重载），1.21.1 侧保留 4 参。两边互不干扰，因为 jar 按 MC 版本分开分发。

---

## 11. (b)(c)(d) 差异 / 风险 / 逐约束结论

见 §8（1.20.1 vs 1.21.1 差异）、§9（风险表）。逐约束判定：

| 约束 | 判定 | 依据 |
|---|---|---|
| ① 类/字段/方法名 + 描述符 | **RISK** | 12 个符号里 fork 已破 2 个致命项（`STORAGE_INSTANCE`、3 参 ctor）+ 1 个字段类型（`AETItems` 的 `RegistryObject`）+ 1 个接口多余抽象方法（`clone`）。照 §10 checklist 恢复即 PASS |
| ② mixin 目标（`Command` / `DISKCellInventory`） | **PASS（当前形态满足）** | fork 的 `lambda$help$3` 与 `insert` 内唯一 `isBlackListed` invokeinterface 都还在（`javap` 已核）。移植时**不得重构**这两处 |
| ③ 依赖/版本门 | **RISK** | 门是 `[1.2,)`，只卡版本号不卡 API；万一 1.20.1 产物版本号 <1.2 或被改 modId ⇒ 直接不加载。保持 modId 与版本 ≥1.2 即 PASS |
| ④ 我们 1.21.1 fork 改动的部分是否需要回退 | **RISK/RESOLVED-BY-PORT** | **不需要**回退 1.21.1 线（它已被 MEGA Things 2.0.4 追平）；**1.20.1 线**必须按 §10 采用 1.2.1 表面：modId 不变、物品 id 不变、命令名/文案不变、NBT `diskuuid` 不变；侧表 `ic` 建议保留原键（R11）；recipe id 无耦合（它 0 引用） |
| ⑤ 发布说明/issue 已知不兼容 | **PASS（未发现）** | `releases.atom` 全部 1.20.1 版本与 git log 已核，无与 ae2things 相关的不兼容条目；唯一「会使所有装不上/崩」的是它自己 ≤1.0.5 的 dev-jar 问题（已由它 1.0.6 修）。issues 内容 UNKNOWN（rate limit） |

---

## 12. 复现命令（可选）

```bash
# 1. 确认它依赖的符号（应只出现 §3 列表里的内容）
JAVAP="C:/Program Files/Zulu/zulu-21/bin/javap.exe"
cd /f/MCProject/_ref/_tmp/mega_x
find . -name '*.class' | sed 's|^./||; s|/|.|g; s|.class$||' \
  | xargs -n 40 "$JAVAP" -p -c 2>/dev/null \
  | grep -oE '(Field|Method|InterfaceMethod|class) io/github/projectet/ae2things/[^ ]+' \
  | sort | uniq -c

# 2. 确认 mixin 目标
"$JAVAP" -v -cp . io.github.lapis256.ae2_mega_things.mixin.MixinAE2ThingsCommand | grep -A3 ModifyConstant
"$JAVAP" -v -cp . io.github.lapis256.ae2_mega_things.mixin.MixinDISKCellInventory | grep -A5 'ModifyExpressionValue\|isBlackListed'

# 3. 确认 1.2.1 侧的 lambda 编号（lambda$help$3 = 单 ldc_w 字面量）
cd /f/MCProject/_ref/_tmp/at_x
"$JAVAP" -p -c io.github.projectet.ae2things.command.Command | sed -n '/lambda\$help\$3/,/^$/p'
```
