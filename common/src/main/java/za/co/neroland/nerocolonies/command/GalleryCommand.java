package za.co.neroland.nerocolonies.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.ColonyGallery;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;

/**
 * {@code /nerocolonies gallery}: a creative-mode showcase for operators.
 *
 * <pre>
 * /nerocolonies gallery            build the gallery around you
 * /nerocolonies gallery clear      remove it completely
 * /nerocolonies gallery release    let the held demo mobs go (the guard pen, the claim-edge runner)
 * </pre>
 *
 * <p>All three need permission level 2, a player as the source, and that player in creative mode.
 * A server holds at most one gallery at a time; {@code clear} finds it from its own records, so it
 * works after a restart (the gallery's chunks must be loaded for the blocks to be removed).
 *
 * <p>What is built, and why the sandbox colony behind it is invisible to telemetry, companion
 * clients and progression, is documented on {@link ColonyGallery}.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> nothing about the caller is stored or logged. Replies are counts
 * and sizes.
 */
public final class GalleryCommand {

    private GalleryCommand() {
    }

    /** The {@code gallery} subtree, for {@link NeroColoniesCommands} to hang under {@code /nerocolonies}. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("gallery")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> NeroColoniesCommands.runSafely(ctx.getSource(), "gallery",
                        () -> build(ctx.getSource())))
                .then(Commands.literal("clear")
                        .executes(ctx -> NeroColoniesCommands.runSafely(ctx.getSource(), "gallery clear",
                                () -> clear(ctx.getSource()))))
                .then(Commands.literal("release")
                        .executes(ctx -> NeroColoniesCommands.runSafely(ctx.getSource(), "gallery release",
                                () -> release(ctx.getSource()))));
    }

    /** The calling player if they may use the gallery, otherwise null after telling them why not. */
    @Nullable
    private static ServerPlayer creativePlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.nerocolonies.player_only"));
            return null;
        }
        if (!player.isCreative()) {
            source.sendFailure(Component.translatable("command.nerocolonies.gallery.creative_only"));
            return null;
        }
        return player;
    }

    private static int build(CommandSourceStack source) {
        ServerPlayer player = creativePlayer(source);
        if (player == null) {
            return 0;
        }
        ColonyGallery.BuildResult result = ColonyGallery.build(player);
        switch (result.outcome()) {
            case BUILT -> {
                source.sendSuccess(() -> Component.translatable("command.nerocolonies.gallery.built",
                        result.buildings(), result.nerans(), result.side(), result.side()), false);
                return Command.SINGLE_SUCCESS;
            }
            case EXISTS -> source.sendFailure(Component.translatable("command.nerocolonies.gallery.exists"));
            case TOO_BIG -> source.sendFailure(Component.translatable("command.nerocolonies.gallery.too_big",
                    result.side(), result.side(), ColonyGallery.maxSide(), ColonyGallery.maxSide()));
            case NOT_LOADED -> source.sendFailure(
                    Component.translatable("command.nerocolonies.gallery.not_loaded", result.radius()));
            case TOO_HIGH -> source.sendFailure(Component.translatable("command.nerocolonies.gallery.too_high"));
            case OVERLAP -> source.sendFailure(Component.translatable("message.nerocolonies.claim.overlap"));
            case COLONY_CAP -> source.sendFailure(Component.translatable("message.nerocolonies.claim.cap_total",
                    NeroColoniesConfig.MAX_COLONIES_TOTAL.get()));
        }
        return 0;
    }

    private static int clear(CommandSourceStack source) {
        ServerPlayer player = creativePlayer(source);
        if (player == null) {
            return 0;
        }
        ColonyGallery.ClearResult result = ColonyGallery.clear(source.getServer());
        if (!result.found()) {
            source.sendFailure(Component.translatable("command.nerocolonies.gallery.none"));
            return 0;
        }
        if (result.unloadedChunks() > 0) {
            source.sendFailure(Component.translatable("command.nerocolonies.gallery.cleared_partial",
                    result.blocks(), result.entities(), result.unloadedChunks()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.gallery.cleared",
                result.blocks(), result.entities()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int release(CommandSourceStack source) {
        ServerPlayer player = creativePlayer(source);
        if (player == null) {
            return 0;
        }
        int released = ColonyGallery.release(player);
        if (released < 0) {
            source.sendFailure(Component.translatable("command.nerocolonies.gallery.none"));
            return 0;
        }
        if (released == 0) {
            source.sendFailure(Component.translatable("command.nerocolonies.gallery.release.none"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.gallery.released", released), false);
        return Command.SINGLE_SUCCESS;
    }
}
