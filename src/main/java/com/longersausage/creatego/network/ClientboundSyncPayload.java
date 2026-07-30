/*
 * Defines events sent from the authority server to clients.
 * 定义权威服务端发送给客户端的事件。
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
 * Carries one client event and its JSON body.
 * 携带一个客户端事件及其 JSON 内容。
 *
 * @param action event identifier / 事件标识
 * @param json JSON body / JSON 内容
 */
public record ClientboundSyncPayload(String action, String json) implements CustomPacketPayload {
    public static final Type<ClientboundSyncPayload> TYPE =
            new Type<>(CreateGo.id("sync_event"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundSyncPayload> STREAM_CODEC =
            StreamCodec.of(ClientboundSyncPayload::encode, ClientboundSyncPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundSyncPayload payload) {
        buffer.writeUtf(payload.action, 64);
        buffer.writeUtf(payload.json, 1024 * 1024);
    }

    private static ClientboundSyncPayload decode(RegistryFriendlyByteBuf buffer) {
        return new ClientboundSyncPayload(buffer.readUtf(64), buffer.readUtf(1024 * 1024));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
