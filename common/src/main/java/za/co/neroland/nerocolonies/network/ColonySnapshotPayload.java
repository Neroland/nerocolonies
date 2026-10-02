package za.co.neroland.nerocolonies.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerolandcore.economy.CurrencyApi;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyLife;
import za.co.neroland.nerocolonies.colony.ColonyNeeds;
import za.co.neroland.nerocolonies.colony.ColonyPermissions;
import za.co.neroland.nerocolonies.colony.ColonyProgress;
import za.co.neroland.nerocolonies.colony.ColonyRoles;
import za.co.neroland.nerocolonies.colony.ColonyStage;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.ColonyStores;
import za.co.neroland.nerocolonies.colony.Construction;
import za.co.neroland.nerocolonies.colony.ExportBuffer;
import za.co.neroland.nerocolonies.colony.GratitudeCache;
import za.co.neroland.nerocolonies.colony.Growth;
import za.co.neroland.nerocolonies.colony.JobBoard;
import za.co.neroland.nerocolonies.colony.LifeSupport;
import za.co.neroland.nerocolonies.colony.Population;
import za.co.neroland.nerocolonies.colony.Research;
import za.co.neroland.nerocolonies.colony.ResearchEffects;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * One player's view of the one colony they currently have open.
 *
 * <h2>What is in it</h2>
 *
 * <p>Colony <b>state</b>: name, morale, population, the research it has unlocked, its job slots, how
 * full its stores are, what its export buffer is worth — and, in {@link Life}, its growth stage, what
 * it needs, how long its current building will take, and how many Allies, Chiefs and Enemies it has.
 * That is what a GUI legitimately draws. {@link #isOwner} and {@link Life#role} are facts about the
 * <em>recipient</em>. The whole payload is built for one player and sent to that player.
 *
 * <h2>Player names: one narrow case, and only that one (POPIA/GDPR)</h2>
 *
 * <p><b>No UUID of any player is ever in this payload</b> — not the owner's, not a member's.
 *
 * <p>For almost every recipient there are no names in it either: {@link Life#members} is empty, and
 * membership is three counts. The exception is a viewer whose role lets them <b>manage this colony's
 * members</b> (its owner, a Chief, or a server operator). Somebody who is asked to decide who is an
 * Ally, a Chief or an Enemy has to be able to see who already is, so for that viewer — and nobody
 * else — the server resolves the colony's Chiefs, Allies and Enemies to their current player names
 * and includes them, each with a role.
 *
 * <ul>
 *   <li>The names are resolved <b>on the server, at the moment the snapshot is built</b>: an online
 *       player's from the player list, anybody else's from the server's own profile cache, and
 *       {@code ?} when the server does not know. The lookup is by an id the colony already holds;
 *       it is never driven by text a client sent.</li>
 *   <li>They are <b>never stored and never logged</b> by this mod. The colony keeps ids; a name
 *       exists for as long as it takes to write this packet.</li>
 *   <li>They go to that one viewer, are shown on the beacon's Roles tab, and are replaced by the next
 *       snapshot. A player who stops being a member is sent the empty snapshot, which clears them.</li>
 *   <li>The roster is capped at {@value #MAX_MEMBERS} entries and each name at
 *       {@value #MAX_MEMBER_NAME_CHARS} characters, on write and on read.</li>
 * </ul>
 *
 * <p>An Ally sees counts and their own role, and that is all. A stranger is not sent a snapshot.
 *
 * <p>{@link #affordable()} is the same data-minimisation principle applied to inventory: the server
 * decides which research nodes the colony can pay for and sends a list of ids, so the screen can grey
 * out what is unaffordable without ever being sent the colony's stock.
 */
public record ColonySnapshotPayload(
        boolean present,
        BlockPos anchor,
        String colonyId,
        String name,
        int morale,
        int population,
        int housingCapacity,
        int foodStock,
        int lifeSupportState,
        int claimRadius,
        int jobSlots,
        int jobsActive,
        int jobStations,
        int storageUsed,
        int storageSlots,
        int exportFilled,
        int exportSlots,
        long exportValue,
        int outpostCount,
        int accessCount,
        boolean isOwner,
        boolean marketAvailable,
        String buildName,
        int buildPercent,
        int structuresBuilt,
        List<String> researchUnlocked,
        List<String> affordable,
        Life life) implements CustomPacketPayload {

    private static final int MAX_IDS = 2_048;
    private static final int MAX_ID_CHARS = 256;
    private static final int MAX_NAME_CHARS = 64;

    /** How many need lines one snapshot carries. The screen shows fewer at a time. */
    public static final int MAX_NEEDS = 8;

    /** How many roster entries one snapshot carries, for the one kind of viewer who gets any. */
    public static final int MAX_MEMBERS = 64;

    /** Cap on one roster name. A Minecraft name is 16 characters; this is slack, not an invitation. */
    public static final int MAX_MEMBER_NAME_CHARS = 32;

    /** What the roster shows for somebody the server cannot put a name to. */
    public static final String UNKNOWN_NAME = "?";

    /**
     * One line of the needs list.
     *
     * @param label          an item id, or {@code #tag}; what a prioritise intent sends back
     * @param nameKey        translation key for display
     * @param etaSoloMinutes minutes until the colony gathers it alone, or -1 for "not without help"
     */
    public record NeedLine(String label, String nameKey, int have, int needed, int etaSoloMinutes,
            boolean priority) {

        public NeedLine {
            label = bounded(label, MAX_ID_CHARS);
            nameKey = bounded(nameKey, MAX_ID_CHARS);
        }
    }

    /**
     * One roster entry: a display name and a {@link ColonyPermissions.Role} ordinal. Never a UUID.
     * Only ever built for a viewer who may manage the colony's members; see the class notes.
     */
    public record Member(String name, int role) {

        public Member {
            name = bounded(name, MAX_MEMBER_NAME_CHARS);
        }
    }

    /**
     * The living-colony half of the snapshot.
     *
     * @param stage           {@link ColonyStage} ordinal
     * @param role            the <em>viewer's</em> effective {@link ColonyPermissions.Role} ordinal
     * @param nextPopulation  population the next stage asks for, or 0 when there is no such milestone
     * @param nextStructures  structures the next stage asks for, or 0
     * @param children        child Nerans currently in the colony
     * @param etaSoloMinutes  minutes until the current building is finished unaided; -1 = not alone
     * @param etaHelpMinutes  the same, if its materials were supplied now
     * @param allies          members who are not Chiefs — a count
     * @param chiefs          a count
     * @param enemies         a count
     * @param needs           at most {@value ColonySnapshotPayload#MAX_NEEDS} lines, most urgent first
     * @param members         empty unless the viewer may manage members; see the class notes
     */
    public record Life(int stage, int role, int nextPopulation, int nextStructures, int children,
            int etaSoloMinutes, int etaHelpMinutes, int allies, int chiefs, int enemies,
            boolean cacheShared, int cacheFilled, int cacheSlots, int stuckEvents,
            List<NeedLine> needs, List<Member> members) {

        /** No colony, or nothing known about one. */
        public static final Life NONE = new Life(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, 0,
                List.of(), List.of());

        public Life {
            needs = needs.size() > MAX_NEEDS ? List.copyOf(needs.subList(0, MAX_NEEDS)) : List.copyOf(needs);
            members = members.size() > MAX_MEMBERS
                    ? List.copyOf(members.subList(0, MAX_MEMBERS)) : List.copyOf(members);
        }
    }

    /** "You have no colony open" — sent when a GUI is opened on an unbound or dissolved beacon. */
    public static final ColonySnapshotPayload EMPTY = new ColonySnapshotPayload(
            false, BlockPos.ZERO, "", "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0, 0, false, false,
            "", 0, 0, List.of(), List.of(), Life.NONE);

    public static final Type<ColonySnapshotPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, "colony_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonySnapshotPayload> STREAM_CODEC =
            StreamCodec.of(ColonySnapshotPayload::write, ColonySnapshotPayload::read);

    public ColonySnapshotPayload {
        anchor = anchor.immutable();
        researchUnlocked = List.copyOf(researchUnlocked);
        affordable = List.copyOf(affordable);
        life = life == null ? Life.NONE : life;
    }

    /**
     * Builds {@code viewer}'s view of {@code colony}. Server-side only.
     *
     * <p>{@code anchor} is the block the player opened, and it is in the payload for one reason: a
     * screen has to tell the server <em>which</em> block its intents are about, and a menu's data
     * slots are 16-bit — a block position does not fit through one. Carrying it here is both exact
     * and free.
     */
    public static ColonySnapshotPayload of(MinecraftServer server, ServerPlayer viewer, Colony colony,
            BlockPos anchor) {
        List<String> unlocked = new ArrayList<>(colony.researchUnlocked());
        List<String> affordable = new ArrayList<>();
        for (Identifier id : Research.affordable(server, colony)) {
            affordable.add(id.toString());
        }
        int storageSlots = ColonyStorage.usableSlots(viewer.level(), colony);
        int storageUsed = 0;
        for (int slot = 0; slot < storageSlots; slot++) {
            if (!ColonyStores.get(server).store(colony.colonyId()).storage().get(slot).isEmpty()) {
                storageUsed++;
            }
        }
        return new ColonySnapshotPayload(
                true,
                anchor,
                colony.colonyId().toString(),
                colony.name(),
                (int) Math.round(colony.morale()),
                colony.population(),
                colony.housingCapacity(),
                colony.foodStock(),
                LifeSupport.stateOf(colony).ordinal(),
                colony.claimRadius(),
                ResearchEffects.jobSlots(colony),
                JobBoard.activeCount(colony.colonyId()),
                JobBoard.stationCount(colony.colonyId()),
                storageUsed,
                storageSlots,
                ExportBuffer.filledSlots(server, colony.colonyId()),
                ExportBuffer.usableSlots(),
                ExportBuffer.previewValue(server, colony),
                colony.outpostIds().size(),
                colony.accessList().size(),
                colony.isOwner(viewer.getUUID()),
                CurrencyApi.hasRealProvider(),
                // A translation key, not a rendered name: what the client shows is the client's
                // business, and it keeps the payload the same size in every language.
                Construction.activeNameKey(server, colony.colonyId()),
                Construction.progressPercent(server, colony.colonyId()),
                Construction.structuresBuilt(server, colony.colonyId()),
                unlocked,
                affordable,
                lifeOf(server, viewer, colony));
    }

    // --- the living-colony half -------------------------------------------------

    private static Life lifeOf(MinecraftServer server, ServerPlayer viewer, Colony colony) {
        UUID id = colony.colonyId();
        ColonyStage stage = ColonyProgress.stage(server, colony);
        int[] milestone = Growth.nextMilestone(stage);
        ColonyPermissions.Role role = ColonyPermissions.effectiveRole(viewer, colony);

        int children = 0;
        List<NeedLine> needs = new ArrayList<>();
        ColonyNeeds.BuildEta eta = ColonyNeeds.BuildEta.NONE;
        ServerLevel level = server.getLevel(colony.dimension());
        if (level != null) {
            for (ColonistEntity neran : Population.colonistsOf(level, colony)) {
                if (neran.isChildNeran()) {
                    children++;
                }
            }
            List<ColonyNeeds.Need> derived = ColonyNeeds.derive(level, colony);
            eta = ColonyNeeds.buildEta(level, colony, derived);
            for (ColonyNeeds.Need need : derived) {
                if (needs.size() >= MAX_NEEDS) {
                    break;
                }
                needs.add(new NeedLine(need.label(), need.nameKey(), need.have(), need.needed(),
                        need.etaSoloMinutes(), need.priority()));
            }
        }

        // The three lists, as ids, for exactly as long as it takes to count them (and, for a viewer
        // who may manage members, to put a name to each). The ids themselves never leave this method.
        ColonyRoles roles = ColonyRoles.get(server);
        Set<UUID> chiefIds = roles.chiefs(id);
        Set<UUID> enemyIds = roles.enemies(id);
        List<UUID> chiefs = new ArrayList<>();
        List<UUID> allies = new ArrayList<>();
        for (UUID member : colony.accessList()) {
            if (enemyIds.contains(member)) {
                continue;
            }
            (chiefIds.contains(member) ? chiefs : allies).add(member);
        }
        List<Member> members = List.of();
        if (ColonyPermissions.allows(role, ColonyPermissions.Action.MANAGE_MEMBERS, false)) {
            List<Member> roster = new ArrayList<>();
            addNamed(server, roster, chiefs, ColonyPermissions.Role.CHIEF);
            addNamed(server, roster, allies, ColonyPermissions.Role.ALLY);
            addNamed(server, roster, enemyIds, ColonyPermissions.Role.ENEMY);
            members = roster.size() > MAX_MEMBERS ? roster.subList(0, MAX_MEMBERS) : roster;
        }

        ColonyLife.Life life = ColonyLife.get(server).life(id);
        return new Life(
                stage.ordinal(),
                role.ordinal(),
                milestone == null ? 0 : milestone[0],
                milestone == null ? 0 : milestone[1],
                children,
                eta.soloMinutes(),
                eta.helpMinutes(),
                allies.size(),
                chiefs.size(),
                enemyIds.size(),
                life.cacheShared(),
                GratitudeCache.filled(life),
                ColonyLife.CACHE_SLOTS,
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, life.stuckEvents())),
                needs,
                members);
    }

    /** Appends one role's members to the roster, by name, in name order. */
    private static void addNamed(MinecraftServer server, List<Member> roster, Iterable<UUID> ids,
            ColonyPermissions.Role role) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            names.add(displayName(server, id));
        }
        // Known names first, alphabetically; the ones nobody can name sink to the end of their role.
        names.sort(Comparator.comparing((String name) -> UNKNOWN_NAME.equals(name))
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        for (String name : names) {
            roster.add(new Member(name, role.ordinal()));
        }
    }

    /**
     * A current name for a player id the colony already holds: from the player list when they are
     * online, else from the server's profile cache (a local lookup, never a network one), else
     * {@link #UNKNOWN_NAME}. Called only while building a member-manager's snapshot; the result is
     * written to that one packet and kept nowhere.
     */
    private static String displayName(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return cleanName(online.getName().getString());
        }
        return server.services().nameToIdCache().get(id)
                .map(NameAndId::name)
                .map(ColonySnapshotPayload::cleanName)
                .orElse(UNKNOWN_NAME);
    }

    /** Drops anything that is not printable, and caps the length. A name is drawn, never trusted. */
    private static String cleanName(String raw) {
        if (raw == null) {
            return UNKNOWN_NAME;
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_MEMBER_NAME_CHARS));
        for (int i = 0; i < raw.length() && out.length() < MAX_MEMBER_NAME_CHARS; i++) {
            char c = raw.charAt(i);
            if (c != '§' && !Character.isISOControl(c) && !Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.isEmpty() ? UNKNOWN_NAME : out.toString();
    }

    private static String bounded(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    // --- wire -------------------------------------------------------------------

    private static void write(RegistryFriendlyByteBuf buf, ColonySnapshotPayload payload) {
        buf.writeBoolean(payload.present);
        if (!payload.present) {
            return;
        }
        buf.writeBlockPos(payload.anchor);
        buf.writeUtf(payload.colonyId, MAX_ID_CHARS);
        buf.writeUtf(payload.name, MAX_NAME_CHARS);
        buf.writeVarInt(payload.morale);
        buf.writeVarInt(payload.population);
        buf.writeVarInt(payload.housingCapacity);
        buf.writeVarInt(payload.foodStock);
        buf.writeVarInt(payload.lifeSupportState);
        buf.writeVarInt(payload.claimRadius);
        buf.writeVarInt(payload.jobSlots);
        buf.writeVarInt(payload.jobsActive);
        buf.writeVarInt(payload.jobStations);
        buf.writeVarInt(payload.storageUsed);
        buf.writeVarInt(payload.storageSlots);
        buf.writeVarInt(payload.exportFilled);
        buf.writeVarInt(payload.exportSlots);
        buf.writeVarLong(payload.exportValue);
        buf.writeVarInt(payload.outpostCount);
        buf.writeVarInt(payload.accessCount);
        buf.writeBoolean(payload.isOwner);
        buf.writeBoolean(payload.marketAvailable);
        buf.writeUtf(payload.buildName, MAX_ID_CHARS);
        buf.writeVarInt(payload.buildPercent);
        buf.writeVarInt(payload.structuresBuilt);
        writeIds(buf, payload.researchUnlocked);
        writeIds(buf, payload.affordable);
        writeLife(buf, payload.life);
    }

    private static ColonySnapshotPayload read(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return EMPTY;
        }
        return new ColonySnapshotPayload(
                true,
                buf.readBlockPos(),
                buf.readUtf(MAX_ID_CHARS),
                buf.readUtf(MAX_NAME_CHARS),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarLong(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readUtf(MAX_ID_CHARS),
                buf.readVarInt(),
                buf.readVarInt(),
                readIds(buf),
                readIds(buf),
                readLife(buf));
    }

    private static void writeLife(RegistryFriendlyByteBuf buf, Life life) {
        buf.writeVarInt(life.stage());
        buf.writeVarInt(life.role());
        buf.writeVarInt(life.nextPopulation());
        buf.writeVarInt(life.nextStructures());
        buf.writeVarInt(life.children());
        buf.writeVarInt(life.etaSoloMinutes());
        buf.writeVarInt(life.etaHelpMinutes());
        buf.writeVarInt(life.allies());
        buf.writeVarInt(life.chiefs());
        buf.writeVarInt(life.enemies());
        buf.writeBoolean(life.cacheShared());
        buf.writeVarInt(life.cacheFilled());
        buf.writeVarInt(life.cacheSlots());
        buf.writeVarInt(life.stuckEvents());

        int needs = Math.min(life.needs().size(), MAX_NEEDS);
        buf.writeVarInt(needs);
        for (int i = 0; i < needs; i++) {
            NeedLine line = life.needs().get(i);
            buf.writeUtf(line.label(), MAX_ID_CHARS);
            buf.writeUtf(line.nameKey(), MAX_ID_CHARS);
            buf.writeVarInt(line.have());
            buf.writeVarInt(line.needed());
            buf.writeVarInt(line.etaSoloMinutes());
            buf.writeBoolean(line.priority());
        }

        int members = Math.min(life.members().size(), MAX_MEMBERS);
        buf.writeVarInt(members);
        for (int i = 0; i < members; i++) {
            Member member = life.members().get(i);
            buf.writeUtf(member.name(), MAX_MEMBER_NAME_CHARS);
            buf.writeVarInt(member.role());
        }
    }

    private static Life readLife(RegistryFriendlyByteBuf buf) {
        int stage = buf.readVarInt();
        int role = buf.readVarInt();
        int nextPopulation = buf.readVarInt();
        int nextStructures = buf.readVarInt();
        int children = buf.readVarInt();
        int etaSolo = buf.readVarInt();
        int etaHelp = buf.readVarInt();
        int allies = buf.readVarInt();
        int chiefs = buf.readVarInt();
        int enemies = buf.readVarInt();
        boolean cacheShared = buf.readBoolean();
        int cacheFilled = buf.readVarInt();
        int cacheSlots = buf.readVarInt();
        int stuckEvents = buf.readVarInt();

        int needCount = Math.min(buf.readVarInt(), MAX_NEEDS);
        List<NeedLine> needs = new ArrayList<>(Math.max(0, needCount));
        for (int i = 0; i < needCount; i++) {
            needs.add(new NeedLine(buf.readUtf(MAX_ID_CHARS), buf.readUtf(MAX_ID_CHARS),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        }

        int memberCount = Math.min(buf.readVarInt(), MAX_MEMBERS);
        List<Member> members = new ArrayList<>(Math.max(0, memberCount));
        for (int i = 0; i < memberCount; i++) {
            members.add(new Member(buf.readUtf(MAX_MEMBER_NAME_CHARS), buf.readVarInt()));
        }
        return new Life(stage, role, nextPopulation, nextStructures, children, etaSolo, etaHelp, allies,
                chiefs, enemies, cacheShared, cacheFilled, cacheSlots, stuckEvents, needs, members);
    }

    private static void writeIds(RegistryFriendlyByteBuf buf, List<String> ids) {
        int count = Math.min(ids.size(), MAX_IDS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeUtf(ids.get(i), MAX_ID_CHARS);
        }
    }

    private static List<String> readIds(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_IDS);
        List<String> out = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            out.add(buf.readUtf(MAX_ID_CHARS));
        }
        return List.copyOf(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
