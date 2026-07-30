/*
 * Implements the map-bound NPC creation editor.
 * 实现与地图绑定的 NPC 编辑器。
 *
 * Author: CreateGo
 * Date: 2026-07-31
 */

package com.longersausage.creatego.item;

import com.longersausage.creatego.server.DimensionPool;
import com.longersausage.creatego.server.ModService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates an NPC above a clicked block in the map dimension.
 * 在地图维度中被点击方块的上方创建 NPC。
 */
public final class NpcEditorItem extends Item {
    private static final Logger LOGGER = LoggerFactory.getLogger(NpcEditorItem.class);

    /**
     * Creates the NPC editor item.
     * 创建 NPC 编辑器物品。
     *
     * @param properties item properties / 物品属性
     */
    public NpcEditorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player) || !player.hasPermissions(2)) {
            return InteractionResult.FAIL;
        }
        if (DimensionPool.activeSession(player) == null) {
            LOGGER.warn("玩家 [{}] 使用 NPC 编辑器失败：NPC 编辑器只能在通过地图编辑器创建的隔离维度中使用。", player.getScoreboardName());
            return InteractionResult.FAIL;
        }
        return ModService.createNpc(player, context.getClickedPos().above())
                ? InteractionResult.SUCCESS
                : InteractionResult.FAIL;
    }
}
