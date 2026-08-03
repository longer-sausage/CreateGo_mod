/*
 * Registers client-only entity rendering hooks.
 * 注册仅客户端使用的实体渲染钩子。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.CreateGo;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Isolates client classes from dedicated-server class loading.
 * 将客户端类与专用服务端类加载隔离。
 */
public final class ClientModEvents {
    private ClientModEvents() {
    }

    /**
     * Connects client lifecycle listeners to the mod event bus.
     * 将客户端生命周期监听器连接到模组事件总线。
     *
     * @param modBus mod lifecycle event bus / 模组生命周期事件总线
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(ClientModEvents::registerRenderers);
        modBus.addListener(ClientModEvents::registerGuiLayers);
    }

    /**
     * Registers the player-model NPC renderer.
     * 注册玩家模型 NPC 渲染器。
     *
     * @param event renderer registration event / 渲染器注册事件
     */
    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CreateGo.NPC_ENTITY.get(), NpcRenderer::new);
    }

    /**
     * Registers the unobtrusive level progress HUD above vanilla overlays.
     * 在原版覆盖层上方注册不遮挡视线的关卡进度 HUD。
     *
     * @param event GUI layer registration event / GUI 图层注册事件
     */
    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(
                VanillaGuiLayers.BOSS_OVERLAY,
                CreateGo.id("level_progress"),
                LevelHud::render
        );
    }
}
