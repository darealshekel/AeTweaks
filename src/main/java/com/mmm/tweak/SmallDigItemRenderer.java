package com.mmm.tweak;

import com.mmm.config.Configs;
import com.mmm.util.BlockBreakdownCatalog;

import net.minecraft.block.Block;
import net.minecraft.client.render.model.json.Transformation;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;

import org.joml.Vector3f;

public final class SmallDigItemRenderer
{
    private static final float GROUND_SCALE = 0.1F;
    private static final float HAND_SCALE = 0.2F;
    private static final ThreadLocal<Float> ACTIVE_SCALE = ThreadLocal.withInitial(() -> 1.0F);

    private SmallDigItemRenderer()
    {
    }

    public static float getScale(ItemStack stack, ItemDisplayContext displayContext)
    {
        if (!Configs.Generic.SMALL_DIG_ITEMS.getBooleanValue() || stack == null || stack.isEmpty())
        {
            return 1.0F;
        }

        if (!(stack.getItem() instanceof BlockItem blockItem) || !isScaledContext(displayContext))
        {
            return 1.0F;
        }

        Block block = blockItem.getBlock();
        if (!BlockBreakdownCatalog.isValid(block))
        {
            return 1.0F;
        }

        return displayContext == ItemDisplayContext.GROUND ? GROUND_SCALE : HAND_SCALE;
    }

    public static void begin(ItemStack stack, ItemDisplayContext displayContext)
    {
        begin(getScale(stack, displayContext));
    }

    public static void begin(float scale)
    {
        ACTIVE_SCALE.set(scale);
    }

    public static void end()
    {
        ACTIVE_SCALE.set(1.0F);
    }

    public static float getActiveScale()
    {
        return ACTIVE_SCALE.get();
    }

    public static Transformation applyActiveScale(Transformation transform)
    {
        float scale = getActiveScale();
        if (transform == null || scale >= 1.0F)
        {
            return transform;
        }

        Vector3f scaled = new Vector3f(scale, scale, scale);
        return new Transformation(transform.rotation(), transform.translation(), scaled);
    }

    private static boolean isScaledContext(ItemDisplayContext displayContext)
    {
        return displayContext == ItemDisplayContext.GROUND ||
                displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ||
                displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ||
                displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND ||
                displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }
}
