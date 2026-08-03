/*
 * Routes server events to client screens and caches.
 * 将服务端事件路由到客户端界面与缓存。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.data.ModState;
import com.longersausage.creatego.data.ModStore;
import com.longersausage.creatego.data.NpcData;
import com.longersausage.creatego.network.ClientboundSkinPayload;
import com.longersausage.creatego.network.ClientboundSyncPayload;
import com.longersausage.creatego.network.ModNetwork;
import com.longersausage.creatego.server.DialogueRuntime;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the synchronized client snapshot and screen transitions.
 * 管理客户端同步快照与界面切换。
 */
public final class ClientController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientController.class);

    private static ModState clientState = new ModState();
    private static ModNetwork.LevelPlayStatus levelPlayStatus;
    private static long levelResultExpiresAt;

    private ClientController() {
    }

    /**
     * Handles a client-bound sync event on the main client thread.
     * 在客户端主线程处理同步事件。
     *
     * @param payload received event / 收到的事件
     * @param context payload context / 载荷上下文
     */
    public static void handleSync(ClientboundSyncPayload payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        switch (payload.action()) {
            case "sync_state" -> {
                ModNetwork.MapEditorView view = readMapEditorView(payload.json());
                clientState = view.state;
                if (minecraft.screen instanceof MapScreen screen) {
                    screen.updateView(view);
                }
            }
            case "open_map_ui" -> {
                ModNetwork.MapEditorView view = readMapEditorView(payload.json());
                clientState = view.state;
                minecraft.setScreen(new MapScreen(view));
            }
            case "open_map_configuration" -> {
                ModNetwork.MapConfigurationView view = ModStore.fromJson(
                        payload.json(),
                        ModNetwork.MapConfigurationView.class
                );
                clientState = view.state;
                minecraft.setScreen(new MapScreen(view, view.configuredMapId));
            }
            case "open_npc_ui", "npc_saved" -> {
                ModNetwork.NpcEditorView view = ModStore.fromJson(payload.json(), ModNetwork.NpcEditorView.class);
                if (view != null && view.npc != null) {
                    minecraft.setScreen(new NpcScreen(view.npc, view.skins == null ? java.util.List.of() : view.skins));
                }
            }
            case "open_level_editor" -> {
                ModNetwork.LevelEditorView view = ModStore.fromJson(payload.json(), ModNetwork.LevelEditorView.class);
                if (view != null) {
                    minecraft.setScreen(new LevelEditorScreen(view));
                }
            }
            case "open_level_restrictions" -> {
                ModNetwork.LevelEditorView view = ModStore.fromJson(payload.json(), ModNetwork.LevelEditorView.class);
                if (view != null && view.level != null) {
                    minecraft.setScreen(new LevelRestrictionScreen(view));
                }
            }
            case "level_play_status" -> {
                levelPlayStatus = ModStore.fromJson(payload.json(), ModNetwork.LevelPlayStatus.class);
                if (levelPlayStatus != null && !levelPlayStatus.active) {
                    levelResultExpiresAt = System.currentTimeMillis() + 4000L;
                }
            }
            case "dialogue_view" -> DialogueScreen.open(
                    ModStore.fromJson(payload.json(), DialogueRuntime.DialogueView.class)
            );
            case "dialogue_close" -> DialogueScreen.close();
            case "close_screen" -> minecraft.setScreen(null);
            case "error" -> logError(payload.json());
            case "notice" -> logNotice(payload.json());
            default -> {
            }
        }
    }

    /**
     * Registers downloaded PNG bytes in the dynamic texture cache.
     * 将下载的 PNG 字节注册到动态纹理缓存。
     *
     * @param payload skin data / 皮肤数据
     * @param context payload context / 载荷上下文
     */
    public static void handleSkin(ClientboundSkinPayload payload, IPayloadContext context) {
        SkinCache.accept(payload.name(), payload.png());
    }

    /**
     * Returns the latest synchronized state.
     * 返回最新同步的状态。
     *
     * @return client state snapshot / 客户端状态快照
     */
    public static ModState state() {
        return clientState;
    }

    /**
     * Returns the current level HUD state, including a short-lived final result.
     * 返回当前关卡 HUD 状态，包括短暂保留的最终结果。
     *
     * @return current status or {@code null} / 当前状态，不存在时返回 {@code null}
     */
    public static ModNetwork.LevelPlayStatus levelPlayStatus() {
        if (levelPlayStatus != null && !levelPlayStatus.active && System.currentTimeMillis() > levelResultExpiresAt) {
            levelPlayStatus = null;
        }
        return levelPlayStatus;
    }

    /**
     * Reads and normalizes a personalized map view.
     * 读取并规范化个性化地图视图。
     *
     * @param json serialized view / 已序列化视图
     * @return non-null view / 非空视图
     */
    private static ModNetwork.MapEditorView readMapEditorView(String json) {
        ModNetwork.MapEditorView view = ModStore.fromJson(json, ModNetwork.MapEditorView.class);
        if (view == null) {
            return new ModNetwork.MapEditorView();
        }
        if (view.state == null) {
            view.state = new ModState();
        }
        if (view.boundMapId == null) {
            view.boundMapId = "";
        }
        return view;
    }

    private static void logError(String json) {
        ModNetwork.MessageBody body = ModStore.fromJson(json, ModNetwork.MessageBody.class);
        if (body != null && body.message() != null) {
            LOGGER.error("错误提示: {}", body.message());
        }
    }

    private static void logNotice(String json) {
        ModNetwork.MessageBody body = ModStore.fromJson(json, ModNetwork.MessageBody.class);
        if (body != null && body.message() != null) {
            LOGGER.info("通知: {}", body.message());
        }
    }
}
