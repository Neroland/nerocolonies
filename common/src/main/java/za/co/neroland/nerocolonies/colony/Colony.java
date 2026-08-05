package za.co.neroland.nerocolonies.colony;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One colony, as stored. Immutable: every change produces a new record which
 * {@link ColonyState#put(Colony)} swaps in, so there is no way to mutate a colony without the index
 * and the dirty flag being updated with it.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>{@link #ownerId()} and {@link #accessList()} are the only player-shaped values here, they are
 * plain Minecraft game UUIDs, and they <b>never leave the server</b>. Nothing in the public query
 * surface ({@link ColonyApi}) returns them: callers ask boolean questions
 * ("is this claimed?", "may this player build here?") and get boolean answers. The client is sent a
 * colony's <em>state</em> (morale, population, food) and never its membership.
 *
 * <p>A colony can be <b>ownerless</b>: {@link #SERVER_OWNER} (the nil UUID) is what an owner slot
 * holds after an erasure request under the {@code transfer_to_server} policy. An ownerless colony
 * keeps running and can be administered by operators; it simply has no player owner. This is
 * deliberate — deleting a shared colony because one member exercised a data right would let an
 * erasure request grief a co-op server.
 */
public record Colony(
        UUID colonyId,
        String name,
        ResourceKey<Level> dimension,
        BlockPos beaconPos,
        int claimRadius,
        UUID ownerId,
        Set<UUID> accessList,
        long createdAt,
        long lastTick,
        double morale,
        int population,
        int housingCapacity,
        Set<String> researchUnlocked,
        boolean lifeSupportOk,
        int foodStock,
        Set<UUID> outpostIds) {

    /** The owner slot of a colony with no player owner (post-erasure, or an admin-created colony). */
    public static final UUID SERVER_OWNER = new UUID(0L, 0L);

    /** Hard cap on a player-supplied colony name, in characters. */
    public static final int MAX_NAME_LENGTH = 32;

    /** Hard cap on access-list size — a bound on stored player-shaped data, not a gameplay rule. */
    public static final int MAX_ACCESS_LIST = 64;

    /** Fallback when a supplied name sanitises to nothing. */
    public static final String DEFAULT_NAME = "Colony";

    private static final double MIN_MORALE = 0.0D;
    private static final double MAX_MORALE = 100.0D;

    /** UUIDs are stored as strings; a malformed one fails its own field instead of the whole file. */
    public static final Codec<UUID> UUID_CODEC = Codec.STRING.comapFlatMap(
            text -> {
                try {
                    return DataResult.success(UUID.fromString(text));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "Not a UUID: " + text);
                }
            },
            UUID::toString);

    public static final Codec<Set<UUID>> UUID_SET_CODEC = UUID_CODEC.listOf()
            .xmap(list -> (Set<UUID>) new LinkedHashSet<>(list), List::copyOf);

    public static final Codec<Set<String>> STRING_SET_CODEC = Codec.STRING.listOf()
            .xmap(list -> (Set<String>) new LinkedHashSet<>(list), List::copyOf);

    public static final MapCodec<Colony> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            UUID_CODEC.fieldOf("id").forGetter(Colony::colonyId),
            Codec.STRING.optionalFieldOf("name", DEFAULT_NAME).forGetter(Colony::name),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Colony::dimension),
            BlockPos.CODEC.fieldOf("beacon").forGetter(Colony::beaconPos),
            Codec.INT.optionalFieldOf("claim_radius", 48).forGetter(Colony::claimRadius),
            UUID_CODEC.optionalFieldOf("owner", SERVER_OWNER).forGetter(Colony::ownerId),
            UUID_SET_CODEC.optionalFieldOf("access", Set.of()).forGetter(Colony::accessList),
            Codec.LONG.optionalFieldOf("created_at", 0L).forGetter(Colony::createdAt),
            Codec.LONG.optionalFieldOf("last_tick", 0L).forGetter(Colony::lastTick),
            Codec.DOUBLE.optionalFieldOf("morale", 50.0D).forGetter(Colony::morale),
            Codec.INT.optionalFieldOf("population", 0).forGetter(Colony::population),
            Codec.INT.optionalFieldOf("housing_capacity", 0).forGetter(Colony::housingCapacity),
            STRING_SET_CODEC.optionalFieldOf("research", Set.of()).forGetter(Colony::researchUnlocked),
            Codec.BOOL.optionalFieldOf("life_support_ok", true).forGetter(Colony::lifeSupportOk),
            Codec.INT.optionalFieldOf("food_stock", 0).forGetter(Colony::foodStock),
            UUID_SET_CODEC.optionalFieldOf("outposts", Set.of()).forGetter(Colony::outpostIds)
    ).apply(instance, Colony::new));

    public static final Codec<Colony> CODEC = MAP_CODEC.codec();

    /** Canonical constructor: sanitises the player-supplied name and freezes every collection. */
    public Colony {
        name = sanitiseName(name);
        claimRadius = Math.max(1, claimRadius);
        morale = Math.clamp(morale, MIN_MORALE, MAX_MORALE);
        population = Math.max(0, population);
        housingCapacity = Math.max(0, housingCapacity);
        foodStock = Math.max(0, foodStock);
        accessList = freezeUuids(accessList);
        researchUnlocked = researchUnlocked == null ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(researchUnlocked));
        outpostIds = freezeUuids(outpostIds);
    }

    /** A brand-new colony at a freshly placed beacon. */
    public static Colony found(UUID colonyId, String name, ResourceKey<Level> dimension, BlockPos beaconPos,
            int claimRadius, UUID ownerId, long now) {
        return new Colony(colonyId, name, dimension, beaconPos.immutable(), claimRadius, ownerId,
                Set.of(), now, now, 50.0D, 0, 0, Set.of(), true, 0, Set.of());
    }

    /**
     * Strips control characters and section signs from a player-supplied name, collapses whitespace,
     * caps the length and falls back to {@link #DEFAULT_NAME} if nothing usable is left. A colony
     * name is shown to other players, so it is treated as untrusted input every time it is set.
     */
    public static String sanitiseName(String raw) {
        if (raw == null) {
            return DEFAULT_NAME;
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_NAME_LENGTH));
        boolean lastWasSpace = true;
        for (int i = 0; i < raw.length() && out.length() < MAX_NAME_LENGTH; i++) {
            char c = raw.charAt(i);
            if (c == '§' || Character.isISOControl(c)) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    out.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            out.append(c);
            lastWasSpace = false;
        }
        String cleaned = out.toString().trim();
        return cleaned.isEmpty() ? DEFAULT_NAME : cleaned;
    }

    private static Set<UUID> freezeUuids(Set<UUID> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<UUID> copy = new LinkedHashSet<>();
        for (UUID id : source) {
            if (id != null && copy.size() < MAX_ACCESS_LIST) {
                copy.add(id);
            }
        }
        return Set.copyOf(copy);
    }

    // --- queries (server-side only for anything player-shaped) --------------

    /** Whether this colony still has a player owner. */
    public boolean hasOwner() {
        return !SERVER_OWNER.equals(this.ownerId);
    }

    /** Server-side only: whether this exact player owns the colony. */
    public boolean isOwner(UUID player) {
        return player != null && hasOwner() && this.ownerId.equals(player);
    }

    /** Server-side only: owner or access-list member. Permission level is checked separately. */
    public boolean isMember(UUID player) {
        return isOwner(player) || (player != null && this.accessList.contains(player));
    }

    /** Whether a position falls inside this colony's square claim (same dimension assumed). */
    public boolean contains(BlockPos pos) {
        int dx = Math.abs(pos.getX() - this.beaconPos.getX());
        int dz = Math.abs(pos.getZ() - this.beaconPos.getZ());
        return dx <= this.claimRadius && dz <= this.claimRadius;
    }

    /** Horizontal distance from the beacon, squared — spacing checks never take a square root. */
    public double horizontalDistanceSqr(BlockPos pos) {
        double dx = pos.getX() - this.beaconPos.getX();
        double dz = pos.getZ() - this.beaconPos.getZ();
        return dx * dx + dz * dz;
    }

    // --- copy-with helpers --------------------------------------------------

    public Colony withName(String newName) {
        return new Colony(colonyId, newName, dimension, beaconPos, claimRadius, ownerId, accessList,
                createdAt, lastTick, morale, population, housingCapacity, researchUnlocked,
                lifeSupportOk, foodStock, outpostIds);
    }

    public Colony withOwner(UUID newOwner) {
        return new Colony(colonyId, name, dimension, beaconPos, claimRadius,
                newOwner == null ? SERVER_OWNER : newOwner, accessList, createdAt, lastTick, morale,
                population, housingCapacity, researchUnlocked, lifeSupportOk, foodStock, outpostIds);
    }

    public Colony withClaimRadius(int radius) {
        return new Colony(colonyId, name, dimension, beaconPos, radius, ownerId, accessList,
                createdAt, lastTick, morale, population, housingCapacity, researchUnlocked,
                lifeSupportOk, foodStock, outpostIds);
    }

    public Colony withAccessList(Set<UUID> members) {
        return new Colony(colonyId, name, dimension, beaconPos, claimRadius, ownerId, members,
                createdAt, lastTick, morale, population, housingCapacity, researchUnlocked,
                lifeSupportOk, foodStock, outpostIds);
    }

    /** Adds a member (no-op if already present, the owner, or the list is full). */
    public Colony grantAccess(UUID player) {
        if (player == null || isMember(player) || this.accessList.size() >= MAX_ACCESS_LIST) {
            return this;
        }
        LinkedHashSet<UUID> next = new LinkedHashSet<>(this.accessList);
        next.add(player);
        return withAccessList(next);
    }

    /** Removes a member (no-op if absent). Never touches the owner slot. */
    public Colony revokeAccess(UUID player) {
        if (player == null || !this.accessList.contains(player)) {
            return this;
        }
        LinkedHashSet<UUID> next = new LinkedHashSet<>(this.accessList);
        next.remove(player);
        return withAccessList(next);
    }

    public Colony withLastTick(long tick) {
        return new Colony(colonyId, name, dimension, beaconPos, claimRadius, ownerId, accessList,
                createdAt, tick, morale, population, housingCapacity, researchUnlocked,
                lifeSupportOk, foodStock, outpostIds);
    }

    public Colony withMorale(double value) {
        return new Colony(colonyId, name, dimension, beaconPos, claimRadius, ownerId, accessList,
                createdAt, lastTick, value, population, housingCapacity, researchUnlocked,
                lifeSupportOk, foodStock, outpostIds);
    }

    public Colony withPopulation(int value) {
        return new Colony(colonyId, name, dimension, beaconPos, claimRadius, ownerId, accessList,
                createdAt, lastTick, morale, value, housingCapacity, researchUnlocked,
                lifeSupportOk, foodStock, outpostIds);
    }

    public Colony withHousingCapacity(int value) {
        return new Colony(colonyId, name, dimension, beaconPos, claimRadius, ownerId, accessList,
                createdAt, lastTick, morale, population, value, researchUnlocked,
                lifeSupportOk, foodStock, outpostIds);
    }

    public Colony withResearch(Set<String> nodes) {
        return new Colony(colonyId, name, dimension, beaconPos, claimRadius, ownerId, accessList,
                createdAt, lastTick, morale, population, housingCapacity, nodes,
                lifeSupportOk, foodStock, outpostIds);
    }

    public Colony withLifeSupport(boolean ok) {
        return new Colony(colonyId, name, dimension, beaconPos, claimRadius, ownerId, accessList,
                createdAt, lastTick, morale, population, housingCapacity, researchUnlocked,
                ok, foodStock, outpostIds);
    }

    public Colony withFoodStock(int value) {
        return new Colony(colonyId, name, dimension, beaconPos, claimRadius, ownerId, accessList,
                createdAt, lastTick, morale, population, housingCapacity, researchUnlocked,
                lifeSupportOk, value, outpostIds);
    }

    public Colony withOutposts(Set<UUID> outposts) {
        return new Colony(colonyId, name, dimension, beaconPos, claimRadius, ownerId, accessList,
                createdAt, lastTick, morale, population, housingCapacity, researchUnlocked,
                lifeSupportOk, foodStock, outposts);
    }
}
