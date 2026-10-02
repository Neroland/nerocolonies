package za.co.neroland.nerocolonies.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/**
 * The Neran's model: a suited biped in cube geometry.
 *
 * <p>Programmer art, and one model for every Neran. What tells trades apart is the tool in the hand
 * (drawn by the vanilla held-item layer through {@link ArmedModel}) and the status symbol by the
 * name, not a different body.
 *
 * <p>Animation is the vanilla walk cycle plus a tool swing: arms and legs swing in opposition from
 * {@code walkAnimationPos}, the head tracks {@code yRot}/{@code xRot}, and the working arm chops down
 * with the swing animation when the server swings the Neran's hand.
 */
public class ColonistModel extends EntityModel<ArmedEntityRenderState> implements ArmedModel<ArmedEntityRenderState> {

    private static final float LIMB_SWING = 1.0F;
    private static final float TOOL_SWING = 1.6F;

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
        // The suit backpack: the one silhouette cue that says "this is a Neran, not a villager".
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
    public void setupAnim(ArmedEntityRenderState state) {
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

        // 26.3 replaced attackTime/attackArm with a swing description and its animation progress.
        //? if >=26.3 {
        /*if (state.swingAnimation > 0.0F && state.currentSwing != null) {
            float chop = Mth.sin(state.swingAnimation * Mth.PI) * TOOL_SWING;
            arm(state.currentSwing.hand().asArm(state.mainArm)).xRot -= chop;
        }
        *///?} else {
        if (state.attackTime > 0.0F) {
            float chop = Mth.sin(state.attackTime * Mth.PI) * TOOL_SWING;
            arm(state.attackArm).xRot -= chop;
        }
        //?}
    }

    private ModelPart arm(HumanoidArm arm) {
        return arm == HumanoidArm.LEFT ? this.leftArm : this.rightArm;
    }

    @Override
    public void translateToHand(ArmedEntityRenderState state, HumanoidArm arm, PoseStack poseStack) {
        this.root().translateAndRotate(poseStack);
        arm(arm).translateAndRotate(poseStack);
    }
}
