package za.co.neroland.nerocolonies.colony;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ItemTarget;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.link.ColonyLinkEvents;
import za.co.neroland.nerocolonies.link.ColonyLinkModule;

/**
 * Colony defence: who guards and guardian animals may attack, guardian upkeep, enemies in the claim,
 * and the retribution rule.
 *
 * <h2>The target rule</h2>
 *
 * <p>Inside the claim plus {@code guardPursuitMargin}, a guard (and a colony's wolves and golems) may
 * attack hostile mobs (except creepers, which would blow a hole in the colony) and players on the
 * colony's Enemy list when {@code guardsAttackEnemyPlayers} is on. Never anyone else: not a stranger,
 * not an Ally, not the owner, not an operator, not a Neran, not another colony's guardian.
 *
 * <h2>Retribution</h2>
 *
 * <p>When a player on colony X's Enemy list deals the killing blow to colony X's owner — directly, with
 * a projectile, or through a pet they own — they are removed from <em>that</em> colony's Enemy list.
 * The members are told "an enemy has claimed retribution", naming nobody, and the colony counts it.
 * The killer's identity is used in memory for that one comparison and never stored or logged. With
 * PvP off a player cannot kill another, so only a Chief or the owner can lift an enmity.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Nothing here stores a player id. Guardians are marked with an entity tag carrying the colony id.
 * Enemy sightings are counts.
 */
public final class ColonyDefence {

    /** Entity tag prefix marking a guardian animal: {@code nerocolonies.guardian.<colony id>}. */
    public static final String GUARDIAN_TAG = "nerocolonies.guardian.";

    /** Wolves a Kennel keeps, golems a Golem Forge keeps. */
    private static final int WOLVES_PER_KENNEL = 2;
    private static final int GOLEMS_PER_FORGE = 1;

    private static final double GUARDIAN_SCAN_RADIUS = 16.0D;

    /** Cost of a wolf and of a golem, from colony storage. */
    private static final List<ItemTarget> WOLF_COST = List.of(item("minecraft:bone", 2));
    private static final List<ItemTarget> GOLEM_COST = List.of(item("minecraft:iron_block", 4));

    /** Last enemy count seen per colony, so the alert fires on arrival rather than every cycle. */
    private static final Map<UUID, Integer> LAST_ENEMIES = new HashMap<>();

    /** Last "guards engaged" event per colony (rate limit). */
    private static final Map<UUID, Long> LAST_ENGAGED = new HashMap<>();
    private static final long ENGAGED_COOLDOWN_TICKS = 1_200L;

    private ColonyDefence() {
    }

    private static ItemTarget item(String id, int count) {
        return new ItemTarget(java.util.Optional.of(net.minecraft.resources.Identifier.parse(id)),
                java.util.Optional.empty(), count);
    }

    // --- the target rule --------------------------------------------------------

    /** Whether a colony's guards and guardians may attack {@code entity}. */
    public static boolean isValidTarget(ServerLevel level, Colony colony, LivingEntity entity) {
        if (!entity.isAlive() || entity.isSpectator() || entity instanceof ColonistEntity) {
            return false;
        }
        if (!colony.dimension().equals(level.dimension()) || !withinReach(colony, entity.blockPosition())) {
            return false;
        }
        if (entity instanceof Player player) {
            return isEnemyPlayer(level.getServer(), colony, player);
        }
        if (isGuardian(entity)) {
            return false;
        }
        return entity instanceof Enemy && !(entity instanceof Creeper);
    }

    /** Whether a player is an attackable enemy of this colony right now. */
    public static boolean isEnemyPlayer(MinecraftServer server, Colony colony, Player player) {
        if (!NeroColoniesConfig.GUARDS_ATTACK_ENEMY_PLAYERS.get() || player.isCreative() || player.isSpectator()
                || colony.isOwner(player.getUUID()) || ColonyClaims.isGamemaster(player)) {
            return false;
        }
        return ColonyRoles.get(server).isEnemy(colony.colonyId(), player.getUUID());
    }

    private static boolean withinReach(Colony colony, BlockPos pos) {
        int reach = colony.claimRadius() + NeroColoniesConfig.GUARD_PURSUIT_MARGIN.get();
        return Math.abs(pos.getX() - colony.beaconPos().getX()) <= reach
                && Math.abs(pos.getZ() - colony.beaconPos().getZ()) <= reach;
    }

    /** Whether an entity is some colony's guardian animal. */
    public static boolean isGuardian(Entity entity) {
        for (String tag : entity.entityTags()) {
            if (tag.startsWith(GUARDIAN_TAG)) {
                return true;
            }
        }
        return false;
    }

    /** Publishes a rate-limited "guards engaged" event for a guard's colony. */
    public static void guardsEngaged(ColonistEntity guard) {
        Colony colony = guard.colony();
        if (colony == null) {
            return;
        }
        long now = guard.level().getGameTime();
        Long last = LAST_ENGAGED.get(colony.colonyId());
        if (last != null && now - last < ENGAGED_COOLDOWN_TICKS && now >= last) {
            return;
        }
        LAST_ENGAGED.put(colony.colonyId(), now);
        ColonyLinkEvents.colonyEvent(colony, ColonyLinkModule.TOPIC_GUARDS, new JsonObject());
    }

    // --- per colony cycle -------------------------------------------------------

    /**
     * Guardian upkeep and enemy watch, once per colony cycle: points guardian animals at valid
     * targets (and away from invalid ones), walks strays home, has Beastkeepers replace missing
     * guardians from colony storage, and raises the enemy-in-claim event on arrival.
     */
    public static void tick(ServerLevel level, Colony colony, boolean hasBeastkeeper) {
        String tag = GUARDIAN_TAG + colony.colonyId();
        int reach = colony.claimRadius() + NeroColoniesConfig.GUARD_PURSUIT_MARGIN.get();
        BlockPos beacon = colony.beaconPos();
        AABB box = new AABB(beacon.getX() - reach, level.getMinY(), beacon.getZ() - reach,
                beacon.getX() + reach + 1, level.getMaxY() + 1, beacon.getZ() + reach + 1);
        List<Mob> guardians = level.getEntitiesOfClass(Mob.class, box.inflate(32.0D),
                mob -> mob.isAlive() && mob.entityTags().contains(tag));
        int wolves = 0;
        int golems = 0;
        for (Mob guardian : guardians) {
            if (guardian instanceof Wolf) {
                wolves++;
            } else if (guardian instanceof IronGolem) {
                golems++;
            }
            direct(level, colony, guardian);
        }
        if (hasBeastkeeper && level.getServer() != null) {
            replenish(level, colony, tag, wolves, golems);
        }
        watchEnemies(level, colony);
    }

    private static void direct(ServerLevel level, Colony colony, Mob guardian) {
        LivingEntity target = guardian.getTarget();
        if (target != null && !isValidTarget(level, colony, target)) {
            guardian.setTarget(null); // never let a guardian keep chasing someone it may not attack
            target = null;
        }
        if (target == null) {
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                    guardian.getBoundingBox().inflate(GUARDIAN_SCAN_RADIUS), e -> isValidTarget(level, colony, e))) {
                double distance = entity.distanceToSqr(guardian);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entity;
                }
            }
            if (best != null) {
                guardian.setTarget(best);
            } else if (!withinReach(colony, guardian.blockPosition())) {
                BlockPos beacon = colony.beaconPos();
                guardian.getNavigation().moveTo(beacon.getX() + 0.5D, beacon.getY(), beacon.getZ() + 0.5D, 1.0D);
            }
        }
    }

    private static void replenish(ServerLevel level, Colony colony, String tag, int wolves, int golems) {
        MinecraftServer server = level.getServer();
        int wantedWolves = WOLVES_PER_KENNEL * ColonyBuildings.withRole(level, colony, ColonyBuildings.ROLE_KENNEL).size();
        int wantedGolems = GOLEMS_PER_FORGE * ColonyBuildings.withRole(level, colony, ColonyBuildings.ROLE_GOLEM).size();
        if (wolves < wantedWolves && ColonyStorage.consume(server, colony.colonyId(), WOLF_COST)) {
            spawnGuardian(level, colony, tag, wolfType(), ColonyBuildings.ROLE_KENNEL);
        } else if (golems < wantedGolems && ColonyStorage.consume(server, colony.colonyId(), GOLEM_COST)) {
            spawnGuardian(level, colony, tag, golemType(), ColonyBuildings.ROLE_GOLEM);
        }
    }

    private static EntityType<Wolf> wolfType() {
        //? if >=26.2 {
        return net.minecraft.world.entity.EntityTypes.WOLF;
        //?} else {
        /*return EntityType.WOLF;
        *///?}
    }

    private static EntityType<IronGolem> golemType() {
        //? if >=26.2 {
        return net.minecraft.world.entity.EntityTypes.IRON_GOLEM;
        //?} else {
        /*return EntityType.IRON_GOLEM;
        *///?}
    }

    private static void spawnGuardian(ServerLevel level, Colony colony, String tag, EntityType<? extends Mob> type,
            String role) {
        BlockPos at = ColonyBuildings.anchor(level, colony, role, colony.beaconPos());
        if (at == null) {
            return;
        }
        Mob mob = type.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (mob == null) {
            return;
        }
        mob.snapTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
        mob.addTag(tag);
        mob.setPersistenceRequired();
        if (mob instanceof TamableAnimal tamable) {
            tamable.setTame(true, false); // ownerless and tame: no sheep-hunting, attacks only what it is set on
        }
        if (mob instanceof IronGolem golem) {
            golem.setPlayerCreated(true); // never turns on players for hurting it by accident
        }
        if (level.addFreshEntity(mob)) {
            NeroColoniesCommon.LOGGER.debug("[NeroColonies] A colony guardian arrived.");
        }
    }

    private static void watchEnemies(ServerLevel level, Colony colony) {
        int count = 0;
        for (ServerPlayer player : level.players()) {
            if (colony.contains(player.blockPosition()) && isEnemyPlayer(level.getServer(), colony, player)) {
                count++;
            }
        }
        Integer previous = LAST_ENEMIES.put(colony.colonyId(), count);
        if (count > 0 && (previous == null || previous == 0)) {
            JsonObject extra = new JsonObject();
            extra.addProperty("enemies", count); // a count, never who
            ColonyLinkEvents.colonyEvent(colony, ColonyLinkModule.TOPIC_ENEMY, extra);
            ColonyLinkEvents.colonyAlert(level.getServer(), colony, "enemy", true,
                    "Enemies are inside " + colony.name() + ".");
        }
    }

    // --- retribution -----------------------------------------------------------

    /**
     * Called by every loader when a living entity dies. Applies the retribution rule when a player
     * who owns colonies is killed by a player on one of those colonies' Enemy lists.
     */
    public static void onLivingDeath(LivingEntity dead, DamageSource source) {
        if (!(dead instanceof ServerPlayer victim) || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        UUID killer = killerOf(source);
        if (killer == null || killer.equals(victim.getUUID())) {
            return;
        }
        MinecraftServer server = level.getServer();
        ColonyRoles roles = ColonyRoles.get(server);
        ColonyLife life = ColonyLife.get(server);
        for (Colony colony : ColonyState.get(server).colonies()) {
            if (colony.isOwner(victim.getUUID()) && roles.removeEnemy(colony.colonyId(), killer)) {
                life.life(colony.colonyId()).countRetribution();
                life.touch();
                ColonyProgress.tellMembers(server, colony,
                        Component.translatable("message.nerocolonies.retribution", colony.name()));
                NeroColoniesCommon.LOGGER.info("[NeroColonies] An enemy claimed retribution at a colony.");
            }
        }
    }

    /** The player responsible for a death: the attacker, a projectile's owner, or a pet's owner. */
    @org.jetbrains.annotations.Nullable
    static UUID killerOf(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker instanceof Player player) {
            return player.getUUID();
        }
        if (attacker instanceof OwnableEntity pet && pet.getOwnerReference() != null) {
            return pet.getOwnerReference().getUUID();
        }
        return null;
    }

    /** Clears session state. Called when the server stops. */
    public static void reset() {
        LAST_ENEMIES.clear();
        LAST_ENGAGED.clear();
    }
}
