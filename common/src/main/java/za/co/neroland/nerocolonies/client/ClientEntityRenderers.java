package za.co.neroland.nerocolonies.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import za.co.neroland.nerocolonies.client.renderer.ColonistModel;
import za.co.neroland.nerocolonies.client.renderer.ColonistRenderer;
import za.co.neroland.nerocolonies.registry.NeroColoniesEntityTypes;

/**
 * The canonical entity-to-renderer table, iterated by all three loader client setups so a new
 * entity renderer is wired once here instead of three times — the same arrangement
 * {@link ScreenBindings} uses for menus.
 *
 * <p>The renderer set is identical on all three loaders, but the registration <em>call</em> is not:
 * NeoForge and Forge each have their own {@code EntityRenderersEvent.RegisterRenderers}, Fabric has
 * {@code EntityRendererRegistry}. So the list lives here once and each loader passes in its own
 * {@link Sink}.
 *
 * <p><b>The model is baked directly</b> via {@code createBodyLayer().bakeRoot()} rather than being
 * registered with a model-layer registry: Fabric's {@code EntityModelLayerRegistry} is not on this
 * de-obfuscated classpath, and a baked-at-construction model needs no registry on any loader. The
 * cost is that a resource-pack reload does not rebuild the geometry, which is irrelevant for
 * programmer art defined in Java.
 *
 * <p><b>CLIENT-ONLY:</b> this class references renderer classes, so it must only ever be loaded from
 * a client entry point.
 */
public final class ClientEntityRenderers {

    /** A loader's renderer-registration entry point. */
    public interface Sink {
        <E extends Entity> void register(EntityType<? extends E> type, EntityRendererProvider<E> provider);
    }

    private ClientEntityRenderers() {
    }

    public static void registerAll(Sink sink) {
        sink.register(NeroColoniesEntityTypes.COLONIST.get(), context ->
                new ColonistRenderer(context, new ColonistModel(ColonistModel.createBodyLayer().bakeRoot())));
    }
}
