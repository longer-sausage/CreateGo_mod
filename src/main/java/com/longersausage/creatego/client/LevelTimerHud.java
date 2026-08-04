/*
 * Renders the challenge time limit without restoring the removed condition HUD.
 * 渲染挑战时间限制，但不恢复已经移除的条件 HUD。
 *
 * Author: CreateGo
 * Date: 2026-08-05
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.client.ui.UITheme;
import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws only the centered top timer for an active challenge.
 * 仅为活动挑战绘制顶部居中的倒计时。
 */
public final class LevelTimerHud {
    private LevelTimerHud() {
    }

    /**
     * Renders the current challenge time while normal gameplay HUD is visible.
     * 在正常游戏 HUD 可见时渲染当前挑战时间。
     *
     * @param graphics GUI drawing context / GUI 绘制上下文
     * @param deltaTracker frame timing / 帧计时器
     */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        ModNetwork.LevelPlayStatus status = ClientController.levelPlayStatus();
        Minecraft minecraft = Minecraft.getInstance();
        if (status == null || !status.active || !"CHALLENGE".equals(status.mode)
                || status.totalTicks <= 0 || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        renderTimer(graphics, minecraft.font, status);
    }

    /**
     * Draws the remaining-time bar and minute-second label.
     * 绘制剩余时间条与分秒标签。
     */
    private static void renderTimer(GuiGraphics graphics, Font font, ModNetwork.LevelPlayStatus status) {
        int screenWidth = graphics.guiWidth();
        int barWidth = Math.min(300, screenWidth - 80);
        int x = (screenWidth - barWidth) / 2;
        int y = 9;
        float ratio = Math.max(0.0F, Math.min(1.0F, (float) status.remainingTicks / status.totalTicks));
        UITheme.roundedPanel(graphics, x, y, barWidth, 18, 5, 0x8A425066, 0xB518202B);
        UITheme.roundedRect(graphics, x + 3, y + 13, barWidth - 6, 2, 1, 0x66364151);
        int progressWidth = Math.round((barWidth - 6) * ratio);
        int progressColor = ratio > 0.25F ? UITheme.ACCENT : UITheme.DANGER;
        UITheme.roundedRect(graphics, x + 3, y + 13, progressWidth, 2, 1, progressColor);
        int seconds = Math.max(0, (status.remainingTicks + 19) / 20);
        String time = String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        graphics.drawCenteredString(font, "剩余时间  " + time, screenWidth / 2, y + 3, UITheme.TEXT);
    }
}
