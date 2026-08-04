/*
 * Displays the active level session menu.
 * 显示活动关卡会话菜单。
 *
 * Author: CreateGo
 * Date: 2026-08-05
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.client.ui.BaseScreen;
import com.longersausage.creatego.client.ui.ModernButton;
import com.longersausage.creatego.client.ui.UITheme;
import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Replaces the former right-side HUD with an explicit progress and session-control screen.
 * 使用显式的进度与会话控制界面替代原右侧 HUD。
 */
public final class LevelMenuScreen extends BaseScreen {
    private ModNetwork.LevelPlayStatus status;

    /**
     * Creates a menu from the latest play status.
     * 根据最新游玩状态创建菜单。
     *
     * @param status synchronized play status / 已同步游玩状态
     */
    public LevelMenuScreen(ModNetwork.LevelPlayStatus status) {
        super(Component.translatable("screen.creatego.level_menu"));
        this.status = status;
    }

    /**
     * Builds restart, exit, and return controls.
     * 构建重新开始、退出与返回控件。
     */
    @Override
    protected void init() {
        int left = width / 2 - 220;
        int y = height / 2 + 116;
        addRenderableWidget(ModernButton.create(Component.translatable("button.creatego.restart_level"), button -> {
            ScreenHelper.send("restart_level_session", new Object());
            onClose();
        }).bounds(left + 20, y, 125, 26).build());
        addRenderableWidget(ModernButton.create(Component.translatable("button.creatego.exit_level"), button -> {
            ScreenHelper.send("exit_level_session", new Object());
            onClose();
        }).bounds(left + 155, y, 125, 26).variant(ModernButton.Variant.DANGER).build());
        addRenderableWidget(ModernButton.create(Component.translatable("button.creatego.return_game"), button -> onClose())
                .bounds(left + 290, y, 130, 26).variant(ModernButton.Variant.GHOST).build());
    }

    /**
     * Refreshes status while the menu remains open.
     * 菜单保持打开时刷新状态。
     */
    @Override
    public void tick() {
        ModNetwork.LevelPlayStatus latest = ClientController.levelPlayStatus();
        if (latest == null || !latest.active) {
            onClose();
        } else {
            status = latest;
        }
    }

    /**
     * Draws timer, completion, failure, and team information.
     * 绘制计时、过关、失败与队伍信息。
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        UITheme.drawBackground(graphics, width, height);
        int left = width / 2 - 220;
        int top = height / 2 - 150;
        UITheme.shadow(graphics, left, top, 440, 308, 10);
        UITheme.roundedPanel(graphics, left, top, 440, 308, 10, UITheme.BORDER, UITheme.SURFACE);
        graphics.drawString(font, Component.translatable("screen.creatego.level_menu"), left + 20, top + 17, UITheme.TEXT, false);
        graphics.drawString(font, status.mapId, left + 112, top + 17, UITheme.ACCENT, false);
        String mode = "PREVIEW".equals(status.mode) ? "预览" : "挑战";
        String time = status.totalTicks <= 0 ? "--:--" : formatTime(status.remainingTicks);
        graphics.drawString(font, mode + "  ·  剩余时间 " + time, left + 20, top + 40, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "过关条件与完成度", left + 20, top + 68, UITheme.TEXT, false);
        drawProgress(graphics, status.progress, left + 28, top + 86, 6);
        graphics.drawString(font, "失败条件", left + 230, top + 68, UITheme.TEXT, false);
        int restrictionY = top + 86;
        int restrictionCount = Math.min(6, status.restrictions.size());
        for (int index = 0; index < restrictionCount; index++) {
            ModNetwork.RuleProgress rule = status.restrictions.get(index);
            int color = rule.matched() ? UITheme.DANGER : UITheme.TEXT_MUTED;
            graphics.drawString(font, font.plainSubstrByWidth((rule.matched() ? "! " : "• ") + rule.name(), 180), left + 238, restrictionY + index * 17, color, false);
        }
        if (status.restrictions.isEmpty()) {
            graphics.drawString(font, "无", left + 238, restrictionY, UITheme.TEXT_DIM, false);
        }
        graphics.drawString(font, "队伍完成状态", left + 20, top + 202, UITheme.TEXT, false);
        int memberX = left + 28;
        for (int index = 0; index < Math.min(8, status.members.size()); index++) {
            ModNetwork.MemberProgress member = status.members.get(index);
            int color = member.completed() ? UITheme.SUCCESS : UITheme.TEXT_MUTED;
            graphics.drawString(font, (member.completed() ? "✓ " : "○ ") + member.name(), memberX + index % 4 * 100, top + 220 + index / 4 * 17, color, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Draws a bounded live completion checklist.
     * 绘制数量受限的实时完成度清单。
     */
    private void drawProgress(GuiGraphics graphics, java.util.List<com.longersausage.creatego.server.LevelConditionEvaluator.Progress> progress, int x, int y, int limit) {
        for (int index = 0; index < Math.min(limit, progress.size()); index++) {
            var entry = progress.get(index);
            int color = entry.matched() ? UITheme.SUCCESS : UITheme.TEXT_MUTED;
            graphics.drawString(font, font.plainSubstrByWidth((entry.matched() ? "✓ " : "○ ") + entry.description(), 180), x, y + index * 17, color, false);
        }
        if (progress.isEmpty()) {
            graphics.drawString(font, "预览模式不计算完成度", x, y, UITheme.TEXT_DIM, false);
        }
    }

    /**
     * Formats remaining ticks as a minute-second string.
     * 将剩余刻数格式化为分秒字符串。
     */
    private static String formatTime(int ticks) {
        int seconds = Math.max(0, (ticks + 19) / 20);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
