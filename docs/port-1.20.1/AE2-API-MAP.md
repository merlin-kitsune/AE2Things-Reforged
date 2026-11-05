# AE2 15.4.12 / Forge 1.20.1 — API map for the AE2Things-Reforged port

Audience: whoever writes the 1.20.1 code. Everything below is **read from the real artifacts**, not from memory.
If a fact could not be settled from sources it is marked **UNKNOWN** (§6).

## How the evidence is cited

| Tag | Location |
|---|---|
| `AE2:` | `F:/MCProject/_ref/ae2-forge-15.4.12/src/main/java/...` — AE2 source at tag `forge/v15.4.12` (1.20.1 Forge, readable Java) |
| `UP:` | `F:/MCProject/_ref/ae2things-1.20.1/src/main/java/...` — upstream AE2Things **1.2.1**, the real shipped 1.20.1 Forge build (behaviour already proven in production) |
| `JAR:` | `javap` against `F:/MCProject/astral_dice_multiloader/forge-1.20.1/build/moddev/artifacts/forge-1.20.1-47.4.10-merged.jar` (Mojmap/named Minecraft+Forge) |
| `SRC:` | `F:/MCProject/astral_dice_multiloader/forge-1.20.1/build/moddev/artifacts/forge-1.20.1-47.4.10-sources.jar` (Mojmap Minecraft sources) |
| `OURS:` | `F:/MCProject/ae2things-reforged/src/main/java/io/github/projectet/ae2things/...` — the 1.21.1 / NeoForge tree being ported |

javap command used throughout (`C:/Program Files/Zulu/zulu-21/bin/javap.exe`):

    javap -p -c -cp <jar> <fqcn>

Warning about the AE2 jar: use `F:/MCProject/_ref/libs/ae2-forge-real-15.4.12.jar` for bytecode questions. **`F:/MCProject/_ref/libs/ae2-forge-15.4.12.jar` is the Fabric jar by mistake** — never javap it for Forge facts.

---

## 0. Delta summary — 1.21.1 construct becomes what on 1.20.1

| # | What our 1.21.1 code does | OURS file:line | 1.20.1 / 15.4.12 answer |
|---|---|---|---|
| 1 | `AEKey.fromTagGeneric(registries, tag)` (registry-carrying) | `storage/DISKCellInventory.java:317` | **`AEKey.fromTagGeneric(CompoundTag)` — the registry argument does not exist in 15.4.12.** Returns `@Nullable AEKey`. §1 |
| 2 | `DeferredRegister.Items` / `DeferredItem<Item>` | `AE2Things.java:40`, `item/AETItems.java:19-30` | `DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)`; items are `RegistryObject<Item>` (`.get()` instead of `.asItem()`). §3.1 |
| 3 | `DeferredRegister.DataComponents` + 3 `DataComponentType`s (`DATA_DISK_ID`, `DATA_DISK_ITEM_COUNT`, `DATA_FUZZY_MODE`) | `AE2Things.java:42,44-54` | **No data components on 1.20.1.** All three become plain item NBT: `"diskuuid"`, `"ic"`, `"FuzzyMode"`. §3.2 |
| 4 | `SavedData.Factory<>` + `save(CompoundTag, HolderLookup.Provider)` | `util/StorageManager.java:17-49` | `SavedData#save(CompoundTag)` (1 arg); `SavedData$Factory` **absent**; `DimensionDataStorage.computeIfAbsent(Function<CompoundTag,T>, Supplier<T>, String)`. §3.3 |
| 5 | `WeakReference<HolderLookup.Provider> registries` kept for key serde | `util/StorageManager.java:102,106`, `storage/DISKCellInventory.java:317` | **Delete it.** 15.4.12 key serde is registry-free (`AEKeyType.loadKeyFromTag` resolves through `BuiltInRegistries`). §1.1 |
| 6 | `@Mod` ctor takes `IEventBus modEventBus`; `NeoForge.EVENT_BUS` | `AE2Things.java:66-77`, `:23` | no-arg ctor + `FMLJavaModLoadingContext.get().getModEventBus()`; game bus is `MinecraftForge.EVENT_BUS`. §3.4 |
| 7 | `CellConfig.create(Set.of(getKeyType()), is)` | `item/DISKDrive.java:76` | `CellConfig.create(getKeyType().filter(), is)`. §2.5 |
| 8 | `appeng.api.storage.StorageCells` | `AE2Things.java:31` | **same package & class** in 15.4.12 (`appeng/api/storage/StorageCells.java`). Only the *registration* needs `addCellGuiHandler`. §2.3 |
| 9 | `appendHoverText(ItemStack, TooltipContext, List, TooltipFlag)` | `item/DISKDrive.java:169` | `appendHoverText(ItemStack, Level, List<Component>, TooltipFlag)` — `TooltipContext` does not exist in 1.20.1. §3.5 |
| 10 | `.component(...)` in `Item.Properties` | `item/DISKDrive.java:47-48` | no such builder method; "absent NBT reads as default" replaces the component defaults. §3.2 |
| 11 | mixin config without refmap, `compatibilityLevel JAVA_21` | `mixins.ae2things.json` | needs `"refmap"` + `"compatibilityLevel": "JAVA_8"`, a `build.gradle` `mixin{}` block, and a `MixinConfigs` jar-manifest entry. §3.6 |
| 12 | recipe data at `data/ae2things/recipe/**` (singular) | `src/generated/resources/data/ae2things/{advancement,recipe}/` | 1.20.1 uses **`data/ae2things/recipes/**` (plural)**; `UP:` ships `data/ae2things/recipes/cells/*.json`. §3.7 |
| 13 | `RecipeOutput` (1.20.2+ data-gen) | `data/CraftingRecipeProvider.java:12` | `RecipeProvider.buildRecipes(Consumer<FinishedRecipe>)`; `RecipeOutput` **absent**. §3.7 |

Imports that **survive unchanged** (all present in 15.4.12 under the same FQCN): `appeng.api.config.{Actionable,FuzzyMode,IncludeExclude}`, `appeng.api.ids.AECreativeTabIds`, `appeng.api.networking.security.IActionSource`, `appeng.api.stacks.{AEItemKey,AEKey,AEKeyType,KeyCounter}`, `appeng.api.storage.StorageCells`, `appeng.api.storage.cells.{CellState,IBasicCellItem,ICellHandler,ICellWorkbenchItem,ISaveProvider,StorageCell}`, `appeng.api.upgrades.{IUpgradeInventory,UpgradeInventories,Upgrades}`, `appeng.api.client.StorageCellModels`, `appeng.core.AELog`, `appeng.core.definitions.{AEBlocks,AEItems}`, `appeng.core.localization.{GuiText,Tooltips}`, `appeng.hooks.AEToolItem`, `appeng.items.contents.CellConfig`, `appeng.me.cells.BasicCellHandler`, `appeng.util.{ConfigInventory,InteractionUtil}`, `appeng.util.prioritylist.{FuzzyPriorityList,IPartitionList}`. Verified by reading the `OURS` imports against the `AE2` tree.

---

## 1. Keys — `AEKey` / `AEKeyType` / `KeyCounter`, and the NBT contract  (priority 1)

### 1.1 The registry question — answered: there is none

`AE2:appeng/api/stacks/AEKey.java:43`

    @Nullable
    public static AEKey fromTagGeneric(CompoundTag tag)

Body (`AEKey.java:43-60`), which is the whole serialisation contract:

- `:45` `var channelId = tag.getString("#c");`
- `:46-50` empty `"#c"` gives `AELog.warn("Cannot deserialize generic key from %s because key '#c' is missing.", tag)` and **return null** (it does *not* throw).
- `:53-58` `channel = AEKeyTypes.get(new ResourceLocation(channelId))` inside `catch (IllegalArgumentException | ResourceLocationException e)` gives warn `"Cannot deserialize generic key from %s because channel '%s' is missing."` and **return null** (this is the "mod that owned this channel was uninstalled" path).
- `:59` `return channel.loadKeyFromTag(tag);`

Writer, `AEKey.java:105-110`:

    public final CompoundTag toTagGeneric() {
        var tag = toTag();
        tag.putString("#c", getType().getId().toString());
        return tag;
    }

So a serialised key = **the key's own `toTag()` plus one string member `"#c"` = the AE2 channel id** (`"ae2:i"` for items, `"ae2:f"` for fluids, from `AEKeyType.getId()`).

**What this means for the port (the single biggest simplification):**

* `DISKCellInventory.java:317` becomes `AEKey key = AEKey.fromTagGeneric(tags.getCompound(i));` — drop the first argument entirely.
* `util/StorageManager.java` no longer needs `WeakReference<HolderLookup.Provider>`, `getRegistries()`, the `IllegalStateException("StorageManager was not initialized properly.")` guard, or the "server was already closed" guard **for key serde purposes**. Whether to keep them for other reasons is a design choice; the API no longer forces them.
* **Both return-null paths exist, so `loadCellItems()` must null-check** before `storedAmounts.put(...)`, or a save that references a removed mod / a corrupted entry NPEs the whole cell load. Our current 1.21.1 code (`DISKCellInventory.java:317`) puts the value straight in — flag this while porting.
* `AELog.warn(String, Object...)` is `AE2:appeng/core/AELog.java:140` — signature unchanged.

### 1.2 `AEKeyType` (`AE2:appeng/api/stacks/AEKeyType.java`)

| member | line |
|---|---|
| `public static AEKeyType items()` | `:65` |
| `public static AEKeyType fluids()` | `:82` |
| `public static AEKeyType fromRawId(int)` | `:73` |
| `public abstract AEKey loadKeyFromTag(CompoundTag tag)` | `:134` |
| `public final boolean contains(AEKey key)` | `:147` |
| `public final AEKeyFilter filter()` (field `:50`) | `:164` |
| `getId()` (`ResourceLocation`, e.g. `ae2:i`) | used by `AEKey#toTagGeneric` |

`AEKeyTypes.get(ResourceLocation)` is `AE2:appeng/api/stacks/AEKeyTypes.java:68`; `getAll()` `:82`; `register` `:55`.

### 1.3 Item keys (`AE2:appeng/api/stacks/AEItemKey.java`)

| member | line | note |
|---|---|---|
| `static AEItemKey of(ItemStack stack)` | `:87` | null for an empty stack |
| `boolean matches(AEItemKey)` | `:97` | used by fuzzy matching |
| `static AEItemKey of(ItemLike)` | `:134` | |
| `static AEItemKey of(ItemLike, @Nullable CompoundTag extraTag)` | `:138` | |
| `ItemStack getReadOnlyStack()` | `:159` | |
| `ItemStack toStack()` / `toStack(int count)` | `:175` / `:179` | |
| `Item getItem()` | `:189` | |
| `@Nullable static AEItemKey fromTag(CompoundTag)` | `:194` | resolves `BuiltInRegistries.ITEM.getOptional(new ResourceLocation(tag.getString("id")))`; optional `"tag"` and `"caps"` |
| `CompoundTag toTag()` | `:208` | writes `"id"`, `"tag"`, `"caps"` |
| `Object getFuzzySearchValue()` | `:231` | |
| `int getMaxStackSize()` | `:303` | |

`OURS` only uses `AEItemKey` at `storage/DISKCellInventory.java:23` and `storage/IDISKCellItem.java:10` — both source-compatible.

### 1.4 `KeyCounter` (`AE2:appeng/api/stacks/KeyCounter.java`)

`add(AEKey,long)` `:96`, `remove(AEKey,long)` `:104`, `remove(AEKey)` `:111`, `set(AEKey,long)` `:120`, `get(AEKey)` `:124`, `isEmpty()` `:145`, `size()` `:154`, `iterator()` `:163`, `keySet()` `:219`, `static findFuzzy(...)` `:50`.

### 1.5 `MEStorage` — the storage contract our cell implements (`AE2:appeng/api/storage/MEStorage.java`)

All of these are `default` except `getDescription()`:

    :62  default boolean isPreferredStorageFor(AEKey what, IActionSource source)
    :74  default long insert(AEKey what, long amount, Actionable mode, IActionSource source)
    :86  default long extract(AEKey what, long amount, Actionable mode, IActionSource source)
    :95  default void getAvailableStacks(KeyCounter out)
    :101 Component getDescription();                    // abstract
    :108 default KeyCounter getAvailableStacks()

`OURS storage/DISKCellInventory.java:289` does `out.add(entry.getKey(), entry.getLongValue())` and `:370/:423` implement `insert`/`extract(AEKey,long,Actionable,IActionSource)` — **shape matches 15.4.12 verbatim**, no change.

### 1.6 Net code shape for the persist/load path

Load (replacing `DISKCellInventory.java:305-320`):

    ListTag tags = nbt.getList(KEYS_TAG, Tag.TAG_COMPOUND);
    long[] amounts = nbt.getLongArray(AMOUNTS_TAG);
    if (amounts.length != tags.size()) {
        AELog.warn("Loading storage cell with mismatched amounts/tags: %d != %d",
                amounts.length, tags.size());
    }
    for (int i = 0; i < Math.min(amounts.length, tags.size()); i++) {
        AEKey key = AEKey.fromTagGeneric(tags.getCompound(i));   // 15.4.12: ONE argument
        if (key == null) {
            continue;                                           // NEW: required, see §1.1
        }
        storedAmounts.put(key, amounts[i]);
    }

Save (unchanged apart from there being no registries anywhere):

    ListTag tags = new ListTag();
    for (var entry : sortedEntries) {
        tags.add(entry.getKey().toTagGeneric());                // writes "#c" itself
    }

---

## 2. Storage cells — `StorageCell` / `ICellHandler` / `ISaveProvider` / `ICellWorkbenchItem`  (priority 2)

### 2.1 Interfaces

Package `appeng/api/storage/cells/` contains exactly: `CellState`, `IBasicCellItem`, `ICellGuiHandler`, `ICellHandler`, `ICellWorkbenchItem`, `ISaveProvider`, `StorageCell`.

**`StorageCell`** (`AE2:appeng/api/storage/cells/StorageCell.java:31`) — `public interface StorageCell extends MEStorage`:

    CellState getStatus();
    double getIdleDrain();
    default boolean canFitInsideCell() { return true; }   // :39
    void persist();

**`ICellHandler`** (`ICellHandler.java:36`) — two methods, `getCellInventory` is `@Nullable`:

    boolean isCell(ItemStack is);
    @Nullable StorageCell getCellInventory(ItemStack is, @Nullable ISaveProvider host);

Its javadoc states the persistence responsibility explicitly: "anytime the contents of your storage cell changes it should use this [ISaveProvider] to request a save … this value can be null. If provided, the host is responsible for persisting the cell content."

**`ISaveProvider`** (`ISaveProvider.java:30`) — functional interface, single `void saveChanges();`.

**`ICellWorkbenchItem`** (`ICellWorkbenchItem.java:32`) — `extends IUpgradeableItem`:

    :40 default boolean isEditable(ItemStack is)
    :51 default ConfigInventory getConfigInventory(ItemStack is) { return ConfigInventory.EMPTY_TYPES; }
    :63 void setFuzzyMode(ItemStack is, FuzzyMode fzMode);
        FuzzyMode getFuzzyMode(ItemStack is);            // abstract on the interface

**`IUpgradeableItem`** supplies `default IUpgradeInventory getUpgrades(ItemStack)` giving `EmptyUpgradeInventory.INSTANCE`.

**`IBasicCellItem`** (`IBasicCellItem.java`) — `extends ICellWorkbenchItem`, only relevant if you switch `IDISKCellItem` from `ICellWorkbenchItem` to `IBasicCellItem` (upstream 1.20.1 keeps `ICellWorkbenchItem`):

    :50 getKeyType(): AEKeyType     :60 getBytes(ItemStack)      :68 getBytesPerType(ItemStack)
    :76 getTotalTypes(ItemStack)    :86 default isBlackListed(ItemStack, AEKey)
    :97 default storableInStorageCell()   :107 default isStorageCell(ItemStack)
    :114 getIdleDrain()
    :119 default addCellInformationToTooltip(ItemStack, List<Component>)  // -> BasicCellHandler.INSTANCE
    :127 getCellTooltipImage(ItemStack) -> Optional<TooltipComponent>

### 2.2 `CellState` (`CellState.java`)

    :33 ABSENT(0)   :38 EMPTY(0x00FF00)   :43 NOT_EMPTY(0x00AAFF)
    :48 TYPES_FULL(0xFFAA00)   :53 FULL(0xFF0000)   :67 public int getStateColor()

`OURS item/DISKDrive.java:182` calls `cellStatus.getStateColor()` — exists, unchanged.

### 2.3 `StorageCells` registration (`AE2:appeng/api/storage/StorageCells.java`)

**Package note:** `StorageCells` is in `appeng.api.storage`, **not** `appeng.api.storage.cells`. Our import (`AE2Things.java:31`) is already right.

    :46 public final class StorageCells
    :48 handlers (List<ICellHandler>)     :49 guiHandlers (List<ICellGuiHandler>)
    :62 public static synchronized void addCellHandler(ICellHandler handler)
    :75 public static synchronized void addCellGuiHandler(ICellGuiHandler handler)   // body: guiHandlers.add(handler)
    :86 isCellHandled(ItemStack)          :105 getHandler(ItemStack)
    :124 getGuiHandler(ItemStack)   // first handler with isSpecializedFor()==true, else the FIRST registered one
    :147 @Nullable public static synchronized StorageCell getCellInventory(ItemStack is, @Nullable ISaveProvider host)

Registration must happen **after** AE2 has registered its own handlers, i.e. inside `FMLCommonSetupEvent` (both lists are plain `List`s consulted in registration order; `:124` uses "first registered wins" only as the *fallback*, so a specialized GUI handler is order-insensitive; our cell handler checks `instanceof DISKDrive`, so ordering is safe either way).

`UP:` does exactly this in `commonSetup`:

    StorageCells.addCellHandler(DISKCellHandler.INSTANCE);
    StorageCells.addCellGuiHandler(new DISKItemCellGuiHandler());
    event.enqueueWork(() -> { /* Upgrades.add(...) for every disk */ });

### 2.4 `ICellGuiHandler` + the MEStorageMenu opening path

**Our 1.21.1 tree has no GUI handler at all** (this fork deleted it). Upstream 1.20.1 ships one, and it is the documented AE2 extension point for "right-click the terminal side of an ME Chest with this cell" (`ICellGuiHandler` javadoc).

`AE2:appeng/api/storage/cells/ICellGuiHandler.java`:

    boolean isSpecializedFor(ItemStack cell);
    void openChestGui(Player player, IChestOrDrive chest, ICellHandler cellHandler, ItemStack cell);

`AE2:appeng/api/implementations/blockentities/IChestOrDrive.java:35` — `extends IActionHost`.

`UP:gui/cell/DISKItemCellGuiHandler.java` (whole class, verbatim shape):

    public final class DISKItemCellGuiHandler implements ICellGuiHandler {
        @Override
        public boolean isSpecializedFor(final ItemStack cell) {
            return cell.getItem() instanceof IDISKCellItem diskCell
                    && diskCell.getKeyType() == AEKeyType.items();
        }

        @Override
        public void openChestGui(final Player player, final IChestOrDrive chest,
                                final ICellHandler cellHandler, final ItemStack cell) {
            MenuOpener.open(MEStorageMenu.TYPE, player,
                    MenuLocators.forBlockEntity((BlockEntity) chest));
        }
    }

The opening path, all verified in 15.4.12:

| piece | evidence |
|---|---|
| `public final class MenuOpener` | `AE2:appeng/menu/MenuOpener.java:40` |
| `public static boolean open(MenuType<?>, Player, MenuLocator)` | `:57` (delegates to the 4-arg `:59`); `:56` `returnTo(...)` for sub-menus |
| server-side only | `:60` `Preconditions.checkArgument(!player.level().isClientSide(), "Menus must be opened on the server.")` |
| `public static MenuLocator forBlockEntity(BlockEntity te)` | `AE2:appeng/menu/locator/MenuLocators.java:69` — throws if `te.getLevel() == null` |
| `forPart(AEBasePart)` `:76`, `forItemUseContext(UseOnContext)` `:85`, `forHand(Player, InteractionHand)` `:94`, `forInventorySlot(int)` `:99` | same file |
| `public static final MenuType<MEStorageMenu> TYPE` (menu id `item_terminal`) | `AE2:appeng/menu/me/common/MEStorageMenu.java:97` (class `:94 extends AEBaseMenu`) |
| portable/wireless variants | `:101` `PORTABLE_ITEM_CELL_TYPE`, `:104` `PORTABLE_FLUID_CELL_TYPE`, `:108` `WIRELESS_TYPE` |
| menu ctor | `:156` `MEStorageMenu(MenuType<?>, int, Inventory, ITerminalHost)` |

So for the port: if the 1.20.1 build wants the ME-Chest GUI, add `gui/cell/DISKItemCellGuiHandler.java` with the ~20 lines above and register it in `commonSetup`. It needs **no** menu/container registration of its own — `MEStorageMenu.TYPE` is AE2's.

### 2.5 Config / partition / fuzzy (how the disk's filter state persists)

* `AE2:appeng/items/contents/CellConfig.java` — **the only creation entry points**:

      :34 public static ConfigInventory create(@Nullable AEKeyFilter filter, ItemStack is, int size)  // size must be 1..63 (:35-36)
      :43 public static ConfigInventory create(@Nullable AEKeyFilter filter, ItemStack is)           // size 63
      :50 public static ConfigInventory create(ItemStack is)

  **There is no `Set<AEKeyType>` overload** (that is the AE2-19 signature our `item/DISKDrive.java:76` calls). Port: `CellConfig.create(getKeyType().filter(), is)`.
  Storage: child tag `"list"` of `stack.getOrCreateTag()` (`:65` `inv.readFromChildTag(stack.getOrCreateTag(), "list")`, `:71` the write side).

* `AE2:appeng/api/storage/AEKeyFilter.java` — `@FunctionalInterface boolean matches(AEKey what)` + `static AEKeyFilter none()`.
* `AE2:appeng/util/ConfigInventory.java`: `EMPTY_TYPES` `:39`, `configTypes(int, Runnable)` `:52`, `configTypes(AEKeyFilter, int, Runnable)` `:60`, `configStacks` `:68`, `getStack(int)` `:90`, `getKey(int)` `:102`, `public Set<AEKey> keySet()` `:115`, `setStack(int, @Nullable GenericStack)` `:127`, `getMaxAmount(AEKey)` `:152`, `createMenuWrapper()` `:159`, `addFilter(ItemLike|Fluid|AEKey)` `:163/:168/:173`.
* Partition list (ours `storage/DISKCellInventory.java:73-79`): `AE2:appeng/util/prioritylist/IPartitionList.java:56 static builder()` giving `Builder.add(@Nullable AEKey)` `:69`, `addAll(Iterable<AEKey>)` `:75`, `fuzzyMode(FuzzyMode)` `:81`, `build()` `:85`. `AE2:appeng/util/prioritylist/FuzzyPriorityList.java:30` ctor is `FuzzyPriorityList(KeyCounter, FuzzyMode)` — the fuzzy variant is built from a **KeyCounter**, not from the config's raw `Set`.
* Fuzzy mode persistence — **the 15.4.12 idiom is plain NBT, mirror it exactly** (`AE2:appeng/items/storage/BasicStorageCell.java:133-147`):

      public FuzzyMode getFuzzyMode(ItemStack is) {
          String fz = is.getOrCreateTag().getString("FuzzyMode");
          if (fz.isEmpty()) return FuzzyMode.IGNORE_ALL;
          try { return FuzzyMode.valueOf(fz); } catch (Throwable t) { return FuzzyMode.IGNORE_ALL; }
      }
      public void setFuzzyMode(ItemStack is, FuzzyMode fzMode) {
          is.getOrCreateTag().putString("FuzzyMode", fzMode.name());
      }

  `UP:item/DISKDrive.java:75-90` is byte-for-byte the same idiom, so the tag name `"FuzzyMode"` is proven in the shipped 1.20.1 build. Keep it identical — our 1.20.1 saves must interoperate with upstream 1.2.1.
* `FuzzyMode` itself (`AE2:appeng/api/config/FuzzyMode.java`): `IGNORE_ALL(-1)`, `PERCENT_99(0)`, `PERCENT_75(25)`, `PERCENT_50(50)`, `PERCENT_25(75)`; `calculateBreakPoint(int)`; public `float percentage`, `float breakPoint`. **No Codec and no StreamCodec** — see §3.2 for why that matters.

### 2.6 Support classes we already use

| class | evidence |
|---|---|
| `BasicCellHandler` | `AE2:appeng/me/cells/BasicCellHandler.java:44 INSTANCE`, `:47 isCell`, `:52 BasicCellInventory getCellInventory(ItemStack, ISaveProvider)`, `:56 addCellInformationToTooltip`, `:79 getTooltipImage` |
| `UpgradeInventories` | `empty()`, `forMachine(ItemLike,int,MachineUpgradesChanged)`, `forItem(ItemStack,int)`, `forItem(ItemStack,int,ItemUpgradesChanged)` |
| `Upgrades` | `:42 add(ItemLike upgradeCard, ItemLike upgradableObject, int maxSupported)`; `:54 add(..., int, String translationKey)` |
| `Tooltips.bytesUsed(long,long)` | `AE2:appeng/core/localization/Tooltips.java:427` |
| `GuiText` constants | `Excluded:93`, `Fuzzy:103`, `Included:115`, `Partitioned:161`, `Precise:171` |
| `AEItems` defs | `FUZZY_CARD:218`, `INVERTER_CARD:220`, `CELL_COMPONENT_1K:227`, `4K:228`, `16K:229`, `64K:230`, `256K:231` (all `ItemDefinition`, so `.asItem()`) |
| `InteractionUtil` | `:75 isInAlternateUseMode(Player)`, `:52 canWrenchDisassemble`, `:60 canWrenchRotate`, `:85/:90 getPlayerRay`, `:112 rayTrace` |
| `AEToolItem` | marker interface at `appeng/hooks/AEToolItem.java` ("On Forge, we use onItemUseFirst to the same effect") |
| `StorageCellModels` | `AE2:appeng/api/client/StorageCellModels.java` — `registerModel(ItemLike, ResourceLocation)`, `model(ItemLike)`, `models()`, `getDefaultModel()` = `ae2:block/drive/drive_cell`; throws on double registration |
| `IncludeExclude` / `Actionable` | `AE2:appeng/api/config/IncludeExclude.java:27 WHITELIST` (`BLACKLIST` is the other constant our code uses); `Actionable.MODULATE:32`, `SIMULATE:37` |

---

## 3. NeoForge to Forge 1.20.1 replacements  (priority 3)

Everything here is from `JAR:` javap unless marked otherwise.

### 3.1 `DeferredRegister` / `RegistryObject`

    // net.minecraftforge.registries.DeferredRegister
    static <B> DeferredRegister<B> create(IForgeRegistry<B> registry, String modId)
    static <B> DeferredRegister<B> create(ResourceKey<? extends Registry<B>> key, String modId)
    static <B> DeferredRegister<B> create(ResourceLocation registryName, String modId)
    public <I extends T> RegistryObject<I> register(String name, Supplier<? extends I> sup)
    public void register(IEventBus bus)
    public ResourceKey<? extends Registry<T>> getRegistryKey()
    public TagKey<T> createTagKey(String name)
    public Collection<RegistryObject<T>> getEntries()

Inner classes present in 47.4.10: **only** `DeferredRegister$EventDispatcher` and `DeferredRegister$RegistryHolder`. So there is no `.Items`, no `.Blocks`, no `.DataComponents`, no `DeferredItem`/`DeferredBlock` — all NeoForge-only. Items become `RegistryObject<Item>`:

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);          // UP:AE2Things.java
    public static final RegistryObject<Item> DISK_HOUSING = ITEMS.register("disk_housing", ...);

`RegistryObject<T>`: `T get()`, `ResourceLocation getId()`, `ResourceKey<T> getKey()`, `Stream<RegistryObject<T>> stream()`, `boolean isPresent()`, `ifPresent`, `map`, `flatMap`, `lazyMap`, `orElse`, `getHolder`.

Consequences for `item/AETItems.java:19-30`: `DeferredItem<Item>` becomes `RegistryObject<Item>`; the core item lookup `AEItems.CELL_COMPONENT_1K.asItem()` still works (that is AE2's own `ItemDefinition`, not Forge's). `AETItems.DISK_DRIVES` becomes `List<RegistryObject<Item>>` and consumers need `.get()` (upstream writes `Upgrades.add(..., cell.get(), ...)`).

`ForgeRegistries.ITEMS` is `IForgeRegistry<Item>`, `ForgeRegistries.BLOCKS` is `IForgeRegistry<Block>`.

### 3.2 Data components become item NBT (the biggest rewrite in the item layer)

Our three components (`OURS AE2Things.java:42-54`):

| component | value type | codec used | 1.20.1 replacement |
|---|---|---|---|
| `DATA_DISK_ID` | `UUID` | `UUIDUtil.CODEC` / `UUIDUtil.STREAM_CODEC` | NBT uuid at key `"diskuuid"`: `stack.getOrCreateTag().getUUID(...)` / `putUUID(...)` / `hasUUID(...)` |
| `DATA_DISK_ITEM_COUNT` | `Long` | `Codec.LONG` / `ByteBufCodecs.VAR_LONG` | NBT long at key `"ic"`: `getLong` / `putLong` |
| `DATA_FUZZY_MODE` | `FuzzyMode` | `FuzzyMode.CODEC` / `FuzzyMode.STREAM_CODEC` | **cannot be a component even in principle**: `FuzzyMode` has no codec (§2.5), hence NBT string `"FuzzyMode"` |

`"diskuuid"` and `"ic"` are not inventions — they are what the shipped 1.20.1 build writes:

* `UP:util/Constants.java` — `DISKUUID = "diskuuid"`, `MANAGER_NAME = "disk_manager"`, `DISKDATA = "diskdata"`, `DISKLIST = "disklist"`.
  **Our `OURS util/Constants.java` (9 lines) has only the last three — no `DISKUUID`.** Add it.
* `UP:storage/DISKCellInventory.java:41` — `public static final String ITEM_COUNT_TAG = "ic";` and `:412` `i.getTag().getLong(ITEM_COUNT_TAG)`; `:192` `i.getOrCreateTag().putLong(ITEM_COUNT_TAG, itemCount)`; `:355` `i.getOrCreateTag().putUUID(Constants.DISKUUID, UUID.randomUUID())`; `:231` `i.getOrCreateTag().getUUID(Constants.DISKUUID)`.

Call-shape mapping for the places our 1.21.1 code touches components:

| OURS line | now | becomes |
|---|---|---|
| `storage/DISKCellInventory.java:173-174` | `i.remove(DATA_DISK_ID); i.remove(DATA_DISK_ITEM_COUNT);` | `i.removeTagKey(Constants.DISKUUID); i.removeTagKey(ITEM_COUNT_TAG);` (upstream does this at `:160-161`) |
| `:212` | `i.getOrDefault(DATA_DISK_ITEM_COUNT, 0L)` | `i.getOrCreateTag().getLong(ITEM_COUNT_TAG)` (absent reads as 0 anyway) |
| `:214` | `i.set(DATA_DISK_ITEM_COUNT, itemCount)` | `i.getOrCreateTag().putLong(ITEM_COUNT_TAG, itemCount)` |
| `:244` | `i.has(DATA_DISK_ID)` | `i.hasTag() && i.getTag().hasUUID(Constants.DISKUUID)` |
| `:256` | `i.get(DATA_DISK_ID)` | `i.getTag().getUUID(Constants.DISKUUID)` (guard with `hasTag()`; `getUUID` returns null when absent) |
| `item/DISKDrive.java:47-48` | `new Properties()...component(DATA_DISK_ITEM_COUNT, 0L).component(DATA_FUZZY_MODE, FuzzyMode.IGNORE_ALL)` | `new Item.Properties().stacksTo(1).fireResistant()` only — the components were *defaults*, and with NBT the "absent reads as default" idiom replaces them (`getLong` gives 0, empty `getString("FuzzyMode")` gives `IGNORE_ALL`) |
| `item/DISKDrive.java:85/:94/:97` | `newStack.set(...)` / `remove(...)` | the `putUUID`/`putLong`/`removeTagKey` equivalents |
| `item/DISKDrive.java:108/:113` | `is.getOrDefault(DATA_FUZZY_MODE)` / `is.set(DATA_FUZZY_MODE, ...)` | the `getFuzzyMode`/`setFuzzyMode` pair from §2.5 |

`ItemStack` API available on 1.20.1 (javap): `hasTag()`, `getTag()`, `getOrCreateTag()`, `getOrCreateTagElement(String)`, `getTagElement(String)`, `removeTagKey(String)`, `setTag(CompoundTag)`, `setCount(int)`, `getMaxStackSize()`, `copy()`, **`copyWithCount(int)`**. There is **no** `getComponents()` / `set(DataComponentType, ...)`, and `net.minecraft.core.component.*` is 1.20.5+ (only `dev/GoldenSampleDump.java:28` references `DataComponents` — see §3.8/§5.9).

This also keeps the save format identical to upstream 1.2.1, which is the compatibility requirement anyway.

### 3.3 `SavedData` / `StorageManager`

`JAR:` `net.minecraft.world.level.saveddata.SavedData`:

    public abstract CompoundTag save(CompoundTag tag)          // ONE argument
    public void setDirty() / setDirty(boolean) / boolean isDirty()

`net/minecraft/world/level/saveddata/SavedData$Factory.class` is **absent** from 1.20.1 (zip listing check = False) — `SavedData.Factory` is 1.20.2+/NeoForge.

`JAR:` `net.minecraft.world.level.storage.DimensionDataStorage`:

    public <T extends SavedData> T computeIfAbsent(Function<CompoundTag, T> load, Supplier<T> create, String name)
    public <T extends SavedData> T get(Function<CompoundTag, T> load, String name)

No `Level` and no `DataFixTypes` arguments. So `OURS util/StorageManager.java` changes as:

    // 1.21.1
    public static final Factory<StorageManager> FACTORY = new Factory<>(StorageManager::new, StorageManager::readNbt);
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) { ... }
    public static StorageManager readNbt(CompoundTag nbt, HolderLookup.Provider registries) { ... }
    world.getDataStorage().computeIfAbsent(FACTORY, Constants.MANAGER_NAME);

    // 1.20.1 (this is literally UP:util/StorageManager.java)
    public CompoundTag save(CompoundTag nbt) { ... }
    public static StorageManager readNbt(CompoundTag nbt) { ... }
    ServerLevel world = server.getLevel(ServerLevel.OVERWORLD);
    return world.getDataStorage().computeIfAbsent(StorageManager::readNbt, StorageManager::new, Constants.MANAGER_NAME);

Notes:
* The `DataStorage` *name* (`Constants.MANAGER_NAME = "disk_manager"`) and the `diskdata`/`disklist` keys must not change — that is saved-world compatibility.
* `save()` writing a `ListTag` of `stackKeys` plus a `long[]` `stackAmounts` stays structurally identical; only the registry parameter goes away.
* The `WeakReference<HolderLookup.Provider>` machinery exists only to feed `AEKey.fromTagGeneric(registries, ...)`. Once §1.1 lands it has no consumer — **delete it** rather than porting it (keeping it turns a compile fix into a lifetime bug).
* `MinecraftServer#registryAccess()` still exists on 1.20.1 (`RegistryAccess$Frozen registryAccess()`), so registries remain obtainable for anything else that needs them; key serde just does not.

### 3.4 Mod entrypoint + events

FML classes live outside the merged jar. Verified against `C:/Users/xmace/.gradle/caches/modules-2/files-2.1/net.minecraftforge/javafmllanguage/1.20.1-47.4.10/ccc533c7dd67e0983217197d7549c8b49e7ab453/javafmllanguage-1.20.1-47.4.10.jar`:

    net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
        public static FMLJavaModLoadingContext get()
        public IEventBus getModEventBus()
    net.minecraftforge.fml.common.Mod$EventBusSubscriber      // annotation: Dist[] value(), Bus bus()

`FMLCommonSetupEvent` is in `forge-1.20.1-47.4.10-universal.jar` and extends `ParallelDispatchEvent`, which provides `CompletableFuture<Void> enqueueWork(Runnable)` and `<T> CompletableFuture<T> enqueueWork(Supplier<T>)` — so our `event.enqueueWork(...)` call (`OURS AE2Things.java:85`) ports unchanged.

Event-bus map:

| 1.21.1 (NeoForge) | 1.20.1 (Forge) |
|---|---|
| `@Mod(value = MOD_ID)` + `AE2Things(IEventBus modEventBus)` (`AE2Things.java:66`) | `@Mod(MOD_ID)` on a **no-arg** ctor; `var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();` |
| `NeoForge.EVENT_BUS.addListener(...)` (`:75-77`) | `MinecraftForge.EVENT_BUS.addListener(...)` |
| `net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent` | `net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent` (same simple name) |
| `BuildCreativeModeTabContentsEvent` (`net.neoforged.neoforge.event...`) | `net.minecraftforge.event.BuildCreativeModeTabContentsEvent`: `getTabKey() -> ResourceKey<CreativeModeTab>`, `accept(ItemStack, TabVisibility)`, `accept(Supplier<? extends ItemLike>, TabVisibility)`, `accept(Supplier<? extends ItemLike>)`. It `implements IModBusEvent`, so register it on the **mod** bus. Upstream registers it with `modEventBus.addListener(AE2Things::addContentsToCreativeTab)` and guards `event.getTabKey().equals(AECreativeTabIds.MAIN)`. |
| `RegisterColorHandlersEvent` (`net.neoforged.neoforge.client.event...`) | `net.minecraftforge.client.event.RegisterColorHandlersEvent$Item#register(ItemColor, ItemLike...)` — same shape |
| `RegisterCommandsEvent` | `net.minecraftforge.event.RegisterCommandsEvent#getDispatcher() -> CommandDispatcher<CommandSourceStack>` |
| `ServerStartedEvent` / `ServerStoppedEvent` | `net.minecraftforge.event.server.ServerStartedEvent` / `ServerStoppedEvent` (extend `ServerLifecycleEvent`) |
| `@EventBusSubscriber` (`net.neoforged.fml.common`) | `net.minecraftforge.fml.common.Mod$EventBusSubscriber` |
| `@Mod(value = MOD_ID, dist = Dist.CLIENT)` client class | `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> AE2ThingsClient::init)` (upstream's shape) — keep it, so no client class is touched on a dedicated server |

`AECreativeTabIds` is unchanged: `AE2:appeng/api/ids/AECreativeTabIds.java:12` class, `:16 public static final ResourceKey<CreativeModeTab> MAIN = create("main")`.

### 3.5 Client

* `appendHoverText`: 1.20.1 signature is `public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag)` — `TooltipContext` (1.20.5+) does not exist. `OURS item/DISKDrive.java:169` must change the second parameter.
* `use`: `public InteractionResultHolder<ItemStack> use(Level, Player, InteractionHand)` — unchanged from our code.
* Color handler: `RegisterColorHandlersEvent.Item.register(ItemColor, ItemLike...)`; ours (`client/AE2ThingsClient.java`) passes `DISKDrive::getColor` with `(ItemStack, int)` — `ItemColor` is `@FunctionalInterface int getColor(ItemStack, int)`, unchanged. Register it on the **mod** bus (it is a mod-bus event), not on `MinecraftForge.EVENT_BUS`.
* `AE2ThingsClient` becomes a plain `init()` called via `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> AE2ThingsClient::init)`; the `@Mod(dist = Dist.CLIENT)` value-object pattern is NeoForge-only.

### 3.6 `mods.toml`, mixin config, refmap

Resource file name changes: `META-INF/neoforge.mods.toml` becomes **`META-INF/mods.toml`** (`UP:` proves the 1.20.1 layout):

    modLoader="javafml"
    loaderVersion="[40,)"
    license="MIT"

    [[mods]]
    modId="ae2things"
    version="${version}"
    displayName="AE2 Things"
    logoFile="icon.png"

    [[dependencies.ae2things]]
    modId="forge"       versionRange="[43.1.24,)"        ordering="NONE"   side="BOTH"
    [[dependencies.ae2things]]
    modId="minecraft"   versionRange="[1.20.1,)"
    [[dependencies.ae2things]]
    modId="ae2"         versionRange="[15.0.0, 16.0.0)"  ordering="AFTER"  side="BOTH"

For our port the floors become **Forge 47.1.3** and **AE2 15.4.12** (AE2 itself builds against 47.1.47), so e.g. `versionRange="[47.1.3,)"` and `versionRange="[15.4.12, 16.0.0)"`.

Mixins — a real porting hazard, because Forge 1.20.1 production runs **SRG** names while our 1.21.1 mixin config has no refmap and `compatibilityLevel JAVA_21`:

    {
      "required": false,
      "minVersion": "0.8",
      "package": "io.github.projectet.ae2things.mixin",
      "compatibilityLevel": "JAVA_8",
      "refmap": "mixins.ae2things.refmap.json",
      "mixins": ["CursedInternalSlotMixin"],
      "injectors": { "defaultRequire": 1 }
    }

`UP:build.gradle:79-83`:

    mixin {
        add sourceSets.main, 'mixins.ae2things.refmap.json'
        config 'mixins.ae2things.json'
    }

plus a jar-manifest entry `"MixinConfigs": "mixins.ae2things.json"` (`UP:build.gradle:158`). Our repo's `build.gradle` has **no** `mixin {}` block and **no** `MixinConfigs` entry at all (1.21.1 NeoForge bundles sponge-mixin and does not need the refmap) — both must be added, and the build plugin must actually emit the refmap.

### 3.7 Data generation

1.20.1 data-gen (`SRC:`/`JAR:`):

* `net.minecraft.data.recipes.RecipeProvider` is abstract, with `protected abstract void buildRecipes(Consumer<FinishedRecipe> recipeSaver)`.
* **`net.minecraft.data.recipes.RecipeOutput` does not exist in 1.20.1** (our `data/CraftingRecipeProvider.java:12` imports it and passes it to `ShapedRecipeBuilder#save`).
* `ShapedRecipeBuilder.shaped(RecipeCategory, ItemLike, int)`, `define(char, TagKey<Item>)`, `define(char, ItemLike)`, `define(char, Ingredient)`, `unlockedBy(String, CriterionTriggerInstance)`, `save(Consumer<FinishedRecipe>, ResourceLocation)`; `ShapelessRecipeBuilder` likewise.
* `DataProvider.run(CachedOutput) -> CompletableFuture<?>`.
* `net.minecraftforge.data.event.GatherDataEvent` (forge universal jar): `getGenerator() -> DataGenerator`, `getLookupProvider() -> CompletableFuture<HolderLookup$Provider>`, `includeServer()`, `includeClient()`. Our `data/AE2ThingsDataGenerator.java` already subscribes to `GatherDataEvent` — same simple name, but the **package differs** on NeoForge (`net.neoforged.neoforge.data.event`), so the import changes.
* `net.minecraftforge.common.Tags.Items.GLASS` exists on 1.20.1 (`Tags$Items` has 182 `TagKey<Item>` fields including `GLASS`, `GLASS_PANES`, `STAINED_GLASS...`), so `data/CraftingRecipeProvider.java` can keep using a glass tag — but the import moves from `net.neoforged.neoforge.common.Tags` to `net.minecraftforge.common.Tags`.

**Datapack directory rename (silent-failure class of bug):**

| | recipes | advancements |
|---|---|---|
| 1.21.1 (`OURS src/generated/resources/data/ae2things/`) | `recipe/` (singular) | `advancement/` (singular) |
| 1.20.1 (`UP src/main/resources/data/ae2things/`) | `recipes/` (plural) | `advancements/` (plural) |

1.20.1 silently ignores everything under `recipe/`. Regenerate with `runData` on the 1.20.1 toolchain rather than renaming by hand — advancement criteria and `parent` paths also embed these directory names (`OURS` has `data/ae2things/advancement/recipes/misc/disk_drive_1k.json`, i.e. the literal `recipes` segment inside the advancement path, which happens to already be plural).

### 3.8 Resources: special cell models + atlas

Our 1.21.1 disk textures live at `assets/ae2things/textures/block/drive/drive_cells.png` (under `block/`), and `item/AETItems.java:39-43` registers models via `id("model/drive/cells/disk_1k")` … `disk_256k`, then `StorageCellModels.registerModel(item.get(), MODEL_*)` in `commonSetup`.

What 15.4.12 does with those models (so we know nothing else is needed):

* `AE2:appeng/client/render/model/DriveModel.java:57-64` bakes **every** `StorageCellModels.models()` entry (`baker.bake(entry.getValue(), modelTransform)`).
* `DriveModel.getDependencies()` (`:71-73`) returns `StorageCellModels.getDefaultModel()` + `InitStorageCells.getModels()` + `StorageCellModels.models().values()` — so the special models are pulled into the model graph through the dependency set. **No mod-side `ModelLoader.addSpecialModel` / `ModelEvent.RegisterAdditional` call is required** in 15.4.12 (AE2's own only `RegisterAdditional` calls are `AppEngClient.java:284` -> `InitAdditionalModels.init`, for parts/renderers).
* `StorageCellModels.registerModel`'s javadoc still mentions `ModelLoader#addSpecialModel` from an older era; treat the javadoc as stale and follow the code above.

Atlas: 1.20.1 `SpriteResourceLoader.load(ResourceManager, ResourceLocation)` iterates `resourceManager.getResourceStack(...)` and concatenates every pack's `assets/minecraft/atlases/blocks.json`, so a mod-provided partial atlas **merges** with vanilla's. Vanilla's `blocks.json` (client-extra jar) only declares directory sources `block/` and `item/`.

So there are two valid layouts, pick one deliberately:

1. **Textures under `textures/block/`** (our current layout) — stitched by vanilla's directory source, **no extra file needed**; the model JSONs must then reference `ae2things:block/drive/drive_cells`.
2. **Textures under `textures/model/drive/cells/`** (upstream 1.20.1 layout) — requires `assets/minecraft/atlases/blocks.json` in *our* jar listing each one as `{"type": "single", "resource": "ae2things:model/drive/cells/disk_1k"}` (upstream ships exactly 5 such entries).

Staying with (1) is cheaper — but only if all 5 model JSONs' `textures` entries and `StorageCellModels` model ids use the `block/` prefix; verify each one.

---

## 4. The mixin — `AbstractContainerMenu#doClick` on 1.20.1  (priority 4)

**Verdict: the injection target and the `@Slice` still match, and the injection lands exactly inside the `ClickType.CLONE` branch. `ItemStack#copyWithCount(int)` exists in 1.20.1.**

### 4.1 Source evidence (`SRC:net/minecraft/world/inventory/AbstractContainerMenu.java`)

`doClick` spans lines **328-526**. `Slot.hasItem()` call sites inside `doClick`, in order:

| ordinal | line | context |
|---|---|---|
| 0 | 371 | early "slot has item" bookkeeping |
| **1** | **495** | inside `else if (clickType == ClickType.CLONE && player.getAbilities().instabuild && this.getCarried().isEmpty() && slotId >= 0)` (branch opens at 493) |
| 2 | 507 | |
| 3 | 514 | |

`ItemStack.copyWithCount` occurrences inside `doClick`:

| ordinal | line | code |
|---|---|---|
| 0 | 375 | |
| **1** | **497** | `this.setCarried(itemstack5.copyWithCount(itemstack5.getMaxStackSize()));` |

So `slice = @Slice(from = @At(INVOKE, target = "…/Slot.hasItem()Z", ordinal = 1))` plus `@At(INVOKE, target = "…/ItemStack.copyWithCount (I)Lnet/minecraft/world/item/ItemStack;")` resolves to "immediately before the single `copyWithCount` inside the CLONE branch" — exactly what our mixin (`OURS mixin/CursedInternalSlotMixin.java:20-37`) and the shipped upstream 1.20.1 mixin (`UP:mixin/CursedInternalSlotMixin.java:32`) both assume.

### 4.2 Bytecode evidence (`JAR:`, `javap -p -c net.minecraft.world.inventory.AbstractContainerMenu`)

`doClick(int,int,ClickType,Player)` contains **exactly four** `invokevirtual net/minecraft/world/inventory/Slot.hasItem:()Z` at bytecode offsets **430, 1428, 1576, 1681** (ordinals 0,1,2,3), and **two** `invokevirtual net/minecraft/world/item/ItemStack.copyWithCount:(I)Lnet/minecraft/world/item/ItemStack;` at **506** and **1449**.

Between offset 1428 (hasItem ordinal 1) and 1449 (copyWithCount):

    1428: invokevirtual Slot.hasItem:()Z
    ...   ifeq 1455
          aload 6
          invokevirtual Slot.getItem:()Lnet/minecraft/world/item/ItemStack;
          astore 7
          aload_0
          aload 7
          aload 7
          invokevirtual ItemStack.getMaxStackSize:()I
    1449: invokevirtual ItemStack.copyWithCount:(I)Lnet/minecraft/world/item/ItemStack;

So `@Inject(@At(INVOKE, target = ItemStack.copyWithCount, slice = @Slice(from = @At(INVOKE, target = Slot.hasItem, ordinal = 1))))` injects at 1449 with locals `[6] = Slot` and `[7] = that slot's ItemStack` in scope. Our mixin body only reads `slots.get(slotIndex)` and calls `setCarried`, so it needs no locals — it works as-is.

`ItemStack.copyWithCount` exists on 1.20.1: javap prints `public net.minecraft.world.item.ItemStack copyWithCount(int)`.

`doClick` is `private` on 1.20.1 (`private void doClick(int, int, ClickType, Player)`); mixing into private methods is fine. `setCarried(ItemStack)` is `public`, so the `@Shadow` in our mixin stays valid.

### 4.3 What still must change

* Our handler signature `doClick(int slotIndex, int button, ClickType mode, Player player, CallbackInfo ci)` does not change — only the `target` strings and the slice matter.
* **Refmap/remap**: the `target` strings name vanilla members, so the annotation processor remaps them to SRG and writes the refmap. Without §3.6's `mixin{}` + `MixinConfigs` + `"refmap"`, the mixin applies in dev (named) and fails in production (SRG). That is the only real 1.20.1-specific failure mode here.
* Verified on **Forge 47.4.10** artifacts while our floor is 47.1.3 (AE2 builds against 47.1.47). `AbstractContainerMenu` is vanilla 1.20.1 code; if you pin a 47.1.x merged jar, re-run the same check in 30 seconds:

      "C:/Program Files/Zulu/zulu-21/bin/javap.exe" -p -c -cp <47.1.x-merged.jar> net.minecraft.world.inventory.AbstractContainerMenu | grep -n "Slot.hasItem|copyWithCount"

  Expected: 4 x `Slot.hasItem:()Z`, 2 x `copyWithCount:(I)…`, ordinal-1 sitting immediately before the 2nd `copyWithCount`.

---

## 5. Concrete migration checklist (ordered by risk)

1. `util/Constants.java` — add `DISKUUID = "diskuuid"`; keep `MANAGER_NAME`/`DISKDATA`/`DISKLIST` byte-identical.
2. `storage/DISKCellInventory.java` — component calls become NBT (`diskuuid`, `ic`); `AEKey.fromTagGeneric(tags.getCompound(i))` **with a null check**; `CellConfig.create(getKeyType().filter(), is)` in `item/DISKDrive.java:76`; fuzzy mode through the `"FuzzyMode"` string.
3. `util/StorageManager.java` — `save(CompoundTag)` 1-arg, `readNbt(CompoundTag)`, `computeIfAbsent(StorageManager::readNbt, StorageManager::new, name)` on `server.getLevel(OVERWORLD).getDataStorage()`; delete the `HolderLookup.Provider` machinery.
4. `AE2Things.java` — `DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)`, no-arg ctor + `FMLJavaModLoadingContext`, `MinecraftForge.EVENT_BUS`, `BuildCreativeModeTabContentsEvent`, drop the data-component registration entirely.
5. `item/AETItems.java` — `RegistryObject<Item>`; add `StorageCells.addCellGuiHandler(new DISKItemCellGuiHandler())` next to the cell handler; add the GUI handler class (§2.4) if the ME-Chest GUI is wanted.
6. `item/DISKDrive.java` — `Properties` without `.component(...)`; `appendHoverText(..., Level, ...)`.
7. Build config — `mods.toml` (not `neoforge.mods.toml`), floors `[47.1.3,)` / `[15.4.12, 16.0.0)`, `mixin{}` block, refmap, `MixinConfigs` manifest entry, `compatibilityLevel JAVA_8`.
8. Data — rewrite `CraftingRecipeProvider` for `Consumer<FinishedRecipe>` and `net.minecraftforge.common.Tags`, regenerate into `data/ae2things/recipes/` + `data/ae2things/advancements/`.
9. `dev/GoldenSampleDump.java` (dev-only, 635 lines) — uses `DataComponents` and `NbtIo`, so it cannot compile on 1.20.1; port its NBT-only parts or exclude it from the 1.20.1 source set (upstream has no such file).
10. `client/AE2ThingsClient.java` — `init()` invoked through `DistExecutor.unsafeRunWhenOn`; color handler on the mod bus.

---

## 6. UNKNOWN / not settled from sources

* **AE2 15.4.12 Forge jar bytecode** — the API facts above come from the source tree at the same tag, which is authoritative for shape; I did not additionally javap `ae2-forge-real-15.4.12.jar` member by member. If a member turns out missing at runtime, that jar is the tiebreaker.
* **Refmap emission under our exact build plugin** — `UP:build.gradle` proves the `mixin {}` + `MixinConfigs` recipe for FG6/1.20.1, but our repo is not on FG6 yet; whether the chosen plugin emits `mixins.ae2things.refmap.json` (and under which task) is UNKNOWN until the build file is settled.
* **Server-storage acquisition timing** — upstream fetches the `StorageManager` in a `TickEvent.LevelTickEvent` START handler (phase START, `side.isServer()`) rather than on `ServerStartedEvent` (what our 1.21.1 code does at `AE2Things.java:75-77`). Keeping the upstream tick-based shape is the safe port, but I did not verify whether `ServerStartedEvent` ordering is already safe for cells queried on the very first tick.
* **Forge 47.1.x (vs 47.4.10) bytecode of `AbstractContainerMenu#doClick`** — expected identical, not re-checked because no 47.1.x merged jar was located for this write-up; §4.3 gives the 30-second re-check.
* **Whether all 5 disk model JSONs use a `block/`-prefixed texture path** (needed for the §3.8 layout-1 decision) — not inspected here.
* **`GoldenSampleDump`'s fate** (port vs exclude) is a project decision, not an API fact.
