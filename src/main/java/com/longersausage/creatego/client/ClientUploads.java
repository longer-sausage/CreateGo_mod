/*
 * Reads selected local files and sends bounded chunks.
 * 读取选定的本地文件并发送受限制的分块。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.network.UploadChunkPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Provides local schematic and skin discovery plus chunked uploads.
 * 提供本地蓝图与皮肤发现以及分块上传。
 */
public final class ClientUploads {
    private static final int CHUNK_SIZE = 60 * 1024;

    private ClientUploads() {
    }

    /**
     * Lists Create schematic files from the instance schematics folder.
     * 从实例 schematics 目录列出机械动力蓝图文件。
     *
     * @return sorted NBT paths / 已排序的 NBT 路径
     */
    public static List<Path> listSchematics() {
        return listFiles(Minecraft.getInstance().gameDirectory.toPath().resolve("schematics"), ".nbt");
    }

    /**
     * Uploads a local file in ordered chunks.
     * 按顺序分块上传本地文件。
     *
     * @param type SCHEMATIC or SKIN / SCHEMATIC 或 SKIN
     * @param ownerId map or NPC identifier / 地图或 NPC 标识
     * @param file selected file / 已选择文件
     * @throws IOException when the file cannot be read / 文件无法读取时抛出
     */
    public static void upload(String type, String ownerId, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int total = Math.max(1, (bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        String uploadId = UUID.randomUUID().toString();
        for (int index = 0; index < total; index++) {
            int start = index * CHUNK_SIZE;
            int end = Math.min(bytes.length, start + CHUNK_SIZE);
            byte[] chunk = java.util.Arrays.copyOfRange(bytes, start, end);
            PacketDistributor.sendToServer(new UploadChunkPayload(
                    type,
                    ownerId,
                    file.getFileName().toString(),
                    uploadId,
                    index,
                    total,
                    chunk
            ));
        }
    }

    private static List<Path> listFiles(Path directory, String extension) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(extension))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }
}
