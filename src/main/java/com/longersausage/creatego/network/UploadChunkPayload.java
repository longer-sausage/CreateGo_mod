/*
 * Defines bounded chunks for schematic and skin uploads.
 * 定义用于蓝图和皮肤上传的有界数据分块。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.network;

import com.longersausage.creatego.CreateGo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Transfers one ordered upload chunk.
 * 传输一个有序上传分块。
 *
 * @param uploadType SCHEMATIC or SKIN / SCHEMATIC 或 SKIN
 * @param ownerId map identifier / 地图标识
 * @param fileName original safe filename / 原始安全文件名
 * @param uploadId random upload identifier / 随机上传标识
 * @param index zero-based chunk index / 从零开始的分块序号
 * @param total total chunk count / 分块总数
 * @param data bounded bytes / 有界字节数据
 */
public record UploadChunkPayload(
        String uploadType,
        String ownerId,
        String fileName,
        String uploadId,
        int index,
        int total,
        byte[] data
) implements CustomPacketPayload {
    public static final Type<UploadChunkPayload> TYPE =
            new Type<>(CreateGo.id("upload_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UploadChunkPayload> STREAM_CODEC =
            StreamCodec.of(UploadChunkPayload::encode, UploadChunkPayload::decode);
    private static void encode(RegistryFriendlyByteBuf buffer, UploadChunkPayload payload) {
        buffer.writeUtf(payload.uploadType, 16);
        buffer.writeUtf(payload.ownerId, 64);
        buffer.writeUtf(payload.fileName, 128);
        buffer.writeUtf(payload.uploadId, 64);
        buffer.writeVarInt(payload.index);
        buffer.writeVarInt(payload.total);
        buffer.writeByteArray(payload.data);
    }
    private static UploadChunkPayload decode(RegistryFriendlyByteBuf buffer) {
        return new UploadChunkPayload(
                buffer.readUtf(16),
                buffer.readUtf(64),
                buffer.readUtf(128),
                buffer.readUtf(64),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readByteArray(64 * 1024)
        );
    }
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
