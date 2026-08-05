package za.co.neroland.nerocolonies.menu;

import java.util.OptionalInt;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.telemetry.NeroColoniesTelemetry;

/**
 * The single door every NeroColonies GUI goes through — {@code openMenu}, but it cannot take the
 * server thread with it when the host platform misbehaves. <b>Every</b> {@code openMenu} call site
 * in this mod routes through here; a direct {@code player.openMenu(...)} is a review failure.
 *
 * <p>Vanilla {@code Player#openMenu} is a plain call, so anything that throws underneath it unwinds
 * straight out through the packet handler and kills the tick loop. On a stock Fabric/NeoForge/Forge
 * server nothing does. On a Bukkit/Paper hybrid it can: opening a menu there fires
 * {@code InventoryOpenEvent}, and any listening plugin that walks the inventory drags the server's
 * item-meta bridge across modded items it was never written for (see Nerospace's {@code
 * MC-NEROSPACE-E}). Hybrids are not a supported platform and we cannot fix their bridge, but a GUI
 * that refuses to open is a far better outcome than a downed server.
 *
 * <p>Only plain {@link net.minecraft.world.SimpleMenuProvider}-shaped, <b>non-extended</b> menus go
 * through here. NeroColonies syncs colony state through {@code ContainerData} and its own channel,
 * never through extended menu data — which is also why there is no loader-specific open path.
 *
 * <p><b>POPIA/GDPR:</b> the log line and the telemetry report name the menu, never the player. No
 * name, UUID, IP, or position is recorded on this path.
 */
public final class MenuOpener {

    private MenuOpener() {
    }

    /**
     * Opens {@code provider} for {@code player}.
     *
     * @return the container id as vanilla would return it, or {@link OptionalInt#empty()} if the
     *         open was refused by the platform (or by vanilla itself, which also returns empty).
     */
    public static OptionalInt open(Player player, MenuProvider provider) {
        if (player == null || provider == null || player.level() == null
                || player.level().isClientSide()) {
            return OptionalInt.empty();
        }
        try {
            return player.openMenu(provider);
        } catch (RuntimeException | LinkageError e) {
            recover(player, provider, e);
            return OptionalInt.empty();
        }
    }

    private static void recover(Player player, MenuProvider provider, Throwable cause) {
        String menu = describe(provider);
        NeroColoniesCommon.LOGGER.warn(
                "[NeroColonies] The server platform refused to open menu '{}' — the interaction was "
                        + "cancelled instead of being allowed to crash the server thread. This is "
                        + "typically a Bukkit/Paper hybrid server, or a plugin reacting to the "
                        + "inventory-open event, choking on a modded item. Hybrid servers are not a "
                        + "supported platform for NeroColonies.",
                menu, cause);
        // Best-effort cleanup — the platform is already misbehaving, so nothing here may throw again.
        try {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Nothing further to do; the client will resync on its own.
        }
        try {
            player.sendSystemMessage(Component.translatable("gui.nerocolonies.menu_open_failed"));
        } catch (RuntimeException | LinkageError ignored) {
            // The player simply does not get the notice.
        }
        NeroColoniesTelemetry.captureHandledException(cause, "menu_open", menu);
    }

    /** Menu title if it can be resolved, else the provider class — never anything player-specific. */
    private static String describe(MenuProvider provider) {
        try {
            Component title = provider.getDisplayName();
            if (title != null) {
                String text = title.getString();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Fall through to the class name.
        }
        return provider.getClass().getName();
    }
}
