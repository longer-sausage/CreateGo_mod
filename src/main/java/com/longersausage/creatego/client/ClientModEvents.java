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
}
