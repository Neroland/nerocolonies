package za.co.neroland.nerocolonies.platform;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
//? if >=1.21.11 {
import net.minecraft.server.permissions.Permissions;
//?}

/**
 * The handful of vanilla calls whose shape differs between the Minecraft lines NeroColonies builds for,
 * in one place, so gameplay code stays one copy. Each method is a straight pass-through on 26.x.
 *
 * <p>Privacy (POPIA/GDPR): {@link #cachedName} only reads the server's own name cache, exactly as the
 * callers did before; nothing is stored or logged here.
 */
public final class VersionCompat {

    private VersionCompat() {
    }

    /** A chat-line system message to one player. */
    public static void systemMessage(Player player, Component message) {
        //? if >=26.1 {
        player.sendSystemMessage(message);
        //?} else {
        /*player.displayClientMessage(message, false);
        *///?}
    }

    /** A message above the hotbar for one player. */
    public static void overlayMessage(Player player, Component message) {
        //? if >=26.1 {
        player.sendOverlayMessage(message);
        //?} else {
        /*player.displayClientMessage(message, true);
        *///?}
    }

    /** The level's day clock (ticks), which drives the colony day/night cycle. */
    public static long clockTime(Level level) {
        //? if >=26.1 {
        return level.getDefaultClockTime();
        //?} else {
        /*return level.getDayTime();
        *///?}
    }

    /** The highest block Y a level can hold (inclusive). */
    public static int maxY(Level level) {
        //? if >=1.21.2 {
        return level.getMaxY();
        //?} else {
        /*return level.getMaxBuildHeight() - 1;
        *///?}
    }

    /** The player's level, typed as the server level it always is for a server player. */
    public static net.minecraft.server.level.ServerLevel level(ServerPlayer player) {
        //? if >=1.21.6 {
        return player.level();
        //?} else {
        /*return player.serverLevel();
        *///?}
    }

    /** A fresh, unspawned entity of {@code type}. */
    @org.jetbrains.annotations.Nullable
    public static <T extends net.minecraft.world.entity.Entity> T create(net.minecraft.world.entity.EntityType<T> type,
            net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.EntitySpawnReason reason) {
        //? if >=1.21.2 {
        return type.create(level, reason);
        //?} else {
        /*return type.create(level);
        *///?}
    }

    /** The command requirement for permission level 2 ({@code Commands.hasPermission} exists from 1.21.6). */
    public static java.util.function.Predicate<CommandSourceStack> gamemasters() {
        //? if >=1.21.6 {
        return net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS);
        //?} else {
        /*return source -> source.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS);
        *///?}
    }

    /** Operator permission level 2 or better. */
    public static boolean isGamemaster(ServerPlayer player) {
        //? if >=1.21.11 {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        //?} else {
        /*return player.hasPermissions(2);
        *///?}
    }

    /** Operator permission level 2 or better, for a command source. */
    public static boolean isGamemaster(CommandSourceStack source) {
        //? if >=1.21.11 {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        //?} else {
        /*return source.hasPermission(2);
        *///?}
    }

    /** A player's last known name from the server's name cache, if it has one. */
    public static Optional<String> cachedName(MinecraftServer server, UUID id) {
        //? if >=1.21.9 {
        return server.services().nameToIdCache().get(id).map(entry -> entry.name());
        //?} else {
        /*net.minecraft.server.players.GameProfileCache cache = server.getProfileCache();
        return cache == null ? Optional.empty() : cache.get(id).map(profile -> profile.getName());
        *///?}
    }
}
