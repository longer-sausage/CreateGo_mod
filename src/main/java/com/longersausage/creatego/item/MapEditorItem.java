/*
 * Implements the administrator map editor item.
 * 实现管理员地图编辑器物品。
 *
 * Author: CreateGo
 * Date: 2026-07-31
 */

package com.longersausage.creatego.item;

import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens the map management screen for operators.
 * 为管理员打开地图管理界面。
 */
public final class MapEditorItem extends Item {
    private static final Logger LOGGER = LoggerFactory.getLogger(MapEditorItem.class);

    /**
     * Creates the map editor item.
     * 创建地图编辑器物品。
     *
     * @param properties item properties / 物品属性
     */
    public MapEditorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (!serverPlayer.hasPermissions(2)) {
                LOGGER.warn("玩家 [{}] 使用地图编辑器失败：只有管理员可以使用地图编辑器。", serverPlayer.getScoreboardName());
            } else {
                ModNetwork.openMapUI(serverPlayer);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
