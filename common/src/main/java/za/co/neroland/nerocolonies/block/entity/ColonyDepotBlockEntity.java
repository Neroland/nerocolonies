package za.co.neroland.nerocolonies.block.entity;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import za.co.neroland.nerolandcore.sideconfig.SlotGroup;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyContainer;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.ColonyStores;
import za.co.neroland.nerocolonies.colony.Outpost;
import za.co.neroland.nerocolonies.item.ColonyUpgradeItem;
import za.co.neroland.nerocolonies.menu.ColonyStorageMenu;
import za.co.neroland.nerocolonies.registry.NeroColoniesBlockEntities;

/**
 * A colony depot: a door onto the colony's shared storage.
 *
 * <h2>A depot adds access, not capacity</h2>
 *
 * <p>Every depot in a claim shows the <b>same goods</b> — there is one stock per colony, in
 * {@link ColonyStores}, and this block entity is a window onto it rather than an inventory of its
 * own. Building a second depot therefore gives you somewhere else to stand, not somewhere else to
 * put things; capacity comes from {@code CAPACITY} modules in the beacon, which is where a
 * colony-wide upgrade belongs.
 *
 * <p>That also means a depot has nothing to drop when it breaks and nothing to save. Its contents
 * are not its own, and a colony's goods are not hostage to a stray pickaxe.
 *
 * <h2>Automation</h2>
 *
 * <p>Because the window is an ordinary {@code Container}, the loader capability registrations already
 * wrap it: pipes, hoppers, AE2 and Create insert and extract through the standard item capability
 * with no NeroColonies-specific API. A depot is the natural place to plumb a colony into a wider
 * logistics network.
 */
public class ColonyDepotBlockEntity extends AbstractMachineBlockEntity
        implements WorldlyContainer, MenuProvider {

    /** A depot has no modules of its own and burns no power; both are colony-wide concerns. */
    public static final int UPGRADE_SLOTS = 0;

    public static final int SLOTS = ColonyStores.STORAGE_SLOTS;

    /** How often the depot re-resolves which claim it stands in, in ticks. */
    private static final int COLONY_LOOKUP_INTERVAL_TICKS = 200;

    public static final int DATA_USABLE_SLOTS = 0;
    public static final int DATA_BOUND = 1;
    public static final int DATA_SIZE = 2;

    @Nullable
    private UUID colonyId;

    private int lookupCountdown;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_USABLE_SLOTS -> ColonyDepotBlockEntity.this.usableSlots();
                case DATA_BOUND -> ColonyDepotBlockEntity.this.colonyId == null ? 0 : 1;
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
    public ColonyDepotBlockEntity(BlockPos pos, BlockState state) {
        super(NeroColoniesBlockEntities.COLONY_DEPOT.get(), pos, state,
                0, 0, UPGRADE_SLOTS, ColonyUpgradeItem.CLASSIFIER);
        installSideConfig(buildSideConfig()).withItems(() -> this);
    }

    /** Items in and out on every face: a depot is a logistics port, and gating it would be a trap. */
    private static SideConfig buildSideConfig() {
        int[] all = new int[SLOTS];
        for (int slot = 0; slot < SLOTS; slot++) {
            all[slot] = slot;
        }
        SideConfig config = SideConfig.builder()
                .channel(Channel.ITEM, SlotGroup.of(SlotGroup.INPUT, all), SlotGroup.of(SlotGroup.OUTPUT, all))
                // An energy channel with every face disabled. A depot burns nothing, but the machine
                // base pre-wires an energy view into the side config, and a channel that is declared
                // and switched off is a clearer answer to "may I connect a cable here?" than one that
                // was never declared at all.
                .channel(Channel.ENERGY)
                .defaultPreset(SidePreset.STORAGE)
                .build();
        for (RelativeFace face : RelativeFace.VALUES) {
            config.setMode(Channel.ITEM, face, SideMode.IO);
            config.setMode(Channel.ENERGY, face, SideMode.DISABLED);
        }
        return config;
    }

    // --- colony binding -----------------------------------------------------

    @Nullable
    public UUID colonyId() {
        return this.colonyId;
    }

    public ContainerData containerData() {
        return this.data;
    }

    /** How many of the 54 backing slots this colony has unlocked (0 while unbound). */
    public int usableSlots() {
        ColonyContainer view = view();
        return view == null ? 0 : view.usableSlots();
    }

    /** The colony-storage window, or {@code null} while the depot stands on unclaimed ground. */
    @Nullable
    private ColonyContainer view() {
        if (this.colonyId == null || this.level == null || this.level.getServer() == null) {
            return null;
        }
        UUID id = this.colonyId;
        return ColonyStorage.container(this.level.getServer(), id, () -> capacityModulesOf(id));
    }

    /** The capacity-module count of this colony's beacon, read live so an upgrade applies at once. */
    private int capacityModulesOf(UUID id) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        Colony colony = ColonyState.get(serverLevel.getServer()).colony(id);
        if (colony == null) {
            return 0;
        }
        return serverLevel.getBlockEntity(colony.beaconPos()) instanceof ColonyBeaconBlockEntity beacon
                ? beacon.capacityModules()
                : 0;
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
            Outpost outpost = colonies.outpostAt(serverLevel.dimension(), pos);
            this.colonyId = outpost != null && colonies.colony(outpost.parentColonyId()) != null
                    ? outpost.parentColonyId()
                    : null;
        }
    }

    // --- menu ---------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.nerocolonies.colony_depot");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ColonyStorageMenu(containerId, playerInventory, this, this.data);
    }

    // --- container (a window onto colony storage) ---------------------------

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    public boolean isEmpty() {
        ColonyContainer view = view();
        return view == null || view.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        ColonyContainer view = view();
        return view == null ? ItemStack.EMPTY : view.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ColonyContainer view = view();
        return view == null ? ItemStack.EMPTY : view.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ColonyContainer view = view();
        return view == null ? ItemStack.EMPTY : view.removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        ColonyContainer view = view();
        if (view != null) {
            view.setItem(slot, stack);
        }
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        ColonyContainer view = view();
        return view != null && view.canPlaceItem(slot, stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return net.minecraft.world.Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        // Deliberately a no-op: the goods are the colony's, not this block's, and vanilla calls this
        // on block removal. A depot must never be able to empty a colony by being broken.
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
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // Binding is derived from the claim, never stored.
        this.colonyId = null;
        this.lookupCountdown = 0;
    }
}
