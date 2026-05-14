package com.mmm.mixin;

import com.mmm.tweak.SmallDigItemRenderer;

import net.minecraft.client.render.model.json.Transformation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.render.item.ItemRenderState$LayerRenderState")
public class SmallDigItemRenderStateMixin
{
    @Shadow
    Transformation transform;

    @Inject(
            method = "setTransform(Lnet/minecraft/client/render/model/json/Transformation;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void mmm$setSmallDigItemTransform(Transformation transform, CallbackInfo ci)
    {
        Transformation scaledTransform = SmallDigItemRenderer.applyActiveScale(transform);
        if (scaledTransform != transform)
        {
            this.transform = scaledTransform;
            ci.cancel();
        }
    }
}
