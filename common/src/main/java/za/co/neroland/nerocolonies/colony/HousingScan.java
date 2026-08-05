package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.HousingTier;

/**
 * The claim's housing sweep: how many colonists the colony can house, and how comfortably.
 *
 * <h2>Why it scans blocks, not block entities</h2>
 *
 * <p>Housing is matched by <b>block id</b> against the {@link HousingTier} definitions loaded from
 * datapacks. That is one map lookup per block read, it needs no block entity (so housing costs
 * nothing at all when nobody is looking at it), and — the reason that actually matters — it lets a
 * datapack declare <em>any</em> block as colony housing: a NeroColonies habitat pod, a Nerospace
 * crew module, or a plain vanilla bed. Neither side needs compat code.
 *
 * <h2>Why it is budgeted</h2>
 *
 * <p>A default claim is 97 blocks across. Reading every block inside it every cadence would be the
 * single most expensive thing this mod does, so the sweep is a <b>cursor over the claim's chunks</b>:
 * each slice reads at most {@value #CHUNKS_PER_SLICE} loaded chunks over a band of
 * {@value #BAND_BELOW}+{@value #BAND_ABOVE} levels around the beacon, and slices run
 * {@value #SLICE_INTERVAL_TICKS} ticks apart until the cycle closes. Only when a full cycle closes
 * are the totals committed to the colony record — a half-finished sweep never makes capacity
 * flicker — and the colony then rests for {@code housingScanIntervalTicks}.
 *
 * <p>Unloaded chunks are skipped rather than loaded. A colony whose claim is half-unloaded reports
 * the housing it can actually see, which is also the housing its colonists could actually reach.
 */
public final class HousingScan {

    /** Loaded chunks read per slice. */
    private static final int CHUNKS_PER_SLICE = 2;

    /** Ticks between slices while a cycle is still open. */
    private static final int SLICE_INTERVAL_TICKS = 20;

    /** Levels scanned below the beacon. */
    private static final int BAND_BELOW = 6;

    /** Levels scanned above the beacon. */
    private static final int BAND_ABOVE = 12;

    /** Hard cap on remembered housing positions — enough to seat any colony under the population cap. */
    private static final int MAX_HOME_POSITIONS = 256;

    private HousingScan() {
    }

    /**
     * One colony's sweep state. Owned by the colony beacon's block entity, which is also what drives
     * the sweep, so the state lives exactly as long as the beacon is loaded and never needs saving:
     * a reloaded beacon simply starts a fresh cycle.
     */
    public static final class State {

        private int countdown;
        private int chunkCursor;

        private int runningCapacity;
        private double runningComfort;
        private final List<BlockPos> runningHomes = new ArrayList<>();

        /** Committed results — what the colony and the morale engine actually read. */
        private int capacity;
        private double comfortRatio = 1.0D;
        private List<BlockPos> homes = List.of();

        /** Total housing capacity found on the last completed cycle. */
        public int capacity() {
            return this.capacity;
        }

        /**
         * Capacity-weighted mean comfort of the housing found, 0..1. Defaults to {@code 1.0} before
         * the first cycle closes so a brand-new colony is not penalised for not having been scanned.
         */
        public double comfortRatio() {
            return this.comfortRatio;
        }

        /** Positions of housing blocks found, for home assignment. Bounded and immutable. */
        public List<BlockPos> homes() {
            return this.homes;
        }

        /** Forces the next tick to begin a fresh cycle (used after a claim radius change). */
        public void restart() {
            this.countdown = 0;
            this.chunkCursor = 0;
            this.runningCapacity = 0;
            this.runningComfort = 0.0D;
            this.runningHomes.clear();
        }
    }

    /**
     * Advances the sweep. Called every server tick from the colony beacon's block entity; almost
     * every call is a decrement and a return.
     *
     * @return {@code true} when this call closed a cycle and committed new totals
     */
    public static boolean tick(ServerLevel level, Colony colony, State state) {
        if (--state.countdown > 0) {
            return false;
        }
        Map<Identifier, HousingTier> byBlock = ColonyDefinitions.housingByBlock(level.getServer());
        if (byBlock.isEmpty()) {
            // No housing content at all (an empty datapack): commit zero and rest.
            state.capacity = 0;
            state.comfortRatio = 1.0D;
            state.homes = List.of();
            state.countdown = NeroColoniesConfig.HOUSING_SCAN_INTERVAL_TICKS.get();
            return true;
        }

        int radius = colony.claimRadius();
        int minChunkX = (colony.beaconPos().getX() - radius) >> 4;
        int maxChunkX = (colony.beaconPos().getX() + radius) >> 4;
        int minChunkZ = (colony.beaconPos().getZ() - radius) >> 4;
        int maxChunkZ = (colony.beaconPos().getZ() + radius) >> 4;
        int width = maxChunkX - minChunkX + 1;
        int total = width * (maxChunkZ - minChunkZ + 1);

        int scanned = 0;
        while (scanned < CHUNKS_PER_SLICE && state.chunkCursor < total) {
            int index = state.chunkCursor++;
            int chunkX = minChunkX + (index % width);
            int chunkZ = minChunkZ + (index / width);
            scanned++;
            scanChunk(level, colony, byBlock, state, chunkX, chunkZ);
        }

        if (state.chunkCursor < total) {
            state.countdown = SLICE_INTERVAL_TICKS;
            return false;
        }

        // Cycle closed: commit, reset the cursor, rest.
        state.capacity = state.runningCapacity;
        state.comfortRatio = state.runningCapacity <= 0
                ? 1.0D
                : Math.clamp(state.runningComfort / state.runningCapacity, 0.0D, 1.0D);
        state.homes = List.copyOf(state.runningHomes);
        state.chunkCursor = 0;
        state.runningCapacity = 0;
        state.runningComfort = 0.0D;
        state.runningHomes.clear();
        state.countdown = NeroColoniesConfig.HOUSING_SCAN_INTERVAL_TICKS.get();
        return true;
    }

    /** Reads one chunk's band, tallying every block that matches an unlocked housing tier. */
    private static void scanChunk(ServerLevel level, Colony colony,
            Map<Identifier, HousingTier> byBlock, State state, int chunkX, int chunkZ) {
        if (!level.hasChunk(chunkX, chunkZ)) {
            return; // never load a chunk to look for housing
        }
        int beaconY = colony.beaconPos().getY();
        int minY = Math.max(level.getMinY(), beaconY - BAND_BELOW);
        int maxY = Math.min(level.getMaxY(), beaconY + BAND_ABOVE);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            int x = (chunkX << 4) + dx;
            if (Math.abs(x - colony.beaconPos().getX()) > colony.claimRadius()) {
                continue;
            }
            for (int dz = 0; dz < 16; dz++) {
                int z = (chunkZ << 4) + dz;
                if (Math.abs(z - colony.beaconPos().getZ()) > colony.claimRadius()) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState blockState = level.getBlockState(cursor);
                    if (blockState.isAir()) {
                        continue;
                    }
                    HousingTier tier = byBlock.get(
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                    .getKey(blockState.getBlock()));
                    if (tier == null || !unlocked(colony, tier)) {
                        continue;
                    }
                    state.runningCapacity += tier.capacity();
                    state.runningComfort += tier.comfort() * tier.capacity();
                    if (state.runningHomes.size() < MAX_HOME_POSITIONS) {
                        state.runningHomes.add(cursor.immutable());
                    }
                }
            }
        }
    }

    /** Whether this colony has researched the tier (tiers with no prerequisite are always on). */
    private static boolean unlocked(Colony colony, HousingTier tier) {
        return tier.research()
                .map(node -> colony.researchUnlocked().contains(node.toString()))
                .orElse(Boolean.TRUE);
    }
}
