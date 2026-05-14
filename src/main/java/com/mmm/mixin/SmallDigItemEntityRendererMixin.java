package com.mmm.mixin;

import com.mmm.tweak.SmallDigItemRenderer;

import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemDisplayContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
public class SmallDigItemEntityRendererMixin
{
    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
            at = @At("HEAD")
    )
    private void mmm$beginSmallDigItemEntityUpdate(ItemEntity entity, ItemEntityRenderState state, float tickDelta, CallbackInfo ci)
    {
        SmallDigItemRenderer.begin(entity.getStack(), ItemDisplayContext.GROUND);
    }

    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
            at = @At("RETURN")
    )
    private void mmm$endSmallDigItemEntityUpdate(ItemEntity entity, ItemEntityRenderState state, float tickDelta, CallbackInfo ci)
    {
        SmallDigItemRenderer.end();
    }
}
