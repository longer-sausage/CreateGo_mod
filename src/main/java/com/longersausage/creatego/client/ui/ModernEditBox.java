/*
 * Implements a flat modern text field while preserving vanilla editing behavior.
 * 在保留原版编辑行为的同时实现扁平现代文本框。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Gives EditBox a unified dark surface, crisp text, and focused accent border.
 * 为 EditBox 提供统一深色表面、清晰文字和焦点强调边框。
 */
public final class ModernEditBox extends EditBox {
    /**
     * Creates a modern text field.
     * 创建现代文本框。
     *
     * @param font font renderer / 字体渲染器
     * @param x left coordinate / 左坐标
     * @param y top coordinate / 上坐标
     * @param width field width / 文本框宽度
     * @param height field height / 文本框高度
     * @param message narration label / 旁白标签
     */
    public ModernEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
        setBordered(false);
        setTextShadow(false);
        setTextColor(UITheme.TEXT);
        setTextColorUneditable(UITheme.TEXT_DIM);
    }

    /**
     * Draws the modern field surface before delegating text and cursor rendering.
     * 在委托文字和光标渲染前绘制现代文本框表面。
     */
    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int border = isFocused() ? UITheme.ACCENT : isHovered() ? 0xFF65758A : UITheme.BORDER;
        UITheme.roundedPanel(
                graphics,
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                4,
                border,
                UITheme.FIELD
        );
        int originalX = getX();
        int originalY = getY();
        int originalWidth = getWidth();
        int originalHeight = getHeight();
        setRectangle(
                Math.max(1, originalWidth - 12),
                8,
                originalX + 6,
                originalY + (originalHeight - 8) / 2
        );
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        setRectangle(originalWidth, originalHeight, originalX, originalY);
    }

    /**
     * Compensates for the visual left padding before vanilla resolves the cursor position.
     * 在原版解析光标位置前补偿可视左内边距。
     *
     * @param mouseX mouse X / 鼠标 X
     * @param mouseY mouse Y / 鼠标 Y
     */
    @Override
    public void onClick(double mouseX, double mouseY) {
        super.onClick(mouseX - 6.0D, mouseY);
    }
}
