/*
 * Implements the shared modern button used by CreateGo screens.
 * 实现 CreateGo 界面共用的现代按钮。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Renders a flat rounded button while retaining vanilla focus, narration, and input behavior.
 * 在保留原版焦点、旁白与输入行为的同时渲染扁平圆角按钮。
 */
public final class ModernButton extends Button {
    private Variant variant;

    /**
     * Creates a themed button.
     * 创建主题按钮。
     *
     * @param x left coordinate / 左坐标
     * @param y top coordinate / 上坐标
     * @param width button width / 按钮宽度
     * @param height button height / 按钮高度
     * @param message visible label / 可见标签
     * @param onPress click callback / 点击回调
     * @param variant visual variant / 视觉变体
     */
    private ModernButton(
            int x,
            int y,
            int width,
            int height,
            Component message,
            OnPress onPress,
            Variant variant
    ) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.variant = variant;
    }

    /**
     * Starts a modern button builder.
     * 创建现代按钮构建器。
     *
     * @param message visible label / 可见标签
     * @param onPress click callback / 点击回调
     * @return configurable builder / 可配置构建器
     */
    public static Builder create(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    /**
     * Changes this button's visual variant.
     * 修改按钮的视觉变体。
     *
     * @param variant new variant / 新变体
     * @return this button / 当前按钮
     */
    public ModernButton variant(Variant variant) {
        this.variant = variant;
        return this;
    }

    /**
     * Draws the button on the same native GUI layer as its label.
     * 在与标签相同的原生 GUI 图层绘制按钮。
     */
    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean highlighted = isHoveredOrFocused();
        int fill = resolveFill(highlighted);
        int border = highlighted ? resolveAccent() : UITheme.BORDER;
        int text = active ? UITheme.TEXT : UITheme.TEXT_DIM;
        UITheme.shadow(graphics, getX(), getY(), getWidth(), getHeight(), 4);
        UITheme.roundedPanel(
                graphics,
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                4,
                border,
                fill
        );
        renderScrollingString(graphics, Minecraft.getInstance().font, 5, text);
    }

    /**
     * Resolves the fill color for current state.
     * 解析当前状态的填充颜色。
     *
     * @param highlighted whether hover or focus is active / 是否处于悬停或焦点状态
     * @return ARGB fill / ARGB 填充色
     */
    private int resolveFill(boolean highlighted) {
        if (!active) {
            return 0xFF191E27;
        }
        if (highlighted) {
            return switch (variant) {
                case PRIMARY -> 0xFF267DA9;
                case DANGER -> 0xFF713747;
                case GHOST -> 0xFF27313E;
                case NORMAL -> UITheme.SURFACE_HOVERED;
            };
        }
        return switch (variant) {
            case PRIMARY -> 0xFF1E668D;
            case DANGER -> 0xFF542D39;
            case GHOST -> 0xFF1A2029;
            case NORMAL -> UITheme.SURFACE_RAISED;
        };
    }

    /**
     * Resolves the highlighted border color.
     * 解析高亮边框颜色。
     *
     * @return ARGB border / ARGB 边框色
     */
    private int resolveAccent() {
        return switch (variant) {
            case DANGER -> UITheme.DANGER;
            case PRIMARY -> UITheme.ACCENT;
            case GHOST, NORMAL -> 0xFF718196;
        };
    }

    /**
     * Defines compact semantic button styles.
     * 定义紧凑的语义按钮样式。
     */
    public enum Variant {
        NORMAL,
        PRIMARY,
        GHOST,
        DANGER
    }

    /**
     * Builds a ModernButton with vanilla-like fluent bounds configuration.
     * 使用类似原版的流式边界配置构建 ModernButton。
     */
    public static final class Builder {
        private final Component message;
        private final OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 22;
        private Variant variant = Variant.NORMAL;

        private Builder(Component message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        /**
         * Sets all widget bounds.
         * 设置控件全部边界。
         *
         * @return this builder / 当前构建器
         */
        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * Selects a semantic visual variant.
         * 选择语义视觉变体。
         *
         * @param variant visual variant / 视觉变体
         * @return this builder / 当前构建器
         */
        public Builder variant(Variant variant) {
            this.variant = variant;
            return this;
        }

        /**
         * Creates the configured button.
         * 创建配置完成的按钮。
         *
         * @return new modern button / 新现代按钮
         */
        public ModernButton build() {
            return new ModernButton(x, y, width, height, message, onPress, variant);
        }
    }
}
