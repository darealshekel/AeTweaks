package com.mmm.mixin;

import com.mmm.config.FeatureToggle;
import com.mmm.tweak.FlatDigger;
import com.mmm.tweak.PerimeterWallDigHelper;
import com.mmm.tracker.MiningValidationTracker;
import com.mmm.tracker.MiningStats;
import com.mmm.util.BlockBreakdownCatalog;

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
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin
{
    @Unique
    private Block mmm$pendingBlock;
    @Unique
    private BlockState mmm$pendingState;
    @Unique
    private boolean mmm$pendingValidMiningBreak;

    @Inject(method = "breakBlock(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("HEAD"))
    private void mmm$captureBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        this.mmm$clearPendingMiningBreak();
        if (!FeatureToggle.TWEAK_MINING_TRACKER.getBooleanValue())
        {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.interactionManager == null)
        {
            return;
        }

        BlockState state = client.world.getBlockState(pos);
        Block block = state.getBlock();
        if (BlockBreakdownCatalog.isValid(block) == false)
        {
            return;
        }

        if (mmm$isSurvivalMiningMode(client) == false)
        {
            return;
        }

        this.mmm$pendingBlock = block;
        this.mmm$pendingState = state;
        this.mmm$pendingValidMiningBreak = true;
    }

    @Inject(method = "breakBlock(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("RETURN"))
    private void mmm$recordBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        if (this.mmm$pendingValidMiningBreak && FeatureToggle.TWEAK_MINING_TRACKER.getBooleanValue() && Boolean.TRUE.equals(cir.getReturnValue()))
        {
            MiningStats.recordBlockMined(this.mmm$pendingBlock, pos, this.mmm$pendingState);
        }
        this.mmm$clearPendingMiningBreak();
    }

    @Unique
    private void mmm$clearPendingMiningBreak()
    {
        this.mmm$pendingBlock = null;
        this.mmm$pendingState = null;
        this.mmm$pendingValidMiningBreak = false;
    }

    @Unique
    private static boolean mmm$isSurvivalMiningMode(MinecraftClient client)
    {
        if (client == null || client.player == null || client.interactionManager == null)
        {
            return false;
        }

        GameMode gameMode = client.interactionManager.getCurrentGameMode();
        return gameMode == GameMode.SURVIVAL && client.player.isCreative() == false && client.player.isSpectator() == false;
    }

    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void mmm$recordPlacedBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir)
    {
        if (!FeatureToggle.TWEAK_MINING_TRACKER.getBooleanValue() || hitResult == null || cir.getReturnValue() == null || cir.getReturnValue().isAccepted() == false)
        {
            return;
        }

        MiningValidationTracker.onBlockPlaced(hitResult.getBlockPos().offset(hitResult.getSide()), System.currentTimeMillis());
    }

    @Inject(method = "attackBlock(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private void mmm$blockAttackBelowFeet(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir)
    {
        if (FlatDigger.shouldBlock(pos) || PerimeterWallDigHelper.isPositionDisallowed(pos))
        {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Inject(method = "updateBlockBreakingProgress(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private void mmm$blockProgressBelowFeet(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir)
    {
        if (FlatDigger.shouldBlock(pos) || PerimeterWallDigHelper.isPositionDisallowed(pos))
        {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }
}
