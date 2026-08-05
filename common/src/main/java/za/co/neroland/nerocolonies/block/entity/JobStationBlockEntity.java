package za.co.neroland.nerocolonies.block.entity;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;
import za.co.neroland.nerolandcore.sideconfig.Channel;
import za.co.neroland.nerolandcore.sideconfig.RelativeFace;
import za.co.neroland.nerolandcore.sideconfig.SideConfig;
import za.co.neroland.nerolandcore.sideconfig.SideMode;
import za.co.neroland.nerolandcore.sideconfig.SidePreset;
import za.co.neroland.nerolandcore.sideconfig.SlotGroup;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.JobBoard;
import za.co.neroland.nerocolonies.colony.Outpost;
import za.co.neroland.nerocolonies.item.ColonyUpgradeItem;
import za.co.neroland.nerocolonies.menu.JobStationMenu;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * A job station: the block a colonist walks to, and the thing a colony's production is measured in.
 *
 * <h2>It holds no recipe and no inventory</h2>
 *
 * <p>A station has upgrade modules, an energy buffer and nothing else. Its recipe comes from the
 * datapack {@code JobDefinition}s that name its <b>block id</b> — so one block entity class serves
 * every station block, and a datapack can add a job to an existing station (or point a job at some
 * other mod's block) with no code at all. Its inputs come from colony storage and its outputs go
 * back there, or into the export buffer, so there is no per-station inventory to fill, clog or lose.
 *
 * <h2>It does not run its own recipe</h2>
 *
 * <p>Each tick the station files itself with {@link JobBoard} against the colony (or outpost) whose
 * claim it stands in, and the <b>colony's</b> cycle drives the work. That keeps a colony's whole
 * production cost inside the one {@code colonyTickBudgetMs} budget instead of spread across N block
 * entities, which is the difference between "twenty stations" being a design choice and being a
 * server problem. What this class does per tick is a countdown and, occasionally, a map write.
 *
 * <h2>Power</h2>
 *
 * <p>A station spends {@value #ENERGY_PER_CRAFT} energy per completed craft, reduced by
 * {@code EFFICIENCY} modules. Without power it still works, at {@code JobBoard}'s unpowered rate —
 * the same graceful-degradation rule the beacon and life support follow. A colony whose cable was cut
 * gets slower and visibly so, rather than stopping dead for a reason the player cannot see.
 *
 * <h2>Privacy</h2>
 *
 * <p>The synced data slots carry progress, staffing counts and machine state. Nothing here knows who
 * owns the colony; permission questions are asked of the colony record, server-side.
 */
public class JobStationBlockEntity extends AbstractMachineBlockEntity
        implements WorldlyContainer, MenuProvider {

    public static final int UPGRADE_SLOTS = 2;

    public static final int ENERGY_CAPACITY = 20_000;
    public static final int MAX_TRANSFER = 500;

    /** Energy one completed craft costs at base efficiency. */
    public static final int ENERGY_PER_CRAFT = 120;

    /** How often the station refreshes its job-board registration, in ticks. */
    private static final int REGISTER_INTERVAL_TICKS = 40;

    /** How often it re-resolves which claim it stands in, in ticks. Claims move rarely. */
    private static final int COLONY_LOOKUP_INTERVAL_TICKS = 200;

    public static final int DATA_ENERGY_PERMILLE = 0;
    public static final int DATA_ENERGY_SCALE = 1;
    public static final int DATA_PROGRESS_PERMILLE = 2;
    public static final int DATA_PROGRESS_SCALE = 3;
    public static final int DATA_ACTIVE = 4;
    public static final int DATA_BLOCKED = 5;
    public static final int DATA_ASSIGNED = 6;
    public static final int DATA_REQUIRED = 7;
    public static final int DATA_HAS_JOB = 8;
    public static final int DATA_BOUND = 9;
    public static final int DATA_OUTPOST = 10;
    public static final int DATA_EXPORT = 11;
    public static final int DATA_SIZE = 12;

    /** The colony this station works for (a parent colony, when it stands in an outpost). */
    @Nullable
    private UUID colonyId;

    /** The outpost this station stands in, or {@code null} for the colony proper. */
    @Nullable
    private UUID outpostId;

    /**
     * Whether this station's output is routed to the export buffer instead of colony storage.
     *
     * <p>A per-station switch on top of the job's own {@code export} flag: the JSON says what a
     * recipe is <em>for</em>, and this says what this particular colony is doing with it today.
     * Without it, whether a colony can trade at all would be a datapack decision rather than a
     * player's, and the export buffer could not be filled by a pack that shipped no export job.
     */
    private boolean exportOutput;

    private int registerCountdown;
    private int lookupCountdown;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            JobBoard.Station station = JobBoard.station(JobStationBlockEntity.this.colonyId,
                    JobStationBlockEntity.this.worldPosition);
            return switch (index) {
                case DATA_ENERGY_PERMILLE ->
                        (int) (energy.getRaw() * 1000L / Math.max(1, ENERGY_CAPACITY));
                case DATA_ENERGY_SCALE -> 1000;
                case DATA_PROGRESS_PERMILLE -> station == null ? 0
                        : (int) Math.round(station.progressFraction() * 1000.0D);
                case DATA_PROGRESS_SCALE -> 1000;
                case DATA_ACTIVE -> station != null && station.active() ? 1 : 0;
                case DATA_BLOCKED -> station != null && station.blocked() ? 1 : 0;
                case DATA_ASSIGNED -> station == null ? 0 : station.assigned();
                case DATA_REQUIRED -> station == null ? 0 : station.required();
                case DATA_HAS_JOB -> station != null && station.jobId() != null ? 1 : 0;
                case DATA_BOUND -> JobStationBlockEntity.this.colonyId == null ? 0 : 1;
                case DATA_OUTPOST -> JobStationBlockEntity.this.outpostId == null ? 0 : 1;
                case DATA_EXPORT -> JobStationBlockEntity.this.exportOutput ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // Server-authoritative: the client never writes production state.
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    @SuppressWarnings("this-escape") // idiomatic Minecraft block-entity wiring
    public JobStationBlockEntity(BlockPos pos, BlockState state) {
        super(NeroColoniesBlockEntities.JOB_STATION.get(), pos, state,
                ENERGY_CAPACITY, MAX_TRANSFER, UPGRADE_SLOTS, ColonyUpgradeItem.CLASSIFIER);
        installSideConfig(buildSideConfig()).withItems(() -> this);
    }

    /** Power in on every face, modules in through the item channel; nothing ever leaves a station. */
    private static SideConfig buildSideConfig() {
        SideConfig config = SideConfig.builder()
                .channel(Channel.ITEM, SlotGroup.of(SlotGroup.UPGRADE, 0, 1), null)
                .channel(Channel.ENERGY)
                .allow(Channel.ENERGY, SideMode.OUTPUT, false)
                .allow(Channel.ENERGY, SideMode.PUSH, false)
                .defaultPreset(SidePreset.ALL_INPUT)
                .build();
        for (RelativeFace face : RelativeFace.VALUES) {
            config.setMode(Channel.ENERGY, face, SideMode.INPUT);
            config.setMode(Channel.ITEM, face, SideMode.INPUT);
        }
        return config;
    }

    // --- colony binding -----------------------------------------------------

    /** The colony this station produces for, or {@code null} while it stands on unclaimed ground. */
    @Nullable
    public UUID colonyId() {
        return this.colonyId;
    }

    /** The outpost this station stands in, or {@code null}. */
    @Nullable
    public UUID outpostId() {
        return this.outpostId;
    }

    /** This station's live board entry, or {@code null} before the first colony cycle reaches it. */
    @Nullable
    public JobBoard.Station station() {
        return JobBoard.station(this.colonyId, this.worldPosition);
    }

    /** The job this station is set up to run, or {@code null}. */
    @Nullable
    public Identifier jobId() {
        JobBoard.Station station = station();
        return station == null ? null : station.jobId();
    }

    public ContainerData containerData() {
        return this.data;
    }

    /** Whether this station routes its output to the export buffer. */
    public boolean exportOutput() {
        return this.exportOutput;
    }

    /** Flips the export routing. Called from the station's GUI, permission-checked before it gets here. */
    public void setExportOutput(boolean export) {
        if (this.exportOutput != export) {
            this.exportOutput = export;
            this.setChanged();
        }
    }

    // --- power --------------------------------------------------------------

    /** The energy one craft costs here, after {@code EFFICIENCY} modules. */
    public int craftEnergyCost() {
        return (int) Math.max(0L, Math.round(ENERGY_PER_CRAFT * modifiers().energyMultiplier()));
    }

    /** Whether the buffer could pay for a craft right now (the full-rate test). */
    public boolean hasCraftEnergy() {
        int cost = craftEnergyCost();
        return cost <= 0 || this.energy.has(cost);
    }

    /**
     * Spends one craft's energy if it is there.
     *
     * @return {@code true} if the craft was powered
     */
    public boolean consumeCraftEnergy() {
        int cost = craftEnergyCost();
        if (cost <= 0) {
            return true;
        }
        if (!this.energy.has(cost)) {
            return false;
        }
        this.energy.consume(cost);
        return true;
    }

    // --- tick ---------------------------------------------------------------

    @Override
    protected void serverTick(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (--this.lookupCountdown <= 0) {
            this.lookupCountdown = COLONY_LOOKUP_INTERVAL_TICKS;
            refreshColony(serverLevel, pos);
        }
        if (--this.registerCountdown <= 0) {
            this.registerCountdown = REGISTER_INTERVAL_TICKS;
            if (this.colonyId != null) {
                JobBoard.register(this.colonyId, this.outpostId, serverLevel, pos);
            }
        }
    }

    /**
     * Re-resolves which claim this station stands in: the colony first, then any outpost, whose
     * parent colony it then works for. A station on unclaimed ground simply has no colony and does
     * nothing — it is not an error, it is a station somebody has not finished building around.
     */
    private void refreshColony(ServerLevel level, BlockPos pos) {
        ColonyState state = ColonyState.get(level.getServer());
        UUID resolvedColony = null;
        UUID resolvedOutpost = null;

        Colony colony = state.colonyAt(level.dimension(), pos);
        if (colony != null) {
            resolvedColony = colony.colonyId();
        } else {
            Outpost outpost = state.outpostAt(level.dimension(), pos);
            if (outpost != null && state.colony(outpost.parentColonyId()) != null) {
                resolvedColony = outpost.parentColonyId();
                resolvedOutpost = outpost.outpostId();
            }
        }
        if (this.colonyId != null && !this.colonyId.equals(resolvedColony)) {
            JobBoard.unregister(this.colonyId, pos);
        }
        this.colonyId = resolvedColony;
        this.outpostId = resolvedOutpost;
    }

    @Override
    public void setRemoved() {
        if (this.colonyId != null) {
            JobBoard.unregister(this.colonyId, this.worldPosition);
        }
        super.setRemoved();
    }

    // --- menu ---------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return this.level == null
                ? Component.translatable("container.nerocolonies.job_station")
                : this.level.getBlockState(this.worldPosition).getBlock().getName();
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new JobStationMenu(containerId, playerInventory, this, this.data);
    }

    // --- container (upgrade modules only) -----------------------------------

    @Override
    public int getContainerSize() {
        return UPGRADE_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (int slot = 0; slot < this.upgrades.slots(); slot++) {
            if (!this.upgrades.getStack(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < this.upgrades.slots() ? this.upgrades.getStack(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
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
        return slot >= 0 && slot < this.upgrades.slots()
                ? ContainerHelper.takeItem(this.upgrades.items(), slot)
                : ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < this.upgrades.slots()) {
            this.upgrades.setStack(slot, stack);
        }
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return this.upgrades.isModule(stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return net.minecraft.world.Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        for (int slot = 0; slot < this.upgrades.slots(); slot++) {
            this.upgrades.setStack(slot, ItemStack.EMPTY);
        }
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

    // --- persistence --------------------------------------------------------

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("Export", this.exportOutput);
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
        super.loadAdditional(input);
        this.exportOutput = input.getBooleanOr("Export", false);
        // Binding is derived from the claim, never stored: a station that was moved, or whose colony
        // was dissolved while it was unloaded, must re-resolve rather than remember.
        this.colonyId = null;
        this.outpostId = null;
        this.registerCountdown = 0;
        this.lookupCountdown = 0;
    }
}
