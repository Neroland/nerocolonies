package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The Miner: digs a real quarry near its workplace. The pit is 5 x 5 and up to 16 deep, dug a layer
 * at a time from the top; its rim is left as a spiral stair one step deeper per cell, so the Miner
 * walks down to the face and back out. Only blocks in {@code nerocolonies:quarryable} (stone, ores,
 * dirt, gravel, sand and the like) are broken; never a protected block, never a block entity, and
 * never a cell with fluid in or beside it (the column stops there). Every drop goes to colony storage.
 * A pit is never sited on or beside the colony's buildings or the active construction site.
 */
final class QuarryWork {

    /** What a Miner may dig. Datapack-overridable. */
    static final TagKey<Block> QUARRYABLE = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "quarryable"));

    private static final int SIDE = WorkGeometry.QUARRY_SIDE;
    private static final int DEPTH = WorkGeometry.QUARRY_DEPTH;
    private static final List<int[]> ORDER = WorkGeometry.quarryOrder(SIDE, DEPTH);

    /** Pit centres tried, nearest the workplace first. */
    private static final List<int[]> SITES = WorkGeometry.ringOffsets(SIDE / 2 + 3, 16);

    private static final int SITES_PER_CALL = 3;
    private static final int CELLS_PER_CALL = 16;
    private static final long RETRY_TICKS = 1200L;
    private static final int MAX_STALL = 30;

    /** A Miner digs anything within this distance (squared) of where it stands. */
    private static final double DIG_REACH_SQR = 12.25D;

    private QuarryWork() {
    }

    static boolean work(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
            BlockPos anchor) {
        WorkMemory memory = neran.workMemory();
        long now = level.getGameTime();
        BlockPos quarry = memory.quarry;
        if (quarry == null) {
            if (now >= memory.quarryRetryAt) {
                memory.quarry = searchSite(level, colony, anchor, memory, now);
                memory.quarryCursor = 0;
            }
            if (memory.quarry == null && ColonyLife.isSandbox(level.getServer(), colony.colonyId())) {
                return mineFace(level, neran, colony, profession, anchor); // the gallery's ore face
            }
            return WorkSupport.atPost(neran, anchor, profession.workRadius());
        }
        for (int n = 0; n < CELLS_PER_CALL && memory.quarryCursor < ORDER.size(); n++) {
            int[] cell = ORDER.get(memory.quarryCursor);
            BlockPos pos = quarry.offset(cell[0], -cell[1], cell[2]);
            BlockState state = level.getBlockState(pos);
            if (!diggable(level, colony, pos, state)) {
                memory.quarryCursor++;
                continue;
            }
            if (neran.blockPosition().distSqr(pos) > DIG_REACH_SQR) {
                if (++memory.stall > MAX_STALL) {
                    memory.stall = 0;
                    memory.quarryCursor++; // cannot get at it; leave it standing
                    return false;
                }
                WorkSupport.reach(neran, pos.above(), 0.0D);
                return false;
            }
            memory.stall = 0;
            WorkSupport.strike(neran, pos);
            Construction.breakToStorage(level, colony, pos, state);
            memory.quarryCursor++;
            memory.markProductive(now);
            return true;
        }
        if (memory.quarryCursor >= ORDER.size()) {
            memory.finishedQuarries.add(quarry);
            memory.quarry = null;
            memory.quarryCursor = 0;
        }
        return WorkSupport.atPost(neran, anchor, profession.workRadius());
    }

    /** Gallery only: dig the nearest quarryable block standing at or just above the workplace. */
    private static boolean mineFace(ServerLevel level, ColonistEntity neran, Colony colony,
            ProfessionDefinition profession, BlockPos anchor) {
        BlockPos face = FieldWork.nearest(level, colony, anchor, profession.workRadius(), 0, 2,
                (pos, state) -> diggable(level, colony, pos, state));
        if (face == null) {
            return WorkSupport.atPost(neran, anchor, profession.workRadius());
        }
        if (!WorkSupport.reach(neran, face, DIG_REACH_SQR)) {
            return false;
        }
        WorkSupport.strike(neran, face);
        Construction.breakToStorage(level, colony, face, level.getBlockState(face));
        neran.workMemory().markProductive(level.getGameTime());
        return true;
    }

    private static boolean diggable(ServerLevel level, Colony colony, BlockPos pos, BlockState state) {
        return !state.isAir() && state.is(QUARRYABLE) && WorkSupport.mayBreak(level, colony, pos, state)
                && !WorkSupport.nearFluid(level, pos) && !WorkSupport.builtUp(level, colony, pos, 0);
    }

    @Nullable
    private static BlockPos searchSite(ServerLevel level, Colony colony, BlockPos anchor, WorkMemory memory,
            long now) {
        for (int n = 0; n < SITES_PER_CALL; n++) {
            if (memory.quarrySearchCursor >= SITES.size()) {
                memory.quarrySearchCursor = 0;
                memory.quarryRetryAt = now + RETRY_TICKS;
                return null;
            }
            int[] offset = SITES.get(memory.quarrySearchCursor++);
            BlockPos corner = anchor.offset(offset[0] - SIDE / 2, -1, offset[1] - SIDE / 2);
            if (!memory.finishedQuarries.contains(corner) && goodSite(level, colony, corner)) {
                memory.quarrySearchCursor = 0;
                return corner.immutable();
            }
        }
        return null;
    }

    private static boolean goodSite(ServerLevel level, Colony colony, BlockPos corner) {
        int workable = 0;
        for (int dx = -1; dx <= SIDE; dx++) {
            for (int dz = -1; dz <= SIDE; dz++) {
                BlockPos top = corner.offset(dx, 0, dz);
                if (!WorkSupport.inClaim(level, colony, top)
                        || !WorkSupport.inClaim(level, colony, top.below(DEPTH))) {
                    return false;
                }
                boolean inside = dx >= 0 && dz >= 0 && dx < SIDE && dz < SIDE;
                if (!inside) {
                    continue;
                }
                if (WorkSupport.builtUp(level, colony, top, 2) || !level.getFluidState(top).isEmpty()) {
                    return false;
                }
                BlockState state = level.getBlockState(top);
                if (state.is(Construction.PROTECTED) || state.hasBlockEntity()) {
                    return false;
                }
                if (state.is(QUARRYABLE)) {
                    workable++;
                }
            }
        }
        return WorkGeometry.acceptField(workable, SIDE * SIDE);
    }
}
