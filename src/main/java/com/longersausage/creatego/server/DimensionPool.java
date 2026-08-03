/*
 * Creates and deletes isolated terrain dimensions for player map sessions.
 * 为玩家地图会话创建并删除相互隔离的地形维度。
 *
 * Author: CreateGo
 * Date: 2026-07-31
 */

package com.longersausage.creatego.server;

import com.longersausage.creatego.CreateGo;
import com.longersausage.creatego.data.MapDefinition;
import com.longersausage.creatego.data.ModStore;
import com.longersausage.creatego.data.NpcData;
import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Owns one shared editing session per runtime dimension and never reuses dimension data.
 * 为每个运行时维度管理一个共享编辑会话，并且绝不复用维度数据。
 */
public final class DimensionPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionPool.class);
    private static final Map<MinecraftServer, Map<ResourceKey<Level>, Session>> SESSIONS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Binding>> BINDINGS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, PreparedEntry>> PREPARED_ENTRIES = new WeakHashMap<>();

    private DimensionPool() {
    }

    /**
     * Allocates a fresh terrain dimension and records the player's return destination.
     * 分配一个全新的地形维度，并记录玩家的返回目标。
     *
     * @param player entering player / 进入的玩家
     * @param mapId selected map identifier / 所选地图标识
     * @return allocated session / 已分配会话
     */
    public static synchronized Session allocate(ServerPlayer player, String mapId) {
        if (activeSession(player) != null) {
            throw new IllegalStateException("请先退出当前地图会话。");
        }
        ResourceLocation dimensionId = CreateGo.id(
                "cg_dim_" + player.getUUID().toString().replace("-", "") + "_" + UUID.randomUUID().toString().replace("-", "")
        );
        MapDefinition map = ModStore.get(player.server).state().maps.get(mapId);
        MapDefinition.TerrainType terrainType = terrainType(map);
        if (terrainType == MapDefinition.TerrainType.OVERWORLD
                || terrainType == MapDefinition.TerrainType.NETHER) {
            DynamicDimensionSeedOverride.begin(dimensionId, map == null ? 0L : map.terrainSeed);
        }
        ServerLevel level;
        try {
            level = DynamicDimensionRegistry.from(player.server).createDynamicDimension(
                    dimensionId,
                    createChunkGenerator(player.server, map),
                    createDimensionType(terrainType)
            );
        } finally {
            DynamicDimensionSeedOverride.clear();
        }
        if (level == null) {
            throw new IllegalStateException("无法创建隔离维度。");
        }
        Session session = new Session(
                player.getUUID(),
                mapId,
                ResourceKey.create(Registries.DIMENSION, dimensionId),
                captureReturnPoint(player),
                new LinkedHashMap<>()
        );
        sessions(player.server).put(session.dimensionKey(), session);
        LOGGER.info("为玩家 [{}] 分配隔离维度会话 [维度: {}, 地图: {}]", player.getScoreboardName(), dimensionId, mapId);
        return session;
    }

    /**
     * Returns the shared session for the dimension the player is physically inside.
     * 返回玩家实际所在维度的共享会话。
     *
     * @param player target player / 目标玩家
     * @return active session, or {@code null} / 活动会话，不存在时返回 {@code null}
     */
    public static synchronized Session activeSession(ServerPlayer player) {
        return sessions(player.server).get(player.serverLevel().dimension());
    }

    /**
     * Captures an exact return point before a player travels into a registered map dimension.
     * 在玩家前往已注册地图维度前捕获准确返回点。
     *
     * @param player traveling player / 正在跨维度的玩家
     * @param targetDimension requested target dimension / 请求前往的目标维度
     */
    public static synchronized void prepareEntry(
            ServerPlayer player,
            ResourceKey<Level> targetDimension
    ) {
        if (sessions(player.server).containsKey(targetDimension)) {
            Binding currentBinding = bindings(player.server).get(player.getUUID());
            ReturnPoint returnPoint = currentBinding == null
                    ? captureReturnPoint(player)
                    : currentBinding.returnPoint;
            preparedEntries(player.server).put(
                    player.getUUID(),
                    new PreparedEntry(targetDimension, returnPoint)
            );
        } else {
            preparedEntries(player.server).remove(player.getUUID());
        }
    }

    /**
     * Records a player's binding after the player has entered a map dimension.
     * 在玩家进入地图维度后记录其绑定。
     *
     * @param player entering player / 进入的玩家
     * @param fromDimension previous dimension used for a safe return destination / 用于安全返回的原维度
     * @return entered shared session, or {@code null} / 已进入的共享会话，不存在时返回 {@code null}
     */
    public static synchronized Session bindOnEntry(
            ServerPlayer player,
            ResourceKey<Level> fromDimension
    ) {
        Session session = sessions(player.server).get(player.serverLevel().dimension());
        if (session == null) {
            bindings(player.server).remove(player.getUUID());
            preparedEntries(player.server).remove(player.getUUID());
            return null;
        }
        PreparedEntry prepared = preparedEntries(player.server).remove(player.getUUID());
        Binding existing = bindings(player.server).get(player.getUUID());
        ReturnPoint returnPoint;
        if (prepared != null && prepared.dimensionKey.equals(session.dimensionKey)) {
            returnPoint = prepared.returnPoint;
        } else if (existing != null) {
            returnPoint = existing.returnPoint;
        } else if (session.playerId.equals(player.getUUID())) {
            returnPoint = session.returnPoint;
        } else {
            returnPoint = fallbackReturnPoint(player.server, fromDimension);
        }
        bindings(player.server).put(player.getUUID(), new Binding(session.dimensionKey, returnPoint));
        LOGGER.info(
                "玩家 [{}] 进入地图维度并绑定共享会话 [维度: {}, 地图: {}]",
                player.getScoreboardName(),
                session.dimensionKey.location(),
                session.mapId
        );
        return session;
    }

    /**
     * Removes one player's binding to the dimension being left.
     * 移除玩家对正在离开的维度的绑定。
     *
     * @param server running server / 运行中的服务端
     * @param playerId leaving player UUID / 离开玩家 UUID
     * @param dimensionKey dimension being left / 正在离开的维度
     */
    public static synchronized void unbind(
            MinecraftServer server,
            UUID playerId,
            ResourceKey<Level> dimensionKey
    ) {
        Binding binding = bindings(server).get(playerId);
        if (binding != null && binding.dimensionKey.equals(dimensionKey)) {
            bindings(server).remove(playerId);
        }
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
        return sessions(server).get(dimensionKey);
    }

    /**
     * Removes and returns one shared session without touching its runtime dimension.
     * 移除并返回一个共享会话，但不处理其运行时维度。
     *
     * @param server running server / 运行中的服务端
     * @param dimensionKey runtime dimension key / 运行时维度键
     * @return removed session, or {@code null} / 已移除会话，不存在时返回 {@code null}
     */
    public static synchronized Session detachDimension(
            MinecraftServer server,
            ResourceKey<Level> dimensionKey
    ) {
        Session session = sessions(server).get(dimensionKey);
        if (session != null) {
            removeSession(server, session);
        }
        return session;
    }

    /**
     * Returns every active shared session for a map without changing runtime state.
     * 返回地图的全部活动共享会话，但不修改运行时状态。
     *
     * @param server running server / 运行中的服务端
     * @param mapId map identifier / 地图标识
     * @return matching sessions / 匹配会话
     */
    public static synchronized List<Session> sessionsForMap(MinecraftServer server, String mapId) {
        return sessions(server).values().stream()
                .filter(session -> session.mapId.equals(mapId))
                .toList();
    }

    /**
     * Returns a player from the current map to their recorded or safe fallback destination.
     * 将玩家从当前地图送回其记录位置或安全回退位置。
     *
     * @param player player leaving the map / 离开地图的玩家
     */
    public static synchronized void returnPlayer(ServerPlayer player) {
        Binding binding = bindings(player.server).get(player.getUUID());
        ReturnPoint returnPoint = binding == null
                ? fallbackReturnPoint(player.server, Level.OVERWORLD)
                : binding.returnPoint;
        teleportToReturnPoint(player, returnPoint);
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
     * Deletes an empty registered map dimension and clears all bindings to it.
     * 删除已清空的注册地图维度，并清除其全部绑定。
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
        if (dimensionKey == null) {
            return;
        }
        Session session = sessionForDimension(server, dimensionKey);
        if (session == null) {
            return;
        }
        ServerLevel level = server.getLevel(dimensionKey);
        if (level == null) {
            removeSession(server, session);
            deleteDimension(server, dimensionKey);
            return;
        }
        long remainingPlayers = level.players().stream()
                .filter(p -> leavingPlayerId == null || !p.getUUID().equals(leavingPlayerId))
                .count();
        if (remainingPlayers == 0) {
            removeSession(server, session);
            LOGGER.info("隔离维度 [{}] 内已无任何残留玩家，执行彻底销毁", dimensionKey.location());
            deleteDimension(server, dimensionKey);
        }
    }

    /**
     * Ejects all remaining players from a map dimension to their individual return destinations.
     * 将地图维度中残留的所有玩家送回各自的返回位置。
     *
     * @param server running server / 运行中的服务端
     * @param dimensionKey target dimension key / 目标维度键
     */
    public static void ejectAllPlayers(MinecraftServer server, ResourceKey<Level> dimensionKey) {
        ServerLevel level = server.getLevel(dimensionKey);
        if (level == null) {
            clearBindings(server, dimensionKey);
            return;
        }
        List<ServerPlayer> players = new java.util.ArrayList<>(level.players());
        for (ServerPlayer player : players) {
            LOGGER.info("强制驱逐玩家 [{}] 离开隔离维度 [{}]", player.getScoreboardName(), dimensionKey.location());
            Binding binding = binding(server, player.getUUID(), dimensionKey);
            ReturnPoint returnPoint = binding == null
                    ? fallbackReturnPoint(server, Level.OVERWORLD)
                    : binding.returnPoint;
            teleportToReturnPoint(player, returnPoint);
        }
        clearBindings(server, dimensionKey);
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
     * Builds the selected void, superflat, overworld, or Nether chunk generator.
     * 构建所选的虚空、超平坦、主世界或下界区块生成器。
     *
     * @param server running server / 运行中的服务端
     * @param map map definition / 地图定义
     * @return configured chunk generator / 已配置区块生成器
     */
    private static ChunkGenerator createChunkGenerator(MinecraftServer server, MapDefinition map) {
        MapDefinition.TerrainType terrainType = terrainType(map);
        if (terrainType == MapDefinition.TerrainType.OVERWORLD
                || terrainType == MapDefinition.TerrainType.NETHER) {
            return createNoiseChunkGenerator(server, terrainType);
        }
        return createFlatChunkGenerator(server, map, terrainType == MapDefinition.TerrainType.FLAT);
    }

    /**
     * Builds a vanilla multi-noise generator for the overworld or Nether preset.
     * 为主世界或下界预设构建原版多噪声生成器。
     *
     * @param server running server / 运行中的服务端
     * @param terrainType overworld or Nether type / 主世界或下界类型
     * @return vanilla noise generator / 原版噪声生成器
     */
    private static NoiseBasedChunkGenerator createNoiseChunkGenerator(
            MinecraftServer server,
            MapDefinition.TerrainType terrainType
    ) {
        var access = server.registryAccess();
        var biomeParameters = access.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        var noiseSettings = access.lookupOrThrow(Registries.NOISE_SETTINGS);
        boolean nether = terrainType == MapDefinition.TerrainType.NETHER;
        return new NoiseBasedChunkGenerator(
                MultiNoiseBiomeSource.createFromPreset(biomeParameters.getOrThrow(
                        nether
                                ? MultiNoiseBiomeSourceParameterLists.NETHER
                                : MultiNoiseBiomeSourceParameterLists.OVERWORLD
                )),
                noiseSettings.getOrThrow(nether ? NoiseGeneratorSettings.NETHER : NoiseGeneratorSettings.OVERWORLD)
        );
    }

    /**
     * Builds an empty or layered flat generator using the void biome.
     * 使用虚空群系构建空白或分层的平坦生成器。
     *
     * @param server running server / 运行中的服务端
     * @param map map definition / 地图定义
     * @param includeLayers whether configured layers are included / 是否包含配置地层
     * @return flat generator / 平坦生成器
     */
    private static FlatLevelSource createFlatChunkGenerator(
            MinecraftServer server,
            MapDefinition map,
            boolean includeLayers
    ) {
        var access = server.registryAccess();
        var biomes = access.lookupOrThrow(Registries.BIOME);
        List<FlatLayerInfo> layers = new java.util.ArrayList<>();
        if (includeLayers && map != null && map.flatLayers != null) {
            for (MapDefinition.FlatLayer layer : map.flatLayers) {
                if (layer.blockId != null && !layer.blockId.isBlank() && layer.count > 0) {
                    ResourceLocation location = ResourceLocation.tryParse(layer.blockId);
                    if (location != null && BuiltInRegistries.BLOCK.containsKey(location)) {
                        Block block = BuiltInRegistries.BLOCK.get(location);
                        if (block != Blocks.AIR) {
                            layers.add(new FlatLayerInfo(layer.count, block));
                        }
                    }
                }
            }
        }
        FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(
                Optional.of(HolderSet.direct()),
                biomes.getOrThrow(Biomes.THE_VOID),
                List.of()
        ).withBiomeAndLayers(
                layers,
                Optional.of(HolderSet.direct()),
                biomes.getOrThrow(Biomes.THE_VOID)
        );
        settings.updateLayers();
        return new FlatLevelSource(settings);
    }

    /**
     * Creates an isolated dimension type matching the selected terrain's visual rules.
     * 创建符合所选地形视觉规则的隔离维度类型。
     *
     * @param terrainType selected terrain type / 所选地形类型
     * @return isolated dimension type / 隔离维度类型
     */
    private static DimensionType createDimensionType(MapDefinition.TerrainType terrainType) {
        if (terrainType == MapDefinition.TerrainType.NETHER) {
            return new DimensionType(
                    OptionalLong.of(18000L),
                    false,
                    true,
                    true,
                    false,
                    8.0D,
                    false,
                    true,
                    0,
                    256,
                    128,
                    BlockTags.INFINIBURN_NETHER,
                    BuiltinDimensionTypes.NETHER_EFFECTS,
                    0.1F,
                    new DimensionType.MonsterSettings(true, false, ConstantInt.of(7), 15)
            );
        }
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
     * Returns a non-null terrain type for current and legacy map definitions.
     * 为当前及旧版地图定义返回非空地形类型。
     *
     * @param map map definition / 地图定义
     * @return selected type, defaulting to superflat / 所选类型，默认超平坦
     */
    private static MapDefinition.TerrainType terrainType(MapDefinition map) {
        return map == null || map.terrainType == null ? MapDefinition.TerrainType.FLAT : map.terrainType;
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
    private static Map<ResourceKey<Level>, Session> sessions(MinecraftServer server) {
        return SESSIONS.computeIfAbsent(server, ignored -> new HashMap<>());
    }

    /**
     * Returns the mutable player-binding table for one server.
     * 返回指定服务端的可变玩家绑定表。
     *
     * @param server running server / 运行中的服务端
     * @return server binding table / 服务端绑定表
     */
    private static Map<UUID, Binding> bindings(MinecraftServer server) {
        return BINDINGS.computeIfAbsent(server, ignored -> new HashMap<>());
    }

    /**
     * Returns the pre-travel return-point table for one server.
     * 返回指定服务端的跨维度前返回点表。
     *
     * @param server running server / 运行中的服务端
     * @return prepared-entry table / 预备进入表
     */
    private static Map<UUID, PreparedEntry> preparedEntries(MinecraftServer server) {
        return PREPARED_ENTRIES.computeIfAbsent(server, ignored -> new HashMap<>());
    }

    /**
     * Finds a player's binding only when it targets the expected dimension.
     * 仅当玩家绑定指向预期维度时返回该绑定。
     *
     * @param server running server / 运行中的服务端
     * @param playerId player UUID / 玩家 UUID
     * @param dimensionKey expected dimension / 预期维度
     * @return matching binding, or {@code null} / 匹配绑定，不存在时返回 {@code null}
     */
    private static synchronized Binding binding(
            MinecraftServer server,
            UUID playerId,
            ResourceKey<Level> dimensionKey
    ) {
        Binding binding = bindings(server).get(playerId);
        return binding != null && binding.dimensionKey.equals(dimensionKey) ? binding : null;
    }

    /**
     * Removes a session and every stale player binding that points to its dimension.
     * 移除会话以及所有指向其维度的过期玩家绑定。
     *
     * @param server running server / 运行中的服务端
     * @param session removed session / 待移除会话
     */
    private static synchronized void removeSession(MinecraftServer server, Session session) {
        sessions(server).remove(session.dimensionKey);
        clearBindings(server, session.dimensionKey);
        preparedEntries(server).values().removeIf(entry -> entry.dimensionKey.equals(session.dimensionKey));
    }

    /**
     * Clears every binding targeting one dimension.
     * 清除所有指向指定维度的绑定。
     *
     * @param server running server / 运行中的服务端
     * @param dimensionKey target dimension / 目标维度
     */
    private static synchronized void clearBindings(
            MinecraftServer server,
            ResourceKey<Level> dimensionKey
    ) {
        bindings(server).values().removeIf(binding -> binding.dimensionKey.equals(dimensionKey));
    }

    /**
     * Builds a safe return point at the requested dimension's shared spawn.
     * 在请求维度的共享出生点构建安全返回点。
     *
     * @param server running server / 运行中的服务端
     * @param requestedDimension preferred return dimension / 首选返回维度
     * @return safe return point / 安全返回点
     */
    private static ReturnPoint fallbackReturnPoint(
            MinecraftServer server,
            ResourceKey<Level> requestedDimension
    ) {
        ServerLevel level = server.getLevel(requestedDimension);
        if (level == null || sessionForDimension(server, requestedDimension) != null) {
            level = server.overworld();
        }
        BlockPos spawn = level.getSharedSpawnPos();
        return new ReturnPoint(
                level.dimension(),
                spawn.getX() + 0.5D,
                spawn.getY(),
                spawn.getZ() + 0.5D,
                level.getSharedSpawnAngle(),
                0.0F
        );
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

    /**
     * Stores one player's dimension binding and safe return destination.
     * 保存一个玩家的维度绑定及安全返回位置。
     *
     * @param dimensionKey bound map dimension / 已绑定地图维度
     * @param returnPoint destination used by explicit exit / 主动退出时使用的返回位置
     */
    private record Binding(
            ResourceKey<Level> dimensionKey,
            ReturnPoint returnPoint
    ) {
    }

    /**
     * Stores an exact return point captured before cross-dimension travel completes.
     * 保存跨维度传送完成前捕获的准确返回点。
     *
     * @param dimensionKey intended map dimension / 目标地图维度
     * @param returnPoint captured source position / 已捕获来源位置
     */
    private record PreparedEntry(
            ResourceKey<Level> dimensionKey,
            ReturnPoint returnPoint
    ) {
    }
}
