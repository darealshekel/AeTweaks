package com.miningtrackeraddon.mixin;

import com.miningtrackeraddon.tweak.BlockEspRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.border.WorldBorder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin
{
    @Shadow @Final private MinecraftClient client;

    @Shadow
    protected abstract void drawBlockOutline(MatrixStack matrices, VertexConsumer vertexConsumer, Entity entity,
                                             double cameraX, double cameraY, double cameraZ,
                                             BlockPos pos, BlockState state, int color);

    @Inject(method = "renderTargetBlockOutline", at = @At("HEAD"), cancellable = true)
    private void mmm$renderCustomBlockEspOutline(Camera camera,
                                                      VertexConsumerProvider.Immediate vertexConsumers,
                                                      MatrixStack matrices,
                                                      boolean translucent,
                                                      CallbackInfo ci)
    {
        if (!BlockEspRenderer.shouldReplaceVanillaOutline(this.client))
        {
            return;
        }

        HitResult hitResult = this.client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || hitResult.getType() == HitResult.Type.MISS)
        {
            return;
        }

        BlockPos pos = blockHitResult.getBlockPos();
        BlockState state = this.client.world.getBlockState(pos);
        if (state.isAir())
        {
            ci.cancel();
            return;
        }

        WorldBorder border = this.client.world.getWorldBorder();
        if (!border.contains(pos))
        {
            ci.cancel();
            return;
        }

        if (RenderLayers.getBlockLayer(state).isTranslucent() != translucent)
        {
            ci.cancel();
            return;
        }

        Vec3d cameraPos = camera.getPos();
        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(RenderLayer.getLines());
        this.drawBlockOutline(
                matrices,
                vertexConsumer,
                camera.getFocusedEntity(),
                cameraPos.x,
                cameraPos.y,
                cameraPos.z,
                pos,
                state,
                BlockEspRenderer.getCurrentOutlineColor(this.client)
        );
        ci.cancel();
    }
}
