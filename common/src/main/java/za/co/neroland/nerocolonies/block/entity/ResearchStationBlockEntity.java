package za.co.neroland.nerocolonies.block.entity;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;
import za.co.neroland.nerolandcore.sideconfig.Channel;
import za.co.neroland.nerolandcore.sideconfig.RelativeFace;
import za.co.neroland.nerolandcore.sideconfig.SideConfig;
import za.co.neroland.nerolandcore.sideconfig.SideMode;
import za.co.neroland.nerolandcore.sideconfig.SidePreset;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.Outpost;
import za.co.neroland.nerocolonies.colony.ResearchEffects;
import za.co.neroland.nerocolonies.item.ColonyUpgradeItem;
import za.co.neroland.nerocolonies.menu.ResearchMenu;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * The research station: where a colony spends goods and power to unlock a node.
 *
 * <h2>It stores nothing</h2>
 *
 * <p>No inventory, no modules, no per-station state. A node's cost is paid out of <b>colony
 * storage</b> and the unlock is written to the <b>colony record</b>; all this block owns is an energy
 * buffer and the fact of standing inside a claim. Two research stations in one colony are two doors
 * onto the same research, not two research programmes — which is the same rule the colony depot
 * follows, and for the same reason: a colony is the unit, not a block.
 *
 * <h2>Power is the pace</h2>
 *
 * <p>Each unlock costs {@value #ENERGY_PER_UNLOCK}. Unlike a job station this is a hard requirement
 * rather than a rate penalty: research is a discrete event, so there is nothing to slow down — it
 * either happens or it waits for the buffer to fill. That also gives the buffer a visible purpose,
 * which is the honest test of whether a machine should have one.
 *
 * <h2>Privacy</h2>
 *
 * <p>Research is colony-local and therefore not personal data. The data slots carry counts and
 * machine state; who may press the button is decided from the colony record, server-side.
 */
public class ResearchStationBlockEntity extends AbstractMachineBlockEntity
        implements WorldlyContainer, MenuProvider {

    /** A research station has no modules: there is nothing per-station for one to modify. */
    public static final int UPGRADE_SLOTS = 0;

    public static final int ENERGY_CAPACITY = 100_000;
    public static final int MAX_TRANSFER = 2_000;

    /** Energy one research unlock costs. */
    public static final int ENERGY_PER_UNLOCK = 5_000;

    /** How often the station re-resolves which claim it stands in, in ticks. */
    private static final int COLONY_LOOKUP_INTERVAL_TICKS = 200;

    public static final int DATA_ENERGY_PERMILLE = 0;
    public static final int DATA_ENERGY_SCALE = 1;
    public static final int DATA_BOUND = 2;
    public static final int DATA_UNLOCKED = 3;
    public static final int DATA_JOB_SLOTS = 4;
    public static final int DATA_POWERED = 5;
    public static final int DATA_SIZE = 6;

    @Nullable
    private UUID colonyId;

    private int lookupCountdown;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            Colony colony = ResearchStationBlockEntity.this.colony();
            return switch (index) {
                case DATA_ENERGY_PERMILLE ->
                        (int) (energy.getRaw() * 1000L / Math.max(1, ENERGY_CAPACITY));
                case DATA_ENERGY_SCALE -> 1000;
                case DATA_BOUND -> colony == null ? 0 : 1;
                case DATA_UNLOCKED -> colony == null ? 0 : colony.researchUnlocked().size();
                case DATA_JOB_SLOTS -> colony == null ? 0 : ResearchEffects.jobSlots(colony);
                case DATA_POWERED -> ResearchStationBlockEntity.this.hasUnlockEnergy() ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // Server-authoritative: the client never writes research state.
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    @SuppressWarnings("this-escape") // idiomatic Minecraft block-entity wiring
    public ResearchStationBlockEntity(BlockPos pos, BlockState state) {
        super(NeroColoniesBlockEntities.RESEARCH_STATION.get(), pos, state,
                ENERGY_CAPACITY, MAX_TRANSFER, UPGRADE_SLOTS, ColonyUpgradeItem.CLASSIFIER);
        installSideConfig(buildSideConfig());
    }

    /** Power in on every face and nothing else: there is no item channel because there are no slots. */
    private static SideConfig buildSideConfig() {
        SideConfig config = SideConfig.builder()
                .channel(Channel.ENERGY)
                .allow(Channel.ENERGY, SideMode.OUTPUT, false)
                .allow(Channel.ENERGY, SideMode.PUSH, false)
                .defaultPreset(SidePreset.ALL_INPUT)
                .build();
        for (RelativeFace face : RelativeFace.VALUES) {
            config.setMode(Channel.ENERGY, face, SideMode.INPUT);
        }
        return config;
    }

    // --- colony binding -----------------------------------------------------

    @Nullable
    public UUID colonyId() {
        return this.colonyId;
    }

    /** The colony this station researches for, or {@code null}. Server-side. */
    @Nullable
    public Colony colony() {
        if (this.colonyId == null || !(this.level instanceof ServerLevel serverLevel)) {
            return null;
        }
        MinecraftServer server = serverLevel.getServer();
        return server == null ? null : ColonyState.get(server).colony(this.colonyId);
    }

    public ContainerData containerData() {
        return this.data;
    }

    // --- power --------------------------------------------------------------

    /** Whether the buffer holds a full unlock's worth of energy. */
    public boolean hasUnlockEnergy() {
        return ENERGY_PER_UNLOCK <= 0 || this.energy.has(ENERGY_PER_UNLOCK);
    }

    /** The buffer's current contents, for the unlock check. */
    public long storedEnergy() {
        return this.energy.getAmount();
    }

    /** Spends one unlock's energy. Called only after the unlock itself has succeeded. */
    public void spendUnlockEnergy() {
        if (ENERGY_PER_UNLOCK > 0 && this.energy.has(ENERGY_PER_UNLOCK)) {
            this.energy.consume(ENERGY_PER_UNLOCK);
        }
    }

    // --- tick ---------------------------------------------------------------

    @Override
    protected void serverTick(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (--this.lookupCountdown <= 0) {
            this.lookupCountdown = COLONY_LOOKUP_INTERVAL_TICKS;
            ColonyState colonies = ColonyState.get(serverLevel.getServer());
            Colony colony = colonies.colonyAt(serverLevel.dimension(), pos);
            if (colony != null) {
                this.colonyId = colony.colonyId();
                return;
            }
            // An outpost has no research of its own, but a station standing in one still serves the
            // parent — an outpost is part of the same colony, just further away.
            Outpost outpost = colonies.outpostAt(serverLevel.dimension(), pos);
            this.colonyId = outpost != null && colonies.colony(outpost.parentColonyId()) != null
                    ? outpost.parentColonyId()
                    : null;
        }
    }

    // --- menu ---------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.nerocolonies.research_station");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ResearchMenu(containerId, playerInventory, this, this.data);
    }

    // --- container (empty; the block has no slots) --------------------------

    @Override
    public int getContainerSize() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        // No slots.
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return net.minecraft.world.Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        // No slots.
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return new int[0];
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    // --- persistence --------------------------------------------------------

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // Binding is derived from the claim, never stored.
        this.colonyId = null;
        this.lookupCountdown = 0;
    }
}
