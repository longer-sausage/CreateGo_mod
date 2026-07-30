/*
 * Defines the lightweight visual language shared by CreateGo screens.
 * 定义 CreateGo 界面共用的轻量视觉语言。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Supplies colors and small drawing primitives for modern UI surfaces.
 * 为现代界面提供颜色和小型绘制图元。
 */
public final class UITheme {
    public static final int BACKGROUND_TOP = 0xFF0B0E14;
    public static final int BACKGROUND_BOTTOM = 0xFF111722;
    public static final int SURFACE = 0xF51A202A;
    public static final int SURFACE_RAISED = 0xFF222A36;
    public static final int SURFACE_HOVERED = 0xFF2B3544;
    public static final int FIELD = 0xFF111720;
    public static final int BORDER = 0xFF364151;
    public static final int BORDER_SUBTLE = 0xFF293240;
    public static final int ACCENT = 0xFF69C8FF;
    public static final int ACCENT_STRONG = 0xFF3298D0;
    public static final int TEXT = 0xFFF2F5F9;
    public static final int TEXT_MUTED = 0xFF929EAD;
    public static final int TEXT_DIM = 0xFF667382;
    public static final int DANGER = 0xFFE0677B;
    public static final int SUCCESS = 0xFF65D6A0;

    private UITheme() {
    }

    /**
     * Draws the opaque background without invoking Minecraft's blur pass.
     * 绘制不调用 Minecraft 模糊通道的不透明背景。
     *
     * @param graphics GUI drawing context / GUI 绘制上下文
     * @param width screen width / 屏幕宽度
     * @param height screen height / 屏幕高度
     */
    public static void drawBackground(GuiGraphics graphics, int width, int height) {
        graphics.fillGradient(0, 0, width, height, BACKGROUND_TOP, BACKGROUND_BOTTOM);
    }

    /**
     * Draws a compact rounded rectangle using only native GUI primitives.
     * 仅使用原生 GUI 图元绘制紧凑圆角矩形。
     *
     * @param graphics GUI drawing context / GUI 绘制上下文
     * @param x left coordinate / 左坐标
     * @param y top coordinate / 上坐标
     * @param width rectangle width / 矩形宽度
     * @param height rectangle height / 矩形高度
     * @param radius corner radius / 圆角半径
     * @param color ARGB fill color / ARGB 填充颜色
     */
    public static void roundedRect(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            int radius,
            int color
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int safeRadius = Math.max(1, Math.min(radius, Math.min(width, height) / 2));
        graphics.fill(x + safeRadius, y, x + width - safeRadius, y + height, color);
        graphics.fill(x, y + safeRadius, x + width, y + height - safeRadius, color);
        for (int inset = 1; inset < safeRadius; inset++) {
            int verticalInset = safeRadius - inset;
            graphics.fill(
                    x + inset,
                    y + verticalInset,
                    x + width - inset,
                    y + height - verticalInset,
                    color
            );
        }
    }

    /**
     * Draws a rounded outline by layering two rounded rectangles.
     * 通过叠放两个圆角矩形绘制圆角边框。
     *
     * @param graphics GUI drawing context / GUI 绘制上下文
     * @param x left coordinate / 左坐标
     * @param y top coordinate / 上坐标
     * @param width rectangle width / 矩形宽度
     * @param height rectangle height / 矩形高度
     * @param radius corner radius / 圆角半径
     * @param borderColor border color / 边框颜色
     * @param fillColor fill color / 填充颜色
     */
    public static void roundedPanel(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            int radius,
            int borderColor,
            int fillColor
    ) {
        roundedRect(graphics, x, y, width, height, radius, borderColor);
        roundedRect(graphics, x + 1, y + 1, width - 2, height - 2, Math.max(1, radius - 1), fillColor);
    }

    /**
     * Draws a soft two-step shadow under a floating surface.
     * 在浮动表面下绘制两级柔和阴影。
     *
     * @param graphics GUI drawing context / GUI 绘制上下文
     * @param x left coordinate / 左坐标
     * @param y top coordinate / 上坐标
     * @param width surface width / 表面宽度
     * @param height surface height / 表面高度
     * @param radius corner radius / 圆角半径
     */
    public static void shadow(GuiGraphics graphics, int x, int y, int width, int height, int radius) {
        roundedRect(graphics, x + 2, y + 4, width, height, radius + 1, 0x26000000);
        roundedRect(graphics, x + 1, y + 2, width, height, radius, 0x48000000);
    }
}
