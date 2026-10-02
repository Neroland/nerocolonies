package za.co.neroland.nerocolonies.entity;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyState;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ProfessionDefinition;
import za.co.neroland.nerocolonies.entity.ai.DayCycle;
import za.co.neroland.nerocolonies.entity.ai.EatGoal;
import za.co.neroland.nerocolonies.entity.ai.GuardTargetGoal;
import za.co.neroland.nerocolonies.entity.ai.HoldClaimGoal;
import za.co.neroland.nerocolonies.entity.ai.MoveToHomeGoal;
import za.co.neroland.nerocolonies.entity.ai.MoveToWorkstationGoal;
import za.co.neroland.nerocolonies.entity.ai.SocialiseGoal;
import za.co.neroland.nerocolonies.entity.ai.WorkGoal;
import za.co.neroland.nerocolonies.entity.ai.nav.NeranNavigation;

/**
 * A Neran: one of the people of a Neroland colony.
 *
 * <h2>What a Neran carries</h2>
 *
 * <p>Colony id, home, workstation and job; whether it arrived as a founder and when; its
 * <b>profession</b> and <b>experience</b> in it; a <b>small carry inventory</b> (nine slots) for
 * tools and loads; whether it is a <b>child</b> and when it was born; and an optional
 * <b>display name</b> drawn from the language file's name pool. Still nothing player-shaped: a Neran
 * does not know who owns its colony, so it is never in scope for a data-erasure request.
 *
 * <h2>Behaviour</h2>
 *
 * <p>A daily schedule (see {@link DayCycle}): work, a midday meal at a granary or canteen, an
 * evening near the plaza, and sleep at home. At work a Neran runs its trade's behaviour around its
 * workplace — farmers harvest and replant, foresters fell and replant, and so on — and gathers its
 * trade's share of the colony's goods on the colony tick whether or not anyone is watching. Guards
 * (and only guards) fight: hostile mobs, and players on the colony's Enemy list, inside the claim
 * plus a margin. Children play and grow up.
 *
 * <h2>Performance</h2>
 *
 * <p>With no member within {@code aiActiveRadius} a Neran goes <em>quiet</em>: goals start only
 * during a short pulse every {@value #QUIET_PERIOD} ticks, which always overlaps a full
 * goal-selector tick whatever the entity id's parity. A walk that has started always finishes.
 */
public class ColonistEntity extends PathfinderMob {

    private static final EntityDataAccessor<Boolean> DATA_CHILD =
            SynchedEntityData.defineId(ColonistEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Byte> DATA_STATUS =
            SynchedEntityData.defineId(ColonistEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<String> DATA_PROFESSION =
            SynchedEntityData.defineId(ColonistEntity.class, EntityDataSerializers.STRING);

    /** Carry slots. */
    public static final int INVENTORY_SLOTS = 9;

    /** Size of the lang-file name pool ({@code neran.nerocolonies.name.0..N-1}). */
    public static final int NAME_POOL = 64;

    private static final int PRESENCE_CHECK_INTERVAL = 40;
    private static final int QUIET_PERIOD = 8;
    private static final int QUIET_PULSE = 2;
    private static final int COLONY_CACHE_TICKS = 20;
    private static final int DAY_CYCLE_INTERVAL = 20;
    private static final int GROWTH_CHECK_INTERVAL = 200;
    private static final long TICKS_PER_DAY = 24_000L;

    @Nullable
    private UUID colonyId;
    @Nullable
    private BlockPos homePos;
    @Nullable
    private BlockPos jobStationPos;
    @Nullable
    private Identifier jobId;
    private boolean founder;
    private long arrivedAt;

    @Nullable
    private Identifier professionId;
    private int professionXp;
    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SLOTS);
    private long bornAt = -1L;

    private int presenceCountdown;
    private boolean memberNearby = true;
    private int quietCountdown;
    private int quietPulse;
    private DayCycle.Phase phase = DayCycle.Phase.WORK;
    private int dayCycleCountdown;
    private int growthCountdown;

    @Nullable
    private Colony cachedColony;
    private long colonyCachedAt = Long.MIN_VALUE;

    public ColonistEntity(EntityType<? extends ColonistEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.setDropChance(slot, 0.0F); // tools come from, and return to, colony storage
        }
    }

    /** Ordinary people; guards hit a little harder because they carry a weapon. */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new NeranNavigation(this, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CHILD, false);
        builder.define(DATA_STATUS, (byte) 0);
        builder.define(DATA_PROFESSION, "");
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(1, new GuardMeleeGoal(this));
        this.goalSelector.addGoal(2, new WorkGoal(this));
        this.goalSelector.addGoal(2, new MoveToWorkstationGoal(this, 1.0D));
        this.goalSelector.addGoal(2, new EatGoal(this, 0.9D));
        this.goalSelector.addGoal(2, new SocialiseGoal(this, 0.8D));
        this.goalSelector.addGoal(3, new MoveToHomeGoal(this, 0.9D));
        this.goalSelector.addGoal(4, new HoldClaimGoal(this, 1.0D));
        this.goalSelector.addGoal(6, new GatedStrollGoal(this, 0.7D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        // Guards only: see GuardTargetGoal for the rule (hostile mobs and listed enemies, nothing else).
        this.targetSelector.addGoal(1, new GuardTargetGoal(this));
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

    public boolean isFounder() {
        return this.founder;
    }

    public long arrivedAt() {
        return this.arrivedAt;
    }

    public void markArrival(long gameTime, boolean asFounder) {
        this.arrivedAt = gameTime;
        this.founder = asFounder;
    }

    // --- profession, age, name ------------------------------------------------

    @Nullable
    public Identifier professionId() {
        return this.professionId;
    }

    /** The profession definition, or null. */
    @Nullable
    public ProfessionDefinition profession() {
        return this.professionId == null ? null : ColonyDefinitions.profession(this.professionId).orElse(null);
    }

    /** Sets (or clears) the profession; xp resets when the trade changes. */
    public void setProfession(@Nullable Identifier profession) {
        if (profession == null ? this.professionId == null : profession.equals(this.professionId)) {
            return;
        }
        returnToolToStorage();
        this.professionId = profession;
        this.professionXp = 0;
        this.entityData.set(DATA_PROFESSION, profession == null ? "" : profession.toString());
        updateHeldTool();
    }

    /** The synced profession id string (client side), possibly empty. */
    public String syncedProfession() {
        return this.entityData.get(DATA_PROFESSION);
    }

    public int professionXp() {
        return this.professionXp;
    }

    public void addProfessionXp(int amount) {
        this.professionXp = Math.max(0, this.professionXp + amount);
    }

    /** Level in the current trade, 1-based. */
    public int professionLevel() {
        ProfessionDefinition definition = profession();
        return definition == null ? 1 : definition.levelFor(this.professionXp);
    }

    /** The carry inventory (tools and loads). */
    public SimpleContainer inventory() {
        return this.inventory;
    }

    /** Whether this Neran is a child (synced). */
    public boolean isChildNeran() {
        return this.entityData.get(DATA_CHILD);
    }

    /** Makes this Neran a child born at {@code gameTime}. */
    public void makeChild(long gameTime) {
        this.bornAt = gameTime;
        this.entityData.set(DATA_CHILD, true);
        this.refreshDimensions();
    }

    @Override
    public boolean isBaby() {
        return isChildNeran();
    }

    /** Gives this Neran a generated name from the pool, if names are enabled and it has none. */
    public void assignGeneratedName() {
        if (!NeroColoniesConfig.NERAN_NAMES_ENABLED.get() || this.hasCustomName()) {
            return;
        }
        int index = this.random.nextInt(NAME_POOL);
        this.setCustomName(Component.translatable("neran.nerocolonies.name." + index));
    }

    public NeranStatus status() {
        return NeranStatus.byOrdinal(this.entityData.get(DATA_STATUS));
    }

    public void setStatus(NeranStatus status) {
        this.entityData.set(DATA_STATUS, (byte) status.ordinal());
    }

    public DayCycle.Phase phase() {
        return this.phase;
    }

    /** Whether it is night for this Neran. */
    public boolean isNight() {
        return this.phase == DayCycle.Phase.SLEEP;
    }

    /** Whether this Neran is a guard (and so may fight). */
    public boolean isGuard() {
        return GUARD.equals(this.professionId) && !isChildNeran();
    }

    /** The guard profession id. */
    public static final Identifier GUARD = Identifier.fromNamespaceAndPath("nerocolonies", "guard");

    /** Holds the trade's tool if the colony could provide one, otherwise empty hands. */
    public void updateHeldTool() {
        ProfessionDefinition definition = profession();
        ItemStack tool = ItemStack.EMPTY;
        if (definition != null && !isChildNeran()) {
            for (int i = 0; i < this.inventory.getContainerSize(); i++) {
                ItemStack stack = this.inventory.getItem(i);
                if (!stack.isEmpty() && definition.toolItem() != null && stack.is(definition.toolItem())) {
                    tool = stack.copyWithCount(1);
                    break;
                }
            }
        }
        this.setItemSlot(EquipmentSlot.MAINHAND, tool);
    }

    /** Whether the Neran carries its trade's tool (or the trade needs none). */
    public boolean hasTool() {
        ProfessionDefinition definition = profession();
        return definition == null || definition.toolItem() == null
                || this.inventory.hasAnyMatching(stack -> stack.is(definition.toolItem()));
    }

    /** Puts a carried tool back in colony storage (on a trade change); never voided. */
    private void returnToolToStorage() {
        if (!(this.level() instanceof ServerLevel level) || this.colonyId == null) {
            return;
        }
        Colony colony = colony();
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.removeItemNoUpdate(i);
            if (stack.isEmpty()) {
                continue;
            }
            int left = colony == null ? stack.getCount()
                    : ColonyStorage.insert(level.getServer(), this.colonyId, stack.copy(),
                            ColonyStorage.usableSlots(level, colony));
            if (left > 0) {
                this.spawnAtLocation(level, stack.copyWithCount(left));
            }
        }
    }

    /** Counts a stuck event for this Neran's colony (aggregate only). */
    public void reportStuck() {
        if (this.colonyId != null && this.level() instanceof ServerLevel level) {
            ColonyLife store = ColonyLife.get(level.getServer());
            store.life(this.colonyId).countStuck();
            store.touch();
        }
    }

    /** The colony record this Neran belongs to, cached for {@value #COLONY_CACHE_TICKS} ticks. */
    @Nullable
    public Colony colony() {
        if (this.colonyId == null || !(this.level() instanceof ServerLevel level)) {
            return null;
        }
        long now = level.getGameTime();
        if (now - this.colonyCachedAt >= COLONY_CACHE_TICKS || now < this.colonyCachedAt) {
            this.cachedColony = ColonyState.get(level.getServer()).colony(this.colonyId);
            this.colonyCachedAt = now;
        }
        return this.cachedColony;
    }

    // --- AI budget, schedule, growth ------------------------------------------

    public boolean aiActive() {
        return this.memberNearby || this.quietPulse > 0;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (--this.presenceCountdown <= 0) {
            this.presenceCountdown = PRESENCE_CHECK_INTERVAL;
            this.memberNearby = anyMemberNearby(level);
        }
        if (this.quietPulse > 0) {
            this.quietPulse--;
        } else if (--this.quietCountdown <= 0) {
            this.quietCountdown = QUIET_PERIOD;
            this.quietPulse = QUIET_PULSE;
        }
        if (--this.dayCycleCountdown <= 0) {
            this.dayCycleCountdown = DAY_CYCLE_INTERVAL;
            DayCycle.Phase next = DayCycle.phase(level, this.phase);
            if (next != this.phase) {
                this.phase = next;
                if (next == DayCycle.Phase.SLEEP) {
                    setStatus(NeranStatus.SLEEPING);
                } else if (status() == NeranStatus.SLEEPING) {
                    setStatus(NeranStatus.NONE);
                }
            }
        }
        if (--this.growthCountdown <= 0) {
            this.growthCountdown = GROWTH_CHECK_INTERVAL;
            growUpIfDue(level);
        }
    }

    private void growUpIfDue(ServerLevel level) {
        if (!isChildNeran()) {
            return;
        }
        long age = level.getGameTime() - this.bornAt;
        if (this.bornAt < 0 || age >= NeroColoniesConfig.CHILD_GROWTH_DAYS.get() * TICKS_PER_DAY) {
            this.entityData.set(DATA_CHILD, false);
            this.bornAt = -1L;
            this.refreshDimensions();
            // A trade is handed out by the colony on its next cycle.
        }
    }

    private boolean anyMemberNearby(ServerLevel level) {
        int radius = NeroColoniesConfig.AI_ACTIVE_RADIUS.get();
        if (radius <= 0) {
            return false;
        }
        Colony colony = colony();
        if (colony == null) {
            return false;
        }
        double radiusSqr = (double) radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(this) <= radiusSqr && colony.isMember(player.getUUID())) {
                return true;
            }
        }
        return false;
    }

    // --- interaction ----------------------------------------------------------

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(Items.NAME_TAG) && !this.level().isClientSide()) {
            // Renaming a Neran is a member's privilege; strangers are told so and nothing happens.
            if (!ColonyPermissions.check(player, colony(), ColonyPermissions.Action.NERAN)) {
                return InteractionResult.FAIL;
            }
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean shouldDropExperience() {
        return false;
    }

    @Override
    public void die(DamageSource source) {
        returnToolToStorage(); // a Neran's tools go back to the colony, not onto the ground
        super.die(source);
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
        output.putBoolean("Founder", this.founder);
        output.putLong("ArrivedAt", this.arrivedAt);
        if (this.professionId != null) {
            output.putString("Profession", this.professionId.toString());
        }
        output.putInt("ProfessionXp", this.professionXp);
        output.putBoolean("Child", isChildNeran());
        output.putLong("BornAt", this.bornAt);
        this.inventory.storeAsItemList(output.list("Inventory", ItemStack.CODEC));
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
        // Nerans saved before 0.3 carry none of the keys below: they read as adult, unnamed,
        // tradeless non-founders that arrived at time zero, and the colony hands them a trade on its
        // next cycle.
        this.founder = input.getBooleanOr("Founder", false);
        this.arrivedAt = input.getLongOr("ArrivedAt", 0L);
        String rawProfession = input.getStringOr("Profession", "");
        this.professionId = rawProfession.isEmpty() ? null : Identifier.tryParse(rawProfession);
        this.entityData.set(DATA_PROFESSION, this.professionId == null ? "" : this.professionId.toString());
        this.professionXp = input.getIntOr("ProfessionXp", 0);
        this.entityData.set(DATA_CHILD, input.getBooleanOr("Child", false));
        this.bornAt = input.getLongOr("BornAt", -1L);
        input.list("Inventory", ItemStack.CODEC).ifPresent(this.inventory::fromItemList);
    }

    @Nullable
    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
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

    // --- goals defined here -----------------------------------------------------

    /** The vanilla stroll, started only within the AI budget; children stroll, adults at work do not. */
    private static final class GatedStrollGoal extends WaterAvoidingRandomStrollGoal {

        private final ColonistEntity colonist;

        GatedStrollGoal(ColonistEntity colonist, double speed) {
            super(colonist, speed);
            this.colonist = colonist;
        }

        @Override
        public boolean canUse() {
            return this.colonist.aiActive() && this.colonist.phase() != DayCycle.Phase.SLEEP && super.canUse();
        }
    }

    /** Vanilla melee, for guards only. */
    private static final class GuardMeleeGoal extends MeleeAttackGoal {

        private final ColonistEntity colonist;

        GuardMeleeGoal(ColonistEntity colonist) {
            super(colonist, 1.1D, true);
            this.colonist = colonist;
        }

        @Override
        public boolean canUse() {
            return this.colonist.isGuard() && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return this.colonist.isGuard() && super.canContinueToUse();
        }

        @Override
        public void start() {
            super.start();
            this.colonist.setStatus(NeranStatus.GUARDING);
        }
    }

    // --- sounds -----------------------------------------------------------------

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
