package za.co.neroland.nerocolonies.block.entity;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;
import za.co.neroland.nerolandcore.sideconfig.Channel;
import za.co.neroland.nerolandcore.sideconfig.RelativeFace;
import za.co.neroland.nerolandcore.sideconfig.SideConfig;
import za.co.neroland.nerolandcore.sideconfig.SideMode;
import za.co.neroland.nerolandcore.sideconfig.SidePreset;
import za.co.neroland.nerolandcore.sideconfig.SlotGroup;
import za.co.neroland.nerolandcore.upgrade.UpgradeType;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.colony.ColonyContainer;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.ColonyStores;
import za.co.neroland.nerocolonies.colony.ColonyTicker;
import za.co.neroland.nerocolonies.colony.ExportBuffer;
import za.co.neroland.nerocolonies.colony.FoodSupply;
import za.co.neroland.nerocolonies.colony.LifeSupport;
import za.co.neroland.nerocolonies.colony.Morale;
import za.co.neroland.nerocolonies.menu.ColonyBeaconMenu;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * The colony command block's block entity: the physical anchor of a {@link Colony} record, and the
 * thing that drives its tick.
 *
 * <p>It deliberately holds almost no colony state of its own. The colony lives in
 * {@link ColonyState} (a server-wide saved-data store) and this block entity keeps only the
 * {@link #colonyId} that points at it, so a colony survives its beacon's chunk being unloaded and
 * there is exactly one source of truth for population, morale and research.
 *
 * <p>What it does own:
 * <ul>
 *   <li>a small energy buffer with an idle trickle — a beacon with no power is not projecting a
 *       claim field, which is the in-fiction reason the GUI shows a power gauge;</li>
 *   <li>the colony's shared {@link UpgradeType} modules — {@code RANGE} widens the claim,
 *       {@code EFFICIENCY} trims life-support burn, {@code CAPACITY} enlarges storage;</li>
 *   <li>the <b>food supply slots</b>, where any hopper, pipe or player can put food and the colony
 *       tick converts it into rations;</li>
 *   <li>the colony's {@link ColonyTicker.State} — the housing sweep cursor and the catch-up flag —
 *       which is session state and never saved, because {@code Colony.lastTick} is the persisted
 *       truth a reloaded beacon catches up from.</li>
 * </ul>
 *
 * <p><b>Why the tick lives here.</b> Driving the colony from its beacon rather than from a
 * server-tick event means a colony ticks exactly while its beacon's chunk is loaded — which is the
 * whole premise of the catch-up design — and it needs no per-loader event wiring at all.
 *
 * <p><b>Privacy:</b> the synced {@link ContainerData} carries colony <em>state</em> only — morale,
 * population, capacity, food, radius, counts. The owner UUID and the access list stay on the server
 * and are never placed in a data slot.
 */
public class ColonyBeaconBlockEntity extends AbstractMachineBlockEntity
        implements WorldlyContainer, MenuProvider {

    /** Upgrade-module slots. Core clamps the real count to its own server-side cap. */
    public static final int UPGRADE_SLOTS = 3;

    /** Food supply slots — the colony's intake. Anything a colony eats may be put here. */
    public static final int SUPPLY_SLOTS = 6;

    /**
     * What the beacon block itself physically holds: its modules and its supply slots. These are the
     * only slots the menu draws and the only ones that drop when the block breaks.
     */
    public static final int LOCAL_SLOTS = UPGRADE_SLOTS + SUPPLY_SLOTS;

    /** Where the colony-storage window starts in the container's index space. */
    public static final int STORAGE_OFFSET = LOCAL_SLOTS;

    /** Where the export-buffer window starts in the container's index space. */
    public static final int EXPORT_OFFSET = STORAGE_OFFSET + ColonyStores.STORAGE_SLOTS;

    /**
     * The container's full size: local slots, then a window onto colony storage, then a window onto
     * the export buffer.
     *
     * <h2>Why the beacon is a 117-slot container</h2>
     *
     * <p>The plan calls for colony storage and the export buffer to be reachable as a <b>standard
     * item capability</b>, so that pipes, hoppers, AE2 and Create work with no NeroColonies-specific
     * API. The loaders already wrap this block entity's {@code WorldlyContainer} as that capability —
     * so appending the two windows to its index space is all it takes, with no per-loader change and
     * no bridge to publish.
     *
     * <p>The menu still only draws the first {@link #LOCAL_SLOTS}. The rest exist for automation,
     * which is why {@link #allContents()} (the drop-on-break path) also stops at {@code LOCAL_SLOTS}:
     * the colony's goods are not the beacon block's to drop, and they are handled by the dissolve
     * path instead.
     */
    public static final int CONTAINER_SIZE = EXPORT_OFFSET + ColonyStores.EXPORT_SLOTS;

    public static final int ENERGY_CAPACITY = 50_000;
    public static final int MAX_TRANSFER = 1_000;

    /** Idle draw. Small on purpose: a beacon is a controller, not a machine. */
    public static final int IDLE_ENERGY_PER_TICK = 2;

    /** How often the beacon pushes upgrade-derived changes (claim radius) onto the colony record. */
    private static final int REFRESH_INTERVAL_TICKS = 100;

    /** Data slots are shorts on the wire; food stock is clamped rather than allowed to wrap. */
    private static final int MAX_SYNCED_VALUE = 30_000;

    public static final int DATA_ENERGY_PERMILLE = 0;
    public static final int DATA_ENERGY_SCALE = 1;
    public static final int DATA_MORALE = 2;
    public static final int DATA_POPULATION = 3;
    public static final int DATA_HOUSING_CAPACITY = 4;
    public static final int DATA_FOOD_STOCK = 5;
    public static final int DATA_LIFE_SUPPORT = 6;
    public static final int DATA_CLAIM_RADIUS = 7;
    public static final int DATA_RESEARCH_COUNT = 8;
    public static final int DATA_OUTPOST_COUNT = 9;
    public static final int DATA_HAS_COLONY = 10;
    public static final int DATA_COMFORT_PERCENT = 11;
    public static final int DATA_WORK_STOPPED = 12;
    public static final int DATA_LIFE_STATE = 13;
    public static final int DATA_GENERATORS = 14;
    public static final int DATA_SIZE = 15;

    /** The colony this beacon anchors, or {@code null} until placement binds one. */
    @Nullable
    private UUID colonyId;

    /** Server-side snapshot refreshed on the tick, so the GUI's data slots are a cheap field read. */
    @Nullable
    private transient Colony cached;

    /** Session-only ticking state: the housing cursor, the catch-up flag, crossing memory. */
    private final ColonyTicker.State tickState = new ColonyTicker.State();

    /**
     * The colony's food intake. Its own container so the tick can drain it without slot arithmetic;
     * its {@code setChanged} is forwarded so a hopper filling it still marks the beacon dirty.
     */
    private final SimpleContainer supply = new SimpleContainer(SUPPLY_SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            ColonyBeaconBlockEntity.this.setChanged();
        }
    };

    private int refreshCountdown;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            Colony colony = ColonyBeaconBlockEntity.this.cached;
            return switch (index) {
                case DATA_ENERGY_PERMILLE -> (int) (energy.getRaw() * 1000L / Math.max(1, ENERGY_CAPACITY));
                case DATA_ENERGY_SCALE -> 1000;
                case DATA_MORALE -> colony == null ? 0 : (int) Math.round(colony.morale());
                case DATA_POPULATION -> colony == null ? 0 : clamp(colony.population());
                case DATA_HOUSING_CAPACITY -> colony == null ? 0 : clamp(colony.housingCapacity());
                case DATA_FOOD_STOCK -> colony == null ? 0 : clamp(colony.foodStock());
                case DATA_LIFE_SUPPORT -> colony == null || colony.lifeSupportOk() ? 1 : 0;
                case DATA_CLAIM_RADIUS -> colony == null ? 0 : clamp(colony.claimRadius());
                case DATA_RESEARCH_COUNT -> colony == null ? 0 : clamp(colony.researchUnlocked().size());
                case DATA_OUTPOST_COUNT -> colony == null ? 0 : clamp(colony.outpostIds().size());
                case DATA_HAS_COLONY -> colony == null ? 0 : 1;
                case DATA_COMFORT_PERCENT -> (int) Math.round(
                        ColonyBeaconBlockEntity.this.tickState.housing().comfortRatio() * 100.0D);
                case DATA_WORK_STOPPED -> colony != null && Morale.workStopped(colony) ? 1 : 0;
                case DATA_LIFE_STATE -> colony == null ? 0 : LifeSupport.stateOf(colony).ordinal();
                case DATA_GENERATORS -> colony == null ? 0
                        : clamp(LifeSupport.generatorCount(colony.colonyId()));
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // Server-authoritative: the client never writes colony state.
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    @SuppressWarnings("this-escape") // idiomatic Minecraft block-entity wiring
    public ColonyBeaconBlockEntity(BlockPos pos, BlockState state) {
        super(NeroColoniesBlockEntities.COLONY_BEACON.get(), pos, state,
                ENERGY_CAPACITY, MAX_TRANSFER, UPGRADE_SLOTS,
                za.co.neroland.nerocolonies.item.ColonyUpgradeItem.CLASSIFIER);
        installSideConfig(buildSideConfig()).withItems(() -> this);
    }

    /**
     * Energy in on every face (a beacon only ever consumes); the item channel covers the modules, the
     * supply slots and the two colony windows, so one pipe can restock a colony completely and
     * another can drain its exports. Which region a given stack lands in is decided by
     * {@link #canPlaceItem}, not by the face.
     *
     * <p>Item faces are set to {@code IO} rather than left on the {@code ALL_INPUT} preset, because
     * the export buffer is only useful if something can take from it — a colony that can be filled
     * but never emptied is not a trade route.
     */
    private static SideConfig buildSideConfig() {
        int[] insertable = new int[UPGRADE_SLOTS + SUPPLY_SLOTS + ColonyStores.STORAGE_SLOTS];
        for (int slot = 0; slot < insertable.length; slot++) {
            insertable[slot] = slot;
        }
        int[] extractable = new int[ColonyStores.STORAGE_SLOTS + ColonyStores.EXPORT_SLOTS];
        for (int i = 0; i < extractable.length; i++) {
            extractable[i] = STORAGE_OFFSET + i;
        }
        SideConfig config = SideConfig.builder()
                .channel(Channel.ITEM, SlotGroup.of(SlotGroup.INPUT, insertable),
                        SlotGroup.of(SlotGroup.OUTPUT, extractable))
                .channel(Channel.ENERGY)
                .allow(Channel.ENERGY, SideMode.OUTPUT, false)
                .allow(Channel.ENERGY, SideMode.PUSH, false)
                .defaultPreset(SidePreset.ALL_INPUT)
                .build();
        for (RelativeFace face : RelativeFace.VALUES) {
            config.setMode(Channel.ITEM, face, SideMode.IO);
            config.setMode(Channel.ENERGY, face, SideMode.INPUT);
        }
        return config;
    }

    private static int clamp(int value) {
        return Math.clamp(value, 0, MAX_SYNCED_VALUE);
    }

    // --- colony binding -----------------------------------------------------

    @Nullable
    public UUID colonyId() {
        return this.colonyId;
    }

    /** Binds this beacon to a colony record. Called once, from the placement flow. */
    public void bind(UUID id) {
        this.colonyId = id;
        this.cached = null;
        this.refreshCountdown = 0;
        this.tickState.invalidate();
        this.setChanged();
    }

    /** The colony record, or {@code null} (unbound, dissolved, or called on the client). */
    @Nullable
    public Colony colony() {
        if (this.colonyId == null || this.level == null || this.level.isClientSide()) {
            return this.cached;
        }
        MinecraftServer server = this.level.getServer();
        if (server == null) {
            return null;
        }
        return ColonyState.get(server).colony(this.colonyId);
    }

    /** The GUI's synced view. Colony state only — never ownership. */
    public ContainerData containerData() {
        return this.data;
    }

    /** The food supply slots, as a plain container. */
    public Container supply() {
        return this.supply;
    }

    // --- tick ---------------------------------------------------------------

    @Override
    protected void serverTick(Level level, BlockPos pos, BlockState state) {
        // The claim field costs a trickle. No power simply means no trickle — the colony keeps
        // running, because a colony that evaporates when a cable is cut is a colony nobody builds.
        if (this.energy.has(IDLE_ENERGY_PER_TICK)) {
            this.energy.consume(IDLE_ENERGY_PER_TICK);
        }
        if (this.colonyId == null || !(level instanceof ServerLevel serverLevel)) {
            this.cached = null;
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        ColonyState colonies = ColonyState.get(server);
        Colony colony = colonies.colony(this.colonyId);
        if (colony == null) {
            // The record is gone (dissolved elsewhere, or a recovered store). Unbind rather than
            // keep pointing at nothing.
            this.colonyId = null;
            this.cached = null;
            this.setChanged();
            return;
        }

        // The colony's own cycle: staggered, budgeted, and driven from here so it exists exactly
        // while this beacon's chunk is loaded.
        Colony ticked = ColonyTicker.tick(serverLevel, colony, this.tickState, this.supply);
        if (ticked != colony) {
            colonies.put(ticked);
            colony = ticked;
        }

        if (--this.refreshCountdown <= 0) {
            this.refreshCountdown = REFRESH_INTERVAL_TICKS;
            int radius = ColonyClaims.effectiveClaimRadius(modifiers().rangeBonus());
            if (radius != colony.claimRadius()) {
                colony = colony.withClaimRadius(radius);
                colonies.put(colony);
                // A different claim means a different set of chunks to sweep.
                this.tickState.housing().restart();
            }
        }
        this.cached = colony;
    }

    // --- menu ---------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        Colony colony = colony();
        return colony == null
                ? Component.translatable("container.nerocolonies.colony_beacon")
                : Component.literal(colony.name());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ColonyBeaconMenu(containerId, playerInventory, this, this.data);
    }

    // --- container (modules + supply + the two colony windows) ---------------

    private boolean isSupplySlot(int slot) {
        return slot >= UPGRADE_SLOTS && slot < LOCAL_SLOTS;
    }

    private boolean isStorageSlot(int slot) {
        return slot >= STORAGE_OFFSET && slot < EXPORT_OFFSET;
    }

    private boolean isExportSlot(int slot) {
        return slot >= EXPORT_OFFSET && slot < CONTAINER_SIZE;
    }

    /** How many {@code CAPACITY} modules are installed — what sizes the colony's storage. */
    public int capacityModules() {
        return modifiers().count(UpgradeType.CAPACITY);
    }

    /** The colony-storage window, or {@code null} while the beacon is unbound or on the client. */
    @Nullable
    private ColonyContainer storageView() {
        if (this.colonyId == null || this.level == null || this.level.getServer() == null) {
            return null;
        }
        UUID id = this.colonyId;
        return ColonyStorage.container(this.level.getServer(), id, this::capacityModules);
    }

    /** The export-buffer window, or {@code null} while the beacon is unbound or on the client. */
    @Nullable
    private ColonyContainer exportView() {
        if (this.colonyId == null || this.level == null || this.level.getServer() == null) {
            return null;
        }
        return ExportBuffer.container(this.level.getServer(), this.colonyId);
    }

    /** The window a container index falls in, or {@code null} for a local slot. */
    @Nullable
    private ColonyContainer windowFor(int slot) {
        if (isStorageSlot(slot)) {
            return storageView();
        }
        return isExportSlot(slot) ? exportView() : null;
    }

    private static int windowIndex(int slot) {
        return slot >= EXPORT_OFFSET ? slot - EXPORT_OFFSET : slot - STORAGE_OFFSET;
    }

    @Override
    public int getContainerSize() {
        return CONTAINER_SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int slot = 0; slot < this.upgrades.slots(); slot++) {
            if (!this.upgrades.getStack(slot).isEmpty()) {
                return false;
            }
        }
        if (!this.supply.isEmpty()) {
            return false;
        }
        ColonyContainer storage = storageView();
        ColonyContainer exports = exportView();
        return (storage == null || storage.isEmpty()) && (exports == null || exports.isEmpty());
    }

    @Override
    public ItemStack getItem(int slot) {
        ColonyContainer window = windowFor(slot);
        if (window != null) {
            return window.getItem(windowIndex(slot));
        }
        if (isSupplySlot(slot)) {
            return this.supply.getItem(slot - UPGRADE_SLOTS);
        }
        return slot >= 0 && slot < this.upgrades.slots() ? this.upgrades.getStack(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ColonyContainer window = windowFor(slot);
        if (window != null) {
            return window.removeItem(windowIndex(slot), amount);
        }
        if (isSupplySlot(slot)) {
            return this.supply.removeItem(slot - UPGRADE_SLOTS, amount);
        }
        if (slot < 0 || slot >= this.upgrades.slots()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = ContainerHelper.removeItem(this.upgrades.items(), slot, amount);
        if (!removed.isEmpty()) {
            this.setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ColonyContainer window = windowFor(slot);
        if (window != null) {
            return window.removeItemNoUpdate(windowIndex(slot));
        }
        if (isSupplySlot(slot)) {
            return this.supply.removeItemNoUpdate(slot - UPGRADE_SLOTS);
        }
        return slot >= 0 && slot < this.upgrades.slots()
                ? ContainerHelper.takeItem(this.upgrades.items(), slot)
                : ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        ColonyContainer window = windowFor(slot);
        if (window != null) {
            window.setItem(windowIndex(slot), stack);
            return;
        }
        if (isSupplySlot(slot)) {
            this.supply.setItem(slot - UPGRADE_SLOTS, stack);
            return;
        }
        if (slot >= 0 && slot < this.upgrades.slots()) {
            this.upgrades.setStack(slot, stack);
        }
    }

    /**
     * Upgrade slots take modules; supply slots take anything the colony eats; the storage window takes
     * anything, up to whatever the colony has unlocked; the export window takes <b>nothing</b> from
     * outside, so what is in it is exactly what the colony produced for sale.
     *
     * <p>Refusing non-food in the supply slots is what stops a mixed pipe from silently clogging a
     * colony's intake with cobblestone.
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        ColonyContainer window = windowFor(slot);
        if (window != null) {
            return window.canPlaceItem(windowIndex(slot), stack);
        }
        return isSupplySlot(slot) ? FoodSupply.isFood(stack) : this.upgrades.isModule(stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return net.minecraft.world.Container.stillValidBlockEntity(this, player);
    }

    /**
     * Clears only what the block itself holds. The colony's storage and export buffer are pointedly
     * left alone: vanilla calls this on block removal, and a colony must not be emptiable by breaking
     * one block of it.
     */
    @Override
    public void clearContent() {
        for (int slot = 0; slot < this.upgrades.slots(); slot++) {
            this.upgrades.setStack(slot, ItemStack.EMPTY);
        }
        this.supply.clearContent();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return this.sideConfig == null ? new int[0] : this.sideConfig.itemSlotsForFace(side);
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return side != null && this.sideConfig != null
                && this.sideConfig.canInsertItem(slot, side) && canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return this.sideConfig != null && this.sideConfig.canExtractItem(slot, side);
    }

    /**
     * Everything the beacon <b>block</b> holds, for the drop-on-break path: its modules and its
     * supply slots, and deliberately not the colony's goods. Those are dropped (and forgotten) by the
     * dissolve path in {@code ColonyBeaconBlock}, because dropping them from here would duplicate
     * them — the window returns the live stacks that are still in the store.
     */
    public NonNullList<ItemStack> allContents() {
        NonNullList<ItemStack> contents = NonNullList.withSize(LOCAL_SLOTS, ItemStack.EMPTY);
        for (int slot = 0; slot < LOCAL_SLOTS; slot++) {
            contents.set(slot, getItem(slot));
        }
        return contents;
    }

    // --- persistence --------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (this.colonyId != null) {
            output.putString("ColonyId", this.colonyId.toString());
        }
        for (int slot = 0; slot < SUPPLY_SLOTS; slot++) {
            output.store("Supply" + slot, ItemStack.OPTIONAL_CODEC, this.supply.getItem(slot));
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        String raw = input.getStringOr("ColonyId", "");
        if (raw.isEmpty()) {
            this.colonyId = null;
        } else {
            try {
                this.colonyId = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                this.colonyId = null; // a malformed id is an unbound beacon, not a crash
            }
        }
        for (int slot = 0; slot < SUPPLY_SLOTS; slot++) {
            this.supply.setItem(slot,
                    input.read("Supply" + slot, ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        }
        this.cached = null;
        this.refreshCountdown = 0;
        // A reloaded beacon catches up from Colony.lastTick and starts a fresh housing cycle.
        this.tickState.invalidate();
    }
}
