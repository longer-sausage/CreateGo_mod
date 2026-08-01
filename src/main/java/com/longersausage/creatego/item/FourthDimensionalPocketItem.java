/*
 * Provides the fourth-dimensional pocket item interaction and tooltip.
 * 提供四次元口袋物品的交互与提示信息。
 *
 * Author: CreateGo
 * Date: 2026-08-01
 */

package com.longersausage.creatego.item;

import com.longersausage.creatego.server.FourthDimensionalPocketStorage;
import dev.ryanhcode.sable.Sable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * Stores one Sable physical structure and places it beside a clicked block.
 * 存储一个 Sable 物理结构，并将其放置到所点击方块旁。
 */
public final class FourthDimensionalPocketItem extends Item {
    /**
     * Creates a fourth-dimensional pocket.
     * 创建四次元口袋。
     *
     * @param properties item properties / 物品属性
     */
    public FourthDimensionalPocketItem(Properties properties) {
        super(properties);
    }

    /**
     * Stores the clicked Sable structure or releases the currently stored structure.
     * 存储点击的 Sable 结构，或释放当前已经存储的结构。
     *
     * @param context block-use context / 方块使用上下文
     * @return interaction result / 交互结果
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        boolean filled = FourthDimensionalPocketStorage.isFilled(stack);
        if (context.getLevel().isClientSide()) {
            // Only consume an empty-pocket click when it actually targets a Sable plot.
            // 仅当空口袋确实点击了 Sable 区块时才消费客户端交互。
            return filled || Sable.HELPER.getContaining(context.getLevel(), context.getClickedPos()) != null
                    ? InteractionResult.SUCCESS
                    : InteractionResult.PASS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.FAIL;
        }
        FourthDimensionalPocketStorage.Result result = filled
                ? FourthDimensionalPocketStorage.release(
                        player,
                        stack,
                        context.getClickedPos(),
                        context.getClickedFace()
                )
                : FourthDimensionalPocketStorage.store(player, stack, context.getClickedPos());
        return switch (result) {
            case SUCCESS -> InteractionResult.SUCCESS;
            case NOT_APPLICABLE -> InteractionResult.PASS;
            case FAILURE -> InteractionResult.FAIL;
        };
    }

    /**
     * Adds the current storage state to the item tooltip.
     * 将当前存储状态添加到物品提示中。
     *
     * @param stack item stack / 物品堆叠
     * @param context tooltip context / 提示上下文
     * @param tooltip tooltip lines / 提示行
     * @param flag tooltip detail flag / 提示详情标记
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        Component storedName = FourthDimensionalPocketStorage.getStoredName(stack);
        if (storedName == null) {
            tooltip.add(Component.translatable("tooltip.creatego.fourth_dimensional_pocket.empty")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(Component.translatable("tooltip.creatego.fourth_dimensional_pocket.stored", storedName)
                .withStyle(ChatFormatting.AQUA));
    }
}
