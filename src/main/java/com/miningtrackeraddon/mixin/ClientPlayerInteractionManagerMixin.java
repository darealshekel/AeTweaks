package com.miningtrackeraddon.mixin;

import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.tweak.FlatDigger;
import com.miningtrackeraddon.tweak.PerimeterWallDigHelper;
import com.miningtrackeraddon.tracker.MiningValidationTracker;
import com.miningtrackeraddon.tracker.MiningStats;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin
{
    @Unique
    private Block miningtrackeraddon$pendingBlock;
    @Unique
    private BlockState miningtrackeraddon$pendingState;

    @Inject(method = "breakBlock(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("HEAD"))
    private void miningtrackeraddon$captureBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        if (!FeatureToggle.TWEAK_MINING_TRACKER.getBooleanValue())
        {
            this.miningtrackeraddon$pendingBlock = null;
            this.miningtrackeraddon$pendingState = null;
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null)
        {
            BlockState state = client.world.getBlockState(pos);
            this.miningtrackeraddon$pendingBlock = state.getBlock();
            this.miningtrackeraddon$pendingState = state;
        }
        else
        {
            this.miningtrackeraddon$pendingBlock = null;
            this.miningtrackeraddon$pendingState = null;
        }
    }

    @Inject(method = "breakBlock(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("RETURN"))
    private void miningtrackeraddon$recordBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        if (FeatureToggle.TWEAK_MINING_TRACKER.getBooleanValue() && Boolean.TRUE.equals(cir.getReturnValue()))
        {
            MiningStats.recordBlockMined(this.miningtrackeraddon$pendingBlock, pos, this.miningtrackeraddon$pendingState);
        }
        this.miningtrackeraddon$pendingBlock = null;
        this.miningtrackeraddon$pendingState = null;
    }

    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void miningtrackeraddon$recordPlacedBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir)
    {
        if (!FeatureToggle.TWEAK_MINING_TRACKER.getBooleanValue() || hitResult == null || cir.getReturnValue() == null || cir.getReturnValue().isAccepted() == false)
        {
            return;
        }

        MiningValidationTracker.onBlockPlaced(hitResult.getBlockPos().offset(hitResult.getSide()), System.currentTimeMillis());
    }

    @Inject(method = "attackBlock(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private void miningtrackeraddon$blockAttackBelowFeet(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir)
    {
        if (FlatDigger.shouldBlock(pos) || PerimeterWallDigHelper.isPositionDisallowed(pos))
        {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Inject(method = "updateBlockBreakingProgress(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private void miningtrackeraddon$blockProgressBelowFeet(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir)
    {
        if (FlatDigger.shouldBlock(pos) || PerimeterWallDigHelper.isPositionDisallowed(pos))
        {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }
}
