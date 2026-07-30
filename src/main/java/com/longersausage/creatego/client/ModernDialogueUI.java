/*
 * Opens the Modern UI based NPC dialogue editor.
 * 打开基于 Modern UI 的 NPC 对话编辑器。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.data.NpcData;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ScreenCallback;
import net.minecraft.client.Minecraft;

/**
 * Bridges the Minecraft screen lifecycle to the vector dialogue fragment.
 * 将 Minecraft 屏幕生命周期桥接到矢量对话 Fragment。
 */
public final class ModernDialogueUI {
    private ModernDialogueUI() {
    }

    /**
     * Opens the editor and keeps the NPC screen as the native back target.
     * 打开编辑器，并保留 NPC 界面作为原生返回目标。
     *
     * @param npc synchronized NPC document / 已同步的 NPC 文档
     */
    public static void open(NpcData npc) {
        open(npc, java.util.List.of());
    }

    /**
     * Opens the editor and keeps the NPC screen with skins list as the native back target.
     * 打开编辑器，并保留带皮肤列表的 NPC 界面作为原生返回目标。
     *
     * @param npc synchronized NPC document / 已同步的 NPC 文档
     * @param skins available skin names / 可用皮肤名列表
     */
    public static void open(NpcData npc, java.util.List<String> skins) {
        if (npc.dialogue == null) {
            npc.dialogue = new com.longersausage.creatego.data.DialogueGraph();
        }
        npc.dialogue.ensureEntryNode();
        Minecraft minecraft = Minecraft.getInstance();
        ScreenCallback callback = new ScreenCallback() {
            @Override
            public boolean hasDefaultBackground() {
                return false;
            }
            @Override
            public boolean shouldBlurBackground() {
                return false;
            }
        };
        minecraft.setScreen(MuiModApi.get().createScreen(
                new DialogueEditFragment(npc),
                callback,
                new NpcScreen(npc, skins),
                "CreateGo 对话工作流"
        ));
    }
}
