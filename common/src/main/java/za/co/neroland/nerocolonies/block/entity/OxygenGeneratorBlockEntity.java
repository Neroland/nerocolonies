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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.gas.GasBuffer;
import za.co.neroland.nerolandcore.gas.NeroGasStorage;
import za.co.neroland.nerolandcore.gas.NeroGases;
import za.co.neroland.nerolandcore.machine.AbstractMachineBlockEntity;
import za.co.neroland.nerolandcore.sideconfig.Channel;
import za.co.neroland.nerolandcore.sideconfig.RelativeFace;
import za.co.neroland.nerolandcore.sideconfig.SideConfig;
import za.co.neroland.nerolandcore.sideconfig.SideMode;
import za.co.neroland.nerolandcore.sideconfig.SidePreset;
import za.co.neroland.nerolandcore.sideconfig.SlotGroup;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.LifeSupport;
import za.co.neroland.nerocolonies.compat.CompatRegistry;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.item.ColonyUpgradeItem;
import za.co.neroland.nerocolonies.menu.OxygenGeneratorMenu;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * The colony oxygen generator: an electrolyser that turns grid power into breathable gas and files
 * itself with its colony's life support.
 *
 * <h2>Core's gas, not Nerospace's</h2>
 *
 * <p>The tank is Neroland Core's {@link GasBuffer} — {@link Identifier}-keyed, millibuckets — and
 * the gas it makes is {@code nerospace:oxygen}. That is a shared <b>value</b>, not a shared type:
 * using Nerospace's own enum-based gas class would put a Nerospace type on this mod's compile
 * classpath and turn a soft dependency into a hard one. Because the tank speaks Core's gas
 * capability, a Nerospace oxygen tank, a NeroLogistics gas pipe or any third-party gas system can
 * fill or drain it with no NeroColonies-specific API at all.
 *
 * <h2>Why it registers itself</h2>
 *
 * <p>Each tick a running generator files its position with {@link LifeSupport} against the colony
 * whose claim it stands in. The colony tick then drains from the filed generators. The alternative —
 * having the colony search its claim for generators — would be a block scan on a hot path. A
 * generator that stops running (no power, broken, chunk unloaded) simply stops refreshing and its
 * registration expires.
 *
 * <h2>Nerospace absent</h2>
 *
 * <p>On a breathable dimension the generator still builds, still runs and still fills its tank; the
 * colony just never needs the contents. That is the honest behaviour for a machine whose purpose is
 * contingent on where you put it, and it means an Earth-only game can still plumb one in for the
 * day a planet mod arrives.
 */
public class OxygenGeneratorBlockEntity extends AbstractMachineBlockEntity
        implements WorldlyContainer, MenuProvider {

    public static final int UPGRADE_SLOTS = 2;

    public static final int ENERGY_CAPACITY = 50_000;
    public static final int MAX_TRANSFER = 1_000;
    public static final int GAS_CAPACITY = 16_000;

    /** Millibuckets synthesised per tick at base rate, before upgrade modules. */
    public static final int BASE_MB_PER_TICK = 4;

    /** How often the generator refreshes its life-support registration, in ticks. */
    private static final int REGISTER_INTERVAL_TICKS = 40;

    /** How often it re-resolves which colony it stands in, in ticks. Claims move rarely. */
    private static final int COLONY_LOOKUP_INTERVAL_TICKS = 200;

    public static final int DATA_ENERGY_PERMILLE = 0;
    public static final int DATA_ENERGY_SCALE = 1;
    public static final int DATA_GAS_PERMILLE = 2;
    public static final int DATA_GAS_SCALE = 3;
    public static final int DATA_RUNNING = 4;
    public static final int DATA_NEEDED = 5;
    public static final int DATA_SIZE = 6;

    private final GasBuffer gas = new GasBuffer(GAS_CAPACITY, this::setChanged);

    /** The colony this generator feeds, re-resolved on a slow cadence. */
    @Nullable
    private UUID colonyId;

    private int registerCountdown;
    private int lookupCountdown;
    private boolean running;
    private boolean lifeSupportNeeded;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_ENERGY_PERMILLE ->
                        (int) (energy.getRaw() * 1000L / Math.max(1, ENERGY_CAPACITY));
                case DATA_ENERGY_SCALE -> 1000;
                case DATA_GAS_PERMILLE -> (int) (gas.getAmount() * 1000L / Math.max(1, GAS_CAPACITY));
                case DATA_GAS_SCALE -> 1000;
                case DATA_RUNNING -> OxygenGeneratorBlockEntity.this.running ? 1 : 0;
                case DATA_NEEDED -> OxygenGeneratorBlockEntity.this.lifeSupportNeeded ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // Server-authoritative: the client never writes machine state.
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    };

    public OxygenGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(NeroColoniesBlockEntities.OXYGEN_GENERATOR.get(), pos, state,
                ENERGY_CAPACITY, MAX_TRANSFER, UPGRADE_SLOTS, ColonyUpgradeItem.CLASSIFIER);
        installSideConfig(buildSideConfig())
                .withItems(() -> this)
                .withGas(this::getGas);
    }

    /**
     * Power in on every face (an electrolyser only ever consumes), oxygen out on every face, upgrade
     * modules in through the item channel so a pipe can restock them. Gas {@code INPUT} is allowed
     * as well as {@code OUTPUT}: a colony that already has an oxygen supply — a Nerospace tank, a
     * gas pipe network — should be able to feed this tank rather than being made to duplicate it.
     */
    private static SideConfig buildSideConfig() {
        SideConfig config = SideConfig.builder()
                .channel(Channel.ITEM, SlotGroup.of("upgrades", 0, 1), null)
                .channel(Channel.ENERGY)
                .channel(Channel.GAS)
                .allow(Channel.ENERGY, SideMode.OUTPUT, false)
                .allow(Channel.ENERGY, SideMode.PUSH, false)
                .defaultPreset(SidePreset.ALL_DISABLED)
                .build();
        for (RelativeFace face : RelativeFace.VALUES) {
            config.setMode(Channel.ENERGY, face, SideMode.INPUT);
            config.setMode(Channel.GAS, face, SideMode.IO);
            config.setMode(Channel.ITEM, face, SideMode.INPUT);
        }
        return config;
    }

    // --- gas ----------------------------------------------------------------

    /** The tank, as Core's shared gas surface. This is what the side config exposes to pipes. */
    public NeroGasStorage getGas() {
        return this.gas;
    }

    /**
     * Life support's private drain. Deliberately separate from the capability view: colony upkeep
     * must not be blocked by a player having set every face's gas mode to disabled — the side config
     * governs what leaves through the <em>walls</em>, not what the colony consumes internally.
     *
     * @return millibuckets actually drained
     */
    public long drainForLifeSupport(long wanted) {
        if (wanted <= 0 || this.gas.getAmount() <= 0) {
            return 0L;
        }
        return this.gas.drain(wanted, false);
    }

    /** Comparator output (0..15) scaled to the stored oxygen fraction. */
    public int comparatorSignal() {
        long stored = this.gas.getAmount();
        return stored <= 0 ? 0 : 1 + (int) (stored / (double) GAS_CAPACITY * 14.0D);
    }

    public ContainerData containerData() {
        return this.data;
    }

    // --- tick ---------------------------------------------------------------

    @Override
    protected void serverTick(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        this.lifeSupportNeeded = CompatRegistry.requiresLifeSupport(serverLevel);

        electrolyse();

        if (--this.lookupCountdown <= 0) {
            this.lookupCountdown = COLONY_LOOKUP_INTERVAL_TICKS;
            refreshColony(serverLevel, pos);
        }
        if (--this.registerCountdown <= 0) {
            this.registerCountdown = REGISTER_INTERVAL_TICKS;
            if (this.colonyId != null && this.gas.getAmount() > 0) {
                LifeSupport.register(this.colonyId, serverLevel, pos);
            }
        }
    }

    /** Spends energy to make oxygen, scaled by SPEED (throughput) and EFFICIENCY (energy) modules. */
    private void electrolyse() {
        long room = this.gas.getCapacity() - this.gas.getAmount();
        if (room <= 0) {
            this.running = false;
            return;
        }
        int produce = (int) Math.min(
                Math.round(BASE_MB_PER_TICK * modifiers().speedMultiplier()), room);
        if (produce <= 0) {
            this.running = false;
            return;
        }
        long perTick = NeroColoniesConfig.OXYGEN_GENERATOR_ENERGY_PER_TICK.get();
        long cost = Math.round(perTick * modifiers().energyMultiplier());
        if (cost > 0 && this.energy.getAmount() < cost) {
            this.running = false;
            return;
        }
        if (cost > 0) {
            this.energy.consume((int) Math.min(Integer.MAX_VALUE, cost));
        }
        this.gas.fill(LifeSupport.OXYGEN, produce, false);
        this.running = true;
    }

    /** Re-resolves which colony's claim this generator stands in. */
    private void refreshColony(ServerLevel level, BlockPos pos) {
        Colony colony = ColonyState.get(level.getServer()).colonyAt(level.dimension(), pos);
        UUID resolved = colony == null ? null : colony.colonyId();
        if (this.colonyId != null && !this.colonyId.equals(resolved)) {
            LifeSupport.unregister(this.colonyId, pos);
        }
        this.colonyId = resolved;
    }

    @Override
    public void setRemoved() {
        if (this.colonyId != null) {
            LifeSupport.unregister(this.colonyId, this.worldPosition);
        }
        super.setRemoved();
    }

    // --- menu ---------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.nerocolonies.oxygen_generator");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new OxygenGeneratorMenu(containerId, playerInventory, this, this.data);
    }

    // --- container (upgrade modules) ----------------------------------------

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
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("Gas", this.gas.getRawGas().toString());
        output.putInt("GasAmount", this.gas.getRawAmount());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        Identifier stored = Identifier.tryParse(input.getStringOr("Gas", ""));
        this.gas.setRaw(stored == null ? NeroGases.EMPTY : stored, input.getIntOr("GasAmount", 0));
        this.colonyId = null;
        this.registerCountdown = 0;
        this.lookupCountdown = 0;
    }
}
