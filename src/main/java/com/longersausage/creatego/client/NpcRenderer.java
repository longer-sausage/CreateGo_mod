/*
 * Renders stationary NPCs with the vanilla player model.
 * 使用原版玩家模型渲染静止 NPC。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.entity.NpcEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Uses a wide-arm player model and synchronized skin resources.
 * 使用宽手臂玩家模型和已同步皮肤资源。
 */
public final class NpcRenderer extends MobRenderer<NpcEntity, PlayerModel<NpcEntity>> {
    /**
     * Creates the renderer from baked vanilla model layers.
     * 使用烘焙后的原版模型层创建渲染器。
     *
     * @param context renderer context / 渲染器上下文
     */
    public NpcRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(NpcEntity entity) {
        return SkinCache.texture(entity);
    }
}
