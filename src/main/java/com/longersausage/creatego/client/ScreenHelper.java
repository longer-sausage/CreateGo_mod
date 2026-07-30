/*
 * Provides shared client UI parsing and network helpers.
 * 提供客户端界面共用的解析与网络辅助方法。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.data.ModStore;
import com.longersausage.creatego.network.ServerboundActionPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains small reusable screen operations.
 * 包含可复用的小型界面操作。
 */
public final class ScreenHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenHelper.class);

    private ScreenHelper() {
    }

    /**
     * Sends one JSON command to the authority server.
     * 向权威服务端发送一个 JSON 命令。
     *
     * @param action action identifier / 操作标识
     * @param body serializable body / 可序列化内容
     */
    public static void send(String action, Object body) {
        PacketDistributor.sendToServer(new ServerboundActionPayload(action, ModStore.toJson(body)));
    }

    /**
     * Parses an integer field and reports invalid input.
     * 解析整数字段，并报告非法输入。
     *
     * @param value field text / 字段文本
     * @param label field label / 字段标签
     * @return parsed value / 解析后的值
     */
    public static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " 必须是整数。");
        }
    }

    /**
     * Parses a floating-point field and reports invalid input.
     * 解析浮点字段，并报告非法输入。
     *
     * @param value field text / 字段文本
     * @param label field label / 字段标签
     * @return parsed value / 解析后的值
     */
    public static double parseDouble(String value, String label) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " 必须是数字。");
        }
    }

    /**
     * Logs a local validation or status message.
     * 记录本地验证或状态日志。
     *
     * @param message user-facing message / 面向用户的消息
     */
    public static void message(String message) {
        LOGGER.info("客户端提示: {}", message);
    }
}
