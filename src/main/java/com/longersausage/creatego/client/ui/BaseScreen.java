/*
 * Provides a blur-safe base class for all CreateGo screens.
 * 为全部 CreateGo 界面提供不会错层模糊的基类。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Prevents the base Screen renderer from blurring content already submitted by a child screen.
 * 防止 Screen 基类渲染器模糊子界面已经提交的内容。
 */
public abstract class BaseScreen extends Screen {
    /**
     * Creates a base screen.
     * 创建基础界面。
     *
     * @param title accessible screen title / 可访问性界面标题
     */
    protected BaseScreen(Component title) {
        super(title);
    }

    /**
     * Intentionally submits no background because child screens draw it before their widgets.
     * 有意不提交背景，因为子界面会在控件之前自行绘制背景。
     *
     * @param graphics GUI drawing context / GUI 绘制上下文
     * @param mouseX mouse X / 鼠标 X
     * @param mouseY mouse Y / 鼠标 Y
     * @param partialTick partial frame time / 局部帧时间
     */
    @Override
    public final void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The vanilla method runs a framebuffer blur and must stay before custom content. / 原版方法会执行帧缓冲模糊，不能在自定义内容之后调用。
    }
}
