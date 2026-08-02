/*
 * Reassembles bounded schematic and skin uploads on the server.
 * 在服务端重组受限制的蓝图与皮肤上传。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.server;

import com.longersausage.creatego.data.MapDefinition;
import com.longersausage.creatego.data.ModStore;
import com.longersausage.creatego.network.ModNetwork;
import com.longersausage.creatego.network.UploadChunkPayload;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Validates ordered chunks and commits only complete uploads.
 * 验证有序分块，并且只提交完整上传。
 */
public final class UploadManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(UploadManager.class);
    private static final int CHUNK_LIMIT = 64 * 1024;
    private static final int SCHEMATIC_LIMIT = 32 * 1024 * 1024;
    private static final int SKIN_LIMIT = 1024 * 1024;
    private static final Map<UUID, UploadSession> SESSIONS = new HashMap<>();

    private UploadManager() {
    }

    /**
     * Accepts one client upload chunk.
     * 接收一个客户端上传分块。
     *
     * @param player sending operator / 发送上传的管理员
     * @param payload validated packet fields / 已验证的数据包字段
     */
    public static synchronized void accept(ServerPlayer player, UploadChunkPayload payload) {
        if (!player.hasPermissions(2)) {
            ModNetwork.error(player, "没有上传权限。");
            return;
        }
        int limit = payload.uploadType().equals("SKIN") ? SKIN_LIMIT : SCHEMATIC_LIMIT;
        if (payload.data().length > CHUNK_LIMIT
                || payload.total() < 1
                || payload.total() > (limit / CHUNK_LIMIT) + 1
                || payload.index() < 0
                || payload.index() >= payload.total()) {
            ModNetwork.error(player, "上传分块不合法。");
            return;
        }
        UploadSession session = SESSIONS.get(player.getUUID());
        if (session == null || !session.uploadId.equals(payload.uploadId())) {
            if (payload.index() != 0) {
                ModNetwork.error(player, "上传顺序已失效，请重新上传。");
                return;
            }
            session = new UploadSession(payload);
            SESSIONS.put(player.getUUID(), session);
        }
        if (!session.matches(payload) || session.nextIndex != payload.index()) {
            SESSIONS.remove(player.getUUID());
            ModNetwork.error(player, "上传分块次序或元数据不一致。");
            return;
        }
        session.output.writeBytes(payload.data());
        session.nextIndex++;
        if (session.output.size() > limit) {
            SESSIONS.remove(player.getUUID());
            ModNetwork.error(player, "上传文件超过大小限制。");
            return;
        }
        if (session.nextIndex == session.total) {
            SESSIONS.remove(player.getUUID());
            commit(player, session);
        }
    }

    private static void commit(ServerPlayer player, UploadSession session) {
        try {
            byte[] bytes = session.output.toByteArray();
            if (session.type.equals("SCHEMATIC")) {
                commitSchematic(player, session.ownerId, session.fileName, bytes);
            } else {
                ModNetwork.error(player, "未知上传类型。");
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("玩家 [{}] 提交上传文件失败 [类型: {}]", player.getScoreboardName(), session.type, exception);
            ModNetwork.error(player, "上传保存失败：" + exception.getMessage());
        }
    }

    private static void commitSchematic(ServerPlayer player, String mapId, String fileName, byte[] bytes)
            throws IOException {
        ModStore store = ModStore.get(player.server);
        MapDefinition map = ModService.requireConfigurableMap(player, mapId);
        String structureName = ModService.validateStructureName(fileName);
        Path target = store.structureFile(map.id, structureName);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(structureName + ".upload");
        Files.write(temporary, bytes);
        int[] size;
        try {
            size = ModService.readStructureSize(temporary);
            Files.move(
                    temporary,
                    target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
        MapDefinition.StructureData structure = ModService.findStructure(map, structureName);
        if (structure == null) {
            structure = new MapDefinition.StructureData();
            structure.name = structureName;
            map.structures.add(structure);
        }
        structure.sizeX = size[0];
        structure.sizeY = size[1];
        structure.sizeZ = size[2];
        store.save();
        LOGGER.info(
                "玩家 [{}] 成功上传地图结构 [地图: {}, 结构: {}, 尺寸: {}x{}x{}]",
                player.getScoreboardName(),
                map.id,
                structureName,
                size[0],
                size[1],
                size[2]
        );
        ModNetwork.broadcastState(player);
        ModNetwork.send(player, "notice", ModStore.toJson(
                new ModNetwork.MessageBody("结构“" + structureName + "”已保存到地图“" + map.id + "”。")
        ));
    }

    private static final class UploadSession {
        private final String type;
        private final String ownerId;
        private final String fileName;
        private final String uploadId;
        private final int total;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int nextIndex;

        private UploadSession(UploadChunkPayload payload) {
            type = payload.uploadType();
            ownerId = payload.ownerId();
            fileName = payload.fileName();
            uploadId = payload.uploadId();
            total = payload.total();
        }

        private boolean matches(UploadChunkPayload payload) {
            return type.equals(payload.uploadType())
                    && ownerId.equals(payload.ownerId())
                    && fileName.equals(payload.fileName())
                    && total == payload.total();
        }
    }
}
