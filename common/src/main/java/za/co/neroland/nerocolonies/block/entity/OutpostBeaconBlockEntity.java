package za.co.neroland.nerocolonies.block.entity;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.JobBoard;
import za.co.neroland.nerocolonies.colony.Outpost;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.item.ColonyUpgradeItem;
import za.co.neroland.nerocolonies.menu.OutpostMenu;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * The outpost beacon: a colony beacon with almost everything taken out.
 *
 * <p>It anchors an {@link Outpost} record — a small claim that belongs to a parent colony — and that
 * is the whole of it. No colony tick, no morale, no population, no food store, no research: the
 * parent already has all of those and an outpost is part of the parent, not a smaller copy of it.
 * What it does own is a {@link UpgradeType#RANGE} slot, because how far a remote site reaches is the
 * one thing worth tuning per outpost.
 *
 * <p>Job stations inside the claim file themselves against the <b>parent</b> colony (tagged with this
 * outpost's id) and are worked on the parent's colony tick, under the parent's morale, with their
 * output going to the parent's storage. That is what "an outpost feeds its parent" means mechanically,
 * and it needs no new tick and no new budget.
 *
 * <p><b>An orphan goes inert, it does not re-parent.</b> If the parent colony is dissolved the record
 * is removed with it and this block entity resolves to nothing: no production, no claim, no quiet
 * reattachment to whichever colony happens to be nearest. That last part would be a claim exploit.
 */
public class OutpostBeaconBlockEntity extends AbstractMachineBlockEntity
        implements WorldlyContainer, MenuProvider {

    /** One module slot: {@code RANGE}, and nothing else is meaningful at an outpost. */
    public static final int UPGRADE_SLOTS = 1;

    public static final int ENERGY_CAPACITY = 10_000;
    public static final int MAX_TRANSFER = 500;

    /** Idle draw, as at the colony beacon: a claim field costs a trickle, and no power is no trickle. */
    public static final int IDLE_ENERGY_PER_TICK = 1;

    /** How often the outpost pushes upgrade-derived changes (claim radius) onto its record. */
    private static final int REFRESH_INTERVAL_TICKS = 100;

    public static final int DATA_ENERGY_PERMILLE = 0;
    public static final int DATA_ENERGY_SCALE = 1;
    public static final int DATA_BOUND = 2;
    public static final int DATA_PARENT_ALIVE = 3;
    public static final int DATA_CLAIM_RADIUS = 4;
    public static final int DATA_COLONIST_CAP = 5;
    public static final int DATA_JOB_SLOTS = 6;
    public static final int DATA_STATIONS = 7;
    public static final int DATA_SIZE = 8;

    /** The outpost record this beacon anchors, or {@code null} until placement binds one. */
    @Nullable
    private UUID outpostId;

    private int refreshCountdown;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            Outpost outpost = OutpostBeaconBlockEntity.this.outpost();
            Colony parent = OutpostBeaconBlockEntity.this.parent();
            return switch (index) {
                case DATA_ENERGY_PERMILLE ->
                        (int) (energy.getRaw() * 1000L / Math.max(1, ENERGY_CAPACITY));
                case DATA_ENERGY_SCALE -> 1000;
                case DATA_BOUND -> outpost == null ? 0 : 1;
                case DATA_PARENT_ALIVE -> parent == null ? 0 : 1;
                case DATA_CLAIM_RADIUS -> outpost == null ? 0 : outpost.claimRadius();
                case DATA_COLONIST_CAP -> NeroColoniesConfig.OUTPOST_COLONIST_CAP.get();
                case DATA_JOB_SLOTS -> NeroColoniesConfig.OUTPOST_JOB_SLOTS.get();
                case DATA_STATIONS -> parent == null ? 0 : JobBoard.stationCount(parent.colonyId());
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // Server-authoritative.
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    @SuppressWarnings("this-escape") // idiomatic Minecraft block-entity wiring
    public OutpostBeaconBlockEntity(BlockPos pos, BlockState state) {
        super(NeroColoniesBlockEntities.OUTPOST_BEACON.get(), pos, state,
                ENERGY_CAPACITY, MAX_TRANSFER, UPGRADE_SLOTS, ColonyUpgradeItem.CLASSIFIER);
        installSideConfig(buildSideConfig()).withItems(() -> this);
    }

    private static SideConfig buildSideConfig() {
        SideConfig config = SideConfig.builder()
                .channel(Channel.ITEM, SlotGroup.of(SlotGroup.UPGRADE, 0), null)
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

    // --- binding ------------------------------------------------------------

    @Nullable
    public UUID outpostId() {
        return this.outpostId;
    }

    /** Binds this beacon to an outpost record. Called once, from the placement flow. */
    public void bind(UUID id) {
        this.outpostId = id;
        this.refreshCountdown = 0;
        this.setChanged();
    }

    /** The outpost record, or {@code null}. Server-side. */
    @Nullable
    public Outpost outpost() {
        if (this.outpostId == null || !(this.level instanceof ServerLevel serverLevel)) {
            return null;
        }
        MinecraftServer server = serverLevel.getServer();
        return server == null ? null : ColonyState.get(server).outpost(this.outpostId);
    }

    /** The parent colony, or {@code null} when the outpost is unbound or orphaned. */
    @Nullable
    public Colony parent() {
        Outpost outpost = outpost();
        if (outpost == null || !(this.level instanceof ServerLevel serverLevel)) {
            return null;
        }
        MinecraftServer server = serverLevel.getServer();
        return server == null ? null : ColonyState.get(server).colony(outpost.parentColonyId());
    }

    public ContainerData containerData() {
        return this.data;
    }

    // --- tick ---------------------------------------------------------------

    @Override
    protected void serverTick(Level level, BlockPos pos, BlockState state) {
        if (this.energy.has(IDLE_ENERGY_PER_TICK)) {
            this.energy.consume(IDLE_ENERGY_PER_TICK);
        }
        if (this.outpostId == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (--this.refreshCountdown > 0) {
            return;
        }
        this.refreshCountdown = REFRESH_INTERVAL_TICKS;
        ColonyState colonies = ColonyState.get(serverLevel.getServer());
        Outpost outpost = colonies.outpost(this.outpostId);
        if (outpost == null) {
            // The record is gone (the parent was dissolved, or the store was recovered). Unbind
            // rather than keep pointing at nothing.
            this.outpostId = null;
            this.setChanged();
            return;
        }
        int radius = ColonyClaims.effectiveOutpostRadius(modifiers().rangeBonus());
        if (radius != outpost.claimRadius()) {
            colonies.putOutpost(outpost.withClaimRadius(radius));
        }
    }

    // --- menu ---------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.nerocolonies.outpost_beacon");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new OutpostMenu(containerId, playerInventory, this, this.data);
    }

    // --- container (one upgrade module) -------------------------------------

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

    /** The module, for the drop-on-break path. */
    public NonNullList<ItemStack> allContents() {
        NonNullList<ItemStack> contents = NonNullList.withSize(UPGRADE_SLOTS, ItemStack.EMPTY);
        for (int slot = 0; slot < UPGRADE_SLOTS; slot++) {
            contents.set(slot, getItem(slot));
        }
        return contents;
    }

    // --- persistence --------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (this.outpostId != null) {
            output.putString("OutpostId", this.outpostId.toString());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        String raw = input.getStringOr("OutpostId", "");
        if (raw.isEmpty()) {
            this.outpostId = null;
        } else {
            try {
                this.outpostId = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                this.outpostId = null; // a malformed id is an unbound outpost, not a crash
            }
        }
        this.refreshCountdown = 0;
    }
}
