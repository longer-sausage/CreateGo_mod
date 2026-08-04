/*
 * Displays public information and entry actions for one playable level.
 * 显示一个可游玩关卡的公开信息与进入操作。
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
 * Presents level metadata, completion rules, restrictions, preview, and challenge actions.
 * 展示关卡元数据、过关条件、限制规则、预览与挑战操作。
 */
public final class LevelDetailsScreen extends BaseScreen {
    private final ModNetwork.LevelDetailsView view;

    /**
     * Creates a read-only level details screen.
     * 创建只读关卡详情界面。
     *
     * @param view synchronized level details / 已同步关卡详情
     */
    public LevelDetailsScreen(ModNetwork.LevelDetailsView view) {
        super(Component.translatable("screen.creatego.level_details"));
        this.view = view;
    }

    /**
     * Builds preview and challenge controls.
     * 构建预览与挑战控件。
     */
    @Override
    protected void init() {
        int left = width / 2 - 230;
        int top = height / 2 + 112;
        addRenderableWidget(ModernButton.create(Component.translatable("button.creatego.preview_level"), button -> {
            ScreenHelper.send("start_level_preview", new LevelRequest(view.mapId));
            onClose();
        }).bounds(left + 20, top, 200, 28).build());
        addRenderableWidget(ModernButton.create(Component.translatable("button.creatego.start_challenge"), button -> {
            ScreenHelper.send("start_level_challenge", new LevelRequest(view.mapId));
            onClose();
        }).bounds(left + 240, top, 200, 28).variant(ModernButton.Variant.PRIMARY).build());
    }

    /**
     * Draws metadata and compact rule summaries.
     * 绘制元数据与紧凑规则摘要。
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        UITheme.drawBackground(graphics, width, height);
        int left = width / 2 - 230;
        int top = height / 2 - 150;
        UITheme.shadow(graphics, left, top, 460, 305, 10);
        UITheme.roundedPanel(graphics, left, top, 460, 305, 10, UITheme.BORDER, UITheme.SURFACE);
        graphics.drawString(font, Component.translatable("screen.creatego.level_details"), left + 20, top + 18, UITheme.TEXT, false);
        graphics.drawString(font, view.mapId, left + 124, top + 18, UITheme.ACCENT, false);
        String metadata = Component.translatable(
                "screen.creatego.level_metadata",
                view.terrainType,
                formatTime(view.timeLimitSeconds),
                view.structureCount,
                view.npcCount
        ).getString();
        graphics.drawString(font, metadata, left + 20, top + 42, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, Component.translatable("screen.creatego.completion_conditions"), left + 20, top + 70, UITheme.TEXT, false);
        drawLines(graphics, view.completionConditions, left + 28, top + 88, 5, 190);
        graphics.drawString(font, Component.translatable("screen.creatego.failure_conditions"), left + 240, top + 70, UITheme.TEXT, false);
        int y = top + 88;
        int visible = Math.min(3, view.restrictions.size());
        for (int index = 0; index < visible; index++) {
            ModNetwork.RestrictionView rule = view.restrictions.get(index);
            String punishment = "IMMEDIATE_FAILURE".equals(rule.punishment) ? "立即失败" : "持续伤害";
            graphics.drawString(font, font.plainSubstrByWidth("• " + rule.name + " · " + punishment, 190), left + 248, y, UITheme.TEXT_MUTED, false);
            String condition = rule.conditions.isEmpty() ? "未配置条件" : rule.conditions.getFirst();
            graphics.drawString(font, font.plainSubstrByWidth("  " + condition, 190), left + 248, y + 14, UITheme.TEXT_DIM, false);
            y += 34;
        }
        if (view.restrictions.isEmpty()) {
            graphics.drawString(font, "无", left + 248, y, UITheme.TEXT_DIM, false);
        }
        graphics.drawString(font, Component.translatable("screen.creatego.challenge_hint"), left + 20, top + 222, UITheme.TEXT_DIM, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Draws a bounded list of compact rule descriptions.
     * 绘制数量受限的紧凑规则说明列表。
     */
    private void drawLines(GuiGraphics graphics, java.util.List<String> lines, int x, int y, int limit, int maxWidth) {
        int visible = Math.min(limit, lines.size());
        for (int index = 0; index < visible; index++) {
            graphics.drawString(font, font.plainSubstrByWidth("• " + lines.get(index), maxWidth), x, y + index * 18, UITheme.TEXT_MUTED, false);
        }
        if (lines.isEmpty()) {
            graphics.drawString(font, "未配置", x, y, UITheme.TEXT_DIM, false);
        } else if (lines.size() > limit) {
            graphics.drawString(font, "+" + (lines.size() - limit) + " 项", x, y + limit * 18, UITheme.TEXT_DIM, false);
        }
    }

    /**
     * Formats seconds as a stable minute-second string.
     * 将秒数格式化为稳定的分秒字符串。
     */
    private static String formatTime(int seconds) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * Identifies a level entry request.
     * 标识一次关卡进入请求。
     *
     * @param mapId selected map identifier / 所选地图标识
     */
    public record LevelRequest(String mapId) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
