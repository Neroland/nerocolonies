package za.co.neroland.nerocolonies.progression;

import java.util.List;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.event.CoreEvents;
import za.co.neroland.nerolandcore.progression.GateEvents.GateChange;
import za.co.neroland.nerolandcore.progression.GateScope;
import za.co.neroland.nerolandcore.progression.ProgressionGates;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.lifecycle.ServerStateReset;

/**
 * The two <b>hook gates</b> a quest pack may name as a {@code mod_hook} reward, and what happens when
 * one opens.
 *
 * <p>A quest mod that wants to say "this player finished a quest of yours" opens the record gate
 * {@code nerocolonies:hook/<id>} for that player through Neroland Core — online or offline — and
 * this class listens on Core's gate events, applies the effect to the player's <b>own colony</b>, and
 * closes the gate again to re-arm it. Nothing here imports a quest mod; the pack is inert data and
 * the only contract is Core's.
 *
 * <ul>
 *   <li>{@link #SUPPLY_DROP} — a fixed, small crate of starter goods lands in the colony's store.
 *       What does not fit is skipped, never dropped on the ground.</li>
 *   <li>{@link #MORALE_BOOST} — morale rises by {@value #MORALE_BOOST_POINTS} points, capped at
 *       100. The morale engine then drifts it back toward the colony's real target at
 *       {@code moraleChangeRate} per cycle, so the lift is temporary by construction and needs no
 *       timer of its own.</li>
 * </ul>
 *
 * <p>Both effects go to the colony rather than the player, so an offline player's colony receives
 * them at once. A player who owns no colony yet keeps the gate open; the effect lands the moment
 * they found one ({@link #onFounded}). The gallery sandbox never receives anything.
 *
 * <p>Hook gates gate nothing and are never checked for access. Effects are idempotent per opening
 * and bounded: a quest completion is not a payment rail.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> the change's target is a player UUID, used only to find their
 * colony and to re-arm the gate through Core; it is never logged.
 */
public final class QuestHooks {

    /** Hook gate: a crate of starter goods for the completing player's colony. */
    public static final Identifier SUPPLY_DROP =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "hook/supply_drop");

    /** Hook gate: a one-off lift to the completing player's colony morale. */
    public static final Identifier MORALE_BOOST =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "hook/morale_boost");

    /** How much a {@link #MORALE_BOOST} adds, in morale points (0..100). */
    public static final double MORALE_BOOST_POINTS = 15.0D;

    private static final List<Identifier> HOOKS = List.of(SUPPLY_DROP, MORALE_BOOST);

    private QuestHooks() {
    }

    /** Registers the gate listener. Called once from common init; safe when Core's bus is quiet. */
    public static void init() {
        try {
            CoreEvents.onProgression(QuestHooks::onChange);
        } catch (RuntimeException | LinkageError e) {
            NeroColoniesCommon.LOGGER.warn(
                    "[NeroColonies] Could not register the quest-hook listener; quest rewards are off.", e);
        }
    }

    private static void onChange(GateChange change) {
        if (!change.open() || change.scope() != GateScope.PLAYER || !HOOKS.contains(change.gate())) {
            return;
        }
        MinecraftServer server = ServerStateReset.currentServer();
        if (server == null) {
            return;
        }
        UUID player;
        try {
            player = UUID.fromString(change.target());
        } catch (IllegalArgumentException e) {
            return;
        }
        deliver(server, player, change.gate());
    }

    /**
     * Called when a player founds a colony: delivers any hook that opened while they owned none. The
     * {@code isOpen} reads are the whole cost on the common path.
     */
    public static void onFounded(@Nullable MinecraftServer server, Colony colony) {
        if (server == null || colony == null || !colony.hasOwner()) {
            return;
        }
        try {
            for (Identifier hook : HOOKS) {
                if (ProgressionGates.isOpen(server, colony.ownerId(), hook)) {
                    deliver(server, colony.ownerId(), hook);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            warn(e);
        }
    }

    /** Applies the hook to the player's own colony and re-arms the gate; no colony means it waits. */
    private static void deliver(MinecraftServer server, UUID player, Identifier hook) {
        Colony colony = ColonyState.get(server).ownedBy(player);
        if (colony == null) {
            return; // the gate stays open; onFounded delivers it later
        }
        try {
            if (!ColonyLife.isSandbox(server, colony.colonyId())) {
                if (SUPPLY_DROP.equals(hook)) {
                    supplyDrop(server, colony);
                } else if (MORALE_BOOST.equals(hook)) {
                    moraleBoost(server, colony);
                }
            }
            // Re-arm. Closing fires a change with open=false, which onChange ignores.
            ProgressionGates.close(server, player, hook);
        } catch (RuntimeException | LinkageError e) {
            warn(e);
        }
    }

    /** The fixed crate: building basics and a few meals, the things a young colony's needs list asks for. */
    private static void supplyDrop(MinecraftServer server, Colony colony) {
        ServerLevel level = server.getLevel(colony.dimension());
        int slots = level != null ? ColonyStorage.usableSlots(level, colony) : ColonyStorage.usableSlots(0);
        List<ItemStack> crate = List.of(
                new ItemStack(Items.COBBLESTONE, 32),
                new ItemStack(Items.OAK_PLANKS, 32),
                new ItemStack(Items.OAK_LOG, 16),
                new ItemStack(Items.BREAD, 16),
                new ItemStack(Items.LANTERN, 4));
        int skipped = 0;
        for (ItemStack stack : crate) {
            skipped += ColonyStorage.insert(server, colony.colonyId(), stack, slots);
        }
        // A count, never a colony or a player (POPIA/GDPR).
        NeroColoniesCommon.LOGGER.debug("[NeroColonies] A supply drop landed ({} item(s) did not fit).", skipped);
    }

    private static void moraleBoost(MinecraftServer server, Colony colony) {
        double next = Math.min(100.0D, colony.morale() + MORALE_BOOST_POINTS);
        if (next > colony.morale()) {
            ColonyState.get(server).put(colony.withMorale(next));
        }
        NeroColoniesCommon.LOGGER.debug("[NeroColonies] A morale boost was applied to a colony.");
    }

    private static void warn(Throwable e) {
        // Never who (POPIA/GDPR).
        NeroColoniesCommon.LOGGER.warn("[NeroColonies] A quest-hook reward failed; colonies are unaffected.", e);
    }
}
