package com.mmm.mixin;

import com.mmm.tweak.SmallDigItemRenderer;

import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.HeldItemContext;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemModelManager.class)
public class SmallDigItemRendererMixin
{
    @Inject(
            method = "update(Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/world/World;Lnet/minecraft/util/HeldItemContext;I)V",
            at = @At("HEAD")
    )
    private void mmm$beginSmallDigItemRender(ItemRenderState state, ItemStack stack, ItemDisplayContext displayContext, World world, HeldItemContext context, int seed, CallbackInfo ci)
    {
        SmallDigItemRenderer.begin(stack, displayContext);
    }

    @Inject(
            method = "update(Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/world/World;Lnet/minecraft/util/HeldItemContext;I)V",
            at = @At("RETURN")
    )
    private void mmm$endSmallDigItemRender(ItemRenderState state, ItemStack stack, ItemDisplayContext displayContext, World world, HeldItemContext context, int seed, CallbackInfo ci)
    {
        SmallDigItemRenderer.end();
    }
}
