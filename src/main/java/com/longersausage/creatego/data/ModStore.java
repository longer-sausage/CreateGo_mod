/*
 * Persists server-authoritative map and NPC documents.
 * 持久化服务端权威的地图与 NPC 文档。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Stream;

/**
 * Owns one cached mod state per running server.
 * 为每个运行中的服务端维护一份状态缓存。
 */
public final class ModStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<MinecraftServer, ModStore> INSTANCES = new WeakHashMap<>();

    private final Path root;
    private final Path stateFile;
    private ModState state;

    private ModStore(MinecraftServer server) {
        root = FMLPaths.CONFIGDIR.get().resolve("creatego");
        stateFile = root.resolve("maps.json");
        state = readState();
    }

    /**
     * Returns the state owner for a server.
     * 返回指定服务端的状态管理器。
     *
     * @param server running server / 运行中的服务端
     * @return cached store / 已缓存的存储管理器
     */
    public static synchronized ModStore get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, ModStore::new);
    }

    /**
     * Returns the mutable server-authoritative state.
     * 返回可变的服务端权威状态。
     *
     * @return mod state / 模组状态
     */
    public ModState state() {
        return state;
    }

    /**
     * Returns a map-specific storage directory.
     * 返回地图专属存储目录。
     *
     * @param mapId normalized map identifier / 规范化地图标识
     * @return map directory / 地图目录
     */
    public Path mapDirectory(String mapId) {
        return root.resolve("maps").resolve(mapId);
    }

    /**
     * Returns the directory containing every structure owned by a map.
     * 返回包含地图全部结构的目录。
     *
     * @param mapId normalized map identifier / 规范化地图标识
     * @return structure directory / 结构目录
     */
    public Path structureDirectory(String mapId) {
        return mapDirectory(mapId).resolve("structures");
    }

    /**
     * Resolves one structure file while preventing directory traversal.
     * 解析一个结构文件，同时阻止目录穿越。
     *
     * @param mapId normalized map identifier / 规范化地图标识
     * @param structureName exact schematic filename / 完整蓝图文件名
     * @return compressed structure path / 压缩结构路径
     * @throws IllegalArgumentException when the name escapes the structure directory / 名称越出结构目录时抛出
     */
    public Path structureFile(String mapId, String structureName) {
        Path directory = structureDirectory(mapId).normalize();
        Path file = directory.resolve(structureName).normalize();
        if (!file.startsWith(directory) || !directory.equals(file.getParent())) {
            throw new IllegalArgumentException("结构名称不合法。");
        }
        return file;
    }

    /**
     * Returns the shared server skin directory.
     * 返回服务端共享皮肤目录。
     *
     * @return skin directory / 皮肤目录
     */
    public Path skinDirectory() {
        return root.resolve("skins");
    }

    /**
     * Deletes every persisted file owned by one normalized map identifier.
     * 删除一个规范化地图标识拥有的全部持久化文件。
     *
     * @param mapId normalized map identifier / 规范化地图标识
     * @throws IOException when a map file cannot be deleted / 地图文件无法删除时抛出
     */
    public void deleteMapDirectory(String mapId) throws IOException {
        Path directory = mapDirectory(mapId).normalize();
        Path mapsRoot = root.resolve("maps").normalize();
        if (!directory.startsWith(mapsRoot) || directory.equals(mapsRoot) || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Atomically replaces the state document as closely as the platform allows.
     * 在平台允许范围内尽量以原子方式替换状态文档。
     *
     * @throws IOException when the config folder cannot be written / 配置目录不可写时抛出
     */
    public synchronized void save() throws IOException {
        Files.createDirectories(root);
        Path temporary = stateFile.resolveSibling("maps.json.tmp");
        Files.writeString(temporary, GSON.toJson(state), StandardCharsets.UTF_8);
        Files.move(
                temporary,
                stateFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
        );
    }

    /**
     * Serializes the current state for client synchronization.
     * 序列化当前状态以供客户端同步。
     *
     * @return state JSON / 状态 JSON
     */
    public String toJson() {
        return GSON.toJson(state);
    }

    /**
     * Serializes an arbitrary object.
     * 序列化任意对象。
     *
     * @param value value to serialize / 要序列化的值
     * @return JSON document / JSON 文档
     */
    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    /**
     * Deserializes an object.
     * 反序列化对象。
     *
     * @param json JSON document / JSON 文档
     * @param type target class / 目标类型
     * @param <T> target type / 目标类型
     * @return parsed object / 解析后的对象
     */
    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    private ModState readState() {
        if (!Files.isRegularFile(stateFile)) {
            return new ModState();
        }
        try {
            ModState loaded = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), ModState.class);
            return loaded == null ? new ModState() : loaded;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("无法读取 CreateGo 地图数据：" + stateFile, exception);
        }
    }
}
