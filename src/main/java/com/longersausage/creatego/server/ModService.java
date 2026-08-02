/*
 * Implements server-authoritative map and NPC operations.
 * 实现服务端权威的地图与 NPC 操作。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.server;

import com.longersausage.creatego.CreateGo;
import com.longersausage.creatego.data.DialogueGraph;
import com.longersausage.creatego.data.MapDefinition;
import com.longersausage.creatego.data.ModState;
import com.longersausage.creatego.data.ModStore;
import com.longersausage.creatego.data.NpcData;
import com.longersausage.creatego.entity.NpcEntity;
import com.longersausage.creatego.network.ClientboundSkinPayload;
import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Validates client commands and mutates state only on the logical server.
 * 验证客户端命令，并且只在逻辑服务端修改状态。
 */
public final class ModService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModService.class);
    private static final Set<String> COMPARISON_OPERATORS = Set.of("≥", ">", "=", "≠", "<", "≤");
    private static final int MAX_PRELOADED_STRUCTURE_CHUNKS = 1024;
    private static final Map<MinecraftServer, List<PendingEntry>> PENDING_ENTRIES = new WeakHashMap<>();

    private ModService() {
    }

    /**
     * Routes one client command.
     * 路由一个客户端命令。
     *
     * @param player command sender / 命令发送者
     * @param action action identifier / 操作标识
     * @param json JSON body / JSON 内容
     */
    public static void handleCommand(ServerPlayer player, String action, String json) {
        try {
            if (action.startsWith("dialogue_")) {
                DialogueRuntime.handle(player, action, json);
                return;
            }
            if (action.equals("request_skin")) {
                sendSkin(player, ModStore.fromJson(json, SkinRequest.class).name);
                return;
            }
            if (!player.hasPermissions(2)) {
                ModNetwork.error(player, "没有管理权限。");
                return;
            }
            switch (action) {
                case "create_map" -> createMap(player, ModStore.fromJson(json, ModNetwork.MapMetadataForm.class));
                case "save_map" -> saveMap(player, ModStore.fromJson(json, ModNetwork.MapMetadataForm.class));
                case "load_map" -> loadMap(player, ModStore.fromJson(json, MapIdRequest.class).mapId);
                case "exit_map" -> exitMap(player);
                case "delete_map" -> deleteMap(player, ModStore.fromJson(json, MapIdRequest.class).mapId);
                case "save_structure_configuration" -> saveStructureConfiguration(
                        player,
                        ModStore.fromJson(json, ModNetwork.StructureConfigurationForm.class)
                );
                case "delete_structure" -> deleteStructure(
                        player,
                        ModStore.fromJson(json, ModNetwork.StructureRequest.class)
                );
                case "save_all_npcs" -> saveAllNpcData(player);
                case "save_npc" -> saveNpc(player, ModStore.fromJson(json, NpcData.class), false);
                case "save_dialogue" -> saveNpc(player, ModStore.fromJson(json, NpcData.class), false);
                case "delete_npc" -> deleteNpc(player, UUID.fromString(
                        ModStore.fromJson(json, NpcIdRequest.class).npcId
                ));
                default -> ModNetwork.error(player, "未知操作：" + action);
            }
        } catch (Exception exception) {
            ModNetwork.error(player, exception.getMessage() == null ? "操作失败。" : exception.getMessage());
        }
    }

    /**
     * Creates a map-bound NPC at a world position.
     * 在世界坐标创建一个与地图绑定的 NPC。
     *
     * @param player creating operator / 创建 NPC 的管理员
     * @param position entity feet position / 实体脚部位置
     * @return whether creation succeeded / 是否创建成功
     */
    public static boolean createNpc(ServerPlayer player, BlockPos position) {
        DimensionPool.Session session = DimensionPool.activeSession(player);
        MapDefinition map = session == null
                ? null
                : ModStore.get(player.server).state().maps.get(session.mapId());
        if (map == null) {
            LOGGER.warn("玩家 [{}] 创建 NPC 失败：请先进入一张地图。", player.getScoreboardName());
            return false;
        }
        NpcData data = new NpcData();
        data.mapId = map.id;
        data.name = "NPC " + (session.npcDrafts().size() + 1);
        data.x = position.getX() + 0.5D;
        data.y = position.getY();
        data.z = position.getZ() + 0.5D;
        double deltaX = player.getX() - (position.getX() + 0.5D);
        double deltaZ = player.getZ() - (position.getZ() + 0.5D);
        data.yaw = (float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0D);
        session.npcDrafts().put(data.id, data);
        spawnNpc(player.serverLevel(), data);
        NpcEntity entity = findLiveNpc(player.serverLevel(), data.id);
        if (entity != null) {
            ModNetwork.openNpcUI(player, entity);
        }
        LOGGER.info("玩家 [{}] 在地图 [{}] 中创建 NPC 草稿 [{}]", player.getScoreboardName(), map.id, data.name);
        return true;
    }

    /**
     * Locates one NPC within a named map.
     * 在指定地图中查找 NPC。
     *
     * @param server running server / 运行中的服务端
     * @param mapId map identifier / 地图标识
     * @param npcId NPC identifier / NPC 标识
     * @return matching NPC, or {@code null} / 匹配 NPC，不存在时返回 {@code null}
     */
    public static NpcData findNpc(MinecraftServer server, String mapId, UUID npcId) {
        MapDefinition map = ModStore.get(server).state().maps.get(mapId);
        return map == null
                ? null
                : map.npcs.stream().filter(npc -> npc.id.equals(npcId)).findFirst().orElse(null);
    }

    /**
     * Locates one NPC draft in the requesting player's active session.
     * 在请求玩家的活动会话中查找 NPC 草稿。
     *
     * @param player session owner / 会话所有者
     * @param npcId NPC identifier / NPC 标识
     * @return session draft, or {@code null} / 会话草稿，不存在时返回 {@code null}
     */
    public static NpcData findSessionNpc(ServerPlayer player, UUID npcId) {
        DimensionPool.Session session = DimensionPool.activeSession(player);
        return session == null ? null : session.npcDrafts().get(npcId);
    }

    /**
     * Resolves dialogue data from the current temporary dimension before persistent data.
     * 优先从当前临时维度解析对话数据，不存在会话时再读取持久数据。
     *
     * @param player interacting player / 交互玩家
     * @param mapId NPC map identifier / NPC 地图标识
     * @param npcId NPC identifier / NPC 标识
     * @return matching NPC data, or {@code null} / 匹配的 NPC 数据，不存在时返回 {@code null}
     */
    public static NpcData findSessionOrPersistentNpc(ServerPlayer player, String mapId, UUID npcId) {
        DimensionPool.Session session = DimensionPool.sessionForDimension(
                player.server,
                player.serverLevel().dimension()
        );
        if (session != null && session.mapId().equals(mapId)) {
            return session.npcDrafts().get(npcId);
        }
        return findNpc(player.server, mapId, npcId);
    }

    /**
     * Applies updated persistent data to an NPC in the requesting player's isolated dimension.
     * 将更新后的持久数据应用到请求玩家隔离维度内的 NPC。
     *
     * @param player editing player / 编辑玩家
     * @param npc updated NPC document / 已更新 NPC 文档
     */
    public static void updateLiveNpc(ServerPlayer player, NpcData npc) {
        DimensionPool.Session session = DimensionPool.activeSession(player);
        ServerLevel level = session == null ? null : player.server.getLevel(session.dimensionKey());
        if (level == null) {
            return;
        }
        NpcEntity entity = findLiveNpc(level, npc.id);
        if (entity != null) {
            entity.applyData(npc);
            entity.teleportTo(npc.x, npc.y, npc.z);
        }
    }

    /**
     * Captures every live NPC's absolute transform and synchronized identity data.
     * 捕获全部实时 NPC 的绝对变换与同步身份数据。
     *
     * @param server running server / 运行中的服务端
     * @param session isolated session / 隔离会话
     */
    public static void captureNpcData(MinecraftServer server, DimensionPool.Session session) {
        ServerLevel level = server.getLevel(session.dimensionKey());
        if (level == null) {
            return;
        }
        for (Entity rawEntity : level.getAllEntities()) {
            if (!(rawEntity instanceof NpcEntity entity) || !entity.getMapId().equals(session.mapId())) {
                continue;
            }
            NpcData data = session.npcDrafts().get(entity.getNpcId());
            if (data != null) {
                data.x = entity.getX();
                data.y = entity.getY();
                data.z = entity.getZ();
                data.yaw = entity.getYRot();
                data.skinName = entity.getSkinName();
                if (entity.getCustomName() != null) {
                    data.name = entity.getCustomName().getString();
                }
            }
        }
    }

    /**
     * Saves all NPC data in the requesting player's isolated dimension.
     * 保存请求玩家隔离维度内的全部 NPC 数据。
     *
     * @param player requesting operator / 请求管理员
     * @throws IOException when state cannot be written / 状态无法写入时抛出
     */
    private static void saveAllNpcData(ServerPlayer player) throws IOException {
        DimensionPool.Session session = requireActiveSession(player);
        captureNpcData(player.server, session);
        ModStore store = ModStore.get(player.server);
        MapDefinition map = store.state().maps.get(session.mapId());
        if (map == null) {
            throw new IllegalArgumentException("地图不存在。");
        }
        map.npcs = session.npcDrafts().values().stream()
                .map(ModService::copyNpc)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        store.save();
        ModNetwork.broadcastState(player);
        ModNetwork.send(
                player,
                "notice",
                ModStore.toJson(new ModNetwork.MessageBody("地图中的全部 NPC 数据已保存。"))
        );
    }

    /**
     * Reads structure dimensions without placing the structure.
     * 在不放置结构的情况下读取结构尺寸。
     *
     * @param file compressed vanilla/Create structure file / 压缩的原版或机械动力结构文件
     * @return X, Y, Z dimensions / X、Y、Z 尺寸
     * @throws IOException when NBT cannot be read / NBT 无法读取时抛出
     */
    public static int[] readStructureSize(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024L * 1024L));
        ListTag size = root.getList("size", CompoundTag.TAG_INT);
        if (size.size() != 3) {
            throw new IllegalArgumentException("蓝图不是有效的结构 NBT：缺少 size。");
        }
        return new int[]{size.getInt(0), size.getInt(1), size.getInt(2)};
    }

    /**
     * Normalizes and validates a user-facing map identifier.
     * 规范化并验证用户输入的地图标识。
     *
     * @param raw raw identifier / 原始标识
     * @return normalized identifier / 规范化标识
     */
    public static String normalizeMapId(String raw) {
        String normalized = raw == null
                ? ""
                : raw.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("地图 ID 过滤特殊字符后不能为空。");
        }
        return normalized.substring(0, Math.min(normalized.length(), 48));
    }

    /**
     * Creates persistent map metadata without allocating a dimension.
     * 创建持久地图元数据，但不分配维度。
     */
    private static void createMap(ServerPlayer player, ModNetwork.MapMetadataForm form) throws IOException {
        requireNotInMapSession(player);
        String mapId = normalizeMapId(form.id);
        ModStore store = ModStore.get(player.server);
        if (store.state().maps.containsKey(mapId)) {
            throw new IllegalArgumentException("地图 ID 已存在。");
        }
        MapDefinition map = new MapDefinition();
        map.id = mapId;
        applyMetadata(map, form);
        store.state().maps.put(mapId, map);
        store.save();
        LOGGER.info("玩家 [{}] 创建了新地图定义 [地图 ID: {}]", player.getScoreboardName(), mapId);
        ModNetwork.broadcastState(player);
        ModNetwork.openMapConfiguration(player, mapId);
    }

    /**
     * Saves non-NPC map settings when the shared configuration screen is completed.
     * 在共用配置界面点击完成时保存非 NPC 地图设置。
     */
    private static void saveMap(ServerPlayer player, ModNetwork.MapMetadataForm form) throws IOException {
        String mapId = normalizeMapId(form.id);
        String boundMapId = DimensionPool.boundMapId(player);
        if (!boundMapId.isEmpty() && !boundMapId.equals(mapId)) {
            throw new IllegalArgumentException("不能在会话中配置其他地图。");
        }
        ModStore store = ModStore.get(player.server);
        MapDefinition map = store.state().maps.get(mapId);
        if (map == null) {
            throw new IllegalArgumentException("地图不存在。");
        }
        applyMetadata(map, form);
        store.save();
        LOGGER.info("玩家 [{}] 保存了地图配置 [地图 ID: {}]", player.getScoreboardName(), mapId);
        ModNetwork.broadcastState(player);
        ModNetwork.send(player, "notice", ModStore.toJson(new ModNetwork.MessageBody("地图配置已保存。")));
    }

    /**
     * Allocates a fresh empty dimension and queues structure and NPC population.
     * 分配全新空白维度，并将结构与 NPC 生成加入队列。
     */
    private static void loadMap(ServerPlayer player, String rawMapId) {
        requireNotInMapSession(player);
        String mapId = normalizeMapId(rawMapId);
        if (!ModStore.get(player.server).state().maps.containsKey(mapId)) {
            throw new IllegalArgumentException("地图不存在。");
        }
        DimensionPool.Session session = DimensionPool.allocate(player, mapId);
        pendingEntries(player.server).add(new PendingEntry(player.getUUID(), session.dimensionKey(), 0));
        LOGGER.info("玩家 [{}] 请求进入地图 [地图 ID: {}]", player.getScoreboardName(), mapId);
        ModNetwork.send(
                player,
                "notice",
                ModStore.toJson(new ModNetwork.MessageBody("正在创建全新的空白维度……"))
        );
    }

    /**
     * Completes pending entries after Dynamic Dimensions registers their levels with the server.
     * 在 Dynamic Dimensions 向服务端注册世界后完成待处理进入操作。
     *
     * @param server ticking server / 正在运行刻的服务端
     */
    public static void tickPendingEntries(MinecraftServer server) {
        List<PendingEntry> entries = pendingEntries(server);
        for (int index = entries.size() - 1; index >= 0; index--) {
            PendingEntry pending = entries.get(index);
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId);
            DimensionPool.Session session = DimensionPool.session(server, pending.playerId);
            if (player == null) {
                entries.remove(index);
                if (session != null) {
                    DimensionPool.checkAndCleanupDimension(server, session.dimensionKey(), null);
                }
                continue;
            }
            if (session == null || !session.dimensionKey().equals(pending.dimensionKey)) {
                entries.remove(index);
                continue;
            }
            ServerLevel level = server.getLevel(pending.dimensionKey);
            if (level == null) {
                pending.attempts++;
                if (pending.attempts > 200) {
                    entries.remove(index);
                    DimensionPool.detach(server, pending.playerId);
                    ModNetwork.error(player, "动态维度注册超时。");
                }
                continue;
            }
            entries.remove(index);
            try {
                populateSession(player, level, session);
            } catch (Exception exception) {
                DimensionPool.detach(server, pending.playerId);
                DimensionPool.deleteDimension(server, session.dimensionKey());
                ModNetwork.error(player, "地图加载失败：" + exception.getMessage());
            }
        }
    }

    /**
     * Places every configured structure and restores every persistent NPC.
     * 放置全部已配置结构，并恢复全部持久 NPC。
     */
    private static void populateSession(
            ServerPlayer player,
            ServerLevel level,
            DimensionPool.Session session
    ) throws IOException {
        try {
            NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        } catch (Throwable throwable) {
            LOGGER.warn("发送 LevelEvent.Load 异常：{}", throwable.getMessage());
        }
        DimensionPool.enableSablePhysics(level);
        MapDefinition map = ModStore.get(player.server).state().maps.get(session.mapId());
        if (map == null) {
            throw new IllegalArgumentException("地图已被删除。");
        }
        ModStore store = ModStore.get(player.server);
        List<PreparedStructure> preparedStructures = new ArrayList<>();
        if (map.structures != null) {
            for (MapDefinition.StructureData structure : map.structures) {
                Path file = store.structureFile(map.id, structure.name);
                if (Files.isRegularFile(file)) {
                    preparedStructures.add(prepareStructure(level, file, structure));
                } else {
                    LOGGER.warn("地图结构文件不存在 [地图: {}, 结构: {}]", map.id, structure.name);
                }
            }
        }
        preloadStructureChunks(level, preparedStructures, map.id);
        preparedStructures.forEach(prepared -> placeStructure(level, prepared));
        clearStructureTicks(level, preparedStructures, map.id);
        session.npcDrafts().clear();
        map.npcs.stream().map(ModService::copyNpc).forEach(npc -> {
            session.npcDrafts().put(npc.id, npc);
            spawnNpc(level, npc);
        });
        // Complete structure loading before teleport so chunk tracking sends final chunks once.
        // 在传送前完成结构加载，使区块跟踪仅发送一次最终区块数据。
        teleportToSpawn(player, level, map);
        LOGGER.info("成功为玩家 [{}] 加载并填充地图维度 [地图 ID: {}]", player.getScoreboardName(), map.id);
        ModNetwork.broadcastState(player);
        ModNetwork.send(player, "close_screen", "{}");
    }

    /**
     * Teleports the requesting owner back and triggers dimension cleanup if empty.
     * 将请求的所有者传送回进入前位置，并在维度清空时触发维度销毁与会话解绑。
     */
    private static void exitMap(ServerPlayer player) {
        requireActiveSession(player);
        closeSession(player, true);
    }

    /**
     * Handles player session exit or dimension exit without unbinding unless dimension becomes empty.
     * 处理玩家离开地图会话或传送出维度；若维度内还有其他玩家则保持绑定，直到维度变空才彻底解绑与销毁。
     *
     * @param player session owner / 会话所有者
     * @param returnOwner whether to return the online owner / 是否送回在线所有者
     */
    public static void closeSession(ServerPlayer player, boolean returnOwner) {
        DimensionPool.Session session = DimensionPool.session(player);
        if (session == null) {
            return;
        }
        removePendingEntry(player.server, session.dimensionKey());
        if (returnOwner) {
            DimensionPool.returnOwner(player.server, session);
        }
        LOGGER.info("玩家 [{}] 离开地图维度活动状态 [地图 ID: {}]", player.getScoreboardName(), session.mapId());
        ModNetwork.broadcastState(player);
        ModNetwork.send(player, "close_screen", "{}");
        DimensionPool.checkAndCleanupDimension(player.server, session.dimensionKey(), player.getUUID());
    }

    /**
     * Deletes a configured map and closes every isolated session using it.
     * 删除已配置地图，并关闭使用它的全部隔离会话。
     */
    private static void deleteMap(ServerPlayer player, String rawMapId) throws IOException {
        String mapId = normalizeMapId(rawMapId);
        DimensionPool.Session requesterSession = DimensionPool.session(player);
        if (requesterSession != null && !requesterSession.mapId().equals(mapId)) {
            throw new IllegalArgumentException("不能在会话中删除其他地图。");
        }
        ModStore store = ModStore.get(player.server);
        if (!store.state().maps.containsKey(mapId)) {
            throw new IllegalArgumentException("地图不存在。");
        }
        for (DimensionPool.Session session : DimensionPool.detachMapSessions(player.server, mapId)) {
            removePendingEntry(player.server, session.dimensionKey());
            DimensionPool.returnOwner(player.server, session);
            DimensionPool.ejectAllPlayers(player.server, session.dimensionKey());
            DimensionPool.deleteDimension(player.server, session.dimensionKey());
        }
        store.deleteMapDirectory(mapId);
        store.state().maps.remove(mapId);
        store.save();
        LOGGER.info("玩家 [{}] 删除了地图 [地图 ID: {}]", player.getScoreboardName(), mapId);
        ModNetwork.broadcastState(player);
        ModNetwork.openMapUI(player);
    }

    /**
     * Saves one NPC only when it belongs to the requesting player's bound map.
     * 仅当 NPC 属于请求玩家绑定的地图时保存该 NPC。
     */
    private static void saveNpc(ServerPlayer player, NpcData incoming, boolean reopenNpcUI) throws IOException {
        DimensionPool.Session session = requireActiveSession(player);
        if (!session.mapId().equals(incoming.mapId)) {
            throw new IllegalArgumentException("NPC 不属于当前绑定地图。");
        }
        NpcData stored = session.npcDrafts().get(incoming.id);
        if (stored == null) {
            throw new IllegalArgumentException("NPC 不存在。");
        }
        stored.name = sanitizeText(incoming.name, 64, "NPC");
        stored.skinName = incoming.skinName == null ? "" : incoming.skinName;
        stored.x = bounded(incoming.x, -4096, 4096);
        stored.y = bounded(incoming.y, 0, 383);
        stored.z = bounded(incoming.z, -4096, 4096);
        stored.yaw = incoming.yaw;
        if (incoming.dialogue != null) {
            validateGraph(incoming.dialogue);
            stored.dialogue = incoming.dialogue;
        }
        updateLiveNpc(player, stored);
        LOGGER.info("玩家 [{}] 更新了 NPC 草稿 [{}]（地图: {}）", player.getScoreboardName(), stored.name, session.mapId());
        if (reopenNpcUI) {
            ModNetwork.send(player, "npc_saved", ModStore.toJson(new ModNetwork.NpcEditorView(
                    stored,
                    listServerSkins(player.server)
            )));
        } else {
            ModNetwork.send(
                    player,
                    "notice",
                    ModStore.toJson(new ModNetwork.MessageBody("对话工作流已应用到当前会话；点击地图编辑器的“保存 NPC”后才会写入配置。"))
            );
        }
    }

    /**
     * Deletes an NPC from the requesting player's bound map.
     * 从请求玩家绑定的地图中删除 NPC。
     */
    private static void deleteNpc(ServerPlayer player, UUID npcId) throws IOException {
        DimensionPool.Session session = requireActiveSession(player);
        NpcData removed = session.npcDrafts().remove(npcId);
        if (removed == null) {
            throw new IllegalArgumentException("NPC 不存在。");
        }
        ServerLevel level = player.server.getLevel(session.dimensionKey());
        if (level == null) {
            throw new IllegalStateException("隔离维度尚未就绪。");
        }
        NpcEntity live = findLiveNpc(level, npcId);
        if (live != null) {
            live.discard();
        }
        LOGGER.info("玩家 [{}] 删除了 NPC 草稿 [ID: {}]（地图: {}）", player.getScoreboardName(), npcId, session.mapId());
        ModNetwork.send(player, "close_screen", "{}");
    }

    private static void applyMetadata(MapDefinition map, ModNetwork.MapMetadataForm form) {
        map.spawnX = form.spawnX;
        map.spawnY = form.spawnY;
        map.spawnZ = form.spawnZ;
        map.direction = form.direction == null ? MapDefinition.Direction.SOUTH : form.direction;
        map.flatLayers = form.flatLayers == null ? new ArrayList<>() : new ArrayList<>(form.flatLayers);
    }

    /**
     * Saves the independent placement origin of one map structure.
     * 保存一个地图结构的独立放置原点。
     *
     * @param player requesting operator / 请求管理员
     * @param form structure configuration / 结构配置
     * @throws IOException when state cannot be written / 状态无法写入时抛出
     */
    private static void saveStructureConfiguration(
            ServerPlayer player,
            ModNetwork.StructureConfigurationForm form
    ) throws IOException {
        MapDefinition map = requireConfigurableMap(player, form.mapId);
        String structureName = validateStructureName(form.structureName);
        MapDefinition.StructureData structure = findStructure(map, structureName);
        if (structure == null) {
            throw new IllegalArgumentException("结构不存在。");
        }
        structure.originX = form.originX;
        structure.originY = form.originY;
        structure.originZ = form.originZ;
        ModStore.get(player.server).save();
        LOGGER.info(
                "玩家 [{}] 保存了结构配置 [地图: {}, 结构: {}, 原点: ({}, {}, {})]",
                player.getScoreboardName(),
                map.id,
                structure.name,
                structure.originX,
                structure.originY,
                structure.originZ
        );
        ModNetwork.broadcastState(player);
        ModNetwork.send(player, "notice", ModStore.toJson(
                new ModNetwork.MessageBody("结构“" + structure.name + "”的配置已保存。")
        ));
    }

    /**
     * Deletes one structure document and its exact schematic file.
     * 删除一个结构配置及其对应的蓝图文件。
     *
     * @param player requesting operator / 请求管理员
     * @param request structure identity / 结构标识
     * @throws IOException when state or the schematic cannot be deleted / 状态或蓝图无法删除时抛出
     */
    private static void deleteStructure(ServerPlayer player, ModNetwork.StructureRequest request) throws IOException {
        MapDefinition map = requireConfigurableMap(player, request.mapId);
        String structureName = validateStructureName(request.structureName);
        MapDefinition.StructureData structure = findStructure(map, structureName);
        if (structure == null) {
            throw new IllegalArgumentException("结构不存在。");
        }
        Files.deleteIfExists(ModStore.get(player.server).structureFile(map.id, structure.name));
        map.structures.remove(structure);
        ModStore.get(player.server).save();
        LOGGER.info(
                "玩家 [{}] 删除了地图结构 [地图: {}, 结构: {}]",
                player.getScoreboardName(),
                map.id,
                structure.name
        );
        ModNetwork.broadcastState(player);
        ModNetwork.send(player, "notice", ModStore.toJson(
                new ModNetwork.MessageBody("结构“" + structure.name + "”已删除。")
        ));
    }

    /**
     * Returns a map that the player may modify from the current session context.
     * 返回玩家在当前会话上下文中可修改的地图。
     *
     * @param player requesting operator / 请求管理员
     * @param rawMapId requested map identifier / 请求地图标识
     * @return configurable map / 可配置地图
     */
    public static MapDefinition requireConfigurableMap(ServerPlayer player, String rawMapId) {
        String mapId = normalizeMapId(rawMapId);
        String boundMapId = DimensionPool.boundMapId(player);
        if (!boundMapId.isEmpty() && !boundMapId.equals(mapId)) {
            throw new IllegalArgumentException("不能在会话中配置其他地图。");
        }
        MapDefinition map = ModStore.get(player.server).state().maps.get(mapId);
        if (map == null) {
            throw new IllegalArgumentException("地图不存在。");
        }
        if (map.structures == null) {
            map.structures = new ArrayList<>();
        }
        return map;
    }

    /**
     * Validates an exact schematic filename used as the structure name.
     * 校验用作结构名的完整蓝图文件名。
     *
     * @param value filename received from the client / 客户端传入的文件名
     * @return unchanged safe filename / 未更改的安全文件名
     */
    public static String validateStructureName(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("结构名称不合法。");
        }
        String filename;
        try {
            filename = Path.of(value).getFileName().toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("结构名称不合法。", exception);
        }
        if (!filename.equals(value)
                || !filename.toLowerCase(Locale.ROOT).endsWith(".nbt")
                || filename.equals(".")
                || filename.equals("..")) {
            throw new IllegalArgumentException("结构名称必须与安全的 .nbt 蓝图文件名一致。");
        }
        return filename;
    }

    /**
     * Finds one structure by its exact filename.
     * 按完整文件名查找一个结构。
     *
     * @param map owning map / 所属地图
     * @param structureName exact structure name / 完整结构名
     * @return matching structure, or {@code null} / 匹配结构，不存在时返回 {@code null}
     */
    public static MapDefinition.StructureData findStructure(MapDefinition map, String structureName) {
        if (map.structures == null) {
            return null;
        }
        return map.structures.stream()
                .filter(structure -> structure != null && structureName.equals(structure.name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Requires an active map binding owned by the requesting player.
     * 要求请求玩家拥有有效的地图绑定。
     *
     * @param player requesting player / 请求玩家
     * @return bound map definition / 已绑定地图定义
     */
    public static MapDefinition requireBoundMap(ServerPlayer player) {
        MapDefinition map = ModStore.get(player.server).state().maps.get(DimensionPool.boundMapId(player));
        if (map == null) {
            throw new IllegalArgumentException("请先通过地图编辑器进入一张地图。");
        }
        return map;
    }

    /**
     * Requires the player to be physically present in their isolated dimension.
     * 要求玩家实际位于自己的隔离维度中。
     *
     * @param player requesting player / 请求玩家
     * @return active isolated session / 活动隔离会话
     */
    private static DimensionPool.Session requireActiveSession(ServerPlayer player) {
        DimensionPool.Session session = DimensionPool.activeSession(player);
        if (session == null) {
            throw new IllegalArgumentException("请先进入一张地图。");
        }
        return session;
    }

    /**
     * Rejects catalog entry operations while the player is already editing a map.
     * 当玩家已在编辑地图时拒绝目录入口操作。
     *
     * @param player requesting player / 请求玩家
     */
    private static void requireNotInMapSession(ServerPlayer player) {
        if (DimensionPool.session(player) != null) {
            throw new IllegalArgumentException("请先退出当前地图。");
        }
    }

    /**
     * Returns the pending-entry list owned by one running server.
     * 返回一个运行中服务端拥有的待进入列表。
     *
     * @param server running server / 运行中的服务端
     * @return mutable pending-entry list / 可变待进入列表
     */
    private static synchronized List<PendingEntry> pendingEntries(MinecraftServer server) {
        return PENDING_ENTRIES.computeIfAbsent(server, ignored -> new ArrayList<>());
    }

    /**
     * Removes a queued entry associated with a detached dynamic dimension.
     * 移除与已解除动态维度关联的待进入操作。
     *
     * @param server running server / 运行中的服务端
     * @param dimensionKey detached dimension key / 已解除维度键
     */
    private static void removePendingEntry(
            MinecraftServer server,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey
    ) {
        pendingEntries(server).removeIf(entry -> entry.dimensionKey.equals(dimensionKey));
    }

    /**
     * Reads one structure template before chunk preloading begins.
     * 在开始区块预加载前读取一个结构模板。
     *
     * @param level target level / 目标世界
     * @param file compressed structure file / 压缩结构文件
     * @param structure placement metadata / 放置元数据
     * @return prepared structure placement / 已准备的结构放置数据
     * @throws IOException when structure data cannot be read / 结构数据无法读取时抛出
     */
    private static PreparedStructure prepareStructure(
            ServerLevel level,
            Path file,
            MapDefinition.StructureData structure
    ) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024L * 1024L));
        StructureTemplate template = new StructureTemplate();
        template.load(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), root);
        Vec3i size = template.getSize();
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1) {
            throw new IllegalArgumentException("蓝图尺寸必须全部大于 0：" + structure.name);
        }
        structure.sizeX = size.getX();
        structure.sizeY = size.getY();
        structure.sizeZ = size.getZ();
        BlockPos origin = new BlockPos(structure.originX, structure.originY, structure.originZ);
        long maxX = (long) origin.getX() + size.getX() - 1L;
        long maxY = (long) origin.getY() + size.getY() - 1L;
        long maxZ = (long) origin.getZ() + size.getZ() - 1L;
        if (maxX > Integer.MAX_VALUE || maxY > Integer.MAX_VALUE || maxZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("蓝图坐标超出可放置范围：" + structure.name);
        }
        BoundingBox bounds = new BoundingBox(
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                (int) maxX,
                (int) maxY,
                (int) maxZ
        );
        return new PreparedStructure(template, structure.name, origin, size, bounds);
    }

    /**
     * Preloads the deduplicated chunk footprint of every map structure.
     * 预加载地图全部结构去重后的区块覆盖范围。
     *
     * @param level target level / 目标世界
     * @param structures prepared structures / 已准备结构
     * @param mapId owning map identifier / 所属地图标识
     */
    private static void preloadStructureChunks(
            ServerLevel level,
            List<PreparedStructure> structures,
            String mapId
    ) {
        Set<Long> chunks = new HashSet<>();
        for (PreparedStructure structure : structures) {
            int minChunkX = Math.floorDiv(structure.bounds.minX(), 16);
            int minChunkZ = Math.floorDiv(structure.bounds.minZ(), 16);
            int maxChunkX = Math.floorDiv(structure.bounds.maxX(), 16);
            int maxChunkZ = Math.floorDiv(structure.bounds.maxZ(), 16);
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    chunks.add(ChunkPos.asLong(chunkX, chunkZ));
                    if (chunks.size() > MAX_PRELOADED_STRUCTURE_CHUNKS) {
                        throw new IllegalArgumentException(
                                "地图结构覆盖区块超过预加载上限（" + MAX_PRELOADED_STRUCTURE_CHUNKS + "）。"
                        );
                    }
                }
            }
        }
        LOGGER.debug("预加载地图结构区块 [地图: {}, 数量: {}]", mapId, chunks.size());
        for (long packedChunk : chunks) {
            level.getChunk(ChunkPos.getX(packedChunk), ChunkPos.getZ(packedChunk));
        }
    }

    /**
     * Clears block and fluid ticks created inside every loaded structure volume.
     * 清除全部已加载结构范围内产生的方块与流体计划刻。
     *
     * @param level target level / 目标世界
     * @param structures placed structures / 已放置结构
     * @param mapId owning map identifier / 所属地图标识
     */
    private static void clearStructureTicks(
            ServerLevel level,
            List<PreparedStructure> structures,
            String mapId
    ) {
        for (PreparedStructure structure : structures) {
            level.getBlockTicks().clearArea(structure.bounds);
            level.getFluidTicks().clearArea(structure.bounds);
        }
        LOGGER.debug("清理地图结构计划刻 [地图: {}, 结构数量: {}]", mapId, structures.size());
    }

    /**
     * Places one prepared structure without neighbor, shape, or client updates.
     * 在不触发邻居、形状或客户端更新的情况下放置一个已准备结构。
     *
     * @param level target level / 目标世界
     * @param prepared prepared structure / 已准备结构
     */
    private static void placeStructure(ServerLevel level, PreparedStructure prepared) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setKnownShape(true);
        boolean placed = prepared.template.placeInWorld(
                level,
                prepared.origin,
                prepared.origin,
                settings,
                level.random,
                Block.UPDATE_NONE
        );
        if (!placed) {
            throw new IllegalStateException("蓝图放置失败：" + prepared.name);
        }
    }

    private static void teleportToSpawn(ServerPlayer player, ServerLevel level, MapDefinition map) {
        player.teleportTo(
                level,
                map.spawnX + 0.5D,
                map.spawnY,
                map.spawnZ + 0.5D,
                Set.of(),
                map.direction.yaw,
                0.0F
        );
    }

    private static void spawnNpc(ServerLevel level, NpcData data) {
        NpcEntity entity = CreateGo.NPC_ENTITY.get().create(level);
        if (entity == null) {
            throw new IllegalStateException("NPC 实体创建失败。");
        }
        entity.applyData(data);
        entity.moveTo(
                data.x,
                data.y,
                data.z,
                data.yaw,
                0.0F
        );
        level.addFreshEntity(entity);
    }

    private static NpcEntity findLiveNpc(ServerLevel level, UUID npcId) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof NpcEntity npc && npc.getNpcId().equals(npcId)) {
                return npc;
            }
        }
        return null;
    }

    /**
     * Creates a detached deep copy so session edits cannot mutate persistent configuration.
     * 创建完全分离的深拷贝，避免会话编辑直接修改持久配置。
     *
     * @param source persistent or draft NPC / 持久或草稿 NPC
     * @return independent NPC copy / 独立 NPC 副本
     */
    private static NpcData copyNpc(NpcData source) {
        return ModStore.fromJson(ModStore.toJson(source), NpcData.class);
    }

    public static List<String> listServerSkins(MinecraftServer server) {
        Path directory = ModStore.get(server).skinDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(filename -> filename.toLowerCase(Locale.ROOT).endsWith(".png"))
                    .map(filename -> filename.substring(0, filename.length() - 4))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            LOGGER.error("列出服务端皮肤失败", exception);
            return List.of();
        }
    }

    private static void sendSkin(ServerPlayer player, String name) throws IOException {
        if (name == null || name.isBlank() || name.contains("..") || name.contains("/") || name.contains("\\")) {
            return;
        }
        Path file = ModStore.get(player.server).skinDirectory().resolve(name + ".png");
        if (Files.isRegularFile(file) && Files.size(file) <= 1024 * 1024) {
            PacketDistributor.sendToPlayer(player, new ClientboundSkinPayload(name, Files.readAllBytes(file)));
        }
    }

    private static String sanitizeText(String value, int maximumLength, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.strip().substring(0, Math.min(value.strip().length(), maximumLength));
    }

    private static double bounded(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("坐标必须是有限数字。");
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void validateGraph(DialogueGraph graph) {
        if (graph.nodes == null || graph.nodes.stream().anyMatch(node -> node == null || node.type == null)) {
            throw new IllegalArgumentException("对话图含有空节点或非法节点类型。");
        }
        graph.ensureEntryNode();
        if (graph.nodes.size() > 512) {
            throw new IllegalArgumentException("单个 NPC 最多允许 512 个对话节点。");
        }
        Set<Integer> identifiers = new HashSet<>();
        int entryCount = 0;
        for (var node : graph.nodes) {
            if (node.id <= 0 || !identifiers.add(node.id)) {
                throw new IllegalArgumentException("对话图节点 ID 必须为不重复的正整数。");
            }
            if (node.options == null || node.branches == null) {
                throw new IllegalArgumentException("对话图节点缺少选项或分支列表。");
            }
            node.text = sanitizeText(node.text, 1024, "");
            if (node.type == DialogueGraph.NodeType.ENTRY) {
                entryCount++;
                graph.rootNodeId = node.id;
            }
            if (node.options.size() > 32 || node.branches.size() > 32) {
                throw new IllegalArgumentException("单个节点最多允许 32 个选项或条件。");
            }
            if (node.options.stream().anyMatch(option -> option == null)
                    || node.branches.stream().anyMatch(branch -> branch == null || branch.condition == null)) {
                throw new IllegalArgumentException("对话图含有空选项或非法分支。");
            }
            node.options.forEach(option -> option.text = sanitizeText(option.text, 256, ""));
            node.branches.forEach(branch -> {
                branch.key = sanitizeText(branch.key, 256, "");
                if (branch.condition == DialogueGraph.ConditionType.PLAYER_TAG) {
                    branch.operator = "=";
                    branch.value = 1;
                } else if (branch.condition == DialogueGraph.ConditionType.PERMISSION) {
                    branch.operator = "≥";
                } else {
                    branch.operator = sanitizeText(branch.operator, 2, "≥");
                    if (!COMPARISON_OPERATORS.contains(branch.operator)) {
                        throw new IllegalArgumentException("对话分支包含不支持的比较运算符。");
                    }
                }
            });
        }
        if (entryCount != 1) {
            throw new IllegalArgumentException("对话图必须且只能包含一个入口节点。");
        }
        int entryId = graph.rootNodeId;
        for (DialogueGraph.NodeData node : graph.nodes) {
            switch (node.type) {
                case ENTRY, DIALOGUE -> validateTarget(node.id, node.nextNodeId, entryId, identifiers);
                case OPTION -> node.options.forEach(option ->
                        validateTarget(node.id, option.targetNodeId, entryId, identifiers)
                );
                case BRANCH -> {
                    node.branches.forEach(branch ->
                            validateTarget(node.id, branch.targetNodeId, entryId, identifiers)
                    );
                    validateTarget(node.id, node.defaultNodeId, entryId, identifiers);
                }
                case EXIT -> throw new IllegalArgumentException("对话图不能包含旧版出口节点。");
            }
        }
    }

    /**
     * Validates one graph edge without requiring every output to be connected.
     * 验证一条图边，但不强制每个输出都必须连接。
     */
    private static void validateTarget(int sourceId, int targetId, int entryId, Set<Integer> identifiers) {
        if (targetId < 0) {
            return;
        }
        if (!identifiers.contains(targetId)) {
            throw new IllegalArgumentException("对话图包含指向不存在节点的连接。");
        }
        if (targetId == entryId) {
            throw new IllegalArgumentException("入口节点不能作为连接目标。");
        }
        if (targetId == sourceId) {
            throw new IllegalArgumentException("对话节点不能连接到自身。");
        }
    }

    /**
     * Holds a decoded structure template and its resolved placement bounds.
     * 保存已解码的结构模板及其解析后的放置范围。
     *
     * @param template decoded structure template / 已解码结构模板
     * @param name exact structure name / 完整结构名
     * @param origin configured placement origin / 已配置放置原点
     * @param size structure dimensions / 结构尺寸
     * @param bounds inclusive structure bounds / 结构闭区间边界
     */
    private record PreparedStructure(
            StructureTemplate template,
            String name,
            BlockPos origin,
            Vec3i size,
            BoundingBox bounds
    ) {
    }

    /**
     * Tracks one dynamic dimension waiting to become visible in the server level map.
     * 跟踪一个等待出现在服务端世界表中的动态维度。
     */
    private static final class PendingEntry {
        private final UUID playerId;
        private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey;
        private int attempts;

        /**
         * Creates one pending entry.
         * 创建一个待进入操作。
         *
         * @param playerId owner UUID / 所有者 UUID
         * @param dimensionKey allocated dimension key / 已分配维度键
         * @param attempts elapsed retry ticks / 已经过的重试刻数
         */
        private PendingEntry(
                UUID playerId,
                net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey,
                int attempts
        ) {
            this.playerId = playerId;
            this.dimensionKey = dimensionKey;
            this.attempts = attempts;
        }
    }

    /**
     * Defines a map identifier request.
     * 定义地图标识请求。
     */
    public static final class MapIdRequest {
        public String mapId = "";
    }

    /**
     * Defines an NPC identifier request.
     * 定义 NPC 标识请求。
     */
    public static final class NpcIdRequest {
        public String npcId = "";
    }

    /**
     * Defines a skin cache request.
     * 定义皮肤缓存请求。
     */
    public static final class SkinRequest {
        public String name = "";
    }
}
