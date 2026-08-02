/*
 * Persists every Sable physical structure owned by one CreateGo map.
 * 持久化一张 CreateGo 地图拥有的全部 Sable 物理结构。
 *
 * Author: CreateGo
 * Date: 2026-08-02
 */

package com.longersausage.creatego.server;

import com.longersausage.creatego.CreateGo;
import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Captures and restores complete Sable sub-level tags without retaining a runtime dimension.
 * 捕获并恢复完整的 Sable 子世界标签，无需保留运行时维度。
 */
public final class MapPhysicalStructureStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(MapPhysicalStructureStorage.class);
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_STRUCTURE_COUNT = 4096;
    private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;
    private static final String VERSION_TAG = "format_version";
    private static final String STRUCTURES_TAG = "structures";
    private static final SubLevelLoadingTicketType<Boolean> MAP_SESSION_TICKET =
            SubLevelLoadingTicketType.create(
                    ResourceLocation.fromNamespaceAndPath(CreateGo.MOD_ID, "map_physical_structure"),
                    Codec.BOOL
            );

    private MapPhysicalStructureStorage() {
    }

    /**
     * Force-loads every discovered structure for the lifetime of its isolated editing session.
     * 在隔离编辑会话存续期间强制加载每个已发现的物理结构。
     *
     * @param level active isolated map level / 活动隔离地图世界
     */
    public static void keepLoaded(ServerLevel level) {
        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        for (ServerSubLevel structure : new ArrayList<>(container.getAllSubLevels())) {
            if (structure != null && !structure.isRemoved()) {
                container.addForceLoadTicket(structure, MAP_SESSION_TICKET, Boolean.TRUE);
            }
        }
    }

    /**
     * Serializes every live Sable sub-level and atomically replaces the map snapshot.
     * 序列化全部实时 Sable 子世界，并原子替换地图快照。
     *
     * @param level source map level / 源地图世界
     * @param target destination snapshot path / 目标快照路径
     * @return saved structure count / 已保存结构数量
     * @throws IOException when capture or persistence fails / 捕获或持久化失败时抛出
     */
    public static int save(ServerLevel level, Path target) throws IOException {
        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        List<ServerSubLevel> structures = new ArrayList<>(container.getAllSubLevels()).stream()
                .filter(structure -> structure != null && !structure.isRemoved())
                .toList();
        if (structures.size() > MAX_STRUCTURE_COUNT) {
            throw new IOException("Sable 物理结构数量超过上限：" + structures.size());
        }

        CompoundTag root = new CompoundTag();
        root.putInt(VERSION_TAG, FORMAT_VERSION);
        ListTag serializedStructures = new ListTag();
        try {
            for (ServerSubLevel structure : structures) {
                // Preserve the complete Sable tag, including blocks, actors, motion, metadata, and dependencies.
                // 保留完整 Sable 标签，包括方块、执行器、运动状态、元数据与依赖关系。
                List<UUID> dependencies = SubLevelHelper.getLoadingDependencyChain(structure).stream()
                        .map(ServerSubLevel::getUniqueId)
                        .toList();
                serializedStructures.add(SubLevelSerializer.toData(structure, dependencies).fullTag().copy());
            }
        } catch (RuntimeException exception) {
            LOGGER.error("捕获地图 Sable 物理结构失败 [维度: {}]。", level.dimension().location(), exception);
            throw new IOException("无法捕获地图中的 Sable 物理结构。", exception);
        }
        root.put(STRUCTURES_TAG, serializedStructures);
        writeAtomically(target, root);
        return structures.size();
    }

    /**
     * Restores every persisted Sable sub-level with its original UUID, plot, pose, and motion.
     * 使用原 UUID、内部区块、姿态与运动状态恢复全部 Sable 子世界。
     *
     * @param level target map level / 目标地图世界
     * @param source snapshot path / 快照路径
     * @return restored structure count / 已恢复结构数量
     * @throws IOException when validation or restoration fails / 校验或恢复失败时抛出
     */
    public static int load(ServerLevel level, Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            return 0;
        }
        CompoundTag root = NbtIo.readCompressed(source, NbtAccounter.create(MAX_UNCOMPRESSED_BYTES));
        if (root.getInt(VERSION_TAG) != FORMAT_VERSION) {
            throw new IOException("不支持的 Sable 物理结构快照版本：" + root.getInt(VERSION_TAG));
        }
        ListTag serializedStructures = root.getList(STRUCTURES_TAG, CompoundTag.TAG_COMPOUND);
        if (serializedStructures.size() > MAX_STRUCTURE_COUNT) {
            throw new IOException("Sable 物理结构快照数量超过上限：" + serializedStructures.size());
        }

        List<SubLevelData> structures = new ArrayList<>(serializedStructures.size());
        Set<UUID> structureIds = new HashSet<>();
        try {
            for (int index = 0; index < serializedStructures.size(); index++) {
                SubLevelData data = SubLevelSerializer.fromData(serializedStructures.getCompound(index));
                if (!structureIds.add(data.uuid())) {
                    throw new IOException("Sable 物理结构快照包含重复 UUID：" + data.uuid());
                }
                structures.add(data);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Sable 物理结构快照格式无效。", exception);
        }

        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        List<ServerSubLevel> restored = new ArrayList<>(structures.size());
        try {
            for (SubLevelData data : structures) {
                ServerSubLevel structure = SubLevelSerializer.fullyLoad(level, data);
                if (structure == null) {
                    throw new IOException("Sable 物理结构恢复失败：" + data.uuid());
                }
                restored.add(structure);
            }
            return restored.size();
        } catch (Exception exception) {
            // Remove partial results so a failed load never leaves a half-populated editing dimension.
            // 移除不完整结果，避免加载失败后留下半填充的编辑维度。
            for (int index = restored.size() - 1; index >= 0; index--) {
                try {
                    container.removeSubLevel(restored.get(index), SubLevelRemovalReason.REMOVED);
                } catch (RuntimeException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            LOGGER.error("恢复地图 Sable 物理结构失败 [维度: {}, 文件: {}]。",
                    level.dimension().location(), source, exception);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("无法恢复地图中的 Sable 物理结构。", exception);
        }
    }

    /**
     * Writes compressed NBT through a sibling temporary file before publication.
     * 通过同目录临时文件写入压缩 NBT，再发布最终文件。
     *
     * @param target final snapshot path / 最终快照路径
     * @param root complete snapshot tag / 完整快照标签
     * @throws IOException when the snapshot cannot be written / 快照无法写入时抛出
     */
    private static void writeAtomically(Path target, CompoundTag root) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            NbtIo.writeCompressed(root, temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
