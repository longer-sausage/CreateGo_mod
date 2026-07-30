/*
 * Defines compact JSON commands sent to the server.
 * 定义发送到服务端的紧凑 JSON 命令。
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
 * Carries one validated action and its JSON body.
 * 携带一个待验证的操作及其 JSON 内容。
 *
 * @param action action identifier / 操作标识
 * @param json JSON body / JSON 内容
 */
public record ServerboundActionPayload(String action, String json) implements CustomPacketPayload {
    public static final Type<ServerboundActionPayload> TYPE =
            new Type<>(CreateGo.id("action_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundActionPayload> STREAM_CODEC =
            StreamCodec.of(ServerboundActionPayload::encode, ServerboundActionPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ServerboundActionPayload payload) {
        buffer.writeUtf(payload.action, 64);
        buffer.writeUtf(payload.json, 1024 * 1024);
    }

    private static ServerboundActionPayload decode(RegistryFriendlyByteBuf buffer) {
        return new ServerboundActionPayload(buffer.readUtf(64), buffer.readUtf(1024 * 1024));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
