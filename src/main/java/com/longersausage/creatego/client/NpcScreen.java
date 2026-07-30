/*
 * Implements NPC identity, transform, skin, and dialogue entry UI.
 * 实现 NPC 身份、变换、皮肤与对话入口界面。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.client.ui.BaseScreen;
import com.longersausage.creatego.client.ui.ModernButton;
import com.longersausage.creatego.client.ui.ModernEditBox;
import com.longersausage.creatego.client.ui.UITheme;
import com.longersausage.creatego.data.NpcData;
import com.longersausage.creatego.server.ModService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import java.util.List;

/**
 * Edits one map-bound NPC and opens its independent node canvas.
 * 编辑一个与地图绑定的 NPC，并打开其独立节点画布。
 */
public final class NpcScreen extends BaseScreen {
    private final NpcData npc;
    private final List<String> skins;
    private int skinIndex;
    private EditBox nameField;
    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private EditBox yawField;
    private Button skinButton;

    /**
     * Creates an NPC editor screen with an empty skin list.
     * 创建带空皮肤列表的 NPC 编辑界面。
     *
     * @param npc synchronized NPC document / 已同步 NPC 文档
     */
    public NpcScreen(NpcData npc) {
        this(npc, List.of());
    }

    /**
     * Creates an NPC editor screen with available server skin names.
     * 创建带服务端可用皮肤名列表的 NPC 编辑界面。
     *
     * @param npc synchronized NPC document / 已同步 NPC 文档
     * @param skins available server skin names / 服务端可用皮肤名列表
     */
    public NpcScreen(NpcData npc, List<String> skins) {
        super(Component.literal("NPC 编辑器"));
        this.npc = npc;
        this.skins = skins == null ? List.of() : skins;
        this.skinIndex = 0;
        if (npc.skinName != null && !npc.skinName.isBlank()) {
            int index = this.skins.indexOf(npc.skinName);
            if (index >= 0) {
                this.skinIndex = index + 1;
            }
        }
    }

    @Override
    protected void init() {
        int left = width / 2 - 170;
        int top = height / 2 - 115;
        nameField = addRenderableWidget(new ModernEditBox(font, left + 80, top + 5, 260, 20, Component.literal("名字")));
        xField = addRenderableWidget(new ModernEditBox(font, left + 80, top + 35, 70, 20, Component.literal("绝对 X")));
        yField = addRenderableWidget(new ModernEditBox(font, left + 185, top + 35, 70, 20, Component.literal("绝对 Y")));
        zField = addRenderableWidget(new ModernEditBox(font, left + 290, top + 35, 50, 20, Component.literal("绝对 Z")));
        yawField = addRenderableWidget(new ModernEditBox(font, left + 80, top + 65, 100, 20, Component.literal("朝向")));
        skinButton = addRenderableWidget(ModernButton.create(Component.empty(), button -> cycleSkin(1))
                .bounds(left, top + 100, 340, 20).build());
        addRenderableWidget(ModernButton.create(Component.literal("编辑对话工作流"), button -> openDialogueEditor())
                .bounds(left, top + 135, 165, 20).build());
        addRenderableWidget(ModernButton.create(Component.literal("删除 NPC"), button -> delete())
                .bounds(left + 175, top + 135, 165, 20)
                .variant(ModernButton.Variant.DANGER).build());
        addRenderableWidget(ModernButton.create(Component.literal("完成"), button -> finish())
                .bounds(left, top + 165, 340, 20)
                .variant(ModernButton.Variant.PRIMARY).build());
        nameField.setValue(npc.name);
        xField.setValue(Double.toString(npc.x));
        yField.setValue(Double.toString(npc.y));
        zField.setValue(Double.toString(npc.z));
        yawField.setValue(Float.toString(npc.yaw));
        refreshSkinLabel();
    }

    private void cycleSkin(int delta) {
        int totalOptions = 1 + skins.size();
        skinIndex = Math.floorMod(skinIndex + delta, totalOptions);
        if (skinIndex == 0) {
            npc.skinName = "";
        } else {
            npc.skinName = skins.get(skinIndex - 1);
        }
        refreshSkinLabel();
    }

    private void refreshSkinLabel() {
        if (skinIndex == 0) {
            skinButton.setMessage(Component.literal("随机原版模型"));
        } else {
            skinButton.setMessage(Component.literal(skins.get(skinIndex - 1)));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double amount = scrollY == 0.0D ? scrollX : scrollY;
        if (amount != 0.0D) {
            int delta = amount > 0.0D ? -1 : 1;
            cycleSkin(delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean parseInputFields() {
        try {
            npc.name = nameField.getValue();
            npc.x = ScreenHelper.parseDouble(xField.getValue(), "绝对 X");
            npc.y = ScreenHelper.parseDouble(yField.getValue(), "绝对 Y");
            npc.z = ScreenHelper.parseDouble(zField.getValue(), "绝对 Z");
            npc.yaw = (float) ScreenHelper.parseDouble(yawField.getValue(), "朝向");
            return true;
        } catch (IllegalArgumentException exception) {
            ScreenHelper.message(exception.getMessage());
            return false;
        }
    }

    private void finish() {
        if (parseInputFields()) {
            ScreenHelper.send("save_npc", npc);
            onClose();
        }
    }

    private void openDialogueEditor() {
        if (parseInputFields()) {
            ModernDialogueUI.open(npc, skins);
        }
    }

    private void delete() {
        ModService.NpcIdRequest request = new ModService.NpcIdRequest();
        request.npcId = npc.id.toString();
        ScreenHelper.send("delete_npc", request);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        UITheme.drawBackground(graphics, width, height);
        int left = width / 2 - 170;
        int top = height / 2 - 115;
        UITheme.shadow(graphics, left - 18, top - 29, 376, 225, 8);
        UITheme.roundedPanel(
                graphics,
                left - 18,
                top - 29,
                376,
                225,
                8,
                UITheme.BORDER,
                UITheme.SURFACE
        );
        graphics.drawCenteredString(font, title, width / 2, top - 18, UITheme.TEXT);
        graphics.drawString(font, "名字", left, top + 11, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "绝对 X", left, top + 41, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "Y", left + 165, top + 41, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "Z", left + 270, top + 41, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "朝向 Yaw", left, top + 71, UITheme.TEXT_MUTED, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
