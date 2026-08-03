/*
 * Implements the map-bound level editor item.
 * 实现与地图绑定的关卡编辑器物品。
 *
 * Author: CreateGo
 * Date: 2026-08-04
 */

package com.longersausage.creatego.item;

import com.longersausage.creatego.network.ModNetwork;
import com.longersausage.creatego.server.DimensionPool;
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
 * Opens registration or configuration for the player's currently bound map.
 * 为玩家当前绑定的地图打开关卡注册或配置界面。
 */
public final class LevelEditorItem extends Item {
    private static final Logger LOGGER = LoggerFactory.getLogger(LevelEditorItem.class);

    /**
     * Creates the level editor item.
     * 创建关卡编辑器物品。
     *
     * @param properties item properties / 物品属性
     */
    public LevelEditorItem(Properties properties) {
        super(properties);
    }

    /**
     * Opens the level editor only inside an operator-owned map session.
     * 仅在管理员拥有的地图会话内打开关卡编辑器。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (!serverPlayer.hasPermissions(2)) {
                LOGGER.warn("玩家 [{}] 使用关卡编辑器失败：没有管理权限。", serverPlayer.getScoreboardName());
            } else if (DimensionPool.activeSession(serverPlayer) == null) {
                LOGGER.warn("玩家 [{}] 使用关卡编辑器失败：当前没有绑定地图。", serverPlayer.getScoreboardName());
            } else {
                ModNetwork.openLevelEditor(serverPlayer);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
