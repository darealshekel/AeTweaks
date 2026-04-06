package com.miningtrackeraddon.tweak;

import java.util.ArrayList;
import java.util.List;

import com.miningtrackeraddon.config.FeatureToggle;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

public final class PerimeterWallDigHelper
{
    private static final ArrayList<Block> OUTLINE_BLOCKS = new ArrayList<>();

    private PerimeterWallDigHelper()
    {
    }

    public static boolean isPositionDisallowed(BlockPos pos)
    {
        if (!FeatureToggle.TWEAK_PERIMETER_WALL_DIG_HELPER.getBooleanValue())
        {
            return false;
        }

        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null)
        {
            return false;
        }

        BlockPos surfacePos = world.getTopPosition(Heightmap.Type.WORLD_SURFACE, pos).down();
        return OUTLINE_BLOCKS.contains(world.getBlockState(surfacePos).getBlock());
    }

    public static void setOutlineBlocks(List<String> blocks)
    {
        OUTLINE_BLOCKS.clear();

        for (String name : blocks)
        {
            Block block = getBlockFromName(name);
            if (block != null)
            {
                OUTLINE_BLOCKS.add(block);
            }
        }
    }

    private static Block getBlockFromName(String name)
    {
        try
        {
            Identifier identifier = Identifier.of(name);
            return Registries.BLOCK.containsId(identifier) ? Registries.BLOCK.get(identifier) : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
}
