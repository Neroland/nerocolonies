package za.co.neroland.nerocolonies.client.renderer;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;

/**
 * The colonist's model: a suited biped in cube geometry.
 *
 * <p>Programmer art, and deliberately <b>one</b> model for every colonist. There is no variant, no
 * profession overlay and no per-colonist appearance, because there is no per-colonist identity to
 * express — a colonist is an interchangeable labour unit, and a model that suggested otherwise would
 * be the first step down a road this mod is not taking.
 *
 * <p>Animation is the vanilla walk cycle and nothing else: arms and legs swing in opposition from
 * {@code walkAnimationPos}, the head tracks {@code yRot}/{@code xRot}. The real art pass will
 * replace all of it.
 */
public class ColonistModel extends EntityModel<LivingEntityRenderState> {

    private static final float LIMB_SWING = 1.0F;

    private final ModelPart head;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;

    @SuppressWarnings("this-escape") // idiomatic Minecraft constructor wiring
    public ColonistModel(ModelPart root) {
        super(root);
        this.head = root.getChild("head");
        this.leftArm = root.getChild("left_arm");
        this.rightArm = root.getChild("right_arm");
        this.leftLeg = root.getChild("left_leg");
        this.rightLeg = root.getChild("right_leg");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("head",
                CubeListBuilder.create().texOffs(0, 0).addBox(-4F, -8F, -4F, 8F, 8F, 8F),
                PartPose.offset(0F, 0F, 0F));
        root.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(16, 16).addBox(-4F, 0F, -2F, 8F, 12F, 4F),
                PartPose.offset(0F, 0F, 0F));
        // The suit backpack — the one silhouette cue that says "this is a colonist, not a villager".
        root.addOrReplaceChild("pack",
                CubeListBuilder.create().texOffs(0, 32).addBox(-3F, 1F, 2F, 6F, 8F, 3F),
                PartPose.offset(0F, 0F, 0F));
        root.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(40, 16).addBox(-3F, -2F, -2F, 4F, 12F, 4F),
                PartPose.offset(-5F, 2F, 0F));
        root.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(40, 16).addBox(-1F, -2F, -2F, 4F, 12F, 4F),
                PartPose.offset(5F, 2F, 0F));
        root.addOrReplaceChild("right_leg",
                CubeListBuilder.create().texOffs(0, 16).addBox(-2F, 0F, -2F, 4F, 12F, 4F),
                PartPose.offset(-2F, 12F, 0F));
        root.addOrReplaceChild("left_leg",
                CubeListBuilder.create().texOffs(0, 16).addBox(-2F, 0F, -2F, 4F, 12F, 4F),
                PartPose.offset(2F, 12F, 0F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(LivingEntityRenderState state) {
        super.setupAnim(state);
        this.head.yRot = state.yRot * Mth.DEG_TO_RAD;
        this.head.xRot = state.xRot * Mth.DEG_TO_RAD;

        float walkPos = state.walkAnimationPos;
        float walkSpeed = Math.min(1.0F, state.walkAnimationSpeed);
        float swing = Mth.cos(walkPos * 0.6662F) * LIMB_SWING * walkSpeed;
        this.rightLeg.xRot = swing;
        this.leftLeg.xRot = -swing;
        this.rightArm.xRot = -swing * 0.8F;
        this.leftArm.xRot = swing * 0.8F;
    }
}
