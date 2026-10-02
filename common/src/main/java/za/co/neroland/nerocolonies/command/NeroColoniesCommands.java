package za.co.neroland.nerocolonies.command;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyClaims;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyMembership;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyPlanner;
import za.co.neroland.nerocolonies.colony.ColonyProgress;
import za.co.neroland.nerocolonies.colony.ColonyRoles;
import za.co.neroland.nerocolonies.colony.ColonyStage;
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
 * PLAYER (permission 0) — what a member may do depends on their role: Owner, Chief or Ally
 *   /nerocolonies colony list                          your colonies, ids and names
 *   /nerocolonies colony info [&lt;colony&gt;]               one of your colonies in detail: state, stage,
 *                                                      and its roles as COUNTS
 *   /nerocolonies colony rename &lt;colony&gt; &lt;name&gt;         owner
 *
 *   /nerocolonies colony role ally add &lt;colony&gt; &lt;player&gt;      owner or Chief
 *   /nerocolonies colony role ally remove &lt;colony&gt; &lt;player&gt;   owner or Chief (owner, for a Chief)
 *   /nerocolonies colony role ally list &lt;colony&gt;              any member — a COUNT, never a roster
 *   /nerocolonies colony role chief add &lt;colony&gt; &lt;player&gt;     owner
 *   /nerocolonies colony role chief remove &lt;colony&gt; &lt;player&gt;  owner — they stay an Ally
 *   /nerocolonies colony role chief list &lt;colony&gt;             any member — a COUNT
 *   /nerocolonies colony role enemy add &lt;colony&gt; &lt;player&gt; [confirm]
 *                                                      owner or Chief; "confirm" is required to mark
 *                                                      somebody who is a member (owner, for a Chief)
 *   /nerocolonies colony role enemy remove &lt;colony&gt; &lt;player&gt;  owner or Chief
 *   /nerocolonies colony role enemy list &lt;colony&gt;             any member — a COUNT
 *   /nerocolonies colony access list|add|remove …      the older spelling of "role ally …"
 *
 *   /nerocolonies colony plan list &lt;colony&gt;            owner or Chief — what can be planned or is queued
 *   /nerocolonies colony plan cancel &lt;colony&gt; &lt;n&gt;      owner or Chief — drop queued plan number n
 *   /nerocolonies colony cache share &lt;colony&gt; &lt;true|false&gt;   owner — share the Gratitude Cache with Allies
 *   /nerocolonies colony need prioritise &lt;colony&gt; [&lt;item&gt;]   owner or Chief — no item clears it
 *
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
 *   /nerocolonies reload-check                         the datapack validation report, with how much
 *                                                      content of each kind (professions included) loaded
 *   /nerocolonies purge-stale                          run the retention sweep now
 * </pre>
 *
 * <p>An operator may run every player-level command on any colony and acts as its owner. Every role
 * change goes through {@link ColonyMembership}, which is also what the beacon's Roles tab calls, so
 * the two cannot disagree about who may do what.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <ul>
 *   <li><b>No command prints an owner, a member or an enemy</b> — not a name and not a UUID.
 *       {@code admin list} reports colony ids, names, dimensions and state; {@code colony info} and
 *       the {@code role … list} commands answer with <em>counts</em>; a role change answers with what
 *       happened and never to whom. An operator who genuinely needs to know who plays where has the
 *       server's own player data, not this mod's. (The one place membership is shown is the beacon's
 *       Roles tab, to somebody who may manage that colony's members;
 *       {@code ColonySnapshotPayload} documents exactly what it is sent.)</li>
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
 * <p>A colony's roles have to be manageable for somebody who is <b>offline</b> — a co-op colony whose
 * second player is asleep is the normal case, and the beacon's own editor is deliberately
 * online-only. So {@code <player>} accepts an online player's name <em>or</em> a raw UUID, and
 * nothing else: a command never consults the server's profile cache to turn a typed name into a
 * UUID, because a name/UUID correlation lookup driven by user input is exactly the kind of
 * incidental personal-data processing this mod is built to avoid. The limitation is real and
 * documented: to act on somebody who is offline, use their UUID.
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
                        // "access" is the older spelling of "role ally": same handlers, same rules.
                        .then(roleBranch("access", "colony access", ColonyPermissions.Role.ALLY))
                        .then(Commands.literal("role")
                                .then(roleBranch("ally", "colony role ally", ColonyPermissions.Role.ALLY))
                                .then(roleBranch("chief", "colony role chief",
                                        ColonyPermissions.Role.CHIEF))
                                .then(roleBranch("enemy", "colony role enemy",
                                        ColonyPermissions.Role.ENEMY)))
                        .then(Commands.literal("plan")
                                .then(Commands.literal("list")
                                        .then(colonyArgument()
                                                .executes(ctx -> runSafely(ctx.getSource(),
                                                        "colony plan list", () -> planList(ctx)))))
                                .then(Commands.literal("cancel")
                                        .then(colonyArgument()
                                                .then(Commands.argument("place",
                                                                IntegerArgumentType.integer(1))
                                                        .executes(ctx -> runSafely(ctx.getSource(),
                                                                "colony plan cancel",
                                                                () -> planCancel(ctx)))))))
                        .then(Commands.literal("cache")
                                .then(Commands.literal("share")
                                        .then(colonyArgument()
                                                .then(Commands.argument("shared", BoolArgumentType.bool())
                                                        .executes(ctx -> runSafely(ctx.getSource(),
                                                                "colony cache share",
                                                                () -> cacheShare(ctx)))))))
                        .then(Commands.literal("need")
                                .then(Commands.literal("prioritise")
                                        .then(colonyArgument()
                                                .executes(ctx -> runSafely(ctx.getSource(),
                                                        "colony need prioritise",
                                                        () -> needPrioritise(ctx, false)))
                                                .then(Commands.argument("item",
                                                                StringArgumentType.greedyString())
                                                        .executes(ctx -> runSafely(ctx.getSource(),
                                                                "colony need prioritise",
                                                                () -> needPrioritise(ctx, true)))))))
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
                                () -> purgeStale(ctx.getSource()))))
                .then(GalleryCommand.node()));
    }

    /**
     * {@code <literal> add|remove|list …} for one role. The three roles share a shape, and
     * {@code colony access} is the Ally branch under its older name, so one builder makes all four.
     * Only the Enemy branch grows the trailing {@code confirm}.
     *
     * @param label the subcommand name {@link #runSafely} reports — never an argument
     */
    private static LiteralArgumentBuilder<CommandSourceStack> roleBranch(String literal, String label,
            ColonyPermissions.Role role) {
        RequiredArgumentBuilder<CommandSourceStack, String> addTarget = playerArgument()
                .executes(ctx -> runSafely(ctx.getSource(), label + " add",
                        () -> roleChange(ctx, role, true, false)));
        if (role == ColonyPermissions.Role.ENEMY) {
            addTarget.then(Commands.literal("confirm")
                    .executes(ctx -> runSafely(ctx.getSource(), label + " add",
                            () -> roleChange(ctx, role, true, true))));
        }
        return Commands.literal(literal)
                .then(Commands.literal("list")
                        .then(colonyArgument()
                                .executes(ctx -> runSafely(ctx.getSource(), label + " list",
                                        () -> roleList(ctx, role)))))
                .then(Commands.literal("add")
                        .then(colonyArgument().then(addTarget)))
                .then(Commands.literal("remove")
                        .then(colonyArgument()
                                .then(playerArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), label + " remove",
                                                () -> roleChange(ctx, role, false, false))))));
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
        ColonyStage stage = ColonyProgress.stage(server, colony);
        ColonyLife.Life life = ColonyLife.get(server).life(id);
        int structures = Construction.structuresBuilt(server, id);
        long stuck = life.stuckEvents();
        int births = life.births();
        source.sendSuccess(() -> Component.literal("  §7stage§r ")
                .append(Component.translatable("stage.nerocolonies." + stage.key()))
                .append(Component.literal(" §7structures§r " + structures
                        + " §7stuck events§r " + stuck
                        + " §7births§r " + births)), false);
        // Counts, never a roster — see the class notes.
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.info.members",
                colony.accessList().size(), colony.hasOwner()), false);
        int[] roles = roleCounts(server, colony);
        source.sendSuccess(() -> Component.literal(
                "  §7allies§r " + roles[0] + " §7chiefs§r " + roles[1] + " §7enemies§r " + roles[2]),
                false);
        return Command.SINGLE_SUCCESS;
    }

    // --- player: rename -----------------------------------------------------

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

    // --- player: roles -------------------------------------------------------

    /**
     * How many players hold one role, the cap, and the caller's own role — and nothing else. See the
     * class notes: a command never prints who.
     */
    private static int roleList(CommandContext<CommandSourceStack> ctx, ColonyPermissions.Role role) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Acting acting = resolveActing(source, server, colonyId(ctx));
        if (acting == null) {
            return 0;
        }
        int[] counts = roleCounts(server, acting.colony());
        int count = switch (role) {
            case CHIEF -> counts[1];
            case ENEMY -> counts[2];
            default -> counts[0];
        };
        ColonyPermissions.Role own = acting.role();
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.role.count." + role.key(),
                count, Colony.MAX_ACCESS_LIST, Component.translatable("role.nerocolonies." + own.key())),
                false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Gives a player a role or takes it away. Works offline by UUID. What is allowed is
     * {@link ColonyMembership}'s decision; the answer says what happened and never to whom.
     */
    private static int roleChange(CommandContext<CommandSourceStack> ctx, ColonyPermissions.Role role,
            boolean add, boolean confirm) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Acting acting = resolveActing(source, server, colonyId(ctx));
        if (acting == null) {
            return 0;
        }
        // Every role change needs at least this, so say so before looking anybody up.
        if (!ColonyPermissions.allows(acting.role(), ColonyPermissions.Action.MANAGE_MEMBERS, false)) {
            return refuse(source, ColonyMembership.Result.NOT_ALLOWED);
        }
        UUID target = resolvePlayer(source, server, ctx);
        if (target == null) {
            return 0;
        }
        Colony colony = acting.colony();
        ColonyMembership.Result result = switch (role) {
            case CHIEF -> ColonyMembership.setChief(server, colony, acting.role(), acting.actorId(), target,
                    add);
            case ENEMY -> {
                // Only somebody online can be seen to be an operator; offline, the mark is allowed and
                // an operator's own override keeps working regardless.
                ServerPlayer online = server.getPlayerList().getPlayer(target);
                yield ColonyMembership.setEnemy(server, colony, acting.role(), acting.actorId(), target,
                        add, confirm, online != null && ColonyClaims.isGamemaster(online));
            }
            default -> ColonyMembership.setAlly(server, colony, acting.role(), acting.actorId(), target,
                    add);
        };
        if (result != ColonyMembership.Result.DONE) {
            return refuse(source, result);
        }
        source.sendSuccess(() -> Component.translatable(
                ColonyMembership.messageKey(ColonyMembership.Result.DONE)), false);
        Colony latest = ColonyState.get(server).colony(colony.colonyId());
        int[] counts = roleCounts(server, latest == null ? colony : latest);
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.role.counts",
                counts[0], counts[1], counts[2], Colony.MAX_ACCESS_LIST), false);
        return Command.SINGLE_SUCCESS;
    }

    /** {allies, chiefs, enemies} for one colony, counted the way {@link ColonyPermissions} reads them. */
    private static int[] roleCounts(MinecraftServer server, Colony colony) {
        UUID id = colony.colonyId();
        ColonyRoles roles = ColonyRoles.get(server);
        int allies = 0;
        int chiefs = 0;
        for (UUID member : colony.accessList()) {
            if (roles.isEnemy(id, member)) {
                continue;
            }
            if (roles.isChief(id, member)) {
                chiefs++;
            } else {
                allies++;
            }
        }
        return new int[] {allies, chiefs, roles.counts(id)[1]};
    }

    private static int refuse(CommandSourceStack source, ColonyMembership.Result result) {
        source.sendFailure(Component.translatable(ColonyMembership.messageKey(result)));
        return 0;
    }

    // --- player: planning and colony settings --------------------------------

    /** The planner's readout — what can be planned and what is queued — for an owner or a Chief. */
    private static int planList(CommandContext<CommandSourceStack> ctx) {
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
        Acting acting = resolveActing(source, server, colonyId(ctx));
        if (acting == null) {
            return 0;
        }
        if (!ColonyPermissions.allows(acting.role(), ColonyPermissions.Action.PLAN, false)) {
            return refuse(source, ColonyMembership.Result.NOT_ALLOWED);
        }
        ColonyPlanner.describe(player.level(), player, acting.colony());
        return Command.SINGLE_SUCCESS;
    }

    /** Drops one queued plan by its place in the queue, counting from 1 as {@code plan list} does. */
    private static int planCancel(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Acting acting = resolveActing(source, server, colonyId(ctx));
        if (acting == null) {
            return 0;
        }
        if (!ColonyPermissions.allows(acting.role(), ColonyPermissions.Action.PLAN, false)) {
            return refuse(source, ColonyMembership.Result.NOT_ALLOWED);
        }
        int place = IntegerArgumentType.getInteger(ctx, "place");
        if (!ColonyPlanner.cancel(server, acting.colony(), place)) {
            source.sendFailure(Component.translatable("command.nerocolonies.plan.none", place));
            return 0;
        }
        ColonySync.refresh(server, acting.colony().colonyId());
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.plan.cancelled", place),
                false);
        return Command.SINGLE_SUCCESS;
    }

    /** Shares the Gratitude Cache with Allies, or stops. Owner (or operator) only. */
    private static int cacheShare(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Acting acting = resolveActing(source, server, colonyId(ctx));
        if (acting == null) {
            return 0;
        }
        boolean shared = BoolArgumentType.getBool(ctx, "shared");
        ColonyMembership.Result result =
                ColonyMembership.setCacheShared(server, acting.colony(), acting.role(), shared);
        if (result != ColonyMembership.Result.DONE && result != ColonyMembership.Result.ALREADY) {
            return refuse(source, result);
        }
        source.sendSuccess(() -> Component.translatable(shared
                ? "message.nerocolonies.cache.shared" : "message.nerocolonies.cache.private"), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Prioritises one need — the trades that gather it work faster — or, with no item, clears the
     * priority. Owner or Chief. The item has to be a real one; it does not have to be on the needs
     * list today, so an owner can line up what the next building will want.
     */
    private static int needPrioritise(CommandContext<CommandSourceStack> ctx, boolean hasItem) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Acting acting = resolveActing(source, server, colonyId(ctx));
        if (acting == null) {
            return 0;
        }
        if (!ColonyPermissions.allows(acting.role(), ColonyPermissions.Action.PLAN, false)) {
            return refuse(source, ColonyMembership.Result.NOT_ALLOWED);
        }
        Identifier item = null;
        if (hasItem) {
            String raw = StringArgumentType.getString(ctx, "item").trim();
            item = Identifier.tryParse(raw);
            if (item == null || !BuiltInRegistries.ITEM.containsKey(item)) {
                source.sendFailure(Component.translatable("command.nerocolonies.need.unknown_item", raw));
                return 0;
            }
        }
        ColonyMembership.Result result =
                ColonyMembership.setPriorityNeed(server, acting.colony(), acting.role(), item);
        if (result != ColonyMembership.Result.DONE && result != ColonyMembership.Result.ALREADY) {
            return refuse(source, result);
        }
        boolean cleared = item == null;
        source.sendSuccess(() -> Component.translatable(cleared
                ? "message.nerocolonies.need.cleared" : "message.nerocolonies.need.prioritised"), false);
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
        JsonObject mine = ColonyState.get(server).export(player.getUUID());
        ColonyRoles.get(server).exportInto(mine, player.getUUID());
        json.add("nerocolonies", mine);

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
            za.co.neroland.nerocolonies.colony.GratitudeCache.dropAll(level, colony.beaconPos(),
                    colony.colonyId());
        } else {
            ColonyStores.get(server).forget(colony.colonyId());
        }
        Construction.forget(server, colony.colonyId());
        // The Chief and Enemy lists hold player ids, and they have no business outliving the colony.
        ColonyRoles.get(server).forget(colony.colonyId());
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
        // separate slots and holding both would double-count them. Any Chief rank or Enemy mark they
        // carried goes too — an owner is neither.
        ColonyRoles roles = ColonyRoles.get(server);
        roles.removeChief(colony.colonyId(), target);
        roles.removeEnemy(colony.colonyId(), target);
        ColonyState.get(server).put(colony.revokeAccess(target).withOwner(target));
        // The previous owner is no longer a member, so the fan-out below will not reach them: blank
        // their view rather than leave the old snapshot (and its roster) on their client.
        if (colony.hasOwner() && !colony.ownerId().equals(target)) {
            ColonySync.clearView(server, colony.ownerId());
        }
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
        int professions = ColonyDefinitions.professionsForServer(server).size();

        source.sendSuccess(() -> Component.translatable("command.nerocolonies.reload_check.header",
                jobs, research, housing, exports, blueprints), false);
        source.sendSuccess(() -> Component.translatable("command.nerocolonies.reload_check.professions",
                professions), false);
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
     * answers together would let anyone probe for the existence of other people's colonies. Only a
     * member is ever told that their rank is the problem. Membership is {@link ColonyPermissions}'
     * reading of it, so somebody the colony has marked as an Enemy is not a member here even if stale
     * data left them on a list.
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
        ColonyPermissions.Role role = ColonyPermissions.roleOf(server, colony, player.getUUID());
        if (!role.member()) {
            source.sendFailure(Component.translatable("command.nerocolonies.colony.unknown"));
            return null;
        }
        if (ownerOnly && role != ColonyPermissions.Role.OWNER) {
            source.sendFailure(Component.translatable("message.nerocolonies.permission.rank"));
            return null;
        }
        return colony;
    }

    /**
     * A colony, and who is acting on it: the role the rank-gated commands hand to
     * {@link ColonyPermissions#allows} and {@link ColonyMembership}.
     *
     * @param actorId the acting player, or {@code null} for the server console
     */
    private record Acting(Colony colony, ColonyPermissions.Role role, @Nullable UUID actorId) {
    }

    /**
     * The colony named by {@code raw} and the caller's role in it, or {@code null} having already
     * told the caller why not. An operator acts as the owner; anybody else acts as what they are, and
     * somebody who is not a member gets the same non-revealing answer as for a colony that does not
     * exist.
     */
    @Nullable
    private static Acting resolveActing(CommandSourceStack source, MinecraftServer server, String raw) {
        Colony colony = resolveColony(source, server, raw, false);
        if (colony == null) {
            return null;
        }
        ServerPlayer player = source.getPlayer();
        UUID actorId = player == null ? null : player.getUUID();
        if (isOperator(source)) {
            return new Acting(colony, ColonyPermissions.Role.OWNER, actorId);
        }
        ColonyPermissions.Role role = actorId == null
                ? ColonyPermissions.Role.STRANGER
                : ColonyPermissions.roleOf(server, colony, actorId);
        return new Acting(colony, role, actorId);
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
        MinecraftServer server = source.getServer();
        return player != null && server != null
                && ColonyPermissions.roleOf(server, colony, player.getUUID()).member();
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
    static int runSafely(CommandSourceStack source, String subcommand, CommandBody body) {
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
    interface CommandBody {

        int run();
    }
}
