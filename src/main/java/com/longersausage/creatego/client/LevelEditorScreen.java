/*
 * Implements registration and main configuration for a CreateGo level.
 * 实现 CreateGo 关卡的注册与主配置界面。
 *
 * Author: CreateGo
 * Date: 2026-08-04
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.client.ui.BaseScreen;
import com.longersausage.creatego.client.ui.ModernButton;
import com.longersausage.creatego.client.ui.ModernEditBox;
import com.longersausage.creatego.client.ui.UITheme;
import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Shows the one-time registration prompt or the registered level dashboard.
 * 显示一次性注册提示或已注册关卡的控制面板。
 */
public final class LevelEditorScreen extends BaseScreen {
    private final ModNetwork.LevelEditorView view;
    private ModernEditBox minuteField;
    private ModernEditBox secondField;
    private boolean confirmDelete;

    /**
     * Creates a level editor from fresh server state.
     * 根据最新服务端状态创建关卡编辑器。
     *
     * @param view bound level editor view / 绑定关卡编辑器视图
     */
    public LevelEditorScreen(ModNetwork.LevelEditorView view) {
        super(Component.literal("关卡编辑器"));
        this.view = view;
    }

    /**
     * Builds either registration controls or the complete level dashboard.
     * 构建注册控件或完整关卡控制面板。
     */
    @Override
    protected void init() {
        if (view.level == null) {
            buildRegistrationWidgets();
        } else {
            buildDashboardWidgets();
        }
    }

    private void buildRegistrationWidgets() {
        int left = width / 2 - 170;
        int top = height / 2 + 38;
        addRenderableWidget(ModernButton.create(Component.literal("注册为关卡"), button ->
                ScreenHelper.send("register_level", new Object()))
                .bounds(left, top, 220, 24)
                .variant(ModernButton.Variant.PRIMARY)
                .build());
        addRenderableWidget(ModernButton.create(Component.literal("取消"), button -> onClose())
                .bounds(left + 230, top, 110, 24)
                .variant(ModernButton.Variant.GHOST)
                .build());
    }

    private void buildDashboardWidgets() {
        int left = width / 2 - 235;
        int top = height / 2 - 122;
        addRenderableWidget(ModernButton.create(Component.literal("编辑过关条件"), button -> openCompletionTree())
                .bounds(left + 20, top + 50, 205, 42).build());
        addRenderableWidget(ModernButton.create(
                Component.literal("管理限制规则（" + view.level.restrictions.size() + "）"),
                button -> openRestrictions()
        )
                .bounds(left + 245, top + 50, 205, 42).build());
        minuteField = addRenderableWidget(new ModernEditBox(
                font, left + 113, top + 147, 62, 22, Component.literal("分钟")
        ));
        secondField = addRenderableWidget(new ModernEditBox(
                font, left + 207, top + 147, 62, 22, Component.literal("秒")
        ));
        minuteField.setFilter(value -> value.matches("[0-9]{0,4}"));
        secondField.setFilter(value -> value.matches("[0-9]{0,2}"));
        minuteField.setValue(Integer.toString(view.level.timeLimitSeconds / 60));
        secondField.setValue(Integer.toString(view.level.timeLimitSeconds % 60));
        addRenderableWidget(ModernButton.create(Component.literal("保存设置"), button -> save())
                .bounds(left + 20, top + 192, 135, 26)
                .variant(ModernButton.Variant.PRIMARY).build());
        addRenderableWidget(ModernButton.create(Component.literal("删除关卡"), button -> deleteLevel())
                .bounds(left + 310, top + 192, 140, 26)
                .variant(ModernButton.Variant.DANGER).build());
    }

    private void openCompletionTree() {
        if (parseTime()) {
            minecraft.setScreen(new LevelConditionScreen(view));
        }
    }

    private void openRestrictions() {
        if (parseTime()) {
            minecraft.setScreen(new LevelRestrictionScreen(view));
        }
    }

    private boolean parseTime() {
        try {
            int minutes = minuteField.getValue().isBlank() ? 0 : Integer.parseInt(minuteField.getValue());
            int seconds = secondField.getValue().isBlank() ? 0 : Integer.parseInt(secondField.getValue());
            if (seconds > 59 || minutes * 60L + seconds < 1L) {
                throw new IllegalArgumentException("时间限制至少为 1 秒，秒数必须在 0 至 59 之间。");
            }
            view.level.timeLimitSeconds = Math.min(24 * 60 * 60, minutes * 60 + seconds);
            return true;
        } catch (NumberFormatException exception) {
            ScreenHelper.message("时间限制必须是整数。");
            return false;
        } catch (IllegalArgumentException exception) {
            ScreenHelper.message(exception.getMessage());
            return false;
        }
    }

    private void save() {
        if (parseTime()) {
            ScreenHelper.send("save_level", view);
        }
    }

    private void deleteLevel() {
        if (!confirmDelete) {
            confirmDelete = true;
            ScreenHelper.message("再次点击“删除关卡”确认；底层地图不会被删除。");
            return;
        }
        ScreenHelper.send("delete_level", new Object());
    }

    /**
     * Draws the registration card or the polished level dashboard.
     * 绘制注册卡片或精美关卡控制面板。
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        UITheme.drawBackground(graphics, width, height);
        if (view.level == null) {
            renderRegistration(graphics);
        } else {
            renderDashboard(graphics);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRegistration(GuiGraphics graphics) {
        int left = width / 2 - 190;
        int top = height / 2 - 90;
        UITheme.shadow(graphics, left, top, 380, 180, 10);
        UITheme.roundedPanel(graphics, left, top, 380, 180, 10, UITheme.BORDER, UITheme.SURFACE);
        graphics.drawCenteredString(font, "将地图注册为关卡", width / 2, top + 24, UITheme.TEXT);
        graphics.drawCenteredString(font, "当前绑定地图  ·  " + view.mapId, width / 2, top + 48, UITheme.ACCENT);
        graphics.drawCenteredString(font, "注册后可配置条件树、限制惩罚和计时规则。", width / 2, top + 78, UITheme.TEXT_MUTED);
        graphics.drawCenteredString(font, "此操作不会修改地图方块或 NPC。", width / 2, top + 96, UITheme.TEXT_DIM);
    }

    private void renderDashboard(GuiGraphics graphics) {
        int left = width / 2 - 235;
        int top = height / 2 - 122;
        UITheme.shadow(graphics, left, top, 470, 250, 10);
        UITheme.roundedPanel(graphics, left, top, 470, 250, 10, UITheme.BORDER, UITheme.SURFACE);
        graphics.drawString(font, "关卡编辑器", left + 20, top + 17, UITheme.TEXT, false);
        graphics.drawString(font, view.mapId, left + 112, top + 17, UITheme.ACCENT, false);
        graphics.drawString(font, "完成条件树", left + 28, top + 62, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "独立限制规则", left + 253, top + 62, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "时间限制", left + 20, top + 153, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "分", left + 181, top + 154, UITheme.TEXT_DIM, false);
        graphics.drawString(font, "秒", left + 275, top + 154, UITheme.TEXT_DIM, false);
        graphics.drawString(font, "每条限制都拥有独立条件树与惩罚，可同时生效", left + 20, top + 108, UITheme.TEXT_DIM, false);
    }

    /**
     * Keeps the game simulation running while the editor is open.
     * 在编辑器打开时保持游戏模拟运行。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
