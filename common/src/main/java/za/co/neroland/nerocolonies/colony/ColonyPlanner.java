package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.Blueprint;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;

/**
 * Hand-placed buildings: the server half of the Colony Planner item and the Planning Table.
 *
 * <p>A member with planning rights (the owner or a Chief) picks one of the buildings the colony has
 * unlocked, previews its footprint on the ground, and confirms. The plan joins the colony's plan
 * queue, which the builders take before anything the colony would have chosen for itself. Nothing
 * else changes: a planned building is paid for and built exactly like an autonomous one, and a plan
 * whose site has been blocked by the time its turn comes is dropped.
 *
 * <p><b>Server-authoritative.</b> The client sends only "use" and "use on this block"; which
 * building is selected, whether it fits and whether the player may plan are all decided here.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> the selection a player is holding is kept in memory only, keyed by
 * the player's id for as long as the server runs, never saved, logged or sent anywhere.
 */
public final class ColonyPlanner {

    /** What a player has selected and last previewed. In memory only. */
    private static final class Session {
        private UUID colony;
        private Identifier blueprint;
        private Rotation rotation = Rotation.NONE;
        private BlockPos previewed;
        private long previewedAt;
    }

    /** How long a preview stays confirmable, in ticks. */
    private static final long CONFIRM_WINDOW = 600L;

    /** A ceiling on remembered selections, so the map cannot grow without limit. */
    private static final int MAX_SESSIONS = 256;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private ColonyPlanner() {
    }

    /** Forgets every selection. Called when the server stops. */
    public static void reset() {
        SESSIONS.clear();
    }

    /** Forgets one player's selection. Called when they leave. */
    public static void forget(UUID player) {
        SESSIONS.remove(player);
    }

    /**
     * Whether a member may place this blueprint by hand now: it has a grid, the colony's stage and
     * research allow it, it is a first-level building (higher levels come as upgrades), it is within
     * the server's size limits and the colony has not built its maximum.
     */
    public static boolean plannable(Colony colony, ColonyStage stage, @Nullable ColonyConstruction.Plan plan,
            Blueprint blueprint) {
        if (!blueprint.hasGrid() || blueprint.level() > 1 || blueprint.max() <= 0) {
            return false;
        }
        if (!stage.atLeast(blueprint.stage()) && !blueprint.starter()) {
            return false;
        }
        if (blueprint.research().isPresent()
                && !colony.researchUnlocked().contains(blueprint.research().get().toString())) {
            return false;
        }
        int limit = NeroColoniesConfig.MAX_BLUEPRINT_FOOTPRINT.get();
        if (blueprint.width() > limit || blueprint.depth() > limit
                || blueprint.height() > NeroColoniesConfig.MAX_BLUEPRINT_HEIGHT.get()) {
            return false;
        }
        int planned = 0;
        if (plan != null) {
            for (ColonyConstruction.Planned queued : plan.queue()) {
                if (queued.blueprint().equals(blueprint.id())) {
                    planned++;
                }
            }
            if (blueprint.id().equals(plan.active())) {
                planned++;
            }
        }
        int built = plan == null ? 0 : plan.builtCount(blueprint.id());
        return built + planned < blueprint.max();
    }

    /** The buildings a colony may place by hand now, in build-priority order. */
    public static List<Blueprint> options(MinecraftServer server, Colony colony) {
        ColonyStage stage = ColonyProgress.stage(server, colony);
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
        List<Blueprint> options = new ArrayList<>();
        for (Blueprint blueprint : ColonyDefinitions.blueprintsByPriority(server)) {
            if (plannable(colony, stage, plan, blueprint)) {
                options.add(blueprint);
            }
        }
        return options;
    }

    /** The Planning Table's readout: what can be planned, and what is queued. */
    public static void describe(ServerLevel level, ServerPlayer player, Colony colony) {
        MinecraftServer server = level.getServer();
        List<Blueprint> options = options(server, colony);
        player.sendSystemMessage(Component.translatable("message.nerocolonies.plan.header", colony.name(),
                options.size()).withStyle(ChatFormatting.GOLD));
        int shown = 0;
        for (Blueprint blueprint : options) {
            if (shown++ >= 12) {
                player.sendSystemMessage(Component.translatable("message.nerocolonies.needs.more",
                        options.size() - 12).withStyle(ChatFormatting.GRAY));
                break;
            }
            player.sendSystemMessage(Component.translatable("message.nerocolonies.plan.option",
                    Component.translatable(blueprint.nameKey()), blueprint.width(), blueprint.depth()));
        }
        ColonyConstruction.Plan plan = ColonyConstruction.get(server).peek(colony.colonyId());
        List<ColonyConstruction.Planned> queue = plan == null ? List.of() : plan.queue();
        if (queue.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.nerocolonies.plan.queue_empty")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        Map<Identifier, Blueprint> all = ColonyDefinitions.blueprintsForServer(server);
        for (int i = 0; i < queue.size(); i++) {
            Blueprint blueprint = all.get(queue.get(i).blueprint());
            player.sendSystemMessage(Component.translatable("message.nerocolonies.plan.queued", i + 1,
                    blueprint == null ? Component.literal(queue.get(i).blueprint().toString())
                            : Component.translatable(blueprint.nameKey())));
        }
    }

    /** Use in the air: next building, or (sneaking) turn the selected one a quarter. */
    public static void cycle(ServerLevel level, ServerPlayer player, boolean turn) {
        Colony colony = colonyFor(level, player, player.blockPosition());
        if (colony == null) {
            return;
        }
        List<Blueprint> options = options(level.getServer(), colony);
        if (options.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("message.nerocolonies.plan.nothing"));
            return;
        }
        Session session = session(player, colony);
        int index = indexOf(options, session.blueprint);
        Blueprint selected;
        if (turn && index >= 0) {
            selected = options.get(index);
            session.rotation = selected.rotate() ? session.rotation.getRotated(Rotation.CLOCKWISE_90)
                    : Rotation.NONE;
        } else {
            selected = options.get((index + 1) % options.size());
            session.blueprint = selected.id();
            session.rotation = Rotation.NONE;
        }
        session.previewed = null;
        player.sendOverlayMessage(Component.translatable("message.nerocolonies.plan.selected",
                Component.translatable(selected.nameKey()), selected.width(session.rotation),
                selected.depth(session.rotation)));
    }

    /**
     * Use on the ground: preview the footprint centred on {@code spot}. Using the same spot again
     * within half a minute confirms and queues the plan.
     */
    public static void previewOrConfirm(ServerLevel level, ServerPlayer player, BlockPos spot) {
        Colony colony = colonyFor(level, player, spot);
        if (colony == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        Session session = session(player, colony);
        List<Blueprint> options = options(server, colony);
        int index = indexOf(options, session.blueprint);
        if (index < 0) {
            player.sendOverlayMessage(Component.translatable(options.isEmpty()
                    ? "message.nerocolonies.plan.nothing" : "message.nerocolonies.plan.pick_first"));
            return;
        }
        Blueprint blueprint = options.get(index);
        Rotation rotation = session.rotation;
        int width = blueprint.width(rotation);
        int depth = blueprint.depth(rotation);
        BlockPos corner = new BlockPos(spot.getX() - width / 2, spot.getY(), spot.getZ() - depth / 2);
        BlockPos origin = Construction.evaluateAt(server, level, colony, blueprint, corner, rotation);

        boolean confirming = origin != null && spot.equals(session.previewed)
                && level.getGameTime() - session.previewedAt <= CONFIRM_WINDOW;
        if (!confirming) {
            session.previewed = origin == null ? null : spot.immutable();
            session.previewedAt = level.getGameTime();
            outline(level, origin == null ? corner : origin, width, depth, blueprint.height(), origin != null);
            player.sendOverlayMessage(Component.translatable(origin == null
                    ? "message.nerocolonies.plan.no_fit" : "message.nerocolonies.plan.preview",
                    Component.translatable(blueprint.nameKey())));
            return;
        }

        ColonyConstruction store = ColonyConstruction.get(server);
        ColonyConstruction.Plan plan = store.plan(colony.colonyId());
        int limit = Math.min(ColonyConstruction.MAX_PLANS, NeroColoniesConfig.MAX_CONCURRENT_PLANS.get());
        if (plan.queue().size() >= limit
                || !plan.enqueue(new ColonyConstruction.Planned(blueprint.id(), origin, rotation))) {
            player.sendOverlayMessage(Component.translatable("message.nerocolonies.plan.queue_full", limit));
            return;
        }
        store.touch();
        session.previewed = null;
        outline(level, origin, width, depth, blueprint.height(), true);
        player.sendOverlayMessage(Component.translatable("message.nerocolonies.plan.confirmed",
                Component.translatable(blueprint.nameKey())));
    }

    /** Removes a queued plan by its 1-based place in the queue. Returns whether one was removed. */
    public static boolean cancel(MinecraftServer server, Colony colony, int place) {
        ColonyConstruction store = ColonyConstruction.get(server);
        ColonyConstruction.Plan plan = store.peek(colony.colonyId());
        if (plan == null || !plan.cancelPlanned(place - 1)) {
            return false;
        }
        store.touch();
        return true;
    }

    // --- helpers ------------------------------------------------------------------

    @Nullable
    private static Colony colonyFor(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Colony colony = ColonyState.get(level.getServer()).colonyAt(level.dimension(), pos);
        if (colony == null) {
            player.sendOverlayMessage(Component.translatable("message.nerocolonies.plan.outside"));
            return null;
        }
        return ColonyPermissions.check(player, colony, ColonyPermissions.Action.PLAN) ? colony : null;
    }

    private static Session session(ServerPlayer player, Colony colony) {
        if (SESSIONS.size() >= MAX_SESSIONS && !SESSIONS.containsKey(player.getUUID())) {
            SESSIONS.clear();
        }
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
        if (!colony.colonyId().equals(session.colony)) {
            session.colony = colony.colonyId();
            session.blueprint = null;
            session.rotation = Rotation.NONE;
            session.previewed = null;
        }
        return session;
    }

    private static int indexOf(List<Blueprint> options, @Nullable Identifier blueprint) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(blueprint)) {
                return i;
            }
        }
        return -1;
    }

    /** Draws the footprint: its outline on the ground and the four corner posts. */
    private static void outline(ServerLevel level, BlockPos origin, int width, int depth, int height,
            boolean fits) {
        ParticleOptions particle = fits ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.FLAME;
        double y = origin.getY() + 0.15D;
        for (int dx = 0; dx <= width; dx++) {
            dot(level, particle, origin.getX() + dx, y, origin.getZ());
            dot(level, particle, origin.getX() + dx, y, origin.getZ() + depth);
        }
        for (int dz = 1; dz < depth; dz++) {
            dot(level, particle, origin.getX(), y, origin.getZ() + dz);
            dot(level, particle, origin.getX() + width, y, origin.getZ() + dz);
        }
        int posts = Math.min(height, 16);
        for (int dy = 1; dy <= posts; dy++) {
            double py = origin.getY() + dy;
            dot(level, particle, origin.getX(), py, origin.getZ());
            dot(level, particle, origin.getX() + width, py, origin.getZ());
            dot(level, particle, origin.getX(), py, origin.getZ() + depth);
            dot(level, particle, origin.getX() + width, py, origin.getZ() + depth);
        }
    }

    private static void dot(ServerLevel level, ParticleOptions particle, double x, double y, double z) {
        level.sendParticles(particle, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }
}
