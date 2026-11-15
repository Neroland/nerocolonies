package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.block.entity.ResearchStationBlockEntity;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.Research;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ItemAmount;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.content.ResearchNode;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * The Researcher: works at the colony's research station (or its own building when the colony has
 * none nearby) and prepares the colony's next research. For each research the colony could take next
 * but cannot yet pay for, it refines what the cost is missing from what storage holds (sand to glass,
 * raw iron to ingots, sugar cane to paper, paper and leather to a book, ...), one batch at a time, all
 * or nothing. The unlock itself stays the player's choice at the station.
 */
final class StudyWork {

    private static final int STATION_RADIUS = 8;
    private static final int STATION_HEIGHT = 3;
    private static final long STATION_RETRY = 1200L;

    /** Research nodes looked at per call. */
    private static final int NODES_PER_CALL = 16;

    private StudyWork() {
    }

    static boolean work(ServerLevel level, ColonistEntity neran, Colony colony, ProfessionDefinition profession,
            BlockPos anchor) {
        WorkMemory memory = neran.workMemory();
        long now = level.getGameTime();
        if (memory.station != null && !isStation(level, colony, memory.station)) {
            memory.station = null;
        }
        if (memory.station == null && now >= memory.stationRetryAt) {
            memory.station = findStation(level, colony, anchor);
            memory.stationRetryAt = now + STATION_RETRY;
        }
        BlockPos desk = memory.station != null ? memory.station : anchor;
        if (!WorkSupport.reach(neran, desk)) {
            return false;
        }
        WorkSupport.strike(neran, desk);
        if (prepare(level, colony, desk)) {
            memory.markProductive(now);
        }
        return true;
    }

    /** Refines one batch of something the next research is missing; returns whether it did. */
    private static boolean prepare(ServerLevel level, Colony colony, BlockPos at) {
        MinecraftServer server = level.getServer();
        List<ResearchNode> nodes = new ArrayList<>(ColonyDefinitions.researchForServer(server).values());
        nodes.sort(Comparator.comparing(node -> node.id().toString()));
        int looked = 0;
        for (ResearchNode node : nodes) {
            if (!Research.isAvailable(colony, node) || Research.canAfford(server, colony, node)) {
                continue;
            }
            if (++looked > NODES_PER_CALL) {
                break;
            }
            for (ItemAmount line : node.cost()) {
                if (!"minecraft".equals(line.item().getNamespace())
                        || ColonyStorage.count(server, colony.colonyId(), line) >= line.count()) {
                    continue;
                }
                TradeRecipes.Conversion step = TradeRecipes.RESEARCH_PREP.get(line.item().getPath());
                if (step != null && WorkSupport.convert(level, colony, step, at)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isStation(ServerLevel level, Colony colony, BlockPos pos) {
        return WorkSupport.inClaim(level, colony, pos)
                && level.getBlockEntity(pos) instanceof ResearchStationBlockEntity station
                && colony.colonyId().equals(station.colonyId());
    }

    @Nullable
    private static BlockPos findStation(ServerLevel level, Colony colony, BlockPos anchor) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -STATION_RADIUS; dx <= STATION_RADIUS; dx++) {
            for (int dz = -STATION_RADIUS; dz <= STATION_RADIUS; dz++) {
                for (int dy = -STATION_HEIGHT; dy <= STATION_HEIGHT; dy++) {
                    BlockPos pos = anchor.offset(dx, dy, dz);
                    if (pos.distSqr(anchor) < bestDistance && isStation(level, colony, pos)) {
                        best = pos;
                        bestDistance = pos.distSqr(anchor);
                    }
                }
            }
        }
        return best;
    }
}
