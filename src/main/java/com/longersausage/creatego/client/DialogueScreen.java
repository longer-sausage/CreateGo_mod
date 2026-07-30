/*
 * Opens the Modern UI runtime NPC dialogue surface.
 * 打开基于 Modern UI 的 NPC 运行时对话界面。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.server.DialogueRuntime;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ScreenCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Bridges server-resolved dialogue states into a Modern UI fragment.
 * 将服务端解析的对话状态桥接到 Modern UI Fragment。
 */
public final class DialogueScreen {
    private static ActiveDialogue activeDialogue;

    private DialogueScreen() {
    }

    /**
     * Opens a non-pausing dialogue screen or updates its current content in place.
     * 打开不暂停游戏的对话界面，或原位更新当前界面内容。
     *
     * @param view server-resolved dialogue state / 服务端解析的对话状态
     */
    public static void open(DialogueRuntime.DialogueView view) {
        Minecraft minecraft = Minecraft.getInstance();
        if (activeDialogue != null) {
            if (minecraft.screen == activeDialogue.screen && !activeDialogue.closeRequest.isSent()) {
                activeDialogue.fragment.update(view);
            } else if (minecraft.screen != activeDialogue.screen) {
                activeDialogue.closeRequest.send();
            }
            return;
        }
        Screen previousScreen = minecraft.screen;
        CloseRequest closeRequest = new CloseRequest();
        DialogueViewFragment fragment = new DialogueViewFragment(view, closeRequest::send);
        ScreenCallback callback = new ScreenCallback() {
            @Override
            public boolean shouldClose() {
                closeRequest.send();
                return true;
            }
            @Override
            public boolean isPauseScreen() {
                return false;
            }
            @Override
            public boolean hasDefaultBackground() {
                return false;
            }
            @Override
            public boolean shouldBlurBackground() {
                return false;
            }
        };
        Screen screen = MuiModApi.get().createScreen(
                fragment,
                callback,
                previousScreen,
                "CreateGo NPC 对话"
        );
        activeDialogue = new ActiveDialogue(screen, fragment, previousScreen, closeRequest);
        minecraft.setScreen(screen);
    }

    /**
     * Closes only the dialogue screen that owns the acknowledged session.
     * 仅关闭拥有已确认会话的对话界面。
     */
    public static void close() {
        Minecraft minecraft = Minecraft.getInstance();
        ActiveDialogue dialogue = activeDialogue;
        activeDialogue = null;
        if (dialogue != null && minecraft.screen == dialogue.screen) {
            minecraft.setScreen(dialogue.previousScreen);
        }
    }

    /**
     * Owns the single screen and mutable fragment for one client conversation lifecycle.
     * 持有单次客户端对话生命周期内唯一的界面与可变 Fragment。
     *
     * @param screen persistent Minecraft screen / 持久化的 Minecraft 界面
     * @param fragment hot-updated dialogue content / 热更新的对话内容
     * @param previousScreen screen restored after the conversation / 对话结束后恢复的界面
     * @param closeRequest lifecycle close state / 生命周期关闭状态
     */
    private record ActiveDialogue(
            Screen screen,
            DialogueViewFragment fragment,
            Screen previousScreen,
            CloseRequest closeRequest
    ) {
    }

    /**
     * Sends the close request at most once for one screen instance.
     * 为单个界面实例至多发送一次关闭请求。
     */
    private static final class CloseRequest {
        private boolean sent;

        /**
         * Requests authoritative session closure without changing screens reentrantly.
         * 请求服务端关闭会话，且不在点击回调中重入切换界面。
         */
        private void send() {
            if (sent) {
                return;
            }
            sent = true;
            ScreenHelper.send("dialogue_close", new Object());
        }

        /**
         * Returns whether this conversation is already closing.
         * 返回当前对话是否已经进入关闭流程。
         *
         * @return close request state / 关闭请求状态
         */
        private boolean isSent() {
            return sent;
        }
    }
}
