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
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Isolates client classes from dedicated-server class loading.
 * 将客户端类与专用服务端类加载隔离。
 */
public final class ClientModEvents {
    public static final KeyMapping LEVEL_MENU_KEY = new KeyMapping(
            "key.creatego.level_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I,
            "key.categories.creatego"
    );

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
        modBus.addListener(ClientModEvents::registerKeyMappings);
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
     * Registers the configurable in-level menu key.
     * 注册可配置的关卡内菜单按键。
     *
     * @param event key mapping registration event / 按键映射注册事件
     */
    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(LEVEL_MENU_KEY);
    }

    /**
     * Registers only the challenge timer above vanilla overlays.
     * 仅在原版覆盖层上方注册挑战倒计时。
     *
     * @param event GUI layer registration event / GUI 图层注册事件
     */
    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(
                VanillaGuiLayers.BOSS_OVERLAY,
                CreateGo.id("level_timer"),
                LevelTimerHud::render
        );
    }
}
