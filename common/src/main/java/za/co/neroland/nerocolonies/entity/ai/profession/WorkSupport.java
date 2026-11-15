package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.content.ItemAmount;
import za.co.neroland.nerocolonies.content.ItemTarget;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * Shared plumbing for the working trades: walking to work, the land rules, and moving goods in and
 * out of colony storage without ever voiding or duplicating anything.
 */
final class WorkSupport {

    /** Within this distance (squared) a Neran acts on a block instead of walking to it. */
    static final double REACH_SQR = 6.25D;

    private WorkSupport() {
    }

    // --- walking --------------------------------------------------------------

    /** Walks towards {@code pos} if out of reach; returns true when in reach. */
    static boolean reach(ColonistEntity neran, BlockPos pos) {
        return reach(neran, pos, REACH_SQR);
    }

    /** As {@link #reach(ColonistEntity, BlockPos)} with a custom reach (squared). */
    static boolean reach(ColonistEntity neran, BlockPos pos, double reachSqr) {
        if (neran.blockPosition().distSqr(pos) <= reachSqr) {
            neran.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            return true;
        }
        neran.workMemory().setTarget(pos, neran.level().getGameTime());
        BlockPos heading = neran.getNavigation().getTargetPos();
        if (!neran.getNavigation().isInProgress() || heading == null || !heading.equals(pos)) {
            neran.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.9D);
        }
        return false;
    }

    /** Whether the Neran is close enough to its workplace to count as at its post. */
    static boolean atPost(ColonistEntity neran, BlockPos anchor, int radius) {
        return neran.blockPosition().distSqr(anchor) <= (double) radius * radius;
    }

    /** Feet and head clear, firm ground below, no fluid: somewhere a Neran can stand. */
    static boolean standable(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.isLoaded(pos) && level.getFluidState(pos).isEmpty()
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    /** A visible stroke of work: look at the cell and swing. */
    static void strike(ColonistEntity neran, BlockPos pos) {
        neran.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        //? if >=26.3 {
        /*neran.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
        *///?} else {
        neran.swing(InteractionHand.MAIN_HAND);
        //?}
    }

    // --- land rules -----------------------------------------------------------

    /** Loaded and inside this colony's claim. */
    static boolean inClaim(ServerLevel level, Colony colony, BlockPos pos) {
        return level.dimension().equals(colony.dimension()) && level.isLoaded(pos) && colony.contains(pos);
    }

    /** Whether the colony may break this block: in the claim, not protected, no block entity. */
    static boolean mayBreak(ServerLevel level, Colony colony, BlockPos pos, BlockState state) {
        return inClaim(level, colony, pos) && !state.isAir() && !state.is(Construction.PROTECTED)
                && !state.hasBlockEntity() && level.getBlockEntity(pos) == null;
    }

    /** Whether the colony may place a block into this cell: in the claim, unprotected, nobody in it. */
    static boolean mayPlace(ServerLevel level, Colony colony, BlockPos pos) {
        if (!inClaim(level, colony, pos)) {
            return false;
        }
        BlockState existing = level.getBlockState(pos);
        return !existing.is(Construction.PROTECTED) && !existing.hasBlockEntity()
                && level.getBlockEntity(pos) == null && !occupied(level, pos);
    }

    /** Whether any living entity's box overlaps the cell. */
    static boolean occupied(ServerLevel level, BlockPos pos) {
        return !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), LivingEntity::isAlive).isEmpty();
    }

    /** Ground the colony keeps clear of fields and pits: its buildings and the active site. */
    static boolean builtUp(ServerLevel level, Colony colony, BlockPos pos, int margin) {
        MinecraftServer server = level.getServer();
        return Construction.insideStructure(server, colony.colonyId(), pos, margin)
                || Construction.insideActiveSite(server, colony.colonyId(), pos);
    }

    /** Whether a fluid is in or beside (sides and above) the cell. */
    static boolean nearFluid(ServerLevel level, BlockPos pos) {
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) {
            return true;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!level.getFluidState(pos.relative(side)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // --- goods ----------------------------------------------------------------

    /** A vanilla item by path, or null if it is not registered. */
    @Nullable
    static Item item(String path) {
        Identifier id = Identifier.withDefaultNamespace(path);
        return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : null;
    }

    static ItemTarget target(Identifier id, int count) {
        return new ItemTarget(Optional.of(id), Optional.empty(), count);
    }

    static ItemTarget target(String path, int count) {
        return target(Identifier.withDefaultNamespace(path), count);
    }

    /** How many of a vanilla item the colony holds. */
    static int stored(MinecraftServer server, Colony colony, String path) {
        return item(path) == null ? 0 : ColonyStorage.count(server, colony.colonyId(), target(path, 1));
    }

    static int slots(ServerLevel level, Colony colony) {
        return ColonyStorage.usableSlots(level, colony);
    }

    /** Puts a stack in colony storage; what does not fit is dropped at {@code at}, never voided. */
    static void store(ServerLevel level, Colony colony, ItemStack stack, BlockPos at) {
        if (stack.isEmpty()) {
            return;
        }
        int left = ColonyStorage.insert(level.getServer(), colony.colonyId(), stack.copy(), slots(level, colony));
        if (left > 0) {
            Block.popResource(level, at, stack.copyWithCount(left));
        }
    }

    /**
     * Runs one conversion against colony storage, all or nothing: the inputs leave only if every one
     * is there and the output fits; then the output goes in.
     *
     * @return whether it ran
     */
    static boolean convert(ServerLevel level, Colony colony, TradeRecipes.Conversion conversion, BlockPos at) {
        MinecraftServer server = level.getServer();
        Item output = item(conversion.output());
        if (output == null) {
            return false;
        }
        List<ItemTarget> inputs = new ArrayList<>();
        for (TradeRecipes.Part part : conversion.inputs()) {
            if (item(part.item()) == null) {
                return false;
            }
            inputs.add(target(part.item(), part.count()));
        }
        ItemAmount out = new ItemAmount(Identifier.withDefaultNamespace(conversion.output()), conversion.count());
        if (!ColonyStorage.hasAll(server, colony.colonyId(), inputs)
                || !ColonyStorage.fits(server, colony.colonyId(), out, slots(level, colony))) {
            return false;
        }
        if (!ColonyStorage.consume(server, colony.colonyId(), inputs)) {
            return false;
        }
        store(level, colony, new ItemStack(output, conversion.count()), at);
        return true;
    }

    /** Whether storage holds every part. */
    static boolean hasParts(MinecraftServer server, Colony colony, List<TradeRecipes.Part> parts) {
        List<ItemTarget> inputs = new ArrayList<>();
        for (TradeRecipes.Part part : parts) {
            if (item(part.item()) == null) {
                return false;
            }
            inputs.add(target(part.item(), part.count()));
        }
        return ColonyStorage.hasAll(server, colony.colonyId(), inputs);
    }

    /** Takes every part out of storage, all or nothing. */
    static boolean takeParts(MinecraftServer server, Colony colony, List<TradeRecipes.Part> parts) {
        List<ItemTarget> inputs = new ArrayList<>();
        for (TradeRecipes.Part part : parts) {
            if (item(part.item()) == null) {
                return false;
            }
            inputs.add(target(part.item(), part.count()));
        }
        return ColonyStorage.consume(server, colony.colonyId(), inputs);
    }

    // --- carrying -------------------------------------------------------------

    /** Whether the Neran holds anything in its carry inventory other than its own trade tool. */
    static boolean carrying(ColonistEntity neran) {
        for (int i = 0; i < neran.inventory().getContainerSize(); i++) {
            ItemStack stack = neran.inventory().getItem(i);
            if (!stack.isEmpty() && !isOwnTool(neran, stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOwnTool(ColonistEntity neran, ItemStack stack) {
        var trade = neran.profession();
        return trade != null && trade.toolItem() != null && stack.is(trade.toolItem());
    }

    /** The first carried stack that is not the Neran's own tool, or empty. */
    static ItemStack firstCarried(ColonistEntity neran) {
        for (int i = 0; i < neran.inventory().getContainerSize(); i++) {
            ItemStack stack = neran.inventory().getItem(i);
            if (!stack.isEmpty() && !isOwnTool(neran, stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Shows what is carried in the Neran's hand (display only: equipment never drops). */
    static void showCarried(ColonistEntity neran) {
        ItemStack carried = firstCarried(neran);
        if (!carried.isEmpty()) {
            neran.setItemSlot(EquipmentSlot.MAINHAND, carried.copyWithCount(1));
        } else {
            neran.updateHeldTool();
        }
    }

    /** Puts everything carried (not the Neran's own tool) back in colony storage. */
    static void returnCarried(ServerLevel level, Colony colony, ColonistEntity neran) {
        for (int i = 0; i < neran.inventory().getContainerSize(); i++) {
            ItemStack stack = neran.inventory().getItem(i);
            if (!stack.isEmpty() && !isOwnTool(neran, stack)) {
                neran.inventory().setItem(i, ItemStack.EMPTY);
                store(level, colony, stack, neran.blockPosition());
            }
        }
        neran.updateHeldTool();
    }

    /**
     * Inserts a stack into a container, respecting {@code canPlaceItem}, merging first.
     *
     * @return what did not fit
     */
    static ItemStack insertInto(Container container, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int slot = 0; slot < container.getContainerSize() && !rest.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, rest)
                    || !container.canPlaceItem(slot, rest)) {
                continue;
            }
            int room = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
            if (room > 0) {
                int moved = Math.min(room, rest.getCount());
                existing.grow(moved);
                rest.shrink(moved);
            }
        }
        for (int slot = 0; slot < container.getContainerSize() && !rest.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty() || !container.canPlaceItem(slot, rest)) {
                continue;
            }
            int moved = Math.min(Math.min(rest.getMaxStackSize(), container.getMaxStackSize()), rest.getCount());
            container.setItem(slot, rest.copyWithCount(moved));
            rest.shrink(moved);
        }
        if (rest.getCount() != stack.getCount()) {
            container.setChanged();
        }
        return rest;
    }

    /** How many of {@code stack}'s item would fit in the container right now. */
    static int roomFor(Container container, ItemStack stack) {
        int room = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.canPlaceItem(slot, stack)) {
                continue;
            }
            ItemStack existing = container.getItem(slot);
            int max = Math.min(stack.getMaxStackSize(), container.getMaxStackSize());
            if (existing.isEmpty()) {
                room += max;
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                room += Math.max(0, max - existing.getCount());
            }
        }
        return room;
    }
}
