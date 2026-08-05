package za.co.neroland.nerocolonies.progression;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.progression.ProgressionGates;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.FoodSupply;
import za.co.neroland.nerocolonies.colony.Morale;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * The two progression gates NeroColonies <b>writes</b> — and, deliberately, never reads.
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
 * prerequisites of its own. Core loads them; this class only ever calls {@code tryOpen}, which
 * respects whatever those files say.
 *
 * <h2>Why {@code tryOpen} and never {@code open}</h2>
 *
 * <p>{@code tryOpen} checks the gate's own requirements first, so a datapack that made
 * {@code nerocolonies:established} depend on something else keeps that promise. Forcing a gate open
 * past unmet requirements would make this mod the one that broke somebody's progression tree.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> gate state is Core's, is keyed by the player's existing game UUID,
 * and is erased by Core's own {@code PlayerDataErasure} registration. Nothing here logs identity.
 *
 * <p>Server thread only.
 */
public final class ColonyGates {

    /** Opened for the founder the first time they place a colony beacon. */
    public static final Identifier ESTABLISHED =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "established");

    /**
     * Opened for an <b>online</b> owner whose colony is simultaneously housing people, feeding them,
     * breathing and working — the honest definition of a settlement that no longer needs propping up.
     */
    public static final Identifier SELF_SUFFICIENT =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "self_sufficient");

    private ColonyGates() {
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
     * colony qualifies and the owner is connected.
     *
     * <p>Requiring the owner to be online is not a design statement, it is Core's API: a player gate
     * is written against a {@code ServerPlayer}. A colony that becomes self-sufficient while its owner
     * is away simply opens the gate the next cycle after they log back in, which is soon enough for a
     * flag nothing depends on.
     *
     * <p>The {@code isOpen} check first is what keeps this cheap: the common case, on the vast
     * majority of cycles, is one map lookup and a return.
     */
    public static void tick(ServerLevel level, Colony colony) {
        if (!NeroColoniesConfig.GATE_WRITES_ENABLED.get() || !colony.hasOwner() || !qualifies(colony)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(colony.ownerId());
        if (owner == null) {
            return;
        }
        try {
            if (!ProgressionGates.isOpen(owner, SELF_SUFFICIENT)) {
                ProgressionGates.tryOpen(owner, SELF_SUFFICIENT);
            }
        } catch (RuntimeException | LinkageError e) {
            warn(e);
        }
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
