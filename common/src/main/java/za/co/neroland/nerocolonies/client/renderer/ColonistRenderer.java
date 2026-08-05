package za.co.neroland.nerocolonies.client.renderer;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/**
 * The colonist's renderer — the whole of NeroColonies' entity rendering.
 *
 * <p>Notably absent: any per-colonist render state. There is nothing to synchronise to the client
 * about an individual colonist, because everything a player needs to know (population, morale,
 * whether work has stopped) belongs to the <em>colony</em> and is shown in the beacon's GUI. Keeping
 * the client this dumb is what lets the mod stay server-authoritative.
 */
public class ColonistRenderer
        extends MobRenderer<Mob, LivingEntityRenderState, EntityModel<LivingEntityRenderState>> {

    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            NeroColoniesCommon.MOD_ID, "textures/entity/colonist.png");

    private static final float SHADOW_RADIUS = 0.5F;

    public ColonistRenderer(EntityRendererProvider.Context context,
            EntityModel<LivingEntityRenderState> model) {
        super(context, model, SHADOW_RADIUS);
    }

    @Override
    public LivingEntityRenderState createRenderState() {
        return new LivingEntityRenderState();
    }

    @Override
    public Identifier getTextureLocation(LivingEntityRenderState state) {
        return TEXTURE;
    }
}
