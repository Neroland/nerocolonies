package za.co.neroland.nerocolonies.progression;

import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.progression.Gate;
import za.co.neroland.nerolandcore.progression.GateDefinitions;
import za.co.neroland.nerolandcore.progression.ProgressionGates;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyStage;
import za.co.neroland.nerocolonies.colony.FoodSupply;
import za.co.neroland.nerocolonies.colony.Morale;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * The progression gates NeroColonies <b>writes</b> — and, deliberately, never reads.
 *
 * <h2>Standalone-first</h2>
 *
 * <p>Nothing in this mod is gated. Not a block, not a recipe, not a research node, not a colony.
 * These gates exist so that <em>other</em> mods and datapacks can key off "this player has a colony"
 * and "this player's colony stands on its own feet" without NeroColonies knowing they exist — the
 * same one-way relationship Nerotech settled on after its own gates were removed. If Core is present
 * and {@code gateWritesEnabled} is on, the flags are set; if not, nothing changes and no player is
 * ever stopped from doing anything.
 *
 * <p>The gate <em>definitions</em> ship as ordinary datapack files under
 * {@code data/nerocolonies/neroland_gates/}, so a pack may re-scope them, re-title them or add
 * prerequisites of its own. Core loads them; this class honours whatever those files say.
 *
 * <h2>Two kinds of gate</h2>
 *
 * <p>The two <b>soft gates</b>, {@link #ESTABLISHED} and {@link #SELF_SUFFICIENT}, may carry
 * {@code requires} and are opened only once those are met ({@link #tryOpen}): forcing a gate open
 * past unmet requirements would make this mod the one that broke somebody's progression tree.
 *
 * <p>The <b>record gates</b> ({@link #STAGE_SETTLED} and the rest of {@link #record}) are personal
 * milestones — "your colony reached Growing", "your colony sold its first export". They have no
 * {@code requires}, are written with the plain offline-capable {@code open} (Core 1.15.0), and exist
 * only so a quest pack can read them. They gate nothing and nothing in this mod ever checks one.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> gate state is Core's, is keyed by the player's existing game UUID,
 * and is erased by Core's own {@code PlayerDataErasure} registration. Nothing here logs identity.
 *
 * <p>Server thread only.
 */
public final class ColonyGates {

    /** Opened for the founder the first time they place a colony beacon. */
    public static final Identifier ESTABLISHED = id("established");

    /**
     * Opened for the owner whose colony is simultaneously housing people, feeding them, breathing and
     * working — the honest definition of a settlement that no longer needs propping up.
     */
    public static final Identifier SELF_SUFFICIENT = id("self_sufficient");

    /** Record gate: the owner's colony reached {@link ColonyStage#SETTLED}. */
    public static final Identifier STAGE_SETTLED = id("stage_settled");
    /** Record gate: the owner's colony reached {@link ColonyStage#GROWING}. */
    public static final Identifier STAGE_GROWING = id("stage_growing");
    /** Record gate: the owner's colony reached {@link ColonyStage#THRIVING}. */
    public static final Identifier STAGE_THRIVING = id("stage_thriving");
    /** Record gate: the owner's colony reached {@link ColonyStage#METROPOLIS}. */
    public static final Identifier STAGE_METROPOLIS = id("stage_metropolis");
    /** Record gate: the owner's colony completed a cycle with life support OK. */
    public static final Identifier LIFE_SUPPORT_ONLINE = id("life_support_online");
    /** Record gate: the owner's colony completed a cycle with food in store and nobody starving. */
    public static final Identifier FOOD_SECURE = id("food_secure");
    /** Record gate: the owner's colony unlocked a research node. */
    public static final Identifier FIRST_RESEARCH = id("first_research");
    /** Record gate: the owner's colony sold its export buffer. */
    public static final Identifier FIRST_EXPORT = id("first_export");
    /** Record gate: the player founded an outpost. */
    public static final Identifier FIRST_OUTPOST = id("first_outpost");

    private ColonyGates() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, path);
    }

    /** The record gate for a stage, or {@code null} for {@link ColonyStage#FOUNDING} (no gate). */
    @Nullable
    public static Identifier stageGate(ColonyStage stage) {
        return switch (stage) {
            case FOUNDING -> null;
            case SETTLED -> STAGE_SETTLED;
            case GROWING -> STAGE_GROWING;
            case THRIVING -> STAGE_THRIVING;
            case METROPOLIS -> STAGE_METROPOLIS;
        };
    }

    /** Called once, from the beacon's founding path, next to Core's own {@code first_colony}. */
    public static void founded(ServerPlayer player) {
        if (player == null || !NeroColoniesConfig.GATE_WRITES_ENABLED.get()) {
            return;
        }
        try {
            ProgressionGates.tryOpen(player, ESTABLISHED);
        } catch (RuntimeException | LinkageError e) {
            warn(e);
        }
    }

    /**
     * Called once per colony cycle. Opens {@link #SELF_SUFFICIENT} for the colony's owner when the
     * colony qualifies, online or not (Core 1.15.0's offline-capable writes).
     *
     * <p>The {@code isOpen} check first is what keeps this cheap: the common case, on the vast
     * majority of cycles, is one map lookup and a return.
     */
    public static void tick(ServerLevel level, Colony colony) {
        if (!NeroColoniesConfig.GATE_WRITES_ENABLED.get() || !colony.hasOwner() || !qualifies(colony)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null || ColonyLife.isSandbox(server, colony.colonyId())) {
            return;
        }
        try {
            if (!ProgressionGates.isOpen(server, colony.ownerId(), SELF_SUFFICIENT)) {
                tryOpen(server, colony.ownerId(), SELF_SUFFICIENT);
            }
        } catch (RuntimeException | LinkageError e) {
            warn(e);
        }
    }

    /**
     * Writes a <b>record</b> gate for a player, online or offline. A no-op when gate writes are off,
     * when the gate is already open, or when the player is the server-owner placeholder. Never throws:
     * a gate write failing is a Core problem, not a colony one.
     *
     * <p>Only the gates declared on this class are ever passed here. Core resolves an undefined gate
     * to server scope, so a record gate must always ship its datapack file.
     */
    public static void record(@Nullable MinecraftServer server, @Nullable UUID player, Identifier gate) {
        if (server == null || player == null || Colony.SERVER_OWNER.equals(player)
                || !NeroColoniesConfig.GATE_WRITES_ENABLED.get()) {
            return;
        }
        try {
            if (!ProgressionGates.isOpen(server, player, gate)) {
                ProgressionGates.open(server, player, gate);
            }
        } catch (RuntimeException | LinkageError e) {
            warn(e);
        }
    }

    /** {@link #record} for a colony's owner, skipping ownerless colonies and the gallery sandbox. */
    public static void recordForOwner(@Nullable MinecraftServer server, Colony colony, Identifier gate) {
        if (server == null || colony == null || !colony.hasOwner()
                || ColonyLife.isSandbox(server, colony.colonyId())) {
            return;
        }
        record(server, colony.ownerId(), gate);
    }

    /**
     * The offline-capable twin of {@link ProgressionGates#tryOpen}: opens {@code gate} for the player
     * only when every gate its definition {@code requires} is already open for them. Core 1.15.0 has
     * no UUID {@code tryOpen}, so the requirement check is done here against the same definitions.
     */
    private static void tryOpen(MinecraftServer server, UUID player, Identifier gate) {
        Gate def = GateDefinitions.forServer(server).get(gate);
        if (def != null) {
            for (Identifier required : def.requires()) {
                if (!ProgressionGates.isOpen(server, player, required)) {
                    return;
                }
            }
        }
        ProgressionGates.open(server, player, gate);
    }

    /** Housed, fed, breathing and working — all four at once. */
    private static boolean qualifies(Colony colony) {
        return colony.population() > 0
                && colony.housingCapacity() >= colony.population()
                && colony.lifeSupportOk()
                && !FoodSupply.starving(colony)
                && !Morale.workStopped(colony);
    }

    private static void warn(Throwable e) {
        // Never who (POPIA/GDPR) — a gate write failing is a Core problem, not a colony one.
        NeroColoniesCommon.LOGGER.warn(
                "[NeroColonies] A progression-gate write failed; colonies are unaffected.", e);
    }
}
