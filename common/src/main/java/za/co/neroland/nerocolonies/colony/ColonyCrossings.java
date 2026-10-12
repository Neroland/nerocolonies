package za.co.neroland.nerocolonies.colony;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.event.ThresholdEvents;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * The two event-style threshold channels: research unlocks and export sales. Each fires once per
 * occurrence with the running count as both value and threshold, so a "world log" quest can ask for
 * "the third research node" or "the first sale" with {@code min_value}.
 *
 * <p>Like every crossing this mod publishes, the scope is the <b>colony id</b> — a place — never a
 * player, and the publisher is named ({@code nerocolonies}) so a listener can tell the source. The
 * gallery sandbox never publishes, and {@code thresholdEventsEnabled} switches it all off. Server
 * thread only.
 */
public final class ColonyCrossings {

    /** Core threshold channel: how many research nodes the colony has unlocked. */
    public static final Identifier CHANNEL_RESEARCH =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "research");

    /** Core threshold channel: how many export sales the colony has made. */
    public static final Identifier CHANNEL_EXPORTS_SHIPPED =
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "exports_shipped");

    private ColonyCrossings() {
    }

    /** Publishes a rising crossing of {@code count} on {@code channel} for the colony. Never throws. */
    public static void count(@Nullable MinecraftServer server, Colony colony, Identifier channel, long count) {
        if (server == null || !NeroColoniesConfig.THRESHOLD_EVENTS_ENABLED.get()
                || ColonyLife.isSandbox(server, colony.colonyId())) {
            return;
        }
        try {
            ThresholdEvents.fire(NeroColoniesCommon.MOD_ID, new ThresholdEvents.ThresholdCrossing(
                    channel, colony.colonyId().toString(), count, count, true));
        } catch (RuntimeException | LinkageError e) {
            // A subscriber that throws must not take the caller with it.
            NeroColoniesCommon.LOGGER.warn(
                    "[NeroColonies] A threshold-event subscriber failed; the colony continued.", e);
        }
    }
}
