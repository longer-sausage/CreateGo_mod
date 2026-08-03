/*
 * Implements the visual tree editor for CreateGo level conditions.
 * 实现 CreateGo 关卡条件的可视化树编辑器。
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
 * Presents a scrollable tree diagram and a context-sensitive node property panel.
 * 提供可滚动树状图和随节点变化的属性面板。
 */
public final class LevelConditionScreen extends BaseScreen {
    private static final int ROW_HEIGHT = 30;
    private static final LevelDefinition.NodeType[] PREDICATE_TYPES = {
            LevelDefinition.NodeType.PLAYER_DISTANCE,
            LevelDefinition.NodeType.PLAYER_COORDINATE,
            LevelDefinition.NodeType.ENTITY_COUNT,
            LevelDefinition.NodeType.ENTITY_DISTANCE,
            LevelDefinition.NodeType.ENTITY_COORDINATE,
            LevelDefinition.NodeType.PLAYER_TOUCHING_BLOCK,
            LevelDefinition.NodeType.SUBJECT_BLOCK_DISTANCE
    };

    private final ModNetwork.LevelEditorView view;
    private final boolean completion;
    private final LevelDefinition.RestrictionRule restrictionRule;
    private final List<NodeRow> rows = new ArrayList<>();
    private final List<FieldLabel> fieldLabels = new ArrayList<>();
    private LevelDefinition.ConditionNode selected;
    private int scrollOffset;
    private ModernEditBox xField;
    private ModernEditBox yField;
    private ModernEditBox zField;
    private ModernEditBox valueField;
    private ModernEditBox entityField;
    private ModernEditBox blockField;

    /**
     * Creates an editor for the completion condition root.
     * 为过关条件根节点创建编辑器。
     *
     * @param view complete level document / 完整关卡文档
     */
    public LevelConditionScreen(ModNetwork.LevelEditorView view) {
        super(Component.literal("过关条件树"));
        this.view = view;
        this.completion = true;
        this.restrictionRule = null;
        this.selected = root();
    }

    /**
     * Creates an editor for one independently configured restriction rule.
     * 为一条独立配置的限制规则创建编辑器。
     *
     * @param view complete level document / 完整关卡文档
     * @param restrictionRule edited restriction / 正在编辑的限制规则
     */
    public LevelConditionScreen(
            ModNetwork.LevelEditorView view,
            LevelDefinition.RestrictionRule restrictionRule
    ) {
        super(Component.literal("限制条件树 · " + restrictionRule.name));
        this.view = view;
        this.completion = false;
        this.restrictionRule = restrictionRule;
        this.selected = root();
    }

    /**
     * Rebuilds the node rows and context-sensitive widgets.
     * 重建节点行和随上下文变化的控件。
     */
    @Override
    protected void init() {
        rebuildRows();
        rebuildWidgets();
    }

    /**
     * Recreates controls after selection or node type changes.
     * 在选择或节点类型变化后重新创建控件。
     */
    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        resetFields();
        int divider = Math.max(330, width * 55 / 100);
        int panelX = divider + 18;
        int panelWidth = width - panelX - 20;
        addRenderableWidget(ModernButton.create(Component.literal("保存并返回"), button -> saveAndReturn())
                .bounds(20, height - 38, 135, 24)
                .variant(ModernButton.Variant.PRIMARY).build());
        addRenderableWidget(ModernButton.create(Component.literal("返回"), button ->
                minecraft.setScreen(completion
                        ? new LevelEditorScreen(view)
                        : new LevelRestrictionScreen(view)))
                .bounds(165, height - 38, 85, 24)
                .variant(ModernButton.Variant.GHOST).build());
        if (selected == null || panelWidth < 180) {
            return;
        }
        if (selected.isLogic()) {
            buildLogicWidgets(panelX, panelWidth);
        } else {
            buildPredicateWidgets(panelX, panelWidth);
        }
    }

    private void buildLogicWidgets(int x, int width) {
        addRenderableWidget(ModernButton.create(Component.literal("运算：" + logicName(selected.type)), button -> {
            commitFields();
            LevelDefinition.NodeType[] values = {
                    LevelDefinition.NodeType.AND,
                    LevelDefinition.NodeType.OR,
                    LevelDefinition.NodeType.NOT
            };
            int current = java.util.Arrays.asList(values).indexOf(selected.type);
            selected.type = values[(current + 1) % values.length];
            if (selected.type == LevelDefinition.NodeType.NOT && selected.children.size() > 1) {
                selected.children = new ArrayList<>(selected.children.subList(0, 1));
            }
            refreshTree();
        }).bounds(x, 76, width, 24).build());
        int half = (width - 8) / 2;
        addRenderableWidget(ModernButton.create(Component.literal("+ 逻辑节点"), button -> addLogic())
                .bounds(x, 112, half, 24).build());
        addRenderableWidget(ModernButton.create(Component.literal("+ 具体条件"), button -> addPredicate())
                .bounds(x + half + 8, 112, half, 24)
                .variant(ModernButton.Variant.PRIMARY).build());
        if (selected != root()) {
            addRenderableWidget(ModernButton.create(Component.literal("删除此节点"), button -> deleteSelected())
                    .bounds(x, 148, width, 22)
                    .variant(ModernButton.Variant.DANGER).build());
        }
    }

    private void buildPredicateWidgets(int x, int width) {
        addRenderableWidget(ModernButton.create(Component.literal("类型：" + predicateName(selected.type)), button -> {
            commitFields();
            int current = java.util.Arrays.asList(PREDICATE_TYPES).indexOf(selected.type);
            selected.type = PREDICATE_TYPES[(current + 1) % PREDICATE_TYPES.length];
            rebuildWidgets();
        }).bounds(x, 76, width, 24).build());
        int y = 110;
        if (usesComparison(selected.type)) {
            addRenderableWidget(ModernButton.create(Component.literal("比较：" + comparisonName(selected.comparison)), button -> {
                commitFields();
                selected.comparison = cycle(selected.comparison, LevelDefinition.Comparison.values());
                rebuildWidgets();
            }).bounds(x, y, width, 22).build());
            y += 30;
        }
        if (usesAxis(selected.type)) {
            addRenderableWidget(ModernButton.create(Component.literal("坐标轴：" + selected.axis), button -> {
                commitFields();
                selected.axis = cycle(selected.axis, LevelDefinition.Axis.values());
                rebuildWidgets();
            }).bounds(x, y, width, 22).build());
            y += 30;
        }
        if (selected.type == LevelDefinition.NodeType.SUBJECT_BLOCK_DISTANCE) {
            addRenderableWidget(ModernButton.create(Component.literal("主体：" + subjectName(selected.subject)), button -> {
                commitFields();
                selected.subject = cycle(selected.subject, LevelDefinition.Subject.values());
                rebuildWidgets();
            }).bounds(x, y, width, 22).build());
            y += 30;
        }
        if (usesEntityMatch(selected)) {
            addRenderableWidget(ModernButton.create(Component.literal("匹配：" + matchName(selected.entityMatch)), button -> {
                commitFields();
                selected.entityMatch = cycle(selected.entityMatch, LevelDefinition.EntityMatch.values());
                rebuildWidgets();
            }).bounds(x, y, width, 22).build());
            y += 30;
        }
        if (usesEntityType(selected)) {
            entityField = addField(x, y, width, "实体 ID", selected.entityType);
            y += 42;
        }
        if (usesPoint(selected.type)) {
            int fieldWidth = Math.max(48, (width - 16) / 3);
            xField = addField(x, y, fieldWidth, "X", format(selected.x));
            yField = addField(x + fieldWidth + 8, y, fieldWidth, "Y", format(selected.y));
            zField = addField(x + (fieldWidth + 8) * 2, y, fieldWidth, "Z", format(selected.z));
            y += 42;
        }
        if (usesBlock(selected.type)) {
            blockField = addField(x, y, width, "方块/流体 ID", selected.blockId);
            y += 42;
        }
        if (usesComparison(selected.type)) {
            valueField = addField(x, y, width, "比较值", format(selected.value));
            y += 42;
        }
        addRenderableWidget(ModernButton.create(Component.literal("删除此节点"), button -> deleteSelected())
                .bounds(x, Math.min(height - 70, y + 4), width, 22)
                .variant(ModernButton.Variant.DANGER).build());
    }

    private ModernEditBox addField(int x, int y, int width, String label, String value) {
        fieldLabels.add(new FieldLabel(label, x, y));
        ModernEditBox field = addRenderableWidget(new ModernEditBox(
                font, x, y + 11, width, 22, Component.literal(label)
        ));
        field.setHint(Component.literal(label));
        field.setValue(value);
        return field;
    }

    private void addLogic() {
        if (!canAddChild()) {
            return;
        }
        LevelDefinition.ConditionNode child = LevelDefinition.ConditionNode.group(LevelDefinition.NodeType.AND);
        selected.children.add(child);
        selected = child;
        refreshTree();
    }

    private void addPredicate() {
        if (!canAddChild()) {
            return;
        }
        LevelDefinition.ConditionNode child = new LevelDefinition.ConditionNode();
        selected.children.add(child);
        selected = child;
        refreshTree();
    }

    private boolean canAddChild() {
        commitFields();
        if (!selected.isLogic()) {
            ScreenHelper.message("请先选择一个逻辑节点。");
            return false;
        }
        if (selected.type == LevelDefinition.NodeType.NOT && !selected.children.isEmpty()) {
            ScreenHelper.message("NOT 节点只能包含一个子节点。");
            return false;
        }
        return true;
    }

    private void deleteSelected() {
        if (selected == null || selected == root()) {
            ScreenHelper.message("根节点不能删除。");
            return;
        }
        LevelDefinition.ConditionNode parent = findParent(root(), selected);
        if (parent != null) {
            parent.children.remove(selected);
            selected = parent;
            refreshTree();
        }
    }

    private LevelDefinition.ConditionNode findParent(
            LevelDefinition.ConditionNode node,
            LevelDefinition.ConditionNode target
    ) {
        for (LevelDefinition.ConditionNode child : node.children) {
            if (child == target) {
                return node;
            }
            LevelDefinition.ConditionNode nested = findParent(child, target);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private void refreshTree() {
        rebuildRows();
        rebuildWidgets();
    }

    private void rebuildRows() {
        rows.clear();
        appendRows(root(), 0);
        int maximum = Math.max(0, rows.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(scrollOffset, maximum));
    }

    private void appendRows(LevelDefinition.ConditionNode node, int depth) {
        rows.add(new NodeRow(node, depth));
        if (node.children != null) {
            for (LevelDefinition.ConditionNode child : node.children) {
                appendRows(child, depth + 1);
            }
        }
    }

    private int visibleRows() {
        return Math.max(1, (height - 112) / ROW_HEIGHT);
    }

    private void commitFields() {
        if (selected == null || selected.isLogic()) {
            return;
        }
        try {
            if (xField != null) {
                selected.x = ScreenHelper.parseDouble(xField.getValue(), "X");
                selected.y = ScreenHelper.parseDouble(yField.getValue(), "Y");
                selected.z = ScreenHelper.parseDouble(zField.getValue(), "Z");
            }
            if (valueField != null) {
                selected.value = ScreenHelper.parseDouble(valueField.getValue(), "比较值");
            }
            if (entityField != null) {
                selected.entityType = entityField.getValue().strip();
            }
            if (blockField != null) {
                selected.blockId = blockField.getValue().strip();
            }
        } catch (IllegalArgumentException exception) {
            ScreenHelper.message(exception.getMessage());
        }
    }

    private void saveAndReturn() {
        commitFields();
        ScreenHelper.send(completion ? "save_level" : "save_level_restrictions", view);
    }

    /**
     * Selects diagram nodes before delegating remaining clicks to widgets.
     * 在将其余点击交给控件前选择树状图节点。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int divider = Math.max(330, width * 55 / 100);
        if (button == 0 && mouseX >= 20 && mouseX < divider - 12 && mouseY >= 54 && mouseY < height - 48) {
            int visualRow = (int) ((mouseY - 54) / ROW_HEIGHT);
            int index = scrollOffset + visualRow;
            if (index >= 0 && index < rows.size()) {
                commitFields();
                selected = rows.get(index).node;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Scrolls only the condition tree pane.
     * 仅滚动条件树窗格。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int divider = Math.max(330, width * 55 / 100);
        if (mouseX < divider) {
            int maximum = Math.max(0, rows.size() - visibleRows());
            int delta = scrollY > 0.0D ? -1 : 1;
            scrollOffset = Math.max(0, Math.min(maximum, scrollOffset + delta));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * Draws connected tree cards and the selected node inspector.
     * 绘制相连的树节点卡片和所选节点检查器。
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        UITheme.drawBackground(graphics, width, height);
        int divider = Math.max(330, width * 55 / 100);
        UITheme.roundedPanel(graphics, 12, 38, divider - 22, height - 84, 8, UITheme.BORDER, UITheme.SURFACE);
        UITheme.roundedPanel(graphics, divider + 8, 38, width - divider - 20, height - 58, 8, UITheme.BORDER, UITheme.SURFACE);
        graphics.drawString(font, title, 24, 20, UITheme.TEXT, false);
        graphics.drawString(font, "点击节点编辑 · 滚轮浏览", 130, 20, UITheme.TEXT_DIM, false);
        renderTree(graphics, divider);
        renderInspectorText(graphics, divider + 18);
        for (FieldLabel label : fieldLabels) {
            graphics.drawString(font, label.text, label.x, label.y, UITheme.TEXT_MUTED, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderTree(GuiGraphics graphics, int divider) {
        int count = Math.min(visibleRows(), rows.size() - scrollOffset);
        for (int visual = 0; visual < count; visual++) {
            NodeRow row = rows.get(scrollOffset + visual);
            int y = 54 + visual * ROW_HEIGHT;
            int x = 26 + row.depth * 20;
            int cardWidth = Math.max(100, divider - x - 34);
            if (row.depth > 0) {
                int branchX = x - 12;
                graphics.fill(branchX, y - 15, branchX + 1, y + 13, UITheme.BORDER);
                graphics.fill(branchX, y + 12, x - 2, y + 13, UITheme.BORDER);
            }
            int border = row.node == selected ? UITheme.ACCENT : UITheme.BORDER_SUBTLE;
            int fill = row.node.isLogic() ? 0xFF202B38 : UITheme.SURFACE_RAISED;
            UITheme.roundedPanel(graphics, x, y, cardWidth, 24, 5, border, fill);
            int badge = row.node.isLogic() ? UITheme.ACCENT : UITheme.SUCCESS;
            UITheme.roundedRect(graphics, x + 7, y + 7, 10, 10, 3, badge);
            String label = nodeLabel(row.node);
            graphics.drawString(font, font.plainSubstrByWidth(label, cardWidth - 31), x + 24, y + 8, UITheme.TEXT, false);
        }
        if (rows.size() > visibleRows()) {
            graphics.drawString(font, (scrollOffset + 1) + "–" + Math.min(rows.size(), scrollOffset + visibleRows())
                    + " / " + rows.size(), divider - 86, height - 58, UITheme.TEXT_DIM, false);
        }
    }

    private void renderInspectorText(GuiGraphics graphics, int x) {
        if (selected == null) {
            return;
        }
        graphics.drawString(font, "节点属性", x, 52, UITheme.TEXT, false);
        if (selected.isLogic()) {
            String help = switch (selected.type) {
                case AND -> "全部子条件成立时成立";
                case OR -> "任意子条件成立时成立";
                case NOT -> "反转唯一子条件的结果";
                default -> "";
            };
            graphics.drawString(font, help, x, 184, UITheme.TEXT_MUTED, false);
            graphics.drawString(font, "子节点：" + selected.children.size(), x, 202, UITheme.TEXT_DIM, false);
        } else {
            graphics.drawString(font, "输入资源 ID 时请包含命名空间，例如 minecraft:pig。", x, height - 34, UITheme.TEXT_DIM, false);
        }
    }

    private LevelDefinition.ConditionNode root() {
        return completion ? view.level.completionCondition : restrictionRule.condition;
    }

    private void resetFields() {
        xField = null;
        yField = null;
        zField = null;
        valueField = null;
        entityField = null;
        blockField = null;
        fieldLabels.clear();
    }

    private static boolean usesComparison(LevelDefinition.NodeType type) {
        return type != LevelDefinition.NodeType.PLAYER_TOUCHING_BLOCK;
    }

    private static boolean usesAxis(LevelDefinition.NodeType type) {
        return type == LevelDefinition.NodeType.PLAYER_COORDINATE
                || type == LevelDefinition.NodeType.ENTITY_COORDINATE;
    }

    private static boolean usesPoint(LevelDefinition.NodeType type) {
        return type == LevelDefinition.NodeType.PLAYER_DISTANCE
                || type == LevelDefinition.NodeType.ENTITY_DISTANCE;
    }

    private static boolean usesBlock(LevelDefinition.NodeType type) {
        return type == LevelDefinition.NodeType.PLAYER_TOUCHING_BLOCK
                || type == LevelDefinition.NodeType.SUBJECT_BLOCK_DISTANCE;
    }

    private static boolean usesEntityType(LevelDefinition.ConditionNode node) {
        return node.type == LevelDefinition.NodeType.ENTITY_COUNT
                || node.type == LevelDefinition.NodeType.ENTITY_DISTANCE
                || node.type == LevelDefinition.NodeType.ENTITY_COORDINATE
                || node.type == LevelDefinition.NodeType.SUBJECT_BLOCK_DISTANCE
                && node.subject == LevelDefinition.Subject.ENTITY;
    }

    private static boolean usesEntityMatch(LevelDefinition.ConditionNode node) {
        return node.type == LevelDefinition.NodeType.ENTITY_DISTANCE
                || node.type == LevelDefinition.NodeType.ENTITY_COORDINATE
                || node.type == LevelDefinition.NodeType.SUBJECT_BLOCK_DISTANCE
                && node.subject == LevelDefinition.Subject.ENTITY;
    }

    private static <T> T cycle(T current, T[] values) {
        int index = java.util.Arrays.asList(values).indexOf(current);
        return values[(index + 1) % values.length];
    }

    private static String format(double value) {
        return Double.toString(value);
    }

    private static String nodeLabel(LevelDefinition.ConditionNode node) {
        if (node.isLogic()) {
            return logicName(node.type) + "  ·  " + node.children.size() + " 个子节点";
        }
        return predicateName(node.type);
    }

    private static String logicName(LevelDefinition.NodeType type) {
        return switch (type) {
            case AND -> "AND（全部）";
            case OR -> "OR（任意）";
            case NOT -> "NOT（取反）";
            default -> "条件";
        };
    }

    private static String predicateName(LevelDefinition.NodeType type) {
        return switch (type) {
            case PLAYER_DISTANCE -> "玩家与坐标的距离";
            case PLAYER_COORDINATE -> "玩家坐标";
            case ENTITY_COUNT -> "地图实体数量";
            case ENTITY_DISTANCE -> "实体与坐标的距离";
            case ENTITY_COORDINATE -> "实体坐标";
            case PLAYER_TOUCHING_BLOCK -> "玩家接触方块/流体";
            case SUBJECT_BLOCK_DISTANCE -> "玩家/实体与方块的距离";
            default -> logicName(type);
        };
    }

    private static String comparisonName(LevelDefinition.Comparison comparison) {
        return switch (comparison) {
            case GREATER_OR_EQUAL -> "≥ 大于等于";
            case GREATER -> "> 大于";
            case EQUAL -> "= 等于";
            case NOT_EQUAL -> "≠ 不等于";
            case LESS -> "< 小于";
            case LESS_OR_EQUAL -> "≤ 小于等于";
        };
    }

    private static String subjectName(LevelDefinition.Subject subject) {
        return subject == LevelDefinition.Subject.PLAYER ? "玩家" : "指定实体";
    }

    private static String matchName(LevelDefinition.EntityMatch match) {
        return match == LevelDefinition.EntityMatch.ANY ? "至少一个" : "全部";
    }

    /**
     * Holds one flattened tree row and its visual depth.
     * 保存一条扁平树行及其可视深度。
     *
     * @param node represented node / 表示的节点
     * @param depth nesting depth / 嵌套深度
     */
    private record NodeRow(LevelDefinition.ConditionNode node, int depth) {
    }

    /**
     * Stores one property field label position.
     * 保存一条属性字段标签的位置。
     *
     * @param text visible label / 可见标签
     * @param x left coordinate / 左坐标
     * @param y top coordinate / 上坐标
     */
    private record FieldLabel(String text, int x, int y) {
    }

    /**
     * Keeps the game simulation running while conditions are edited.
     * 在编辑条件时保持游戏模拟运行。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
