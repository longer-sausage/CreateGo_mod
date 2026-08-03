/*
 * Renders the compact CreateGo level play HUD.
 * 渲染紧凑的 CreateGo 关卡游玩 HUD。
 *
 * Author: CreateGo
 * Date: 2026-08-04
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.client.ui.UITheme;
import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws a top time bar and a narrow right-side completion checklist.
 * 绘制顶部时间条和右侧窄幅过关条件清单。
 */
public final class LevelHud {
    private LevelHud() {
    }

    /**
     * Renders current level state without capturing input or blocking the crosshair.
     * 渲染当前关卡状态，不捕获输入也不遮挡准星。
     *
     * @param graphics GUI drawing context / GUI 绘制上下文
     * @param deltaTracker frame timing / 帧计时器
     */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        ModNetwork.LevelPlayStatus status = ClientController.levelPlayStatus();
        Minecraft minecraft = Minecraft.getInstance();
        if (status == null || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        if (!status.active) {
            renderResult(graphics, minecraft.font, status.result, status.completionMatched);
            return;
        }
        renderTimer(graphics, minecraft.font, status);
        renderConditions(graphics, minecraft.font, status);
    }

    private static void renderTimer(GuiGraphics graphics, Font font, ModNetwork.LevelPlayStatus status) {
        int screenWidth = graphics.guiWidth();
        int barWidth = Math.min(300, screenWidth - 80);
        int x = (screenWidth - barWidth) / 2;
        int y = 9;
        float ratio = status.totalTicks <= 0 ? 0.0F : Math.max(0.0F, Math.min(1.0F,
                (float) status.remainingTicks / status.totalTicks));
        UITheme.roundedPanel(graphics, x, y, barWidth, 18, 5, 0x8A425066, 0xB518202B);
        UITheme.roundedRect(graphics, x + 3, y + 13, barWidth - 6, 2, 1, 0x66364151);
        int progressWidth = Math.round((barWidth - 6) * ratio);
        int progressColor = ratio > 0.25F ? UITheme.ACCENT : UITheme.DANGER;
        UITheme.roundedRect(graphics, x + 3, y + 13, progressWidth, 2, 1, progressColor);
        int seconds = Math.max(0, (status.remainingTicks + 19) / 20);
        String time = String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        graphics.drawCenteredString(font, "剩余时间  " + time, screenWidth / 2, y + 3, UITheme.TEXT);
    }

    private static void renderConditions(GuiGraphics graphics, Font font, ModNetwork.LevelPlayStatus status) {
        if (status.progress == null || status.progress.isEmpty()) {
            return;
        }
        int panelWidth = Math.min(240, Math.max(150, graphics.guiWidth() / 5));
        int visible = Math.min(7, status.progress.size());
        int panelHeight = 25 + visible * 18;
        int x = graphics.guiWidth() - panelWidth - 10;
        int y = 38;
        UITheme.roundedPanel(graphics, x, y, panelWidth, panelHeight, 6, 0x70425066, 0xA8121821);
        graphics.drawString(font, "过关条件", x + 10, y + 8, UITheme.TEXT, false);
        for (int index = 0; index < visible; index++) {
            var entry = status.progress.get(index);
            int color = entry.matched() ? UITheme.SUCCESS : UITheme.TEXT_MUTED;
            String marker = entry.matched() ? "✓" : "○";
            String description = font.plainSubstrByWidth(entry.description(), panelWidth - 38);
            graphics.drawString(font, marker, x + 10, y + 26 + index * 18, color, false);
            graphics.drawString(font, description, x + 25, y + 26 + index * 18, color, false);
        }
        if (status.progress.size() > visible) {
            graphics.drawString(font, "+" + (status.progress.size() - visible) + " 项", x + panelWidth - 45, y + 8, UITheme.TEXT_DIM, false);
        }
    }

    private static void renderResult(GuiGraphics graphics, Font font, String result, boolean success) {
        String text = result == null || result.isBlank() ? (success ? "关卡完成" : "挑战结束") : result;
        int width = font.width(text) + 36;
        int x = (graphics.guiWidth() - width) / 2;
        int y = 42;
        int color = success ? UITheme.SUCCESS : UITheme.DANGER;
        UITheme.roundedPanel(graphics, x, y, width, 26, 7, color, 0xD818202B);
        graphics.drawCenteredString(font, text, graphics.guiWidth() / 2, y + 9, color);
    }
}
