package za.co.neroland.nerocolonies.client.renderer;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.entity.ColonistEntity;
import za.co.neroland.nerocolonies.entity.NeranStatus;

/**
 * The Neran's renderer.
 *
 * <p>Three things reach the client about an individual Neran, all through vanilla channels: the tool
 * in its hand (ordinary equipment sync, drawn by the vanilla held-item layer), whether it is a child
 * (vanilla's baby scale), and a one-byte status that is shown as a small symbol in front of its name
 * when a player is close. Everything else a player needs to know belongs to the <em>colony</em> and
 * is shown in the beacon's GUI, which keeps the client dumb and the mod server-authoritative.
 */
public class ColonistRenderer extends MobRenderer<Mob, ArmedEntityRenderState, ColonistModel> {

    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            NeroColoniesCommon.MOD_ID, "textures/entity/colonist.png");

    private static final float SHADOW_RADIUS = 0.5F;

    /** A status symbol is shown within this many blocks (squared), without looking at the Neran. */
    private static final double STATUS_RANGE_SQ = 12.0D * 12.0D;

    @SuppressWarnings("this-escape") // idiomatic Minecraft constructor wiring
    public ColonistRenderer(EntityRendererProvider.Context context, ColonistModel model) {
        super(context, model, SHADOW_RADIUS);
        this.addLayer(new ItemInHandLayer<>(this));
    }

    @Override
    public ArmedEntityRenderState createRenderState() {
        return new ArmedEntityRenderState();
    }

    @Override
    public void extractRenderState(Mob entity, ArmedEntityRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        ArmedEntityRenderState.extractArmedEntityRenderState(entity, state, this.itemModelResolver, partialTicks);
    }

    @Override
    protected boolean shouldShowName(Mob entity, double distanceToCameraSq) {
        if (entity instanceof ColonistEntity neran && neran.status() != NeranStatus.NONE
                && distanceToCameraSq <= STATUS_RANGE_SQ) {
            return true;
        }
        return super.shouldShowName(entity, distanceToCameraSq);
    }

    @Override
    @Nullable
    protected Component getNameTag(Mob entity) {
        Component name = super.getNameTag(entity);
        if (entity instanceof ColonistEntity neran) {
            String symbol = neran.status().symbol();
            if (!symbol.isEmpty()) {
                return name == null ? Component.literal(symbol)
                        : Component.literal(symbol + " ").append(name);
            }
        }
        return name;
    }

    @Override
    public Identifier getTextureLocation(ArmedEntityRenderState state) {
        return TEXTURE;
    }
}
