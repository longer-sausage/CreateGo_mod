/*
 * Registers and routes all CreateGo network payloads.
 * 注册并路由全部 CreateGo 网络载荷。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.network;

import com.longersausage.creatego.client.ClientController;
import com.longersausage.creatego.data.MapDefinition;
import com.longersausage.creatego.data.ModState;
import com.longersausage.creatego.data.ModStore;
import com.longersausage.creatego.data.NpcData;
import com.longersausage.creatego.entity.NpcEntity;
import com.longersausage.creatego.server.DialogueRuntime;
import com.longersausage.creatego.server.DimensionPool;
import com.longersausage.creatego.server.ModService;
import com.longersausage.creatego.server.UploadManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the common network facade used by items, entities, and screens.
 * 提供物品、实体与界面共用的网络门面。
 */
public final class ModNetwork {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModNetwork.class);

    private ModNetwork() {
    }

    /**
     * Registers versioned play-phase payloads.
     * 注册带版本号的游戏阶段载荷。
     *
     * @param event payload registration event / 载荷注册事件
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                ServerboundActionPayload.TYPE,
                ServerboundActionPayload.STREAM_CODEC,
                ModNetwork::handleActionCommand
        );
        registrar.playToServer(
                UploadChunkPayload.TYPE,
                UploadChunkPayload.STREAM_CODEC,
                ModNetwork::handleUploadChunk
        );
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registrar.playToClient(
                    ClientboundSyncPayload.TYPE,
                    ClientboundSyncPayload.STREAM_CODEC,
                    ClientController::handleSync
            );
            registrar.playToClient(
                    ClientboundSkinPayload.TYPE,
                    ClientboundSkinPayload.STREAM_CODEC,
                    ClientController::handleSkin
            );
        }
    }

    /**
     * Opens the map UI with fresh server state.
     * 使用最新服务端状态打开地图界面。
     *
     * @param player target operator / 目标管理员
     */
    public static void openMapUI(ServerPlayer player) {
        send(player, "open_map_ui", mapEditorViewJson(player));
    }

    /**
     * Opens configuration for one persistent map without creating a new binding.
     * 打开一个持久地图的配置，但不创建新的维度绑定。
     *
     * @param player target operator / 目标管理员
     * @param mapId configured map identifier / 要配置的地图标识
     */
    public static void openMapConfiguration(ServerPlayer player, String mapId) {
        send(
                player,
                "open_map_configuration",
                ModStore.toJson(new MapConfigurationView(
                        ModStore.get(player.server).state(),
                        DimensionPool.boundMapId(player),
                        mapId
                ))
        );
    }

    /**
     * Opens an NPC UI for one entity.
     * 为一个实体打开 NPC 界面。
     *
     * @param player target operator / 目标管理员
     * @param entity selected NPC / 已选择 NPC
     */
    public static void openNpcUI(ServerPlayer player, NpcEntity entity) {
        NpcData npc = ModService.findSessionNpc(player, entity.getNpcId());
        if (npc != null && DimensionPool.isBoundTo(player, npc.mapId)) {
            send(player, "open_npc_ui", ModStore.toJson(new NpcEditorView(
                    npc,
                    ModService.listServerSkins(player.server)
            )));
        } else {
            error(player, "请先通过地图界面进入该地图。");
        }
    }

    /**
     * Starts or resumes a server-authoritative conversation.
     * 开始或继续一个服务端权威对话。
     *
     * @param player interacting player / 交互玩家
     * @param entity selected NPC / 已选择 NPC
     * @return whether a reachable conversation was started / 是否启动了可达对话
     */
    public static boolean startDialogue(ServerPlayer player, NpcEntity entity) {
        NpcData npc = ModService.findSessionOrPersistentNpc(player, entity.getMapId(), entity.getNpcId());
        return npc != null && DialogueRuntime.start(player, npc);
    }

    /**
     * Broadcasts state changes to every connected client.
     * 向全部已连接客户端广播状态变更。
     *
     * @param player any player on the target server / 目标服务端上的任意玩家
     */
    public static void broadcastState(ServerPlayer player) {
        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            send(target, "sync_state", mapEditorViewJson(target));
        }
    }

    /**
     * Synchronizes the state to one client.
     * 向一个客户端同步状态。
     *
     * @param player target player / 目标玩家
     */
    public static void syncState(ServerPlayer player) {
        send(player, "sync_state", mapEditorViewJson(player));
    }

    /**
     * Sends a typed event.
     * 发送带类型的事件。
     *
     * @param player target player / 目标玩家
     * @param action event identifier / 事件标识
     * @param json JSON body / JSON 内容
     */
    public static void send(ServerPlayer player, String action, String json) {
        PacketDistributor.sendToPlayer(player, new ClientboundSyncPayload(action, json));
    }

    /**
     * Sends a visible error without disconnecting the client.
     * 发送可见错误但不与客户端断开连接。
     *
     * @param player target player / 目标玩家
     * @param message localized error text / 本地化错误文本
     */
    public static void error(ServerPlayer player, String message) {
        LOGGER.error("模组错误 [玩家: {}]: {}", player.getScoreboardName(), message);
        send(player, "error", ModStore.toJson(new MessageBody(message)));
    }

    /**
     * Serializes a state snapshot together with the receiving player's transient map binding.
     * 序列化状态快照以及接收玩家的临时地图绑定。
     *
     * @param player receiving player / 接收玩家
     * @return personalized map view JSON / 个性化地图视图 JSON
     */
    private static String mapEditorViewJson(ServerPlayer player) {
        return ModStore.toJson(new MapEditorView(
                ModStore.get(player.server).state(),
                DimensionPool.boundMapId(player)
        ));
    }

    private static void handleActionCommand(ServerboundActionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ModService.handleCommand(player, payload.action(), payload.json());
        }
    }

    private static void handleUploadChunk(UploadChunkPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            UploadManager.accept(player, payload);
        }
    }

    /**
     * Defines a JSON message body.
     * 定义 JSON 消息内容。
     *
     * @param message user-facing text / 面向用户的文本
     */
    public record MessageBody(String message) {
    }

    /**
     * Defines the map metadata form body.
     * 定义地图元数据表单内容。
     */
    public static final class MapMetadataForm {
        public String id = "";
        public int spawnX;
        public int spawnY = 65;
        public int spawnZ;
        public MapDefinition.Direction direction = MapDefinition.Direction.SOUTH;
    }

    /**
     * Defines the personalized state used to choose normal or editing mode.
     * 定义用于选择普通模式或编辑模式的个性化状态。
     */
    public static class MapEditorView {
        public ModState state = new ModState();
        public String boundMapId = "";

        /**
         * Creates an empty view for JSON deserialization.
         * 创建供 JSON 反序列化使用的空视图。
         */
        public MapEditorView() {
        }

        /**
         * Creates a personalized view.
         * 创建个性化视图。
         *
         * @param state synchronized state / 已同步状态
         * @param boundMapId player-bound map identifier / 玩家绑定的地图标识
         */
        public MapEditorView(ModState state, String boundMapId) {
            this.state = state;
            this.boundMapId = boundMapId;
        }
    }

    /**
     * Defines a map configuration screen payload.
     * 定义地图配置界面载荷。
     */
    public static final class MapConfigurationView extends MapEditorView {
        public String configuredMapId = "";

        /**
         * Creates an empty configuration view for JSON deserialization.
         * 创建供 JSON 反序列化使用的空配置视图。
         */
        public MapConfigurationView() {
        }

        /**
         * Creates a map configuration view.
         * 创建地图配置视图。
         *
         * @param state synchronized state / 已同步状态
         * @param boundMapId player-bound map identifier / 玩家绑定地图标识
         * @param configuredMapId configured map identifier / 配置地图标识
         */
        public MapConfigurationView(ModState state, String boundMapId, String configuredMapId) {
            super(state, boundMapId);
            this.configuredMapId = configuredMapId;
        }
    }

    /**
     * Defines an NPC editor payload containing NPC document and server skins.
     * 定义包含 NPC 文档与服务端皮肤列表的 NPC 编辑载荷。
     */
    public static final class NpcEditorView {
        public NpcData npc = new NpcData();
        public java.util.List<String> skins = java.util.List.of();

        public NpcEditorView() {
        }

        public NpcEditorView(NpcData npc, java.util.List<String> skins) {
            this.npc = npc;
            this.skins = skins;
        }
    }
}
