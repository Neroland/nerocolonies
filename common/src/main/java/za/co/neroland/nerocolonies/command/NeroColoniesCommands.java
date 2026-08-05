package za.co.neroland.nerocolonies.command;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.AccessLog;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.ColonyStores;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.colony.ExportBuffer;
import za.co.neroland.nerocolonies.colony.JobBoard;
import za.co.neroland.nerocolonies.colony.LifeSupport;
import za.co.neroland.nerocolonies.colony.Morale;
import za.co.neroland.nerocolonies.colony.Outpost;
import za.co.neroland.nerocolonies.colony.Research;
import za.co.neroland.nerocolonies.colony.ResearchEffects;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ValidationIssue;
import za.co.neroland.nerocolonies.network.ColonySync;
import za.co.neroland.nerocolonies.telemetry.NeroColoniesTelemetry;

/**
 * The {@code /nerocolonies} command tree — the parts of a colony that are easier to reach with a
 * keyboard than with a beacon interface, plus the operator's levers and the two data-protection
 * commands. Registered identically from all three loaders (NeoForge/Forge
 * {@code RegisterCommandsEvent}, Fabric {@code CommandRegistrationCallback}), so the tree itself is
 * built once here in common.
 *
 * <pre>
 * PLAYER (permission 0)
 *   /nerocolonies colony list                          your colonies, ids and names
 *   /nerocolonies colony info [&lt;colony&gt;]               one of your colonies in detail
 *   /nerocolonies colony rename &lt;colony&gt; &lt;name&gt;         owner only
 *   /nerocolonies colony access list &lt;colony&gt;           owner only — a COUNT, never a roster
 *   /nerocolonies colony access add &lt;colony&gt; &lt;player&gt;   owner only
 *   /nerocolonies colony access remove &lt;colony&gt; &lt;player&gt; owner only
 *   /nerocolonies data export                          your own stored records, as JSON
 *   /nerocolonies data erase                           erase yourself across every Nero mod
 *
 * OPERATOR (permission 2, Commands.LEVEL_GAMEMASTERS)
 *   /nerocolonies colony dissolve &lt;colony&gt;
 *   /nerocolonies colony transfer &lt;colony&gt; &lt;player&gt;
 *   /nerocolonies colony tp &lt;colony&gt;
 *   /nerocolonies colony set-morale &lt;colony&gt; &lt;value&gt;
 *   /nerocolonies colony grant-research &lt;colony&gt; &lt;node&gt;
 *   /nerocolonies colony sell &lt;colony&gt;
 *   /nerocolonies admin list [&lt;dimension&gt;]              ids and names only, never an owner
 *   /nerocolonies reload-check                         the datapack validation report
 *   /nerocolonies purge-stale                          run the retention sweep now
 * </pre>
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <ul>
 *   <li><b>No command prints an owner or a member.</b> {@code admin list} reports colony ids, names,
 *       dimensions and state; {@code colony access list} answers with a <em>count</em>. A colony's
 *       membership never leaves {@link ColonyState}, whoever is asking — an operator who genuinely
 *       needs to know who plays where has the server's own player data, not this mod's.</li>
 *   <li>Every {@code sendSuccess} passes {@code false} for "broadcast to ops", so output goes to the
 *       invoker alone and stays out of {@code latest.log} under the {@code logAdminCommands} game
 *       rule. The one exception is {@code colony dissolve}, which is destructive and therefore
 *       announced to operators — and announces a colony name, which is player-chosen text about a
 *       place, not personal data.</li>
 *   <li><b>{@code data export} is the documented data-access path</b> and {@code data erase} the
 *       erasure path. Both act on the <em>calling player only</em> — there is deliberately no
 *       "export somebody else" subcommand, because an operator who needs one has Core's
 *       {@code /neroland data erase &lt;uuid&gt;} and the same erasure fan-out. {@code erase} routes
 *       through Core's {@link PlayerDataErasure}, so one request purges the caller across every Nero
 *       mod rather than only this one.</li>
 * </ul>
 *
 * <h2>The {@code <player>} argument, and why it is a plain string</h2>
 *
 * <p>An access list has to be manageable for somebody who is <b>offline</b> — a co-op colony whose
 * second player is asleep is the normal case, and the beacon's own editor is deliberately
 * online-only. So {@code <player>} accepts an online player's name <em>or</em> a raw UUID, and
 * nothing else: NeroColonies never consults the server's profile cache to turn an offline name into
 * a UUID, because a name/UUID correlation lookup driven by user input is exactly the kind of
 * incidental personal-data processing this mod is built to avoid. The limitation is real and
 * documented: to add somebody who is offline, use their UUID.
 *
 * <p>Server thread only.
 */
public final class NeroColoniesCommands {

    /** Chat is not a file transfer: an export longer than this is cut off with a note. */
    private static final int EXPORT_CHAR_LIMIT = 32_000;

    /** Upper bound on the rows any one listing prints, so a big server cannot flood a chat box. */
    private static final int LIST_LIMIT = 100;

    private NeroColoniesCommands() {
    }

    // --- tree ---------------------------------------------------------------

    /** Builds {@code /nerocolonies …}. Called once per loader from its command-registration hook. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nerocolonies")
                .then(Commands.literal("colony")
                        // --- player level ---
                        .then(Commands.literal("list")
                                .executes(ctx -> runSafely(ctx.getSource(), "colony list",
                                        () -> listMine(ctx.getSource()))))
                        .then(Commands.literal("info")
                                .executes(ctx -> runSafely(ctx.getSource(), "colony info",
                                        () -> info(ctx.getSource(), null)))
                                .then(colonyArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "colony info",
                                                () -> info(ctx.getSource(), colonyId(ctx))))))
                        .then(Commands.literal("rename")
                                .then(colonyArgument()
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> runSafely(ctx.getSource(), "colony rename",
                                                        () -> rename(ctx))))))
                        .then(Commands.literal("access")
                                .then(Commands.literal("list")
                                        .then(colonyArgument()
                                                .executes(ctx -> runSafely(ctx.getSource(),
                                                        "colony access list", () -> accessList(ctx)))))
                                .then(Commands.literal("add")
                                        .then(colonyArgument()
                                                .then(playerArgument()
                                                        .executes(ctx -> runSafely(ctx.getSource(),
                                                                "colony access add",
                                                                () -> access(ctx, true))))))
                                .then(Commands.literal("remove")
                                        .then(colonyArgument()
                                                .then(playerArgument()
                                                        .executes(ctx -> runSafely(ctx.getSource(),
                                                                "colony access remove",
                                                                () -> access(ctx, false)))))))
                        // --- operator level ---
                        .then(Commands.literal("dissolve")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(colonyArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "colony dissolve",
                                                () -> dissolve(ctx)))))
                        .then(Commands.literal("transfer")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(colonyArgument()
                                        .then(playerArgument()
                                                .executes(ctx -> runSafely(ctx.getSource(),
                                                        "colony transfer", () -> transfer(ctx))))))
                        .then(Commands.literal("tp")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(colonyArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "colony tp",
                                                () -> teleport(ctx)))))
                        .then(Commands.literal("set-morale")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(colonyArgument()
                                        .then(Commands.argument("value",
                                                        DoubleArgumentType.doubleArg(0.0D, 100.0D))
                                                .executes(ctx -> runSafely(ctx.getSource(),
                                                        "colony set-morale", () -> setMorale(ctx))))))
                        .then(Commands.literal("grant-research")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(colonyArgument()
                                        .then(nodeArgument()
                                                .executes(ctx -> runSafely(ctx.getSource(),
                                                        "colony grant-research",
                                                        () -> grantResearch(ctx))))))
                        .then(Commands.literal("sell")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(colonyArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "colony sell",
                                                () -> sell(ctx))))))
                .then(Commands.literal("data")
                        .then(Commands.literal("export")
                                .executes(ctx -> runSafely(ctx.getSource(), "data export",
                                        () -> dataExport(ctx.getSource()))))
                        .then(Commands.literal("erase")
                                .executes(ctx -> runSafely(ctx.getSource(), "data erase",
                                        () -> dataErase(ctx.getSource())))))
                .then(Commands.literal("admin")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("list")
                                .executes(ctx -> runSafely(ctx.getSource(), "admin list",
                                        () -> listAll(ctx.getSource(), null)))
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .executes(ctx -> {
                                            // Resolved outside runSafely: it throws a Brigadier syntax
                                            // exception, which is the right answer for a bad argument
                                            // and not the "something broke" path runSafely reports.
                                            ServerLevel dimension =
                                                    DimensionArgument.getDimension(ctx, "dimension");
                                            return runSafely(ctx.getSource(), "admin list",
                                                    () -> listAll(ctx.getSource(), dimension));
                                        }))))
                .then(Commands.literal("reload-check")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> runSafely(ctx.getSource(), "reload-check",
                                () -> reloadCheck(ctx.getSource()))))
                .then(Commands.literal("purge-stale")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> runSafely(ctx.getSource(), "purge-stale",
                                () -> purgeStale(ctx.getSource())))));
    }

    // --- arguments -----------------------------------------------------------

    /**
     * {@code <colony>} — a colony id. Suggestions are scoped to what the <em>invoker</em> may act on:
     * a player is offered their own colonies, an operator every colony. A UUID's hyphens are inside
     * Brigadier's unquoted-string alphabet, so no quoting is needed.
     */
    private static RequiredArgumentBuilder<CommandSourceStack, String> colonyArgument() {
        return Commands.argument("colony", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    if (server != null) {
                        String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
                        for (Colony colony : visibleColonies(ctx.getSource(), server)) {
                            String id = colony.colonyId().toString();
                            if (id.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                                builder.suggest(id, Component.literal(colony.name()));
                            }
                        }
                    }
                    return builder.buildFuture();
                });
    }

    /** {@code <player>} — an online player's name or a raw UUID (see the class notes). */
    private static RequiredArgumentBuilder<CommandSourceStack, String> playerArgument() {
        return Commands.argument("player", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    if (server != null) {
                        String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
                        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                            String name = online.getName().getString();
                            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                                builder.suggest(name);
                            }
                        }
                    }
                    return builder.buildFuture();
                });
    }

    /**
     * {@code <node>} — a research node id. Greedy, because an id contains {@code :} and {@code /},
     * neither of which Brigadier reads as part of a bare word, and because it is always last.
     */
    private static RequiredArgumentBuilder<CommandSourceStack, String> nodeArgument() {
        return Commands.argument("node", StringArgumentType.greedyString())
                .suggests((ctx, builder) -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    if (server != null) {
                        String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
                        for (Identifier id : ColonyDefinitions.researchForServer(server).keySet()) {
                            String text = id.toString();
                            if (text.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                                builder.suggest(text);
                            }
                        }
                    }
                    return builder.buildFuture();
                });
    }

    private static String colonyId(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "colony");
    }

    // --- player: list and info ----------------------------------------------

    /** The colonies the caller owns or is a member of — ids, names and a one-line state each. */
    private static int listMine(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();
        if (server == null) {
            return noServer(source);
        }
        if (player == null) {
            source.sendFailure(Component.translatable("command.nerocolonies.player_only"));
            return 0;
        }
        ColonyState state = ColonyState.get(server);
        List<UUID> ids = state.memberOf(player.getUUID());
        if (ids.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.nerocolonies.list.none"), false);
            return Command.SINGLE_SUCCESS;
        }
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.list.header", ids.size()),
                false);
        int printed = 0;
        for (UUID id : ids) {
            Colony colony = state.colony(id);
            if (colony == null || printed++ >= LIST_LIMIT) {
                continue;
            }
            source.sendSuccess(() -> Component.literal(summaryLine(colony)), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * One colony in detail. With no id, the colony the caller is standing in is used, which is what
     * somebody typing this while looking at their own beacon means every time.
     */
    private static int info(CommandSourceStack source, @Nullable String rawId) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Colony found;
        if (rawId == null) {
            found = colonyHere(source);
            if (found == null) {
                source.sendFailure(Component.translatable("command.nerocolonies.colony.none_here"));
                return 0;
            }
            if (!mayView(source, found)) {
                source.sendFailure(Component.translatable("command.nerocolonies.colony.no_access"));
                return 0;
            }
        } else {
            found = resolveColony(source, server, rawId, false);
            if (found == null) {
                return 0;
            }
        }
        // Effectively final, so the message suppliers below may capture it.
        final Colony colony = found;

        UUID id = colony.colonyId();
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.info.header",
                colony.name(), id.toString()), false);
        source.sendSuccess(() -> Component.literal(
                "  §7dimension§r " + colony.dimension().identifier()
                        + " §7beacon§r " + colony.beaconPos().toShortString()
                        + " §7radius§r " + colony.claimRadius()), false);
        source.sendSuccess(() -> Component.literal(
                "  §7morale§r " + Math.round(colony.morale())
                        + (Morale.workStopped(colony) ? " §c(work stopped)§r" : "")
                        + " §7population§r " + colony.population() + " / " + colony.housingCapacity()
                        + " §7food§r " + colony.foodStock()), false);
        source.sendSuccess(() -> Component.literal(
                "  §7life support§r " + LifeSupport.stateOf(colony)
                        + " §7generators§r " + LifeSupport.generatorCount(id)), false);
        source.sendSuccess(() -> Component.literal(
                "  §7job slots§r " + JobBoard.activeCount(id) + " / " + ResearchEffects.jobSlots(colony)
                        + " §7stations§r " + JobBoard.stationCount(id)
                        + " §7research§r " + colony.researchUnlocked().size()), false);
        source.sendSuccess(() -> Component.literal(
                "  §7export buffer§r " + ExportBuffer.filledSlots(server, id) + " / "
                        + ExportBuffer.usableSlots()
                        + " §7worth§r " + ExportBuffer.previewValue(server, colony)
                        + " §7outposts§r " + colony.outpostIds().size()), false);
        // A count, never a roster — see the class notes.
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.info.members",
                colony.accessList().size(), colony.hasOwner()), false);
        return Command.SINGLE_SUCCESS;
    }

    // --- player: rename and access ------------------------------------------

    private static int rename(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Colony colony = resolveColony(source, server, colonyId(ctx), true);
        if (colony == null) {
            return 0;
        }
        String name = Colony.sanitiseName(StringArgumentType.getString(ctx, "name"));
        ColonyState.get(server).put(colony.withName(name));
        ColonySync.refresh(server, colony.colonyId());
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.rename.done", name), false);
        return Command.SINGLE_SUCCESS;
    }

    /** The size of the access list, and nothing else — see the class notes. */
    private static int accessList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Colony colony = resolveColony(source, server, colonyId(ctx), true);
        if (colony == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.access.count",
                colony.accessList().size(), Colony.MAX_ACCESS_LIST), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Adds or removes an access-list member. Owner (or operator) only, and it works offline by UUID. */
    private static int access(CommandContext<CommandSourceStack> ctx, boolean grant) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Colony colony = resolveColony(source, server, colonyId(ctx), true);
        if (colony == null) {
            return 0;
        }
        UUID target = resolvePlayer(source, server, ctx);
        if (target == null) {
            return 0;
        }
        if (colony.isOwner(target)) {
            source.sendFailure(Component.translatable("message.nerocolonies.access.is_owner"));
            return 0;
        }
        Colony updated = grant ? colony.grantAccess(target) : colony.revokeAccess(target);
        if (updated == colony) {
            source.sendFailure(Component.translatable(grant
                    ? "message.nerocolonies.access.already"
                    : "message.nerocolonies.access.absent"));
            return 0;
        }
        ColonyState state = ColonyState.get(server);
        state.put(updated);
        state.log(colony.colonyId(), target,
                grant ? AccessLog.Action.ACCESS_GRANT : AccessLog.Action.ACCESS_REVOKE);
        ColonySync.refresh(server, colony.colonyId());
        int members = updated.accessList().size();
        source.sendSuccess(() -> Component.translatable(grant
                ? "message.nerocolonies.access.granted"
                : "message.nerocolonies.access.revoked", members), false);
        return Command.SINGLE_SUCCESS;
    }

    // --- player: data protection --------------------------------------------

    /**
     * POPIA/GDPR data access: prints the caller's own NeroColonies records as pretty JSON — the ids of
     * the colonies they own or belong to, and their own access-log rows. Nobody else's UUID appears in
     * the result, which is a property of {@link ColonyState#export} rather than of this presentation.
     */
    private static int dataExport(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();
        if (server == null) {
            return noServer(source);
        }
        if (player == null) {
            source.sendFailure(Component.translatable("command.nerocolonies.player_only"));
            return 0;
        }
        JsonObject json = new JsonObject();
        json.addProperty("player", player.getUUID().toString());
        json.addProperty("exported_at", System.currentTimeMillis());
        json.add("nerocolonies", ColonyState.get(server).export(player.getUUID()));

        source.sendSuccess(() -> Component.translatable("command.nerocolonies.export.header"), false);
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(json);
        boolean truncated = pretty.length() > EXPORT_CHAR_LIMIT;
        if (truncated) {
            pretty = pretty.substring(0, EXPORT_CHAR_LIMIT);
        }
        for (String line : pretty.split("\n", -1)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        if (truncated) {
            source.sendSuccess(() -> Component.translatable("command.nerocolonies.export.truncated",
                    EXPORT_CHAR_LIMIT), false);
        }
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.export.footer"), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * POPIA/GDPR erasure, routed through Core so one request purges the caller across <b>every</b>
     * Nero mod rather than only this one. Core's {@code /neroland data eraseme} is the same call from
     * the other end; either is enough, and running both is harmless.
     */
    private static int dataErase(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();
        if (server == null) {
            return noServer(source);
        }
        if (player == null) {
            source.sendFailure(Component.translatable("command.nerocolonies.player_only"));
            return 0;
        }
        PlayerDataErasure.erase(server, player.getUUID());
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.erase.done"), false);
        return Command.SINGLE_SUCCESS;
    }

    // --- operator: colony administration ------------------------------------

    /**
     * Deletes a colony record and its goods. The goods are dropped at the beacon when its chunk is
     * loaded and discarded otherwise — leaving the store behind would leak it forever, and there is
     * nowhere to drop items in an unloaded chunk.
     */
    private static int dissolve(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Colony colony = resolveColony(source, server, colonyId(ctx), false);
        if (colony == null) {
            return 0;
        }
        ServerLevel level = server.getLevel(colony.dimension());
        if (level != null && level.isLoaded(colony.beaconPos())) {
            ColonyStores.dropAndForget(level, colony.beaconPos(), colony.colonyId());
        } else {
            ColonyStores.get(server).forget(colony.colonyId());
        }
        Construction.forget(server, colony.colonyId());
        ColonyState.get(server).remove(colony.colonyId());
        String name = colony.name();
        source.sendSuccess(() -> Component.translatable("message.nerocolonies.claim.dissolved", name),
                true);
        return Command.SINGLE_SUCCESS;
    }

    /** Hands a colony to another player. The old owner keeps no membership unless they were on the list. */
    private static int transfer(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Colony colony = resolveColony(source, server, colonyId(ctx), false);
        if (colony == null) {
            return 0;
        }
        UUID target = resolvePlayer(source, server, ctx);
        if (target == null) {
            return 0;
        }
        // The new owner is removed from the access list if they were on it: owner and member are
        // separate slots and holding both would double-count them.
        ColonyState.get(server).put(colony.revokeAccess(target).withOwner(target));
        ColonySync.refresh(server, colony.colonyId());
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.transfer.done",
                colony.name()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int teleport(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.nerocolonies.player_only"));
            return 0;
        }
        Colony colony = resolveColony(source, server, colonyId(ctx), false);
        if (colony == null) {
            return 0;
        }
        ServerLevel level = server.getLevel(colony.dimension());
        if (level == null) {
            source.sendFailure(Component.translatable("command.nerocolonies.tp.no_dimension"));
            return 0;
        }
        BlockPos pos = colony.beaconPos();
        player.teleportTo(level, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                java.util.Set.of(), player.getYRot(), player.getXRot(), true);
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.tp.done", colony.name()),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setMorale(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Colony colony = resolveColony(source, server, colonyId(ctx), false);
        if (colony == null) {
            return 0;
        }
        double value = DoubleArgumentType.getDouble(ctx, "value");
        ColonyState.get(server).put(colony.withMorale(value));
        ColonySync.refresh(server, colony.colonyId());
        // Morale is recomputed toward its target on the next colony tick — this is a nudge, not a pin.
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.morale.done",
                Math.round(value), colony.name()), false);
        return Command.SINGLE_SUCCESS;
    }

    /** The operator grant: no cost, no power, no prerequisites — but still no duplicate unlock. */
    private static int grantResearch(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Colony colony = resolveColony(source, server, colonyId(ctx), false);
        if (colony == null) {
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, "node").trim();
        Identifier node = Identifier.tryParse(raw.indexOf(':') < 0
                ? NeroColoniesCommon.MOD_ID + ":" + raw
                : raw);
        if (node == null || !ColonyDefinitions.researchForServer(server).containsKey(node)) {
            source.sendFailure(Component.translatable("command.nerocolonies.research.unknown", raw));
            return 0;
        }
        if (!Research.grant(server, colony, node)) {
            source.sendFailure(Component.translatable("message.nerocolonies.research.already"));
            return 0;
        }
        ColonySync.refresh(server, colony.colonyId());
        String id = node.toString();
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.research.granted", id,
                colony.name()), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Sells the colony's export buffer through the same path the beacon's Sell button uses. */
    private static int sell(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Colony colony = resolveColony(source, server, colonyId(ctx), false);
        if (colony == null) {
            return 0;
        }
        ExportBuffer.SaleResult result = ExportBuffer.sell(server, colony);
        if (result.status() != ExportBuffer.SaleResult.Status.SOLD) {
            source.sendFailure(Component.translatable(switch (result.status()) {
                case NOTHING_TO_SELL -> "message.nerocolonies.export.nothing";
                case NO_MARKET -> "message.nerocolonies.export.no_market";
                case NO_OWNER -> "message.nerocolonies.export.no_owner";
                default -> "message.nerocolonies.export.nothing";
            }));
            return 0;
        }
        ColonySync.refresh(server, colony.colonyId());
        source.sendSuccess(() -> Component.translatable("message.nerocolonies.export.sold",
                result.items(), result.credits()), false);
        return Command.SINGLE_SUCCESS;
    }

    // --- operator: server-wide ------------------------------------------------

    /**
     * Every colony on the server, or every colony in one dimension. Ids, names, dimensions and state
     * — <b>never an owner</b>. See the class notes.
     */
    private static int listAll(CommandSourceStack source, @Nullable ServerLevel dimension) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        ColonyState state = ColonyState.get(server);
        List<Colony> colonies = dimension == null
                ? List.copyOf(state.colonies())
                : state.coloniesIn(dimension.dimension());
        int total = colonies.size();
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.admin.list.header", total,
                dimension == null
                        ? Component.translatable("command.nerocolonies.admin.list.everywhere")
                        : Component.literal(dimension.dimension().identifier().toString())), false);
        int printed = 0;
        for (Colony colony : colonies) {
            if (printed++ >= LIST_LIMIT) {
                source.sendSuccess(() -> Component.translatable(
                        "command.nerocolonies.admin.list.truncated", LIST_LIMIT), false);
                break;
            }
            source.sendSuccess(() -> Component.literal(summaryLine(colony)), false);
        }
        int outposts = state.allOutposts().size();
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.admin.list.outposts",
                outposts), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Re-reads the datapack content and prints what it rejected. The re-read is the point: an operator
     * who has just run {@code /reload} wants to know whether their pack survived it, and comparing the
     * resource manager here is the same cheap identity check the colony tick makes.
     */
    private static int reloadCheck(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        boolean rereading = ColonyDefinitions.refreshIfReloaded(server);
        List<ValidationIssue> issues = ColonyDefinitions.issuesForServer(server);
        int jobs = ColonyDefinitions.jobsForServer(server).size();
        int research = ColonyDefinitions.researchForServer(server).size();
        int housing = ColonyDefinitions.housingForServer(server).size();
        int exports = ColonyDefinitions.exportsForServer(server).size();
        int blueprints = ColonyDefinitions.blueprintsForServer(server).size();

        source.sendSuccess(() -> Component.translatable("command.nerocolonies.reload_check.header",
                jobs, research, housing, exports, blueprints), false);
        if (rereading) {
            source.sendSuccess(() -> Component.translatable("command.nerocolonies.reload_check.reread"),
                    false);
        }
        if (issues.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.nerocolonies.reload_check.clean"),
                    false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.nerocolonies.reload_check.issues",
                    issues.size()), false);
            int printed = 0;
            for (ValidationIssue issue : issues) {
                if (printed++ >= LIST_LIMIT) {
                    break;
                }
                String line = "  " + (issue.severity() == ValidationIssue.Severity.DROPPED ? "§c" : "§e")
                        + issue.describe() + "§r";
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        // Anyone looking at a colony screen is holding content from before the reload; hand them the
        // new set rather than making them close and reopen it.
        for (Colony colony : ColonyState.get(server).colonies()) {
            ColonySync.refresh(server, colony.colonyId());
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Runs the retention sweep now, instead of waiting for the next server start. */
    private static int purgeStale(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        int[] counts = ColonyState.get(server).sweep(server);
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.purge.done",
                counts[0], counts[1], counts[2]), false);
        return Command.SINGLE_SUCCESS;
    }

    // --- resolution -----------------------------------------------------------

    /**
     * The colony named by the {@code colony} argument, after the permission check, or {@code null}
     * having already told the caller why not.
     *
     * <p>A refusal deliberately does not distinguish "no such colony" from "not yours": the two
     * answers together would let anyone probe for the existence of other people's colonies.
     *
     * @param ownerOnly {@code true} for the operations only an owner (or an operator) may perform
     */
    @Nullable
    private static Colony resolveColony(CommandSourceStack source, MinecraftServer server, String raw,
            boolean ownerOnly) {
        UUID id;
        try {
            id = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("command.nerocolonies.colony.unknown"));
            return null;
        }
        Colony colony = ColonyState.get(server).colony(id);
        if (colony == null) {
            source.sendFailure(Component.translatable("command.nerocolonies.colony.unknown"));
            return null;
        }
        boolean operator = isOperator(source);
        ServerPlayer player = source.getPlayer();
        if (operator) {
            return colony;
        }
        if (player == null) {
            source.sendFailure(Component.translatable("command.nerocolonies.player_only"));
            return null;
        }
        boolean allowed = ownerOnly ? colony.isOwner(player.getUUID()) : colony.isMember(player.getUUID());
        if (!allowed) {
            source.sendFailure(Component.translatable(ownerOnly
                    ? "message.nerocolonies.access.owner_only"
                    : "command.nerocolonies.colony.unknown"));
            return null;
        }
        return colony;
    }

    /**
     * The {@code player} argument as a UUID — an online player's name, or a raw UUID for somebody who
     * is offline. Never a profile-cache lookup; see the class notes.
     */
    @Nullable
    private static UUID resolvePlayer(CommandSourceStack source, MinecraftServer server,
            CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "player").trim();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getName().getString().equalsIgnoreCase(raw)) {
                return online.getUUID();
            }
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("command.nerocolonies.player.unknown"));
            return null;
        }
    }

    /**
     * Whether this source is an operator. {@code Commands.hasPermission(...)} builds the predicate a
     * {@code requires} clause takes; asking the same question in a command <em>body</em> means running
     * the check against the source's own permission set, which is what this does.
     */
    private static boolean isOperator(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** The colonies this source may be offered in a suggestion list. */
    private static List<Colony> visibleColonies(CommandSourceStack source, MinecraftServer server) {
        ColonyState state = ColonyState.get(server);
        if (isOperator(source)) {
            return List.copyOf(state.colonies());
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return List.of();
        }
        return state.memberOf(player.getUUID()).stream()
                .map(state::colony)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** The colony (or the parent of the outpost) the source is standing in, or {@code null}. */
    @Nullable
    private static Colony colonyHere(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        MinecraftServer server = source.getServer();
        if (level == null || server == null) {
            return null;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        ColonyState state = ColonyState.get(server);
        Colony colony = state.colonyAt(level.dimension(), pos);
        if (colony != null) {
            return colony;
        }
        Outpost outpost = state.outpostAt(level.dimension(), pos);
        return outpost == null ? null : state.colony(outpost.parentColonyId());
    }

    private static boolean mayView(CommandSourceStack source, Colony colony) {
        if (isOperator(source)) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return ColonyClaims.canAccess(player, colony);
    }

    /** {@code <id> "Name" — dimension, morale, pop/cap}. Never an owner. */
    private static String summaryLine(Colony colony) {
        return "  §8" + colony.colonyId() + "§r §f" + colony.name() + "§r §7"
                + colony.dimension().identifier() + "§r morale " + Math.round(colony.morale())
                + ", pop " + colony.population() + "/" + colony.housingCapacity()
                + (colony.lifeSupportOk() ? "" : " §c[life support failed]§r");
    }

    private static int noServer(CommandSourceStack source) {
        source.sendFailure(Component.translatable("command.nerocolonies.no_server"));
        return 0;
    }

    /**
     * Runs one subcommand body, turning an unexpected failure into a polite message plus an anonymous
     * telemetry event instead of a Brigadier stack trace in chat. The captured context is the
     * subcommand name only — never its arguments, which may name a player or a colony.
     */
    private static int runSafely(CommandSourceStack source, String subcommand, CommandBody body) {
        try {
            return body.run();
        } catch (RuntimeException e) {
            NeroColoniesTelemetry.captureHandledException(e, "command", "/nerocolonies " + subcommand);
            NeroColoniesCommon.LOGGER.error("[NeroColonies] /nerocolonies {} failed", subcommand, e);
            source.sendFailure(Component.translatable("command.nerocolonies.failed", subcommand));
            return 0;
        }
    }

    @FunctionalInterface
    private interface CommandBody {

        int run();
    }
}
