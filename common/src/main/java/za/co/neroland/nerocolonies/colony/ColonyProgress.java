package za.co.neroland.nerocolonies.colony;

import java.util.UUID;

import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.event.ThresholdEvents;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.link.ColonyLinkEvents;
import za.co.neroland.nerocolonies.link.ColonyLinkModule;

/**
 * The colony's growth stage: deciding it, advancing it, and announcing it.
 *
 * <p>Stages only ever go up. {@link ColonyStage#FOUNDING} becomes {@link ColonyStage#SETTLED} the
 * cycle every Starter Works blueprint has been built once; after that the population and structure
 * thresholds in config decide (see {@link Growth#earnedStage}).
 *
 * <h2>Old worlds</h2>
 *
 * <p>A colony from before stages existed has no stage on record. The first time it is asked, it is
 * placed: {@code SETTLED} if it already has a home and a farm of some kind (the pre-0.3 equivalents of
 * the Starter Works) or more people than its founders, otherwise {@code FOUNDING}. That is the
 * migration rule documented in the changelog.
 */
public final class ColonyProgress {

    /** Core threshold channel: the colony's stage ordinal. */
    public static final Identifier CHANNEL_STAGE =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "stage");

    private ColonyProgress() {
    }

    /** The colony's stage, migrating a pre-stage colony on first ask. */
    public static ColonyStage stage(MinecraftServer server, Colony colony) {
        ColonyLife store = ColonyLife.get(server);
        ColonyLife.Life life = store.life(colony.colonyId());
        if (!life.stageKnown()) {
            life.setStage(migratedStage(server, colony));
            store.touch();
        }
        return life.stage();
    }

    /** The stage a colony would have on its first 0.3 load. */
    private static ColonyStage migratedStage(MinecraftServer server, Colony colony) {
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
        boolean housing = false;
        boolean farm = false;
        if (plan != null) {
            for (var entry : ColonyDefinitions.blueprintsForServer(server).values()) {
                if (plan.builtCount(entry.id()) <= 0) {
                    continue;
                }
                housing |= entry.category() == Blueprint.Category.HOUSING;
                farm |= entry.category() == Blueprint.Category.FARM;
            }
        }
        if ((housing && farm) || colony.population() > Population.founderFloor()) {
            return ColonyStage.SETTLED;
        }
        return ColonyStage.FOUNDING;
    }

    /** Whether every Starter Works blueprint has been built at least once. */
    public static boolean starterWorksDone(MinecraftServer server, Colony colony) {
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
        boolean anyStarter = false;
        for (Blueprint blueprint : ColonyDefinitions.blueprintsForServer(server).values()) {
            if (!blueprint.starter()) {
                continue;
            }
            anyStarter = true;
            if (plan == null || plan.builtCount(blueprint.id()) <= 0 && !upgradedFrom(server, plan, blueprint)) {
                return false;
            }
        }
        return anyStarter || plan != null; // a pack with no Starter Works never gates
    }

    /** Whether a building that started as {@code starter} now stands as one of its upgrades. */
    private static boolean upgradedFrom(MinecraftServer server, ColonyConstruction.Plan plan, Blueprint starter) {
        Identifier next = starter.upgradeTo().orElse(null);
        int guard = 0;
        while (next != null && guard++ < 9) {
            if (plan.builtCount(next) > 0) {
                return true;
            }
            Blueprint upgraded = ColonyDefinitions.blueprintsForServer(server).get(next);
            next = upgraded == null ? null : upgraded.upgradeTo().orElse(null);
        }
        return false;
    }

    /** The Starter Works still missing, for the needs list and GUI. */
    public static java.util.List<Blueprint> missingStarterWorks(MinecraftServer server, Colony colony) {
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
        java.util.List<Blueprint> missing = new java.util.ArrayList<>();
        for (Blueprint blueprint : ColonyDefinitions.blueprintsByPriority(server)) {
            if (blueprint.starter() && (plan == null
                    || plan.builtCount(blueprint.id()) <= 0 && !upgradedFrom(server, plan, blueprint))) {
                missing.add(blueprint);
            }
        }
        return missing;
    }

    /**
     * Advances the stage if it has been earned. Called once per colony cycle.
     *
     * @return the new stage if it changed this cycle, otherwise null
     */
    @Nullable
    public static ColonyStage tick(ServerLevel level, Colony colony) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return null;
        }
        ColonyStage current = stage(server, colony);
        boolean starter = current.atLeast(ColonyStage.SETTLED) || starterWorksDone(server, colony);
        ColonyStage earned = Growth.earnedStage(starter, colony.population(),
                Construction.structuresBuilt(server, colony.colonyId()), Growth.thresholds());
        if (!earned.atLeast(current) || earned == current) {
            return null;
        }
        ColonyLife store = ColonyLife.get(server);
        store.life(colony.colonyId()).setStage(earned);
        store.touch();
        announce(level, colony, earned);
        return earned;
    }

    /** Claim radius bonus a colony has grown into (zero before Growing). */
    public static int claimBonus(MinecraftServer server, Colony colony) {
        if (!stage(server, colony).atLeast(ColonyStage.GROWING)) {
            return 0;
        }
        return Growth.claimBonusFor(colony.population(), Construction.structuresBuilt(server, colony.colonyId()));
    }

    private static void announce(ServerLevel level, Colony colony, ColonyStage stage) {
        MinecraftServer server = level.getServer();
        if (ColonyLife.isSandbox(server, colony.colonyId())) {
            return;
        }
        Component message = Component.translatable("message.nerocolonies.stage_advanced",
                colony.name(), Component.translatable("stage.nerocolonies." + stage.key()));
        tellMembers(server, colony, message);
        if (NeroColoniesConfig.THRESHOLD_EVENTS_ENABLED.get()) {
            try {
                ThresholdEvents.fire(new ThresholdEvents.ThresholdCrossing(
                        CHANNEL_STAGE, colony.colonyId().toString(), stage.ordinal(), 0L, true));
            } catch (RuntimeException | LinkageError e) {
                NeroColoniesCommon.LOGGER.warn(
                        "[NeroColonies] A threshold-event subscriber failed; the colony tick continued.", e);
            }
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("stage", stage.key());
        extra.addProperty("stage_index", stage.ordinal());
        ColonyLinkEvents.colonyEvent(colony, ColonyLinkModule.TOPIC_STAGE, extra);
        NeroColoniesCommon.LOGGER.debug("[NeroColonies] A colony reached stage {}.", stage.key());
    }

    /** Sends a system message to every online member of the colony. Names nobody. */
    public static void tellMembers(MinecraftServer server, Colony colony, Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (colony.isMember(id)) {
                player.sendSystemMessage(message);
            }
        }
    }
}
