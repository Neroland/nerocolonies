package za.co.neroland.nerocolonies.entity;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.entity.ai.HoldClaimGoal;
import za.co.neroland.nerocolonies.entity.ai.MoveToHomeGoal;
import za.co.neroland.nerocolonies.entity.ai.MoveToWorkstationGoal;

/**
 * A colonist: an <b>interchangeable labour unit</b>, and deliberately nothing more.
 *
 * <h2>Scope discipline (this is the project's number-one risk)</h2>
 *
 * <p>A colonist has exactly four persistent fields — {@link #colonyId()}, {@link #homePos()},
 * {@link #jobStationPos()} and {@link #jobId()} — and no others. <b>No names, no personalities, no
 * schedules, no build requests, no relationships, no inventory of their own.</b> Every "just one
 * more colonist detail" is a step toward being a worse version of a mod that already exists; the
 * answer here is structural rather than a matter of restraint, because there is nowhere to put such
 * a detail.
 *
 * <p>The consequence for privacy is a happy one: a colonist carries <b>nothing player-shaped at
 * all</b>. It does not know who owns its colony — the colony record does, server-side — so a
 * colonist entity is never in scope for a data-erasure request.
 *
 * <h2>Behaviour</h2>
 *
 * <p>Vanilla goal AI only: float, walk to the workstation, walk home, stay inside the claim, look
 * around. Colonists <b>never break or place blocks, never open another player's doors, never
 * attack and are never targeted by our own code</b>. There is no target selector on this mob.
 *
 * <h2>Performance</h2>
 *
 * <p>Colonies are a TPS hazard by construction (colonies × colonists × production), so a colonist
 * with no owner or access-list member within {@code aiActiveRadius} goes <em>quiet</em>: our goals
 * consult {@link #aiActive()} and decline to run on three ticks out of four, and navigation is
 * stopped outright. Vanilla's own goals still run — this class does not reach into the goal selector
 * — but the expensive part of a colonist is pathfinding, and that is what stops.
 *
 * <p>Population size is capped from the other direction by {@code colonistsPerColony} and
 * {@code maxLoadedColonists} (see {@code colony/Population}).
 */
public class ColonistEntity extends PathfinderMob {

    /** Ticks between "is anybody here?" checks. Cheap, but not free — it walks the player list. */
    private static final int PRESENCE_CHECK_INTERVAL = 40;

    /** With nobody nearby, goals run on one tick in this many. */
    private static final int QUIET_DIVISOR = 4;

    @Nullable
    private UUID colonyId;

    @Nullable
    private BlockPos homePos;

    @Nullable
    private BlockPos jobStationPos;

    @Nullable
    private Identifier jobId;

    private int presenceCountdown;

    private boolean memberNearby = true;

    public ColonistEntity(EntityType<? extends ColonistEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    /**
     * Ordinary-person attributes: a colonist is not a fighter and is not meant to survive a monster.
     * It has no attack-damage attribute at all, because it has no attack goal to use one.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MoveToWorkstationGoal(this, 1.0D));
        this.goalSelector.addGoal(2, new MoveToHomeGoal(this, 0.9D));
        this.goalSelector.addGoal(3, new HoldClaimGoal(this, 1.0D));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        // Deliberately no target selector: a colonist has no quarrel with anything, and nothing in
        // NeroColonies ever makes one hostile.
    }

    // --- colony binding -----------------------------------------------------

    @Nullable
    public UUID colonyId() {
        return this.colonyId;
    }

    public void bind(UUID colony) {
        this.colonyId = colony;
    }

    @Nullable
    public BlockPos homePos() {
        return this.homePos;
    }

    public void setHomePos(@Nullable BlockPos pos) {
        this.homePos = pos == null ? null : pos.immutable();
    }

    @Nullable
    public BlockPos jobStationPos() {
        return this.jobStationPos;
    }

    public void setJobStationPos(@Nullable BlockPos pos) {
        this.jobStationPos = pos == null ? null : pos.immutable();
    }

    @Nullable
    public Identifier jobId() {
        return this.jobId;
    }

    public void setJobId(@Nullable Identifier job) {
        this.jobId = job;
    }

    /** The colony record this colonist belongs to, or {@code null}. Server-side. */
    @Nullable
    public Colony colony() {
        if (this.colonyId == null || !(this.level() instanceof ServerLevel level)) {
            return null;
        }
        return ColonyState.get(level.getServer()).colony(this.colonyId);
    }

    // --- AI budget ----------------------------------------------------------

    /**
     * Whether our own goals should do work this tick. False when no owner or access-list member is
     * within {@code aiActiveRadius} and this is not the one tick in {@value #QUIET_DIVISOR} that
     * still runs — a colonist nobody is watching still shuffles about, it just does not pathfind.
     */
    public boolean aiActive() {
        if (this.memberNearby) {
            return true;
        }
        return (this.tickCount % QUIET_DIVISOR) == 0;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (--this.presenceCountdown <= 0) {
            this.presenceCountdown = PRESENCE_CHECK_INTERVAL;
            this.memberNearby = anyMemberNearby(level);
        }
        if (!this.memberNearby && (this.tickCount % QUIET_DIVISOR) != 0) {
            // Suspend pathfinding, which is the expensive half of an idle mob.
            this.getNavigation().stop();
        }
    }

    /**
     * Whether an owner or access-list member of this colonist's colony is close enough to warrant
     * full-rate AI. Reads UUIDs on the server only and returns a boolean; nothing player-shaped is
     * stored on the entity or logged.
     */
    private boolean anyMemberNearby(ServerLevel level) {
        int radius = NeroColoniesConfig.AI_ACTIVE_RADIUS.get();
        if (radius <= 0) {
            return false;
        }
        Colony colony = colony();
        double radiusSqr = (double) radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(this) > radiusSqr) {
                continue;
            }
            if (colony == null || colony.isMember(player.getUUID())) {
                return true;
            }
        }
        return false;
    }

    // --- lifecycle ----------------------------------------------------------

    /** A colonist belongs to a colony and must still be there when the player comes back. */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    /** Colonists are never a source of loot; they are people, not a farm. */
    @Override
    public boolean shouldDropExperience() {
        return false;
    }

    // --- persistence --------------------------------------------------------

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (this.colonyId != null) {
            output.putString("ColonyId", this.colonyId.toString());
        }
        putPos(output, "Home", this.homePos);
        putPos(output, "Station", this.jobStationPos);
        if (this.jobId != null) {
            output.putString("JobId", this.jobId.toString());
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        String rawColony = input.getStringOr("ColonyId", "");
        this.colonyId = rawColony.isEmpty() ? null : parseUuid(rawColony);
        this.homePos = readPos(input, "Home");
        this.jobStationPos = readPos(input, "Station");
        String rawJob = input.getStringOr("JobId", "");
        this.jobId = rawJob.isEmpty() ? null : Identifier.tryParse(rawJob);
    }

    @Nullable
    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null; // a malformed id is an unbound colonist, not a crash
        }
    }

    private static void putPos(ValueOutput output, String prefix, @Nullable BlockPos pos) {
        if (pos == null) {
            return;
        }
        output.putInt(prefix + "X", pos.getX());
        output.putInt(prefix + "Y", pos.getY());
        output.putInt(prefix + "Z", pos.getZ());
    }

    @Nullable
    private static BlockPos readPos(ValueInput input, String prefix) {
        if (input.getInt(prefix + "X").isEmpty()) {
            return null;
        }
        return new BlockPos(input.getIntOr(prefix + "X", 0), input.getIntOr(prefix + "Y", 0),
                input.getIntOr(prefix + "Z", 0));
    }

    // --- sounds (mapped vanilla events; NeroColonies ships no audio of its own) ---

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return SoundEvents.VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.VILLAGER_DEATH;
    }
}
