package za.co.neroland.nerocolonies.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyConstruction;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyRoles;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.ColonyStorageBlocks;
import za.co.neroland.nerocolonies.colony.Outpost;
import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;

/**
 * What the map provider reads, behind one seam so tests can seed it without a server. The live implementation reads
 * only state already in memory: the colony, role, life and storage stores, the construction record and the loaded
 * blueprint table. It never loads a chunk and never reads a block.
 */
public interface ColonyMapSource extends ColonyMapAccess.Roles {

    /** A finished colony building: its centre and its blueprint's display-name lang key. */
    record Building(BlockPos centre, String nameKey) {
    }

    /** A Storage Crate ({@code crate}) or Colony Depot. */
    record Storage(ResourceKey<Level> dimension, BlockPos pos, boolean crate) {
    }

    /** Colonies this player owns or is on the access list of (the store's {@code memberOf}). */
    List<Colony> memberColonies(UUID player);

    boolean sandbox(UUID colonyId);

    List<Outpost> outposts();

    List<Building> buildings(Colony colony);

    List<Storage> storage(UUID colonyId);

    /** The live source for {@code server}, or {@code null} without one. */
    static ColonyMapSource of(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        return new ColonyMapSource() {
            @Override
            public List<Colony> memberColonies(UUID player) {
                ColonyState state = ColonyState.get(server);
                List<Colony> out = new ArrayList<>();
                for (UUID id : state.memberOf(player)) {
                    Colony colony = state.colony(id);
                    if (colony != null) {
                        out.add(colony);
                    }
                }
                return out;
            }

            @Override
            public boolean isChief(UUID colonyId, UUID player) {
                return ColonyRoles.get(server).isChief(colonyId, player);
            }

            @Override
            public boolean isEnemy(UUID colonyId, UUID player) {
                return ColonyRoles.get(server).isEnemy(colonyId, player);
            }

            @Override
            public boolean sandbox(UUID colonyId) {
                return ColonyLife.isSandbox(server, colonyId);
            }

            @Override
            public List<Outpost> outposts() {
                return new ArrayList<>(ColonyState.get(server).allOutposts());
            }

            @Override
            public List<Building> buildings(Colony colony) {
                // The same centre ColonyBuildings.placed computes, without its access-spot block reads.
                ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
                if (plan == null) {
                    return List.of();
                }
                Map<Identifier, Blueprint> blueprints = ColonyDefinitions.blueprintsForServer(server);
                List<Building> out = new ArrayList<>();
                for (ColonyConstruction.Structure structure : plan.structures()) {
                    Blueprint blueprint = blueprints.get(structure.blueprint());
                    if (blueprint == null) {
                        continue;
                    }
                    int w = blueprint.width(structure.rotation());
                    int d = blueprint.depth(structure.rotation());
                    out.add(new Building(structure.origin().offset(w / 2, 0, d / 2), blueprint.nameKey()));
                }
                return out;
            }

            @Override
            public List<Storage> storage(UUID colonyId) {
                List<Storage> out = new ArrayList<>();
                for (ColonyStorageBlocks.Box box : ColonyStorageBlocks.get(server).boxes(colonyId)) {
                    out.add(new Storage(box.dimension(), box.pos(), box.crate()));
                }
                return out;
            }
        };
    }
}
