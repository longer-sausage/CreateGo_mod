/*
 * Manages independently configurable CreateGo level restrictions.
 * 管理可独立配置的 CreateGo 关卡限制规则。
 *
 * Author: CreateGo
 * Date: 2026-08-04
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.client.ui.BaseScreen;
import com.longersausage.creatego.client.ui.ModernButton;
import com.longersausage.creatego.client.ui.ModernEditBox;
import com.longersausage.creatego.client.ui.UITheme;
import com.longersausage.creatego.data.LevelDefinition;
import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows every restriction as a separate named rule with its own tree and punishment.
 * 将每条限制显示为拥有独立条件树与惩罚的具名规则。
 */
public final class LevelRestrictionScreen extends BaseScreen {
    private static final int PAGE_SIZE = 4;

    private final ModNetwork.LevelEditorView view;
    private final List<NameBinding> nameBindings = new ArrayList<>();
    private int pageIndex;

    /**
     * Creates the restriction manager for one level document.
     * 为一个关卡文档创建限制规则管理器。
     *
     * @param view complete level document / 完整关卡文档
     */
    public LevelRestrictionScreen(ModNetwork.LevelEditorView view) {
        super(Component.literal("限制规则"));
        this.view = view;
    }

    /**
     * Builds controls for the current restriction page.
     * 为当前限制规则页构建控件。
     */
    @Override
    protected void init() {
        rebuildWidgets();
    }

    /**
     * Recreates all row controls after list mutations.
     * 在列表变化后重新创建全部行控件。
     */
    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        nameBindings.clear();
        int panelWidth = Math.min(720, width - 30);
        int left = (width - panelWidth) / 2;
        int top = Math.max(8, height / 2 - 125);
        addRenderableWidget(ModernButton.create(Component.literal("+ 新增限制"), button -> addRestriction())
                .bounds(left + panelWidth - 130, top + 12, 110, 24)
                .variant(ModernButton.Variant.PRIMARY).build());
        int first = pageIndex * PAGE_SIZE;
        int last = Math.min(view.level.restrictions.size(), first + PAGE_SIZE);
        for (int index = first; index < last; index++) {
            buildRuleRow(left, panelWidth, top + 48 + (index - first) * 38, view.level.restrictions.get(index));
        }
        ModernButton previous = addRenderableWidget(ModernButton.create(Component.literal("上一页"), button -> changePage(-1))
                .bounds(left + 20, top + 220, 72, 22).build());
        previous.active = pageIndex > 0;
        ModernButton next = addRenderableWidget(ModernButton.create(Component.literal("下一页"), button -> changePage(1))
                .bounds(left + 100, top + 220, 72, 22).build());
        next.active = pageIndex + 1 < pageCount();
        addRenderableWidget(ModernButton.create(Component.literal("保存全部"), button -> save())
                .bounds(left + panelWidth - 220, top + 220, 95, 22)
                .variant(ModernButton.Variant.PRIMARY).build());
        addRenderableWidget(ModernButton.create(Component.literal("返回"), button -> back())
                .bounds(left + panelWidth - 115, top + 220, 95, 22)
                .variant(ModernButton.Variant.GHOST).build());
    }

    private void buildRuleRow(
            int left,
            int panelWidth,
            int y,
            LevelDefinition.RestrictionRule rule
    ) {
        int contentWidth = panelWidth - 40;
        int nameWidth = Math.max(100, contentWidth - 300);
        ModernEditBox nameField = addRenderableWidget(new ModernEditBox(
                font, left + 20, y, nameWidth, 26, Component.literal("限制名称")
        ));
        nameField.setMaxLength(64);
        nameField.setValue(rule.name);
        nameBindings.add(new NameBinding(rule, nameField));
        int buttonX = left + 26 + nameWidth;
        addRenderableWidget(ModernButton.create(Component.literal("编辑条件树"), button -> editRule(rule))
                .bounds(buttonX, y, 84, 26).build());
        addRenderableWidget(ModernButton.create(Component.literal(punishmentName(rule.punishment)), button ->
                cyclePunishment(rule))
                .bounds(buttonX + 90, y, 128, 26).build());
        addRenderableWidget(ModernButton.create(Component.literal("删除"), button -> deleteRule(rule))
                .bounds(buttonX + 224, y, 54, 26)
                .variant(ModernButton.Variant.DANGER).build());
    }

    private void addRestriction() {
        commitNames();
        LevelDefinition.RestrictionRule rule = new LevelDefinition.RestrictionRule();
        rule.name = "限制 " + (view.level.restrictions.size() + 1);
        view.level.restrictions.add(rule);
        pageIndex = (view.level.restrictions.size() - 1) / PAGE_SIZE;
        rebuildWidgets();
    }

    private void editRule(LevelDefinition.RestrictionRule rule) {
        commitNames();
        minecraft.setScreen(new LevelConditionScreen(view, rule));
    }

    private void cyclePunishment(LevelDefinition.RestrictionRule rule) {
        commitNames();
        rule.punishment = rule.punishment == LevelDefinition.Punishment.CONTINUOUS_DAMAGE
                ? LevelDefinition.Punishment.IMMEDIATE_FAILURE
                : LevelDefinition.Punishment.CONTINUOUS_DAMAGE;
        rebuildWidgets();
    }

    private void deleteRule(LevelDefinition.RestrictionRule rule) {
        commitNames();
        view.level.restrictions.remove(rule);
        pageIndex = Math.min(pageIndex, Math.max(0, pageCount() - 1));
        rebuildWidgets();
    }

    private void changePage(int delta) {
        commitNames();
        pageIndex = Math.max(0, Math.min(pageCount() - 1, pageIndex + delta));
        rebuildWidgets();
    }

    private int pageCount() {
        return Math.max(1, (view.level.restrictions.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void commitNames() {
        for (NameBinding binding : nameBindings) {
            String value = binding.field.getValue().strip();
            binding.rule.name = value.isBlank() ? "未命名限制" : value;
        }
    }

    private void save() {
        commitNames();
        ScreenHelper.send("save_level", view);
    }

    private void back() {
        commitNames();
        minecraft.setScreen(new LevelEditorScreen(view));
    }

    /**
     * Draws the restriction list surface and empty-state guidance.
     * 绘制限制规则列表表面和空状态提示。
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        UITheme.drawBackground(graphics, width, height);
        int panelWidth = Math.min(720, width - 30);
        int left = (width - panelWidth) / 2;
        int top = Math.max(8, height / 2 - 125);
        UITheme.shadow(graphics, left, top, panelWidth, 258, 9);
        UITheme.roundedPanel(graphics, left, top, panelWidth, 258, 9, UITheme.BORDER, UITheme.SURFACE);
        graphics.drawString(font, "独立限制规则", left + 20, top + 20, UITheme.TEXT, false);
        graphics.drawString(font, "共 " + view.level.restrictions.size() + " 条，可同时生效", left + 124, top + 20, UITheme.TEXT_DIM, false);
        if (view.level.restrictions.isEmpty()) {
            graphics.drawCenteredString(font, "尚无限制规则；新增后可分别配置条件树和惩罚。", width / 2, top + 120, UITheme.TEXT_MUTED);
        }
        graphics.drawString(font, "第 " + (pageIndex + 1) + " / " + pageCount() + " 页", left + 185, top + 227, UITheme.TEXT_DIM, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static String punishmentName(LevelDefinition.Punishment punishment) {
        return punishment == LevelDefinition.Punishment.IMMEDIATE_FAILURE
                ? "直接失败"
                : "持续熔岩伤害";
    }

    /**
     * Binds one visible name field back to its restriction rule.
     * 将一个可见名称字段绑定回对应限制规则。
     *
     * @param rule target restriction / 目标限制规则
     * @param field name field / 名称字段
     */
    private record NameBinding(LevelDefinition.RestrictionRule rule, ModernEditBox field) {
    }

    /**
     * Keeps world simulation active while restrictions are configured.
     * 在配置限制规则时保持世界模拟运行。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
