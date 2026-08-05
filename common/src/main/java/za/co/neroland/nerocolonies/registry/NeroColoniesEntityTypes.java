package za.co.neroland.nerocolonies.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import za.co.neroland.nerolandcore.entity.EntityRegistrationSupport;
import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * NeroColonies' entity types, and the one piece of mob setup that has no vanilla cross-loader home:
 * default attributes.
 *
 * <p>The type itself rides Core's {@link RegistrationProvider} like every other registry entry;
 * attributes ride Core's {@link EntityRegistrationSupport} seam, which buffers them and lets Core's
 * per-loader plumbing flush them into NeoForge's and Forge's attribute-creation events or Fabric's
 * immediate registration. Downstream code sees one API and needs <b>zero loader wiring</b>.
 *
 * <p><b>Two-phase init, and it matters.</b> {@link #init()} registers the type;
 * {@link #registerEntitySupport()} declares its attributes. They are separate calls because Fabric
 * applies both eagerly, so the attributes must not be declared until the type they name exists.
 * {@code NeroColoniesCommon.init()} sequences them (steps 4 and 7).
 *
 * <p><b>No spawn placement is registered, deliberately.</b> A colonist has no natural spawn: the
 * only way one exists is a colony with housing, food and life support choosing to grow (see
 * {@code colony/Population}). Registering a placement for a mob that never spawns naturally would
 * be a lie in the registry.
 */
public final class NeroColoniesEntityTypes {

    public static final RegistrationProvider<EntityType<?>> ENTITY_TYPES =
            RegistrationProvider.get(Registries.ENTITY_TYPE, NeroColoniesCommon.MOD_ID);

    /**
     * The colonist — player-sized, because it is a person, and in {@link MobCategory#MISC} because
     * it is not part of any natural spawn budget and must never be culled by mob-cap pressure.
     */
    public static final RegistryEntry<EntityType<ColonistEntity>> COLONIST = ENTITY_TYPES.register(
            "colonist",
            key -> EntityType.Builder.of(ColonistEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.95F).eyeHeight(1.62F).clientTrackingRange(10).build(key));

    private NeroColoniesEntityTypes() {
    }

    /** Empty by design — exists so Fabric class-loads this holder from common init. */
    public static void init() {
    }

    /**
     * Declares default attributes through Core's entity seam. Must run <b>after</b> {@link #init()}
     * — on Fabric both are applied the moment they are declared.
     */
    public static void registerEntitySupport() {
        EntityRegistrationSupport.get(NeroColoniesCommon.MOD_ID)
                .registerAttributes(COLONIST, ColonistEntity::createAttributes);
    }
}
