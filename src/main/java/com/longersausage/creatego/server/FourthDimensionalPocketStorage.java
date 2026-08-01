/*
 * Persists Sable structures stored by fourth-dimensional pockets.
 * 持久化四次元口袋所存储的 Sable 结构。
 *
 * Author: CreateGo
 * Date: 2026-08-01
 */

package com.longersausage.creatego.server;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * Serializes pocket contents outside the item stack and restores them through Sable.
 * 在物品堆叠之外序列化口袋内容，并通过 Sable 恢复结构。
 */
public final class FourthDimensionalPocketStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(FourthDimensionalPocketStorage.class);
    private static final String POCKET_TAG = "CreateGoFourthDimensionalPocket";
    private static final String ID_TAG = "Id";
    private static final String NAME_TAG = "Name";
    private static final String STORAGE_DIRECTORY = "fourth_dimensional_pockets";
    private static final double PLACEMENT_GAP = 0.01D;

    private FourthDimensionalPocketStorage() {
    }

    /**
     * Describes the outcome of a pocket interaction.
     * 描述口袋交互的处理结果。
     */
    public enum Result {
        SUCCESS,
        NOT_APPLICABLE,
        FAILURE
    }

    /**
     * Checks whether an item stack references stored structure data.
     * 检查物品堆叠是否引用了已存储的结构数据。
     *
     * @param stack pocket item stack / 口袋物品堆叠
     * @return whether the pocket is filled / 口袋是否已存储结构
     */
    public static boolean isFilled(ItemStack stack) {
        return readPocketTag(stack).hasUUID(ID_TAG);
    }

    /**
     * Returns the stored structure name for tooltip rendering.
     * 返回用于渲染提示的已存储结构名称。
     *
     * @param stack pocket item stack / 口袋物品堆叠
     * @return stored name component, or null for an empty pocket / 已存储名称组件，空口袋返回 null
     */
    public static Component getStoredName(ItemStack stack) {
        CompoundTag pocketTag = readPocketTag(stack);
        if (!pocketTag.hasUUID(ID_TAG)) {
            return null;
        }
        String name = pocketTag.getString(NAME_TAG);
        return name.isBlank()
                ? Component.translatable("tooltip.creatego.fourth_dimensional_pocket.unnamed")
                : Component.literal(name);
    }

    /**
     * Stores the Sable sub-level containing the clicked plot position.
     * 存储包含所点击区块坐标的 Sable 子世界。
     *
     * @param player interacting player / 交互玩家
     * @param stack pocket item stack / 口袋物品堆叠
     * @param clickedPos clicked plot position / 点击的区块坐标
     * @return interaction outcome / 交互结果
     */
    public static Result store(ServerPlayer player, ItemStack stack, BlockPos clickedPos) {
        ServerLevel level = player.serverLevel();
        SubLevel containing = Sable.HELPER.getContaining(level, clickedPos);
        if (!(containing instanceof ServerSubLevel subLevel)) {
            return Result.NOT_APPLICABLE;
        }

        UUID pocketId = UUID.randomUUID();
        Path dataPath = getDataPath(player.getServer(), pocketId);
        try {
            SubLevelData data = SubLevelSerializer.toData(subLevel, List.of());
            writeAtomically(dataPath, data.fullTag());
            ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
            writePocketTag(stack, pocketId, subLevel.getName());
            LOGGER.info("玩家 [{}] 已将 Sable 结构 [{}] 存入四次元口袋。",
                    player.getScoreboardName(), subLevel.getUniqueId());
            return Result.SUCCESS;
        } catch (Exception exception) {
            deleteQuietly(dataPath);
            LOGGER.error("玩家 [{}] 存储 Sable 结构 [{}] 失败。",
                    player.getScoreboardName(), subLevel.getUniqueId(), exception);
            return Result.FAILURE;
        }
    }

    /**
     * Restores a stored structure beside the clicked block face.
     * 在所点击方块表面旁恢复已存储结构。
     *
     * @param player interacting player / 交互玩家
     * @param stack pocket item stack / 口袋物品堆叠
     * @param clickedPos clicked block position / 点击的方块坐标
     * @param clickedFace clicked block face / 点击的方块表面
     * @return interaction outcome / 交互结果
     */
    public static Result release(ServerPlayer player, ItemStack stack, BlockPos clickedPos, Direction clickedFace) {
        UUID pocketId = getPocketId(stack);
        if (pocketId == null) {
            return Result.NOT_APPLICABLE;
        }

        Path dataPath = getDataPath(player.getServer(), pocketId);
        if (!Files.isRegularFile(dataPath)) {
            LOGGER.warn("玩家 [{}] 的四次元口袋数据不存在：[存储 ID: {}]。",
                    player.getScoreboardName(), pocketId);
            return Result.FAILURE;
        }

        ServerLevel level = player.serverLevel();
        ServerSubLevel restored = null;
        try {
            CompoundTag serialized = NbtIo.readCompressed(dataPath, NbtAccounter.unlimitedHeap());
            SubLevelData data = SubLevelSerializer.fromData(serialized);
            Pose3d placementPose = createPlacementPose(level, data, clickedPos, clickedFace);
            ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
            restored = (ServerSubLevel) container.allocateNewSubLevel(placementPose);
            restored.getPlot().load(serialized.getCompound("plot"));
            restoreMetadata(restored, serialized);
            restored.updateLastPose();
            restored.updateBoundingBox();
            restored.forceUpdateGlobalBounds();
            clearPocketTag(stack);
            deleteQuietly(dataPath);
            LOGGER.info("玩家 [{}] 已从四次元口袋释放 Sable 结构 [{}]。",
                    player.getScoreboardName(), restored.getUniqueId());
            return Result.SUCCESS;
        } catch (Exception exception) {
            if (restored != null && !restored.isRemoved()) {
                // Preserve the original failure while also reporting a failed partial-load cleanup.
                // 保留原始异常，同时记录不完整结构清理失败的异常。
                try {
                    ServerSubLevelContainer.getContainer(level)
                            .removeSubLevel(restored, SubLevelRemovalReason.REMOVED);
                } catch (Exception cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            LOGGER.error("玩家 [{}] 释放四次元口袋中的结构失败：[存储 ID: {}]。",
                    player.getScoreboardName(), pocketId, exception);
            return Result.FAILURE;
        }
    }

    /**
     * Creates a pose that places the stored bounds immediately outside the clicked face.
     * 创建将已存储边界紧贴点击表面外侧的姿态。
     *
     * @param level target level / 目标世界
     * @param data serialized sub-level data / 已序列化子世界数据
     * @param clickedPos clicked block position / 点击的方块坐标
     * @param clickedFace clicked block face / 点击的方块表面
     * @return translated placement pose / 平移后的放置姿态
     */
    private static Pose3d createPlacementPose(
            ServerLevel level,
            SubLevelData data,
            BlockPos clickedPos,
            Direction clickedFace
    ) {
        Vector3d normal = new Vector3d(clickedFace.getStepX(), clickedFace.getStepY(), clickedFace.getStepZ());
        Vector3d faceCenter = new Vector3d(
                clickedPos.getX() + 0.5D + normal.x * 0.5D,
                clickedPos.getY() + 0.5D + normal.y * 0.5D,
                clickedPos.getZ() + 0.5D + normal.z * 0.5D
        );
        SubLevel support = Sable.HELPER.getContaining(level, clickedPos);
        if (support != null) {
            // Plot coordinates and face directions must both be projected into world space.
            // 区块坐标与表面方向都必须投影到世界空间。
            support.logicalPose().transformPosition(faceCenter);
            support.logicalPose().orientation().transform(normal).normalize();
        }

        BoundingBox3dc bounds = data.bounds();
        Vector3d oldCenter = new Vector3d(
                (bounds.minX() + bounds.maxX()) * 0.5D,
                (bounds.minY() + bounds.maxY()) * 0.5D,
                (bounds.minZ() + bounds.maxZ()) * 0.5D
        );
        double halfX = (bounds.maxX() - bounds.minX()) * 0.5D;
        double halfY = (bounds.maxY() - bounds.minY()) * 0.5D;
        double halfZ = (bounds.maxZ() - bounds.minZ()) * 0.5D;
        double supportDistance = Math.abs(normal.x) * halfX
                + Math.abs(normal.y) * halfY
                + Math.abs(normal.z) * halfZ;
        Vector3d newCenter = faceCenter.fma(supportDistance + PLACEMENT_GAP, normal);
        Pose3d pose = new Pose3d(data.pose());
        pose.position().add(newCenter.sub(oldCenter));
        return pose;
    }

    /**
     * Restores optional name and user data fields saved by Sable.
     * 恢复 Sable 保存的可选名称与用户数据字段。
     *
     * @param subLevel restored sub-level / 已恢复的子世界
     * @param serialized serialized Sable tag / 已序列化的 Sable 标签
     */
    private static void restoreMetadata(ServerSubLevel subLevel, CompoundTag serialized) {
        if (serialized.contains("display_name")) {
            subLevel.setName(serialized.getString("display_name"));
        }
        if (serialized.contains("user_data")) {
            subLevel.setUserDataTag(serialized.getCompound("user_data"));
        }
    }

    /**
     * Writes compressed NBT through a temporary file before publishing it.
     * 先写入临时文件，再发布压缩 NBT 数据。
     *
     * @param target final data path / 最终数据路径
     * @param tag serialized structure tag / 已序列化结构标签
     * @throws IOException if the data cannot be written / 数据无法写入时抛出
     */
    private static void writeAtomically(Path target, CompoundTag tag) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            NbtIo.writeCompressed(tag, temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Resolves a validated UUID-based structure file path inside the current save.
     * 在当前存档内解析基于已验证 UUID 的结构文件路径。
     *
     * @param server current server / 当前服务器
     * @param pocketId storage identifier / 存储标识
     * @return structure data path / 结构数据路径
     */
    private static Path getDataPath(MinecraftServer server, UUID pocketId) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("creatego")
                .resolve(STORAGE_DIRECTORY)
                .resolve(pocketId + ".nbt");
    }

    /**
     * Reads this feature's nested custom-data tag without mutating the stack.
     * 读取此功能的嵌套自定义数据标签，且不修改物品堆叠。
     *
     * @param stack pocket item stack / 口袋物品堆叠
     * @return copied pocket tag / 复制的口袋标签
     */
    private static CompoundTag readPocketTag(ItemStack stack) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return root.getCompound(POCKET_TAG);
    }

    /**
     * Returns the validated pocket identifier stored on the item.
     * 返回物品中保存并验证后的口袋标识。
     *
     * @param stack pocket item stack / 口袋物品堆叠
     * @return storage identifier, or null when absent / 存储标识，不存在时返回 null
     */
    private static UUID getPocketId(ItemStack stack) {
        CompoundTag pocketTag = readPocketTag(stack);
        return pocketTag.hasUUID(ID_TAG) ? pocketTag.getUUID(ID_TAG) : null;
    }

    /**
     * Marks a pocket as filled while preserving unrelated custom data.
     * 在保留无关自定义数据的同时将口袋标记为已填充。
     *
     * @param stack pocket item stack / 口袋物品堆叠
     * @param pocketId storage identifier / 存储标识
     * @param name structure display name / 结构显示名称
     */
    private static void writePocketTag(ItemStack stack, UUID pocketId, String name) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag pocketTag = new CompoundTag();
        pocketTag.putUUID(ID_TAG, pocketId);
        if (name != null && !name.isBlank()) {
            pocketTag.putString(NAME_TAG, name);
        }
        root.put(POCKET_TAG, pocketTag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    /**
     * Marks a pocket as empty while preserving unrelated custom data.
     * 在保留无关自定义数据的同时将口袋标记为空。
     *
     * @param stack pocket item stack / 口袋物品堆叠
     */
    private static void clearPocketTag(ItemStack stack) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        root.remove(POCKET_TAG);
        if (root.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        }
    }

    /**
     * Deletes an obsolete data file without failing an already completed interaction.
     * 删除已失效的数据文件，且不让已完成的交互因此失败。
     *
     * @param path data path / 数据路径
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.warn("无法删除四次元口袋数据文件 [{}]。", path, exception);
        }
    }
}
