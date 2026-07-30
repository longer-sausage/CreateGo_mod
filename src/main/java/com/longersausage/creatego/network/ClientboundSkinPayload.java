/*
 * Defines verified custom skin data sent to clients.
 * 定义发送给客户端的已验证自定义皮肤数据。
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
 * Transfers one complete, size-limited PNG skin.
 * 传输一张完整且受大小限制的 PNG 皮肤。
 *
 * @param name skin name without extension / 皮肤名（不带后缀）
 * @param png PNG bytes / PNG 字节
 */
public record ClientboundSkinPayload(String name, byte[] png) implements CustomPacketPayload {
    public static final Type<ClientboundSkinPayload> TYPE =
            new Type<>(CreateGo.id("skin_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundSkinPayload> STREAM_CODEC =
            StreamCodec.of(ClientboundSkinPayload::encode, ClientboundSkinPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundSkinPayload payload) {
        buffer.writeUtf(payload.name, 64);
        buffer.writeByteArray(payload.png);
    }

    private static ClientboundSkinPayload decode(RegistryFriendlyByteBuf buffer) {
        return new ClientboundSkinPayload(buffer.readUtf(64), buffer.readByteArray(1024 * 1024));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
