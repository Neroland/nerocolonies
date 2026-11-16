package za.co.neroland.nerocolonies.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyPermissions.Role;
import za.co.neroland.nerocolonies.colony.Outpost;
import za.co.neroland.nerolandcore.map.MapArea;
import za.co.neroland.nerolandcore.map.MapAreaStyle;
import za.co.neroland.nerolandcore.map.MapBounds;
import za.co.neroland.nerolandcore.map.MapLabel;
import za.co.neroland.nerolandcore.map.MapLayerInfo;
import za.co.neroland.nerolandcore.map.MapLayerProvider;
import za.co.neroland.nerolandcore.map.MapLayerRegistry;
import za.co.neroland.nerolandcore.map.MapLayerSink;
import za.co.neroland.nerolandcore.map.MapMarker;
import za.co.neroland.nerolandcore.map.MapScope;
import za.co.neroland.nerolandcore.map.MapViewer;

/**
 * NeroColonies' map layers, offered to any map mod through Neroland Core's map registry. NeroColonies depends on
 * no map mod; with none installed this provider is never queried.
 *
 * <p>Three {@link MapScope#MEMBERS} layers, all scoped by {@link ColonyMapAccess} (the link module's rule): the
 * viewer's own colonies and the ones they are an Ally or a Chief of.
 * <ul>
 *   <li>{@code colony_border}: the square claim as an area, labelled with the colony's name.</li>
 *   <li>{@code colony_buildings}: finished buildings, plus Storage Crates and Colony Depots for the owner and
 *       Chiefs only (Allies never see where the goods are). World map by default.</li>
 *   <li>{@code outposts}: outpost beacons and their claims.</li>
 * </ul>
 * No id, label or detail carries a player's name or id: ids are built from colony and outpost ids and block
 * positions, labels from the colony's own name and lang keys.
 */
public final class ColonyMapLayers implements MapLayerProvider {

    private static final Logger LOG = LoggerFactory.getLogger("NeroColonies/Map");

    public static final Identifier BORDER = id("colony_border");
    public static final Identifier BUILDINGS = id("colony_buildings");
    public static final Identifier OUTPOSTS = id("outposts");

    static final Identifier ICON_BORDER = id("colony_border");
    static final Identifier ICON_BUILDING = id("colony_building");
    static final Identifier ICON_STORAGE = id("colony_storage");
    static final Identifier ICON_OUTPOST = id("outpost");

    private static final int SKY = 0xFF56B4E9;
    private static final int SKY_FILL = 0x3356B4E9;
    private static final int SKY_FILL_FAINT = 0x2256B4E9;

    private static final String KEY_PATTERN = "[a-z0-9_.-]{1,128}";

    private static final List<MapLayerInfo> LAYERS = List.of(
            MapLayerInfo.of(BORDER, "map.layer.nerocolonies.colony_border", ICON_BORDER, MapScope.MEMBERS),
            MapLayerInfo.of(BUILDINGS, "map.layer.nerocolonies.colony_buildings", ICON_BUILDING, MapScope.MEMBERS)
                    .withDefaults(true, false),
            MapLayerInfo.of(OUTPOSTS, "map.layer.nerocolonies.outposts", ICON_OUTPOST, MapScope.MEMBERS));

    private final Function<MinecraftServer, ColonyMapSource> sources;

    ColonyMapLayers(Function<MinecraftServer, ColonyMapSource> sources) {
        this.sources = sources;
    }

    /** Registers the provider with Core's map registry. Never throws: a map failure must not stop the mod. */
    public static void register() {
        try {
            MapLayerRegistry.register(new ColonyMapLayers(ColonyMapSource::of));
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("NeroColonies map layers not registered ({})", e.getClass().getSimpleName());
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NeroColoniesCommon.MOD_ID, path);
    }

    @Override
    public String modId() {
        return NeroColoniesCommon.MOD_ID;
    }

    @Override
    public List<MapLayerInfo> layers() {
        return LAYERS;
    }

    @Override
    public void query(MapViewer viewer, ResourceKey<Level> dimension, MapBounds bounds, MapLayerSink sink) {
        if (viewer == null || dimension == null || bounds == null || sink == null) {
            return;
        }
        ColonyMapSource source = sources.apply(viewer.server());
        if (source == null) {
            return;
        }
        List<Colony> colonies = new ArrayList<>();
        for (Colony colony : ColonyMapAccess.visible(source.memberColonies(viewer.id()), source, source::sandbox,
                viewer.id())) {
            if (dimension.equals(colony.dimension())) {
                colonies.add(colony);
            }
        }
        if (colonies.isEmpty()) {
            return;
        }
        BlockPos from = viewer.position().orElse(new BlockPos(bounds.centreX(), 64, bounds.centreZ()));
        colonies.sort(Comparator.comparingDouble(c -> c.horizontalDistanceSqr(from)));

        for (Colony colony : colonies) {
            if (sink.isFull(BORDER)) {
                break;
            }
            border(colony, sink);
        }
        for (Colony colony : colonies) {
            if (sink.isFull(BUILDINGS)) {
                break;
            }
            buildings(colony, ColonyMapAccess.roleOf(colony, source, viewer.id()), source, from, sink);
        }
        Set<UUID> parents = new HashSet<>();
        colonies.forEach(c -> parents.add(c.colonyId()));
        List<Outpost> outposts = new ArrayList<>();
        for (Outpost outpost : source.outposts()) {
            if (parents.contains(outpost.parentColonyId()) && dimension.equals(outpost.dimension())) {
                outposts.add(outpost);
            }
        }
        outposts.sort(Comparator.comparingDouble(o -> o.horizontalDistanceSqr(from)));
        for (Outpost outpost : outposts) {
            if (sink.isFull(OUTPOSTS)) {
                break;
            }
            String key = outpost.outpostId().toString();
            BlockPos p = outpost.pos();
            int r = outpost.claimRadius();
            sink.marker(MapMarker.builder("outpost/" + key, OUTPOSTS, outpost.dimension(), p)
                    .label(MapLabel.translatable("map.nerocolonies.outpost")).build());
            sink.area(new MapArea.Rect("outpost_claim/" + key, OUTPOSTS, outpost.dimension(),
                    MapAreaStyle.of(SKY_FILL_FAINT, SKY), p.getX() - r, p.getZ() - r, p.getX() + r, p.getZ() + r));
        }
    }

    private static void border(Colony colony, MapLayerSink sink) {
        BlockPos b = colony.beaconPos();
        int r = Math.min(colony.claimRadius(), MapBounds.MAX_SPAN / 2 - 1);
        MapAreaStyle style = MapAreaStyle.of(SKY_FILL, SKY).withLabel(MapLabel.literal(colony.name()));
        sink.area(new MapArea.Rect("colony/" + colony.colonyId(), BORDER, colony.dimension(), style,
                b.getX() - r, b.getZ() - r, b.getX() + r, b.getZ() + r));
    }

    private static void buildings(Colony colony, Role role, ColonyMapSource source, BlockPos from,
            MapLayerSink sink) {
        List<ColonyMapSource.Building> buildings = new ArrayList<>(source.buildings(colony));
        buildings.sort(Comparator.comparingDouble(bd -> bd.centre().distSqr(from)));
        for (ColonyMapSource.Building building : buildings) {
            if (sink.isFull(BUILDINGS)) {
                return;
            }
            BlockPos c = building.centre();
            sink.marker(MapMarker.builder("building/" + pos(c), BUILDINGS, colony.dimension(), c)
                    .label(MapLabel.translatable(nameKey(building.nameKey()))).build());
        }
        if (!ColonyMapAccess.seesStorage(role)) {
            return;
        }
        for (ColonyMapSource.Storage box : source.storage(colony.colonyId())) {
            if (sink.isFull(BUILDINGS)) {
                return;
            }
            if (!colony.dimension().equals(box.dimension())) {
                continue;
            }
            sink.marker(MapMarker.builder("storage/" + pos(box.pos()), BUILDINGS, box.dimension(), box.pos())
                    .icon(ICON_STORAGE)
                    .label(MapLabel.translatable(box.crate() ? "map.nerocolonies.storage_crate"
                            : "map.nerocolonies.depot"))
                    .build());
        }
    }

    private static String pos(BlockPos p) {
        return p.getX() + "_" + p.getY() + "_" + p.getZ();
    }

    /** The blueprint's display-name key if it is a valid lang key, else a generic one (a pack may name it oddly). */
    static String nameKey(String key) {
        return key != null && key.matches(KEY_PATTERN) ? key : "map.nerocolonies.building";
    }
}
