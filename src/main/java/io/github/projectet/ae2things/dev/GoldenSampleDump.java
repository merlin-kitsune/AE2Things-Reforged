package io.github.projectet.ae2things.dev;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.projectet.ae2things.AE2Things;
import io.github.projectet.ae2things.item.AETItems;
import io.github.projectet.ae2things.storage.DISKCellInventory;
import io.github.projectet.ae2things.util.Constants;
import io.github.projectet.ae2things.util.DataStorage;
import io.github.projectet.ae2things.util.StorageManager;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

/**
 * Development-only golden sample harness for the DISK storage format.
 *
 * <p>
 * It does nothing unless the environment variable {@code AE2THINGS_GOLDEN_DUMP} points at an output directory (or the
 * trigger file {@code ae2things-golden-dump.txt} exists in the run directory, see {@link #readTrigger()}), so shipping
 * it inside the mod jar cannot change any behaviour on a normal server.
 *
 * <p>
 * Run {@code seed} mode with an upstream build, then {@code load} mode with the reforged build on the same run
 * directory. In {@code seed} mode the samples are written to the world, in {@code load} mode they are read back from
 * the world and re-serialized. Diffing both {@code manifest.txt} files proves that the reforged build reads exactly the
 * bytes the upstream build wrote and writes them back unchanged.
 */
@EventBusSubscriber(modid = AE2Things.MOD_ID)
public final class GoldenSampleDump {
    private static final String ENV_DIR = "AE2THINGS_GOLDEN_DUMP";
    private static final String ENV_MODE = "AE2THINGS_GOLDEN_MODE";
    private static final String TRIGGER_FILE = "ae2things-golden-dump.txt";
    private static final String MODE_SEED = "seed";
    private static final String MODE_LOAD = "load";

    private static final UUID DS_EMPTY = new UUID(0x1000L, 0L);
    private static final UUID DS_SINGLE = new UUID(0x1000L, 1L);
    private static final UUID DS_MULTI = new UUID(0x1000L, 2L);
    private static final UUID DS_PATCHED = new UUID(0x1000L, 3L);
    private static final UUID DS_FLUID = new UUID(0x1000L, 4L);
    private static final UUID DS_BIG = new UUID(0x1000L, 5L);
    private static final UUID CELL_SMALL = new UUID(0x2000L, 0L);
    private static final UUID CELL_MIXED = new UUID(0x2000L, 1L);
    private static final UUID CELL_BIG = new UUID(0x2000L, 2L);

    private static final int BIG_KEY_COUNT = 64;
    private static final int CELL_BIG_KEY_COUNT = 32;
    private static final int TIMING_KEY_COUNT = 32;
    private static final int ZERO_OP_COUNT = 2000;
    private static final int ENCODE_TIMING_KEYS = 256;

    private static final String DATA_KEYS = "keys";
    private static final String DATA_AMOUNTS = "amts";
    private static final String DATA_ITEM_COUNT = "item_count";
    private static final String COMPOUND_TAG_ID = "10";

    private static final HexFormat HEX = HexFormat.of();

    private final Path dir;
    private final boolean seed;
    private final HolderLookup.Provider registries;
    private final StorageManager manager;
    private final List<String> manifest = new ArrayList<>();
    private final List<String> rawHashes = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> summary = new ArrayList<>();
    private int totalKeys;
    private boolean decodeOk = true;
    private boolean reencodeOk = true;
    private boolean cellsMatch = true;
    private int caseCount;

    private record Trigger(Path dir, boolean seed) {
    }

    private GoldenSampleDump(Path dir, boolean seed, HolderLookup.Provider registries, StorageManager manager) {
        this.dir = dir;
        this.seed = seed;
        this.registries = registries;
        this.manager = manager;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        var trigger = readTrigger();
        if (trigger == null) {
            return;
        }
        var server = event.getServer();
        var manager = AE2Things.currentStorageManager();
        if (manager == null) {
            manager = StorageManager.getInstance(server);
        }
        try {
            Files.createDirectories(trigger.dir());
            new GoldenSampleDump(trigger.dir(), trigger.seed(), server.registryAccess(), manager).run();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            server.saveEverything(true, true, true);
        } catch (Exception e) {
            e.printStackTrace();
        }
        server.halt(false);
    }

    @Nullable
    private static Trigger readTrigger() {
        String dir = System.getenv(ENV_DIR);
        String mode = System.getenv(ENV_MODE);
        if (dir == null || dir.isBlank()) {
            for (var candidate : List.of(Path.of(TRIGGER_FILE), Path.of("run", TRIGGER_FILE))) {
                if (!Files.isReadable(candidate)) {
                    continue;
                }
                try {
                    var lines = Files.readAllLines(candidate, StandardCharsets.UTF_8);
                    if (!lines.isEmpty() && !lines.get(0).isBlank()) {
                        dir = lines.get(0).trim();
                    }
                    if (lines.size() > 1 && !lines.get(1).isBlank()) {
                        mode = lines.get(1).trim();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            }
        }
        if (dir == null || dir.isBlank()) {
            return null;
        }
        var seed = !MODE_LOAD.equalsIgnoreCase(mode == null ? MODE_SEED : mode.trim());
        return new Trigger(Path.of(dir.trim()), seed);
    }

    private void run() throws Exception {
        this.notes.add("mode: " + (this.seed ? MODE_SEED : MODE_LOAD));
        this.notes.add("output_dir: " + this.dir);
        if (this.seed) {
            this.seedDataStorageCases();
            this.seedCellCases();
        } else {
            this.requirePersistedState();
        }
        this.dumpCases();
        this.dumpManagerState();
        this.probeOps();
        this.probeBehaviour();
        this.writeFiles();
    }

    private void seedDataStorageCases() {
        this.seedDataStorage(DS_EMPTY, List.of(), new long[0]);
        this.seedDataStorage(DS_SINGLE, List.of(AEItemKey.of(new ItemStack(Items.DIAMOND))), new long[] { 1000L });
        this.seedDataStorage(DS_MULTI,
                List.of(AEItemKey.of(new ItemStack(Items.DIAMOND)), AEItemKey.of(new ItemStack(Items.DIRT)),
                        AEItemKey.of(new ItemStack(Items.COBBLESTONE))),
                new long[] { 1000L, 2500L, 1L });
        this.seedDataStorage(DS_PATCHED, List.of(this.patchedKey(0)), new long[] { 7L });
        this.seedDataStorage(DS_FLUID, List.of(AEFluidKey.of(Fluids.WATER), AEFluidKey.of(Fluids.LAVA)),
                new long[] { 81000L, 500L });
        this.seedDataStorage(DS_BIG, this.bigKeys(BIG_KEY_COUNT), this.bigAmounts(BIG_KEY_COUNT));
    }

    private void seedCellCases() throws Exception {
        this.seedCell(CELL_SMALL, List.of(this.patchedKey(0)), new long[] { 5L });
        this.seedCell(CELL_MIXED,
                List.of(AEItemKey.of(new ItemStack(Items.DIAMOND)), AEItemKey.of(new ItemStack(Items.DIRT)),
                        AEItemKey.of(new ItemStack(Items.COBBLESTONE)), this.patchedKey(0)),
                new long[] { 9000L, 1234L, 1L, 3L });
        this.seedCell(CELL_BIG, this.bigKeys(CELL_BIG_KEY_COUNT), this.bigAmounts(CELL_BIG_KEY_COUNT));
    }

    private void seedDataStorage(UUID uuid, List<AEKey> keys, long[] amounts) {
        var tags = new ListTag();
        long itemCount = 0L;
        for (var i = 0; i < keys.size(); i++) {
            tags.add(keys.get(i).toTagGeneric(this.registries));
            itemCount += amounts[i];
        }
        this.manager.getOrCreateDisk(uuid);
        this.manager.modifyDisk(uuid, tags, amounts.clone(), itemCount);
    }

    private void seedCell(UUID uuid, List<AEKey> keys, long[] amounts) throws Exception {
        var inventory = this.createCell(uuid, 0L);
        for (var i = 0; i < keys.size(); i++) {
            var inserted = inventory.insert(keys.get(i), amounts[i], Actionable.MODULATE, null);
            if (inserted != amounts[i]) {
                throw new IllegalStateException("insert of sample " + uuid + " #" + i + " was clamped: " + inserted
                        + " != " + amounts[i]);
            }
        }
    }

    private void requirePersistedState() {
        for (var uuid : List.of(DS_EMPTY, DS_SINGLE, DS_MULTI, DS_PATCHED, DS_FLUID, DS_BIG, CELL_SMALL, CELL_MIXED,
                CELL_BIG)) {
            if (!this.manager.hasUUID(uuid)) {
                this.notes.add("missing_disk: " + uuid);
            }
        }
    }

    private DISKCellInventory createCell(UUID uuid, long itemCount) throws Exception {
        var stack = this.cellStack(uuid, itemCount);
        var inventory = DISKCellInventory.createInventory(stack, null, this.manager);
        if (inventory == null) {
            throw new IllegalStateException("could not create a DISK cell inventory for " + uuid);
        }
        return inventory;
    }

    private ItemStack cellStack(UUID uuid, long itemCount) {
        var stack = new ItemStack(AETItems.DISK_DRIVE_256K.get());
        stack.set(AE2Things.DATA_DISK_ID, uuid);
        if (itemCount != 0L) {
            stack.set(AE2Things.DATA_DISK_ITEM_COUNT, itemCount);
        }
        return stack;
    }

    private DataStorage disk(UUID uuid) {
        if (!this.manager.hasUUID(uuid)) {
            this.notes.add("missing_disk: " + uuid);
        }
        return this.manager.getOrCreateDisk(uuid);
    }

    private void dumpCases() throws Exception {
        this.dumpDataStorageCase("ds_empty", DS_EMPTY);
        this.dumpDataStorageCase("ds_single", DS_SINGLE);
        this.dumpDataStorageCase("ds_multi", DS_MULTI);
        this.dumpDataStorageCase("ds_patched", DS_PATCHED);
        this.dumpDataStorageCase("ds_fluid", DS_FLUID);
        this.dumpDataStorageCase("ds_big", DS_BIG);
        this.dumpCellCase("cell_small", CELL_SMALL);
        this.dumpCellCase("cell_mixed", CELL_MIXED);
        this.dumpCellCase("cell_big", CELL_BIG);
    }

    private void dumpDataStorageCase(String name, UUID uuid) throws Exception {
        var data = this.disk(uuid);
        var lines = new ArrayList<String>();
        lines.add("case: " + name);
        lines.add("kind: datastorage");
        lines.add("uuid: " + uuid);
        lines.add("key_count: " + data.stackKeys.size());
        lines.add("amount_count: " + data.stackAmounts.length);
        lines.add("item_count: " + data.itemCount);
        lines.add("keys_amounts_aligned: " + (data.stackKeys.size() == data.stackAmounts.length));
        var roundTrip = this.checkRoundTrip(data.stackKeys);
        lines.add("decode_all_keys: " + roundTrip[0]);
        lines.add("reencode_matches_stored: " + roundTrip[1]);
        lines.addAll(this.contentLines(data.stackKeys, data.stackAmounts));
        this.writeText(name + ".txt", lines);
        this.totalKeys += data.stackKeys.size();
        this.decodeOk &= roundTrip[0];
        this.reencodeOk &= roundTrip[1];
        this.caseCount++;
    }

    private void dumpCellCase(String name, UUID uuid) throws Exception {
        var data = this.disk(uuid);
        var stack = this.cellStack(uuid, data.itemCount);
        var inventory = DISKCellInventory.createInventory(stack, null, this.manager);
        var lines = new ArrayList<String>();
        lines.add("case: " + name);
        lines.add("kind: disk_cell");
        lines.add("uuid: " + uuid);
        lines.add("key_count: " + data.stackKeys.size());
        lines.add("amount_count: " + data.stackAmounts.length);
        lines.add("item_count: " + data.itemCount);
        lines.add("keys_amounts_aligned: " + (data.stackKeys.size() == data.stackAmounts.length));
        var roundTrip = this.checkRoundTrip(data.stackKeys);
        lines.add("decode_all_keys: " + roundTrip[0]);
        lines.add("reencode_matches_stored: " + roundTrip[1]);
        var counter = new KeyCounter();
        var matches = false;
        if (inventory != null) {
            inventory.getAvailableStacks(counter);
            matches = this.counterMatchesStorage(counter, data.stackKeys, data.stackAmounts);
        }
        lines.add("inventory_created: " + (inventory != null));
        lines.add("available_stacks_match_storage: " + matches);
        lines.add("stored_item_types: " + (inventory == null ? -1 : inventory.getStoredItemTypes()));
        lines.add("stack_is_256k_drive: " + (stack.getItem() == AETItems.DISK_DRIVE_256K.get()));
        lines.add("stack_count: " + stack.getCount());
        lines.add("component_disk_id: " + stack.get(AE2Things.DATA_DISK_ID));
        lines.add("component_item_count: " + stack.get(AE2Things.DATA_DISK_ITEM_COUNT));
        lines.add("component_fuzzy_mode: " + stack.get(AE2Things.DATA_FUZZY_MODE));
        lines.add("component_custom_name_present: " + stack.has(DataComponents.CUSTOM_NAME));
        lines.add("nbt_item_count: " + (inventory == null ? -1 : inventory.getNbtItemCount()));
        lines.addAll(this.contentLines(data.stackKeys, data.stackAmounts));
        this.writeText(name + ".txt", lines);
        this.totalKeys += data.stackKeys.size();
        this.decodeOk &= roundTrip[0];
        this.reencodeOk &= roundTrip[1];
        this.cellsMatch &= matches;
        this.caseCount++;
    }

    private boolean[] checkRoundTrip(ListTag tags) {
        var decode = true;
        var reencode = true;
        for (var i = 0; i < tags.size(); i++) {
            var stored = tags.get(i);
            if (!(stored instanceof CompoundTag compound)) {
                decode = false;
                continue;
            }
            var key = AEKey.fromTagGeneric(this.registries, compound);
            if (key == null) {
                decode = false;
                continue;
            }
            if (!key.toTagGeneric(this.registries).equals(compound)) {
                reencode = false;
            }
        }
        return new boolean[] { decode, reencode };
    }

    private boolean counterMatchesStorage(KeyCounter counter, ListTag tags, long[] amounts) {
        var expected = new HashMap<AEKey, Long>();
        for (var i = 0; i < tags.size(); i++) {
            if (!(tags.get(i) instanceof CompoundTag compound)) {
                return false;
            }
            var key = AEKey.fromTagGeneric(this.registries, compound);
            if (key == null) {
                return false;
            }
            expected.put(key, amounts[i]);
        }
        var seen = 0;
        for (Map.Entry<AEKey, ?> entry : counter) {
            var amount = expected.get(entry.getKey());
            if (amount == null
                    || amount.longValue() != ((it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey>) entry)
                            .getLongValue()) {
                return false;
            }
            seen++;
        }
        return seen == expected.size();
    }

    private List<String> contentLines(ListTag tags, long[] amounts) {
        var lines = new ArrayList<String>();
        for (var i = 0; i < tags.size(); i++) {
            lines.add(amounts[i] + " " + tags.get(i));
        }
        Collections.sort(lines);
        return lines;
    }

    private void dumpManagerState() throws Exception {
        var saved = this.manager.save(new CompoundTag(), this.registries);
        this.writeNbt("manager.nbt", saved);
        var list = saved.getList(Constants.DISKLIST, Integer.parseInt(COMPOUND_TAG_ID));
        var blocks = new ArrayList<String>();
        for (var i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            var data = entry.getCompound("diskdata");
            var tags = data.getList(DATA_KEYS, Integer.parseInt(COMPOUND_TAG_ID));
            var amounts = data.getLongArray(DATA_AMOUNTS);
            var lines = new ArrayList<String>();
            lines.add("disk " + String.valueOf(entry.get("disk_id")) + " keys=" + tags.size() + " amounts="
                    + amounts.length + " item_count=" + data.getLong(DATA_ITEM_COUNT));
            lines.addAll(this.contentLines(tags, amounts));
            blocks.add(String.join("\n", lines));
        }
        Collections.sort(blocks);
        var out = new ArrayList<String>();
        out.add("disk_count: " + blocks.size());
        for (var block : blocks) {
            Collections.addAll(out, block.split("\n"));
        }
        this.writeText("manager_canonical.txt", out);
    }

    private void probeOps() throws Exception {
        var uuid = new UUID(0x3000L, this.seed ? 0L : 1L);
        var lines = new ArrayList<String>();
        try {
            var inventory = this.createCell(uuid, 0L);
            var diamond = AEItemKey.of(new ItemStack(Items.DIAMOND));
            var dirt = AEItemKey.of(new ItemStack(Items.DIRT));
            var water = AEFluidKey.of(Fluids.WATER);
            lines.add("insert_diamond_1000_modulate: " + inventory.insert(diamond, 1000L, Actionable.MODULATE, null));
            lines.add("insert_diamond_1000_simulate: " + inventory.insert(diamond, 1000L, Actionable.SIMULATE, null));
            lines.add("insert_dirt_2000_modulate: " + inventory.insert(dirt, 2000L, Actionable.MODULATE, null));
            lines.add("insert_fluid_modulate: " + inventory.insert(water, 1000L, Actionable.MODULATE, null));
            lines.add("extract_diamond_500_modulate: " + inventory.extract(diamond, 500L, Actionable.MODULATE, null));
            lines.add("extract_diamond_100_simulate: " + inventory.extract(diamond, 100L, Actionable.SIMULATE, null));
            lines.add("extract_dirt_100000_modulate: " + inventory.extract(dirt, 100000L, Actionable.MODULATE, null));
            lines.add("extract_absent_key_simulate: "
                    + inventory.extract(AEItemKey.of(new ItemStack(Items.STONE)), 10L, Actionable.SIMULATE, null));
            lines.add("insert_zero_modulate: " + inventory.insert(diamond, 0L, Actionable.MODULATE, null));
            lines.add("insert_zero_simulate: " + inventory.insert(diamond, 0L, Actionable.SIMULATE, null));
            lines.add("extract_zero_modulate: " + inventory.extract(diamond, 0L, Actionable.MODULATE, null));
            lines.add("extract_zero_simulate: " + inventory.extract(diamond, 0L, Actionable.SIMULATE, null));
            lines.add("final_nbt_item_count: " + inventory.getNbtItemCount());
        } finally {
            this.manager.removeDisk(uuid);
        }
        this.writeText("ops.txt", lines);
    }

    private void probeBehaviour() throws Exception {
        var uuid = new UUID(0x3001L, this.seed ? 0L : 1L);
        var lines = new ArrayList<String>();
        var timing = new ArrayList<String>();
        lines.add("mode: " + (this.seed ? MODE_SEED : MODE_LOAD));
        try {
            var inventory = this.createCell(uuid, 0L);
            var probe = this.patchedKey(1);
            lines.add("types_before_zero_insert_new_key: " + inventory.getStoredItemTypes());
            inventory.insert(probe, 0L, Actionable.MODULATE, null);
            lines.add("types_after_zero_insert_new_key: " + inventory.getStoredItemTypes());
            lines.add("nbt_item_count_after_zero_insert_new_key: " + inventory.getNbtItemCount());
            var keys = this.bigKeys(TIMING_KEY_COUNT);
            var amounts = this.bigAmounts(TIMING_KEY_COUNT);
            for (var i = 0; i < keys.size(); i++) {
                inventory.insert(keys.get(i), amounts[i], Actionable.MODULATE, null);
            }
            var existing = keys.get(0);
            var start = System.nanoTime();
            for (var i = 0; i < ZERO_OP_COUNT; i++) {
                inventory.insert(existing, 0L, Actionable.MODULATE, null);
            }
            timing.add("zero_insert_" + ZERO_OP_COUNT + "_existing_key_ms: "
                    + ((System.nanoTime() - start) / 1000000L));
            var start2 = System.nanoTime();
            for (var i = 0; i < ZERO_OP_COUNT; i++) {
                inventory.extract(existing, 0L, Actionable.MODULATE, null);
            }
            timing.add("zero_extract_" + ZERO_OP_COUNT + "_existing_key_ms: "
                    + ((System.nanoTime() - start2) / 1000000L));
        } finally {
            this.manager.removeDisk(uuid);
        }
        this.probeEncodingTiming(timing);
        this.writeUnrecorded("behaviour.txt", lines);
        this.writeUnrecorded("timing.txt", timing);
    }

    private void probeEncodingTiming(List<String> timing) throws Exception {
        Method encode;
        try {
            encode = DataStorage.class.getMethod("getOrEncodeKeyTag", AEKey.class, HolderLookup.Provider.class);
        } catch (NoSuchMethodException e) {
            timing.add("cache_method_present: false");
            return;
        }
        timing.add("cache_method_present: true");
        var storage = new DataStorage(new ListTag(), new long[0], 0L);
        var keys = this.bigKeys(ENCODE_TIMING_KEYS);
        var start = System.nanoTime();
        for (var key : keys) {
            encode.invoke(storage, key, this.registries);
        }
        timing.add("encode_cold_" + ENCODE_TIMING_KEYS + "_keys_ms: " + ((System.nanoTime() - start) / 1000000L));
        var start2 = System.nanoTime();
        for (var key : keys) {
            encode.invoke(storage, key, this.registries);
        }
        timing.add("encode_warm_" + ENCODE_TIMING_KEYS + "_keys_ms: " + ((System.nanoTime() - start2) / 1000000L));
    }

    private AEItemKey patchedKey(int index) {
        return AEItemKey.of(this.patchedStack("sample-" + index));
    }

    private ItemStack patchedStack(String name) {
        var stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("ae2things-" + name));
        return stack;
    }

    private List<AEKey> bigKeys(int count) {
        var keys = new ArrayList<AEKey>(count);
        for (var i = 0; i < count; i++) {
            keys.add(this.patchedKey(i));
        }
        return keys;
    }

    private long[] bigAmounts(int count) {
        var amounts = new long[count];
        for (var i = 0; i < count; i++) {
            amounts[i] = i * 7L + 1L;
        }
        return amounts;
    }

    private void writeFiles() throws Exception {
        this.summary.add("cases: " + this.caseCount);
        this.summary.add("total_keys: " + this.totalKeys);
        this.summary.add("all_decode_ok: " + this.decodeOk);
        this.summary.add("all_reencode_matches_stored: " + this.reencodeOk);
        this.summary.add("all_available_stacks_match_storage: " + this.cellsMatch);
        this.writeText("summary.txt", this.summary);
        Collections.sort(this.manifest);
        this.writeUnrecorded("manifest.txt", this.manifest);
        Collections.sort(this.rawHashes);
        this.writeUnrecorded("raw_hashes.txt", this.rawHashes);
        this.writeUnrecorded("notes.txt", this.notes);
    }

    private void writeText(String name, List<String> lines) throws Exception {
        var bytes = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(this.dir.resolve(name), bytes);
        this.manifest.add(sha256(bytes) + "  " + bytes.length + "  " + name);
    }

    private void writeUnrecorded(String name, List<String> lines) throws Exception {
        Files.write(this.dir.resolve(name), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeNbt(String name, CompoundTag tag) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            NbtIo.write(tag, out);
        }
        var bytes = buffer.toByteArray();
        Files.write(this.dir.resolve(name), bytes);
        this.rawHashes.add(sha256(bytes) + "  " + bytes.length + "  " + name);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
