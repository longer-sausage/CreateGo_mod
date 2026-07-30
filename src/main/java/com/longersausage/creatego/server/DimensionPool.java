/*
 * Creates and deletes isolated empty dimensions for player map sessions.
 * 为玩家地图会话创建并删除相互隔离的空白维度。
 *
 * Author: CreateGo
 * Date: 2026-07-31
 */

package com.longersausage.creatego.server;

import com.longersausage.creatego.CreateGo;
import com.longersausage.creatego.data.NpcData;
import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Owns one fresh runtime dimension per active session and never reuses dimension data.
 * 为每个活动会话管理一个全新的运行时维度，并且绝不复用维度数据。
 */
public final class DimensionPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionPool.class);
    private static final Map<MinecraftServer, Map<UUID, Session>> SESSIONS = new WeakHashMap<>();

    private DimensionPool() {
    }

    /**
     * Allocates a fresh empty dimension and records the player's return destination.
     * 分配一个全新的空白维度，并记录玩家的返回目标。
     *
     * @param player entering player / 进入的玩家
     * @param mapId selected map identifier / 所选地图标识
     * @return allocated session / 已分配会话
     */
    public static synchronized Session allocate(ServerPlayer player, String mapId) {
        if (sessions(player.server).containsKey(player.getUUID())) {
            throw new IllegalStateException("请先退出当前地图会话。");
        }
        ResourceLocation dimensionId = CreateGo.id(
                "cg_dim_" + player.getUUID().toString().replace("-", "") + "_" + UUID.randomUUID().toString().replace("-", "")
        );
        ServerLevel level = DynamicDimensionRegistry.from(player.server).createDynamicDimension(
                dimensionId,
                createVoidGenerator(player.server),
                createDimensionType()
        );
        if (level == null) {
            throw new IllegalStateException("无法创建空白维度。");
        }
        Session session = new Session(
                player.getUUID(),
                mapId,
                ResourceKey.create(Registries.DIMENSION, dimensionId),
                captureReturnPoint(player),
                new LinkedHashMap<>()
        );
        sessions(player.server).put(player.getUUID(), session);
        LOGGER.info("为玩家 [{}] 分配隔离维度会话 [维度: {}, 地图: {}]", player.getScoreboardName(), dimensionId, mapId);
        return session;
    }

    /**
     * Returns the allocated session even while its dimension is waiting for registration.
     * 返回已分配会话，即使其维度仍在等待注册。
     *
     * @param player target player / 目标玩家
     * @return session, or {@code null} / 会话，不存在时返回 {@code null}
     */
    public static synchronized Session session(ServerPlayer player) {
        return sessions(player.server).get(player.getUUID());
    }

    /**
     * Returns an allocated session by server and player UUID.
     * 按服务端与玩家 UUID 返回已分配会话。
     *
     * @param server running server / 运行中的服务端
     * @param playerId owner UUID / 所有者 UUID
     * @return session, or {@code null} / 会话，不存在时返回 {@code null}
     */
    public static synchronized Session session(MinecraftServer server, UUID playerId) {
        return sessions(server).get(playerId);
    }

    /**
     * Returns the session only when the player is physically inside its allocated dimension.
     * 仅当玩家实际位于所分配维度内时返回会话。
     *
     * @param player target player / 目标玩家
     * @return active session, or {@code null} / 活动会话，不存在时返回 {@code null}
     */
    public static synchronized Session activeSession(ServerPlayer player) {
        Session session = sessions(player.server).get(player.getUUID());
        return session != null && player.serverLevel().dimension().equals(session.dimensionKey) ? session : null;
    }

    /**
     * Returns the map bound to the player's active isolated dimension.
     * 返回玩家活动隔离维度所绑定的地图。
     *
     * @param player target player / 目标玩家
     * @return bound map identifier, or an empty string / 已绑定地图标识，无绑定时为空
     */
    public static String boundMapId(ServerPlayer player) {
        Session session = activeSession(player);
        return session == null ? "" : session.mapId;
    }

    /**
     * Tests whether the player is actively bound to a specific map.
     * 检查玩家是否正与指定地图绑定。
     *
     * @param player target player / 目标玩家
     * @param mapId expected map identifier / 预期地图标识
     * @return whether the active binding matches / 活动绑定是否匹配
     */
    public static boolean isBoundTo(ServerPlayer player, String mapId) {
        return mapId != null && mapId.equals(boundMapId(player));
    }

    /**
     * Finds the session allocated to a dynamic dimension key.
     * 查找分配给动态维度键的会话。
     *
     * @param server running server / 运行中的服务端
     * @param dimensionKey dynamic dimension key / 动态维度键
     * @return matching session, or {@code null} / 匹配会话，不存在时返回 {@code null}
     */
    public static synchronized Session sessionForDimension(
            MinecraftServer server,
            ResourceKey<Level> dimensionKey
    ) {
        return sessions(server).values().stream()
                .filter(session -> session.dimensionKey.equals(dimensionKey))
                .findFirst()
                .orElse(null);
    }

    /**
     * Removes and returns one player's session without touching its runtime dimension.
     * 移除并返回一个玩家的会话，但不处理其运行时维度。
     *
     * @param server running server / 运行中的服务端
     * @param playerId player UUID / 玩家 UUID
     * @return removed session, or {@code null} / 已移除会话，不存在时返回 {@code null}
     */
    public static synchronized Session detach(MinecraftServer server, UUID playerId) {
        return sessions(server).remove(playerId);
    }

    /**
     * Removes and returns every active session for a map.
     * 移除并返回指定地图的全部活动会话。
     *
     * @param server running server / 运行中的服务端
     * @param mapId map identifier / 地图标识
     * @return detached sessions / 已移除会话
     */
    public static synchronized List<Session> detachMapSessions(MinecraftServer server, String mapId) {
        List<Session> removed = sessions(server).values().stream()
                .filter(session -> session.mapId.equals(mapId))
                .toList();
        removed.forEach(session -> sessions(server).remove(session.playerId));
        return removed;
    }

    /**
     * Teleports an online session owner to the position recorded before allocation.
     * 将在线会话所有者传送到分配前记录的位置。
     *
     * @param server running server / 运行中的服务端
     * @param session detached session / 已移除会话
     */
    public static void returnOwner(MinecraftServer server, Session session) {
        ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
        if (player != null) {
            teleportToReturnPoint(player, session.returnPoint);
        }
    }

    /**
     * Permanently deletes one runtime dimension on the main thread queue.
     * 在主线程任务队列中永久删除一个运行时维度。
     *
     * @param server running server / 运行中的服务端
     * @param dimensionKey dynamic dimension key / 动态维度键
     */
    public static void deleteDimension(MinecraftServer server, ResourceKey<Level> dimensionKey) {
        LOGGER.info("删除隔离维度 [维度: {}]", dimensionKey.location());
        Runnable deleteTask = () -> {
            ServerLevel level = server.getLevel(dimensionKey);
            if (level != null) {
                disableSablePhysics(level);
            }
            DynamicDimensionRegistry.from(server).deleteDynamicDimension(dimensionKey.location(), null);
        };
        if (server.isSameThread()) {
            deleteTask.run();
        } else {
            server.execute(deleteTask);
        }
    }

    /**
     * Permanently deletes one detached runtime dimension for a session.
     * 为会话永久删除一个已移除的运行时维度。
     *
     * @param server running server / 运行中的服务端
     * @param session detached session / 已移除会话
     */
    public static void deleteDimension(MinecraftServer server, Session session) {
        deleteDimension(server, session.dimensionKey());
    }

    /**
     * Checks if a dynamic dimension has 0 remaining players and deletes it and unbinds session if empty.
     * 检查动态维度是否已无玩家停留；若已空则解绑会话并彻底销毁维度。
     *
     * @param server running server / 运行中的服务端
     * @param dimensionKey target dimension key / 目标维度键
     * @param leavingPlayerId UUID of the player currently leaving / 正在离开的玩家 UUID（可为 null）
     */
    public static void checkAndCleanupDimension(
            MinecraftServer server,
            ResourceKey<Level> dimensionKey,
            UUID leavingPlayerId
    ) {
        if (dimensionKey == null || !dimensionKey.location().getNamespace().equals(CreateGo.MOD_ID)) {
            return;
        }
        ServerLevel level = server.getLevel(dimensionKey);
        if (level == null) {
            Session session = sessionForDimension(server, dimensionKey);
            if (session != null) {
                detach(server, session.playerId());
            }
            return;
        }
        long remainingPlayers = level.players().stream()
                .filter(p -> leavingPlayerId == null || !p.getUUID().equals(leavingPlayerId))
                .count();
        if (remainingPlayers == 0) {
            Session session = sessionForDimension(server, dimensionKey);
            if (session != null) {
                detach(server, session.playerId());
                LOGGER.info("隔离维度 [{}] 已空，解绑所有者 [{}] 的地图会话", dimensionKey.location(), session.playerId());
            }
            LOGGER.info("隔离维度 [{}] 内已无任何残留玩家，执行彻底销毁", dimensionKey.location());
            deleteDimension(server, dimensionKey);
        }
    }

    /**
     * Ejects all remaining players from a dynamic dimension back to the overworld spawn.
     * 将动态维度中残留的所有玩家强制驱逐并送回主世界出生点。
     *
     * @param server running server / 运行中的服务端
     * @param dimensionKey target dimension key / 目标维度键
     */
    public static void ejectAllPlayers(MinecraftServer server, ResourceKey<Level> dimensionKey) {
        ServerLevel level = server.getLevel(dimensionKey);
        if (level == null || level.players().isEmpty()) {
            return;
        }
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        List<ServerPlayer> players = new java.util.ArrayList<>(level.players());
        for (ServerPlayer player : players) {
            LOGGER.info("强制驱逐玩家 [{}] 离开隔离维度 [{}]", player.getScoreboardName(), dimensionKey.location());
            player.teleportTo(
                    overworld,
                    spawn.getX() + 0.5D,
                    spawn.getY(),
                    spawn.getZ() + 0.5D,
                    java.util.Set.of(),
                    overworld.getSharedSpawnAngle(),
                    0.0F
            );
        }
    }

    /**
     * Safely pauses Sable physics system on a level via reflection if Sable is present.
     * 若加载了 Sable 模组，通过反射安全暂停该维度的 Sable 物理系统。
     *
     * @param level target server level / 目标服务端世界
     */
    public static void disableSablePhysics(ServerLevel level) {
        if (level == null) {
            return;
        }
        clearSableSubLevels(level);
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> systemClass = Class.forName("dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem");
                java.lang.reflect.Method getMethod = systemClass.getMethod("get", net.minecraft.world.level.Level.class);
                Object system = getMethod.invoke(null, level);
                if (system != null) {
                    java.lang.reflect.Method setPausedMethod = systemClass.getMethod("setPaused", boolean.class);
                    setPausedMethod.invoke(system, true);
                    LOGGER.info("已成功暂停维度 [{}] 的 Sable 物理管线。", level.dimension().location());
                }
            }
        } catch (Throwable throwable) {
            LOGGER.warn("未能暂停 Sable 物理管线：{}", throwable.getMessage());
        }
    }

    /**
     * Clears all Sable sub-levels, tickets, and physics chunk maps for a level to prevent NullPointerException on teardown.
     * 清理维度的所有 Sable 物理结构、区块票据及管线状态，彻底根除维度清理与卸载过程中的空指针崩溃。
     *
     * @param level target server level / 目标服务端世界
     */
    public static void clearSableSubLevels(ServerLevel level) {
        if (level == null) {
            return;
        }
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> holderClass = Class.forName("dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder");
                if (holderClass.isInstance(level)) {
                    java.lang.reflect.Method getContainerMethod = holderClass.getMethod("sable$getPlotContainer");
                    Object container = getContainerMethod.invoke(level);
                    if (container != null) {
                        Class<?> containerClass = container.getClass();
                        // 1. 清理 PhysicsChunkTicketManager 中的 physicsChunks 与 forcedInhabitedChunks
                        try {
                            java.lang.reflect.Method physicsSystemMethod = containerClass.getMethod("physicsSystem");
                            Object physicsSystem = physicsSystemMethod.invoke(container);
                            if (physicsSystem != null) {
                                java.lang.reflect.Field ticketManagerField = physicsSystem.getClass().getDeclaredField("ticketManager");
                                ticketManagerField.setAccessible(true);
                                Object ticketManager = ticketManagerField.get(physicsSystem);
                                if (ticketManager != null) {
                                    java.lang.reflect.Field physicsChunksField = ticketManager.getClass().getDeclaredField("physicsChunks");
                                    physicsChunksField.setAccessible(true);
                                    Map<?, ?> physicsChunks = (Map<?, ?>) physicsChunksField.get(ticketManager);
                                    if (physicsChunks != null) {
                                        physicsChunks.clear();
                                    }
                                    java.lang.reflect.Field forcedField = ticketManager.getClass().getDeclaredField("forcedInhabitedChunks");
                                    forcedField.setAccessible(true);
                                    Map<?, ?> forcedMap = (Map<?, ?>) forcedField.get(ticketManager);
                                    if (forcedMap != null) {
                                        forcedMap.clear();
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            LOGGER.debug("清理 Sable 物理票据失败：{}", t.getMessage());
                        }
                        // 2. 移除容器中的所有 SubLevel
                        java.lang.reflect.Method getAllSubLevelsMethod = containerClass.getMethod("getAllSubLevels");
                        Object subLevelsObj = getAllSubLevelsMethod.invoke(container);
                        if (subLevelsObj instanceof List<?> list && !list.isEmpty()) {
                            List<Object> subLevels = new java.util.ArrayList<>(list);
                            Class<?> reasonClass = Class.forName("dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason");
                            Object removedReason = null;
                            for (Object constant : reasonClass.getEnumConstants()) {
                                if ("REMOVED".equals(constant.toString())) {
                                    removedReason = constant;
                                    break;
                                }
                            }
                            Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
                            java.lang.reflect.Method removeSubLevelMethod = null;
                            try {
                                removeSubLevelMethod = containerClass.getMethod("removeSubLevel", subLevelClass, reasonClass);
                            } catch (NoSuchMethodException e) {
                                for (java.lang.reflect.Method m : containerClass.getMethods()) {
                                    if ("removeSubLevel".equals(m.getName()) && m.getParameterCount() == 2) {
                                        removeSubLevelMethod = m;
                                        break;
                                    }
                                }
                            }
                            if (removeSubLevelMethod != null && removedReason != null) {
                                for (Object subLevel : subLevels) {
                                    try {
                                        removeSubLevelMethod.invoke(container, subLevel, removedReason);
                                    } catch (Throwable t) {
                                        LOGGER.warn("移除 Sable 物理结构失败：{}", t.getMessage());
                                    }
                                }
                                LOGGER.info("已安全清理维度 [{}] 中的 {} 个 Sable 物理结构与票据。", level.dimension().location(), subLevels.size());
                            }
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            LOGGER.debug("未能清理 Sable 物理结构：{}", throwable.getMessage());
        }
    }

    /**
     * Safely initializes Sable physics system on a dynamically created level via reflection if Sable is present.
     * 若加载了 Sable 模组，通过反射为动态创建的维度初始化 Sable 物理场景（以便在隔离维度中使用物理结构）。
     *
     * @param level target server level / 目标服务端世界
     */
    public static void enableSablePhysics(ServerLevel level) {
        if (level == null) {
            return;
        }
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> systemClass = Class.forName("dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem");
                java.lang.reflect.Method getMethod = systemClass.getMethod("get", net.minecraft.world.level.Level.class);
                Object system = getMethod.invoke(null, level);
                if (system != null) {
                    java.lang.reflect.Method initMethod = systemClass.getMethod("initialize");
                    initMethod.invoke(system);
                    java.lang.reflect.Method setPausedMethod = systemClass.getMethod("setPaused", boolean.class);
                    setPausedMethod.invoke(system, false);
                    LOGGER.info("已成功为隔离维度 [{}] 初始化并启用 Sable 物理场景。", level.dimension().location());
                }
            }
        } catch (Throwable throwable) {
            LOGGER.warn("未能初始化 Sable 物理场景：{}", throwable.getMessage());
        }
    }

    /**
     * Builds a flat generator with no layers, features, lakes, or structures.
     * 构建一个没有地层、特征、湖泊或结构的平坦生成器。
     *
     * @param server running server / 运行中的服务端
     * @return completely empty chunk generator / 完全空白的区块生成器
     */
    private static FlatLevelSource createVoidGenerator(MinecraftServer server) {
        var access = server.registryAccess();
        var biomes = access.lookupOrThrow(Registries.BIOME);
        FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(
                Optional.of(HolderSet.direct()),
                biomes.getOrThrow(Biomes.THE_VOID),
                List.of()
        );
        settings.updateLayers();
        return new FlatLevelSource(settings);
    }

    /**
     * Creates a bright fixed-time dimension whose lowest valid build coordinate is Y=0.
     * 创建一个明亮、固定时间且最低有效建筑坐标为 Y=0 的维度类型。
     *
     * @return isolated dimension type / 隔离维度类型
     */
    private static DimensionType createDimensionType() {
        return new DimensionType(
                OptionalLong.of(6000L),
                true,
                false,
                false,
                false,
                1.0D,
                false,
                false,
                0,
                384,
                384,
                BlockTags.INFINIBURN_OVERWORLD,
                BuiltinDimensionTypes.OVERWORLD_EFFECTS,
                1.0F,
                new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0)
        );
    }

    /**
     * Captures the player's exact current position as the session return point.
     * 将玩家当前精确位置记录为会话返回点。
     *
     * @param player entering player / 进入的玩家
     * @return return point / 返回点
     */
    private static ReturnPoint captureReturnPoint(ServerPlayer player) {
        return new ReturnPoint(
                player.serverLevel().dimension(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot()
        );
    }

    /**
     * Teleports a player to a recorded point, falling back to the overworld spawn.
     * 将玩家传送到记录位置；不可用时回退到主世界出生点。
     *
     * @param player target player / 目标玩家
     * @param point recorded return point / 已记录返回点
     */
    private static void teleportToReturnPoint(ServerPlayer player, ReturnPoint point) {
        ServerLevel level = player.server.getLevel(point.dimension);
        if (level != null) {
            player.teleportTo(level, point.x, point.y, point.z, java.util.Set.of(), point.yaw, point.pitch);
            return;
        }
        ServerLevel overworld = player.server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(
                overworld,
                spawn.getX() + 0.5D,
                spawn.getY(),
                spawn.getZ() + 0.5D,
                java.util.Set.of(),
                overworld.getSharedSpawnAngle(),
                0.0F
        );
    }

    /**
     * Returns the mutable session table for one server.
     * 返回指定服务端的可变会话表。
     *
     * @param server running server / 运行中的服务端
     * @return server session table / 服务端会话表
     */
    private static Map<UUID, Session> sessions(MinecraftServer server) {
        return SESSIONS.computeIfAbsent(server, ignored -> new HashMap<>());
    }

    /**
     * Describes one isolated map dimension.
     * 描述一个隔离的地图维度。
     *
     * @param playerId owner UUID / 所有者 UUID
     * @param mapId bound map identifier / 已绑定地图标识
     * @param dimensionKey runtime dimension key / 运行时维度键
     * @param returnPoint entry return point / 进入前返回点
     * @param npcDrafts session-only NPC drafts / 仅会话内存在的 NPC 草稿
     */
    public record Session(
            UUID playerId,
            String mapId,
            ResourceKey<Level> dimensionKey,
            ReturnPoint returnPoint,
            Map<UUID, NpcData> npcDrafts
    ) {
    }

    /**
     * Describes an immutable cross-dimension return destination.
     * 描述不可变的跨维度返回目标。
     *
     * @param dimension target dimension / 目标维度
     * @param x target X / 目标 X
     * @param y target Y / 目标 Y
     * @param z target Z / 目标 Z
     * @param yaw target yaw / 目标水平朝向
     * @param pitch target pitch / 目标俯仰角
     */
    public record ReturnPoint(
            ResourceKey<Level> dimension,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
    }
}
