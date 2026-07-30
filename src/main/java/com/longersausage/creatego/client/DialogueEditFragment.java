/*
 * Implements the Modern UI vector dialogue graph editor.
 * 实现基于 Modern UI 的矢量对话图编辑器。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.data.DialogueGraph;
import com.longersausage.creatego.data.NpcData;
import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.graphics.drawable.Drawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.graphics.drawable.StateListDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.util.StateSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewConfiguration;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.PopupMenu;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Owns a ComfyUI-inspired node canvas, toolbar, zoom controls, and property panel.
 * 管理参考 ComfyUI 的节点画布、工具栏、缩放控件和属性面板。
 */
public final class DialogueEditFragment extends Fragment {
    private static final int TOOLBAR_HEIGHT = 58;
    private static final int INSPECTOR_WIDTH = 340;
    private static final int MAX_NODES = 512;
    private static final int MAX_OUTPUTS = 32;
    private static final int MAX_WORLD_COORDINATE = 90_000;
    private static final float MIN_ZOOM = 0.25F;
    private static final float MAX_ZOOM = 4.0F;
    private static final int COLOR_BACKGROUND = 0xFF0B0D10;
    private static final int COLOR_SURFACE = 0xFF171A1F;
    private static final int COLOR_SURFACE_HIGH = 0xFF20242B;
    private static final int COLOR_BORDER = 0xFF343A43;
    private static final int COLOR_TEXT = 0xFFF1F4F8;
    private static final int COLOR_MUTED = 0xFF9AA3AF;
    private static final int COLOR_ACCENT = 0xFF4DA3D4;
    private static final int COLOR_DANGER = 0xFFDA6575;

    private final NpcData npc;
    private final Map<Integer, NodeCard> nodeCards = new HashMap<>();
    private FrameLayout root;
    private GraphViewport viewport;
    private GraphLayer graphLayer;
    private LinearLayout inspectorContent;
    private TextView zoomLabel;
    private TextView connectionHint;
    private DialogueGraph.NodeData selectedNode;
    private SelectedConnection selectedConnection;
    private PendingPort pendingPort;
    private boolean connectionDragging;
    private float connectionX;
    private float connectionY;
    private float zoom = 1.0F;
    private float cameraX;
    private float cameraY;
    private int touchSlop;

    /**
     * Creates an editor for one synchronized NPC document.
     * 为一个已同步的 NPC 文档创建编辑器。
     *
     * @param npc NPC document / NPC 文档
     */
    public DialogueEditFragment(NpcData npc) {
        this.npc = npc;
    }

    /**
     * Builds the full-screen Modern UI hierarchy.
     * 构建全屏 Modern UI 视图层级。
     *
     * @param inflater layout inflater / 布局加载器
     * @param container parent container / 父容器
     * @param savedInstanceState saved state / 已保存状态
     * @return editor root view / 编辑器根视图
     */
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable DataSet savedInstanceState
    ) {
        Context context = getContext();
        root = new FrameLayout(context);
        root.setBackground(shape(COLOR_BACKGROUND, 0, 0));
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        npc.dialogue.ensureEntryNode();
        viewport = new GraphViewport(context);
        graphLayer = new GraphLayer(context);
        viewport.addView(graphLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        FrameLayout.LayoutParams viewportParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        viewportParams.setMargins(0, dp(TOOLBAR_HEIGHT), dp(INSPECTOR_WIDTH), 0);
        root.addView(viewport, viewportParams);
        root.addView(buildToolbar(context), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(TOOLBAR_HEIGHT),
                Gravity.TOP
        ));
        root.addView(buildInspector(context), inspectorLayoutParams());
        root.addView(buildZoomBar(context), zoomLayoutParams());
        rebuildCards();
        viewport.post(this::fitView);
        return root;
    }

    /**
     * Builds the top command bar with native-looking modern pills.
     * 构建带现代胶囊按钮的顶部命令栏。
     */
    private View buildToolbar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(9), dp(16), dp(9));
        bar.setBackground(shape(0xF0171A1F, 0, 1));
        addToolbarButton(bar, "+ 对话", () -> addNode(DialogueGraph.NodeType.DIALOGUE), false);
        addToolbarButton(bar, "+ 选项", () -> addNode(DialogueGraph.NodeType.OPTION), false);
        addToolbarButton(bar, "+ 分支", () -> addNode(DialogueGraph.NodeType.BRANCH), false);
        TextView title = text(context, "对话工作流  ·  " + npc.name, 15, COLOR_MUTED);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
        );
        titleParams.setMargins(dp(20), 0, dp(20), 0);
        bar.addView(title, titleParams);
        connectionHint = text(context, "从输出端口拖动到目标入口", 12, COLOR_MUTED);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        hintParams.setMargins(0, 0, dp(12), 0);
        bar.addView(connectionHint, hintParams);
        addToolbarButton(bar, "应用", this::saveGraph, true);
        addToolbarButton(bar, "返回", this::returnToNpcEditor, false);
        return bar;
    }

    /**
     * Builds the right-side live property editor.
     * 构建右侧实时属性编辑器。
     */
    private View buildInspector(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(shape(0xF5171A1F, 0, 1));
        TextView heading = text(context, "属性", 18, COLOR_TEXT);
        panel.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
        ));
        ScrollView scroll = new ScrollView(context);
        inspectorContent = new LinearLayout(context);
        inspectorContent.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(inspectorContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        rebuildInspector();
        return panel;
    }

    /**
     * Builds floating zoom controls.
     * 构建悬浮缩放控件。
     */
    private View buildZoomBar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setBackground(shape(0xF020242B, 12, 1));
        addSmallButton(bar, "−", () -> zoomAtCenter(0.72F));
        zoomLabel = text(context, "100%", 13, COLOR_TEXT);
        zoomLabel.setGravity(Gravity.CENTER);
        bar.addView(zoomLabel, new LinearLayout.LayoutParams(dp(74), dp(34)));
        addSmallButton(bar, "+", () -> zoomAtCenter(1.38F));
        addSmallButton(bar, "适应", this::fitView);
        return bar;
    }

    /**
     * Creates and raises one graph node at the exact visible center.
     * 在当前可见中心准确创建并置顶一个图节点。
     *
     * @param type node type / 节点类型
     */
    private void addNode(DialogueGraph.NodeType type) {
        if (npc.dialogue.nodes.size() >= MAX_NODES) {
            connectionHint.setText("单个 NPC 最多允许 " + MAX_NODES + " 个节点");
            return;
        }
        DialogueGraph.NodeData node = new DialogueGraph.NodeData();
        node.id = npc.dialogue.nextNodeId();
        node.type = type;
        node.text = "";
        if (type == DialogueGraph.NodeType.OPTION) {
            node.options.add(new DialogueGraph.OptionData());
        } else if (type == DialogueGraph.NodeType.BRANCH) {
            node.branches.add(new DialogueGraph.BranchCase());
        }
        int nodeWidth = nodeWidth(node);
        int nodeHeight = nodeHeight(node);
        int desiredX = Math.round(viewportToWorldX(viewport.getWidth() * 0.5F) - nodeWidth * 0.5F);
        int desiredY = Math.round(viewportToWorldY(viewport.getHeight() * 0.5F) - nodeHeight * 0.5F);
        node.x = desiredX;
        node.y = desiredY;
        npc.dialogue.nodes.add(node);
        selectedNode = node;
        selectedConnection = null;
        pendingPort = null;
        connectionDragging = false;
        rebuildCards();
        rebuildInspector();
        NodeCard card = nodeCards.get(node.id);
        if (card != null) {
            graphLayer.bringChildToFront(card);
        }
        connectionHint.setText("已创建：" + nodeTitle(node.type) + " #" + node.id);
    }

    /**
     * Recreates node cards after structural data changes.
     * 在结构数据变化后重建节点卡片。
     */
    private void rebuildCards() {
        graphLayer.removeAllViews();
        nodeCards.clear();
        for (DialogueGraph.NodeData node : npc.dialogue.nodes) {
            clampNodePosition(node);
            NodeCard card = new NodeCard(getContext(), node);
            nodeCards.put(node.id, card);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    card.cardWidth(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            graphLayer.addView(card, params);
        }
        if (selectedNode != null) {
            NodeCard selectedCard = nodeCards.get(selectedNode.id);
            if (selectedCard != null) {
                graphLayer.bringChildToFront(selectedCard);
            }
        }
        applyTransform();
        graphLayer.invalidate();
    }

    /**
     * Keeps a node inside the finite world layer used for reliable hit testing.
     * 将节点限制在用于可靠命中测试的有限世界层内。
     */
    private void clampNodePosition(DialogueGraph.NodeData node) {
        int maximumX = MAX_WORLD_COORDINATE - nodeWidth(node);
        int maximumY = MAX_WORLD_COORDINATE - nodeHeight(node);
        node.x = Math.max(-MAX_WORLD_COORDINATE, Math.min(maximumX, node.x));
        node.y = Math.max(-MAX_WORLD_COORDINATE, Math.min(maximumY, node.y));
    }

    /**
     * Commits one node's model position into real layout bounds.
     * 将节点模型位置提交为真实布局边界。
     */
    private void layoutNode(NodeCard card) {
        positionNodeCard(card);
        graphLayer.invalidate();
    }

    /**
     * Maps one node from world coordinates into true viewport layout bounds.
     * 将一个节点从世界坐标映射到真实的视口布局边界。
     *
     * @param card node card / 节点卡片
     */
    private void positionNodeCard(NodeCard card) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) card.getLayoutParams();
        int left = Math.round(cameraX + card.node.x * zoom);
        int top = Math.round(cameraY + card.node.y * zoom);
        params.leftMargin = left;
        params.topMargin = top;
        if (card.isLaidOut()) {
            card.offsetLeftAndRight(left - card.getLeft());
            card.offsetTopAndBottom(top - card.getTop());
        }
    }

    /**
     * Rebuilds the inspector from the currently selected node.
     * 按当前选中节点重建属性面板。
     */
    private void rebuildInspector() {
        if (inspectorContent == null) {
            return;
        }
        inspectorContent.removeAllViews();
        if (selectedConnection != null) {
            DialogueGraph.NodeData source = npc.dialogue.findNode(selectedConnection.nodeId);
            int targetId = source == null ? -1 : targetId(source, selectedConnection.outputIndex);
            DialogueGraph.NodeData target = npc.dialogue.findNode(targetId);
            if (source != null && target != null && isValidOutput(source, selectedConnection.outputIndex)) {
                inspectorContent.addView(section("连线属性"));
                inspectorContent.addView(paragraph(
                        "来源：" + nodeTitle(source.type) + " #" + source.id
                                + " · " + outputLabel(source, selectedConnection.outputIndex)
                ));
                inspectorContent.addView(paragraph(
                        "目标：" + nodeTitle(target.type) + " #" + target.id
                ));
                inspectorContent.addView(action("删除连线", COLOR_DANGER, this::deleteSelectedConnection));
                return;
            }
            selectedConnection = null;
        }
        if (selectedNode == null) {
            inspectorContent.addView(paragraph("选择节点后可直接编辑。节点本体会显示全部配置、端口含义与新增操作。"));
            inspectorContent.addView(paragraph("连接方式：从彩色输出圆点拖动到目标节点左上角的彩色入口。"));
            return;
        }
        inspectorContent.addView(section(nodeTitle(selectedNode.type) + "  #" + selectedNode.id));
        if (selectedNode.type == DialogueGraph.NodeType.DIALOGUE || selectedNode.type == DialogueGraph.NodeType.OPTION) {
            inspectorContent.addView(label(
                    selectedNode.type == DialogueGraph.NodeType.DIALOGUE
                            ? "NPC 对话内容"
                            : "选项提示内容"
            ));
            inspectorContent.addView(edit(selectedNode.text, value -> {
                selectedNode.text = value;
                NodeCard card = nodeCards.get(selectedNode.id);
                if (card != null) {
                    card.updateNodeText(value);
                } else {
                    refreshCard(selectedNode);
                }
            }, false));
        }
        if (selectedNode.type == DialogueGraph.NodeType.OPTION) {
            for (int index = 0; index < selectedNode.options.size(); index++) {
                int itemIndex = index;
                DialogueGraph.OptionData option = selectedNode.options.get(index);
                inspectorContent.addView(label("选项 " + (index + 1)));
                LinearLayout row = horizontalRow();
                EditText field = compactEdit(option.text, value -> {
                    option.text = value;
                    refreshCard(selectedNode);
                });
                row.addView(field, new LinearLayout.LayoutParams(0, dp(40), 1));
                TextView delete = compactAction("删除", COLOR_DANGER, () -> removeOption(selectedNode, itemIndex));
                LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(54), dp(40));
                deleteParams.setMargins(dp(6), 0, 0, 0);
                row.addView(delete, deleteParams);
                inspectorContent.addView(row, inspectorRowParams());
            }
            inspectorContent.addView(action("+ 添加选项", COLOR_ACCENT, () -> addOption(selectedNode)));
        } else if (selectedNode.type == DialogueGraph.NodeType.BRANCH) {
            for (int index = 0; index < selectedNode.branches.size(); index++) {
                int itemIndex = index;
                DialogueGraph.BranchCase branch = selectedNode.branches.get(index);
                inspectorContent.addView(label("分支 " + (index + 1) + " · 输出接口 " + (index + 1)));
                inspectorContent.addView(buildBranchInspectorRow(branch, itemIndex), inspectorRowParams());
            }
            inspectorContent.addView(action("+ 添加分支", COLOR_ACCENT, () -> addBranch(selectedNode)));
            inspectorContent.addView(paragraph("“默认”端口在所有条件均不满足时触发。分支按从上到下的顺序判断。"));
        }
        if (canDelete(selectedNode)) {
            inspectorContent.addView(action("删除节点", COLOR_DANGER, this::deleteSelected));
        }
    }

    private LinearLayout buildBranchInspectorRow(DialogueGraph.BranchCase branch, int index) {
        LinearLayout row = horizontalRow();
        TextView type = conditionDropdown(branch);
        row.addView(type, new LinearLayout.LayoutParams(dp(92), dp(40)));
        if (branch.condition != DialogueGraph.ConditionType.PERMISSION) {
            EditText key = compactEdit(branch.key, conditionKeyHint(branch.condition), value -> {
                branch.key = value;
                refreshCard(selectedNode);
            });
            LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0, dp(40), 1);
            keyParams.setMargins(dp(5), 0, 0, 0);
            row.addView(key, keyParams);
        }
        if (branch.condition == DialogueGraph.ConditionType.INVENTORY_ITEM
                || branch.condition == DialogueGraph.ConditionType.SCOREBOARD) {
            TextView operator = operatorDropdown(branch);
            LinearLayout.LayoutParams operatorParams = new LinearLayout.LayoutParams(dp(48), dp(40));
            operatorParams.setMargins(dp(5), 0, 0, 0);
            row.addView(operator, operatorParams);
        }
        if (branch.condition != DialogueGraph.ConditionType.PLAYER_TAG) {
            EditText value = compactEdit(
                    Integer.toString(branch.value),
                    conditionValueHint(branch.condition),
                    text -> updateBranchValue(branch, text)
            );
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(dp(48), dp(40));
            valueParams.setMargins(dp(5), 0, 0, 0);
            row.addView(value, valueParams);
        }
        TextView delete = compactAction("×", COLOR_DANGER, () -> removeBranch(selectedNode, index));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(32), dp(40));
        deleteParams.setMargins(dp(5), 0, 0, 0);
        row.addView(delete, deleteParams);
        return row;
    }

    private TextView conditionDropdown(DialogueGraph.BranchCase branch) {
        List<String> labels = java.util.Arrays.stream(DialogueGraph.ConditionType.values())
                .map(this::conditionLabel)
                .toList();
        return dropdown(conditionLabel(branch.condition), labels, index -> {
            DialogueGraph.ConditionType next = DialogueGraph.ConditionType.values()[index];
            if (next != branch.condition) {
                applyConditionType(branch, next);
                rebuildCards();
                rebuildInspector();
            }
        });
    }

    private TextView operatorDropdown(DialogueGraph.BranchCase branch) {
        List<String> operators = List.of("≥", ">", "=", "≠", "<", "≤");
        return dropdown(branch.operator, operators, index -> {
            branch.operator = operators.get(index);
            rebuildCards();
            rebuildInspector();
        });
    }

    private void applyConditionType(DialogueGraph.BranchCase branch, DialogueGraph.ConditionType type) {
        branch.condition = type;
        switch (type) {
            case INVENTORY_ITEM -> {
                branch.key = "";
                branch.operator = "≥";
                branch.value = 1;
            }
            case SCOREBOARD -> {
                branch.key = "";
                branch.operator = "≥";
                branch.value = 1;
            }
            case PLAYER_TAG -> {
                branch.key = "";
                branch.operator = "=";
                branch.value = 1;
            }
            case PERMISSION -> {
                branch.key = "";
                branch.operator = "≥";
                branch.value = 2;
            }
        }
    }

    private void updateBranchValue(DialogueGraph.BranchCase branch, String value) {
        try {
            branch.value = Integer.parseInt(value.trim());
            refreshCard(selectedNode);
        } catch (NumberFormatException ignored) {
        }
    }

    private void updateBranchValueInline(DialogueGraph.BranchCase branch, String value) {
        try {
            branch.value = Integer.parseInt(value.trim());
            graphLayer.invalidate();
        } catch (NumberFormatException ignored) {
        }
    }

    private void addOption(DialogueGraph.NodeData node) {
        if (node.options.size() >= MAX_OUTPUTS) {
            connectionHint.setText("单个节点最多允许 " + MAX_OUTPUTS + " 个选项");
            return;
        }
        cancelConnection();
        selectedConnection = null;
        node.options.add(new DialogueGraph.OptionData());
        rebuildCards();
        rebuildInspector();
    }

    private void removeOption(DialogueGraph.NodeData node, int index) {
        if (index >= 0 && index < node.options.size()) {
            cancelConnection();
            selectedConnection = null;
            node.options.remove(index);
            rebuildCards();
            rebuildInspector();
        }
    }

    private void addBranch(DialogueGraph.NodeData node) {
        if (node.branches.size() >= MAX_OUTPUTS) {
            connectionHint.setText("单个节点最多允许 " + MAX_OUTPUTS + " 个分支");
            return;
        }
        cancelConnection();
        selectedConnection = null;
        node.branches.add(new DialogueGraph.BranchCase());
        rebuildCards();
        rebuildInspector();
    }

    private void removeBranch(DialogueGraph.NodeData node, int index) {
        if (index >= 0 && index < node.branches.size()) {
            cancelConnection();
            selectedConnection = null;
            node.branches.remove(index);
            rebuildCards();
            rebuildInspector();
        }
    }

    private void refreshCard(DialogueGraph.NodeData node) {
        rebuildCards();
        graphLayer.invalidate();
    }

    private boolean dragConnection(
            View port,
            DialogueGraph.NodeData source,
            int outputIndex,
            MotionEvent event
    ) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                if (!isValidOutput(source, outputIndex)) {
                    cancelConnection();
                    return false;
                }
                pendingPort = new PendingPort(source.id, outputIndex);
                connectionDragging = true;
                selectedConnection = null;
                updateConnectionPointer(event);
                if (port.getParent() != null) {
                    port.getParent().requestDisallowInterceptTouchEvent(true);
                }
                connectionHint.setText(
                        "拖动连接：" + nodeTitle(source.type) + " #" + source.id
                                + " · " + outputLabel(source, outputIndex)
                );
                graphLayer.invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!connectionDragging) {
                    return false;
                }
                updateConnectionPointer(event);
                graphLayer.invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                if (!connectionDragging) {
                    return false;
                }
                updateConnectionPointer(event);
                DialogueGraph.NodeData target = findInputTarget(connectionX, connectionY, source.id);
                if (target != null) {
                    setTarget(source, outputIndex, target.id);
                    connectionHint.setText("连接已更新 · 可从输出端口拖动重新连接");
                } else {
                    connectionHint.setText("未连接：请将连线拖到目标节点左上角入口");
                }
                pendingPort = null;
                connectionDragging = false;
                if (port.getParent() != null) {
                    port.getParent().requestDisallowInterceptTouchEvent(false);
                }
                NodeCard sourceCard = nodeCards.get(source.id);
                if (sourceCard != null) {
                    selectedNode = source;
                    graphLayer.bringChildToFront(sourceCard);
                    nodeCards.values().forEach(NodeCard::updateBackground);
                }
                rebuildInspector();
                graphLayer.invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                if (port.getParent() != null) {
                    port.getParent().requestDisallowInterceptTouchEvent(false);
                }
                cancelConnection();
                rebuildInspector();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void updateConnectionPointer(MotionEvent event) {
        int[] viewportLocation = new int[2];
        viewport.getLocationOnScreen(viewportLocation);
        connectionX = event.getRawX() - viewportLocation[0];
        connectionY = event.getRawY() - viewportLocation[1];
    }

    @Nullable
    private DialogueGraph.NodeData findInputTarget(float viewportX, float viewportY, int sourceId) {
        for (int index = graphLayer.getChildCount() - 1; index >= 0; index--) {
            if (!(graphLayer.getChildAt(index) instanceof NodeCard card)
                    || card.node.id == sourceId
                    || card.inputPort == null) {
                continue;
            }
            float[] center = graphLayer.centerInViewport(card.inputPort);
            float radius = Math.max(dp(18), Math.max(card.inputPort.getWidth(), card.inputPort.getHeight()) * 1.5F);
            float deltaX = viewportX - center[0];
            float deltaY = viewportY - center[1];
            if (deltaX * deltaX + deltaY * deltaY <= radius * radius) {
                return card.node;
            }
        }
        return null;
    }

    private void cancelConnection() {
        pendingPort = null;
        connectionDragging = false;
        if (connectionHint != null) {
            connectionHint.setText("从输出端口拖动到目标入口");
        }
        if (graphLayer != null) {
            graphLayer.invalidate();
        }
    }

    private boolean isValidOutput(DialogueGraph.NodeData source, int outputIndex) {
        if (source == null || outputIndex < 0) {
            return false;
        }
        return switch (source.type) {
            case ENTRY, DIALOGUE -> outputIndex == 0;
            case OPTION -> outputIndex < source.options.size();
            case BRANCH -> outputIndex <= source.branches.size();
            case EXIT -> false;
        };
    }

    private void setTarget(DialogueGraph.NodeData source, int outputIndex, int targetId) {
        if (source.type == DialogueGraph.NodeType.ENTRY || source.type == DialogueGraph.NodeType.DIALOGUE) {
            source.nextNodeId = targetId;
        } else if (source.type == DialogueGraph.NodeType.OPTION
                && outputIndex >= 0
                && outputIndex < source.options.size()) {
            source.options.get(outputIndex).targetNodeId = targetId;
        } else if (source.type == DialogueGraph.NodeType.BRANCH) {
            if (outputIndex >= 0 && outputIndex < source.branches.size()) {
                source.branches.get(outputIndex).targetNodeId = targetId;
            } else if (outputIndex == source.branches.size()) {
                source.defaultNodeId = targetId;
            }
        }
    }

    private void selectConnection(SelectedConnection connection) {
        selectedConnection = connection;
        selectedNode = null;
        nodeCards.values().forEach(NodeCard::updateBackground);
        rebuildInspector();
        connectionHint.setText("已选择连线 · 可在右侧属性中删除");
        graphLayer.invalidate();
    }

    private void deleteSelectedConnection() {
        if (selectedConnection == null) {
            return;
        }
        DialogueGraph.NodeData source = npc.dialogue.findNode(selectedConnection.nodeId);
        if (source != null && isValidOutput(source, selectedConnection.outputIndex)) {
            setTarget(source, selectedConnection.outputIndex, -1);
        }
        selectedConnection = null;
        connectionHint.setText("连线已删除");
        rebuildInspector();
        graphLayer.invalidate();
    }

    private void deleteSelected() {
        if (!canDelete(selectedNode)) {
            return;
        }
        int removedId = selectedNode.id;
        cancelConnection();
        selectedConnection = null;
        npc.dialogue.nodes.remove(selectedNode);
        for (DialogueGraph.NodeData node : npc.dialogue.nodes) {
            if (node.nextNodeId == removedId) {
                node.nextNodeId = -1;
            }
            if (node.defaultNodeId == removedId) {
                node.defaultNodeId = -1;
            }
            node.options.forEach(option -> {
                if (option.targetNodeId == removedId) {
                    option.targetNodeId = -1;
                }
            });
            node.branches.forEach(branch -> {
                if (branch.targetNodeId == removedId) {
                    branch.targetNodeId = -1;
                }
            });
        }
        selectedNode = null;
        rebuildCards();
        rebuildInspector();
    }

    private void saveGraph() {
        npc.dialogue.ensureEntryNode();
        Minecraft.getInstance().execute(() -> {
            ScreenHelper.send("save_dialogue", npc);
            ScreenHelper.message("对话工作流已应用到当前会话，尚未写入配置。");
        });
    }

    private void returnToNpcEditor() {
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new NpcScreen(npc)));
    }

    private void fitView() {
        if (viewport == null || npc.dialogue.nodes.isEmpty()) {
            return;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (DialogueGraph.NodeData node : npc.dialogue.nodes) {
            NodeCard card = nodeCards.get(node.id);
            float width = card != null && card.getWidth() > 0
                    ? card.getWidth() / zoom
                    : nodeWidth(node);
            float height = card != null && card.getHeight() > 0
                    ? card.getHeight() / zoom
                    : nodeHeight(node);
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxX = Math.max(maxX, node.x + width);
            maxY = Math.max(maxY, node.y + height);
        }
        float availableWidth = Math.max(dp(200), viewport.getWidth() - dp(80));
        float availableHeight = Math.max(dp(160), viewport.getHeight() - dp(100));
        float contentWidth = Math.max(1.0F, maxX - minX);
        float contentHeight = Math.max(1.0F, maxY - minY);
        zoom = clamp(Math.min(availableWidth / contentWidth, availableHeight / contentHeight), MIN_ZOOM, 1.35F);
        cameraX = (viewport.getWidth() - (minX + maxX) * zoom) * 0.5F;
        cameraY = (viewport.getHeight() - (minY + maxY) * zoom) * 0.5F;
        rebuildCards();
    }

    private void zoomAtCenter(float factor) {
        zoomAt(viewport.getWidth() * 0.5F, viewport.getHeight() * 0.5F, zoom * factor);
    }

    private void zoomAt(float anchorX, float anchorY, float requestedZoom) {
        float next = clamp(requestedZoom, MIN_ZOOM, MAX_ZOOM);
        float worldX = viewportToWorldX(anchorX);
        float worldY = viewportToWorldY(anchorY);
        zoom = next;
        cameraX = anchorX - worldX * zoom;
        cameraY = anchorY - worldY * zoom;
        rebuildCards();
    }

    private float viewportToWorldX(float viewportX) {
        return (viewportX - cameraX) / zoom;
    }

    private float viewportToWorldY(float viewportY) {
        return (viewportY - cameraY) / zoom;
    }

    private void applyTransform() {
        if (graphLayer == null) {
            return;
        }
        clampCamera();
        nodeCards.values().forEach(this::positionNodeCard);
        graphLayer.invalidate();
        viewport.invalidate();
        if (zoomLabel != null) {
            float percent = zoom * 100.0F;
            zoomLabel.setText(percent < 0.1F
                    ? String.format(java.util.Locale.ROOT, "%.3f%%", percent)
                    : percent < 1.0F
                    ? String.format(java.util.Locale.ROOT, "%.1f%%", percent)
                    : Math.round(percent) + "%");
        }
    }

    private void clampCamera() {
        if (viewport == null || viewport.getWidth() <= 0 || viewport.getHeight() <= 0) {
            return;
        }
        float horizontalLimit = MAX_WORLD_COORDINATE * zoom + viewport.getWidth();
        float verticalLimit = MAX_WORLD_COORDINATE * zoom + viewport.getHeight();
        cameraX = clamp(cameraX, -horizontalLimit, horizontalLimit);
        cameraY = clamp(cameraY, -verticalLimit, verticalLimit);
    }

    private int nodeWidth(DialogueGraph.NodeData node) {
        return dp(switch (node.type) {
            case ENTRY, EXIT -> 230;
            case DIALOGUE -> 310;
            case OPTION -> 430;
            case BRANCH -> 560;
        });
    }

    private int nodeHeight(DialogueGraph.NodeData node) {
        return switch (node.type) {
            case ENTRY -> dp(104);
            case DIALOGUE -> dp(149);
            case OPTION -> dp(144 + node.options.size() * 48);
            case BRANCH -> dp(147 + node.branches.size() * 48);
            case EXIT -> dp(123);
        };
    }

    private void addToolbarButton(LinearLayout parent, String value, Runnable action, boolean primary) {
        TextView button = action(value, primary ? 0xFF27759B : COLOR_SURFACE_HIGH, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40)
        );
        params.setMargins(0, 0, dp(8), 0);
        parent.addView(button, params);
    }

    private void addSmallButton(LinearLayout parent, String value, Runnable action) {
        TextView button = action(value, COLOR_SURFACE_HIGH, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                value.length() > 1 ? dp(58) : dp(34),
                dp(34)
        );
        params.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(button, params);
    }

    private EditText edit(String value, Consumer<String> changed, boolean singleLine) {
        EditText field = new EditText(getContext());
        field.setText(value == null ? "" : value);
        field.setTextSize(13);
        field.setTextColor(COLOR_TEXT);
        field.setHintTextColor(COLOR_MUTED);
        field.setSingleLine(singleLine);
        if (!singleLine) {
            field.setHorizontallyScrolling(false);
            field.setMinLines(1);
            field.setMaxLines(Integer.MAX_VALUE);
            field.setGravity(Gravity.TOP);
        }
        field.setPadding(dp(10), dp(8), dp(10), dp(8));
        field.setBackground(shape(0xFF111419, 8, 1));
        field.addTextChangedListener(new SimpleWatcher(textValue -> {
            changed.accept(textValue);
            if (!singleLine) {
                field.post(() -> updateEditorHeight(field));
            }
        }));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
        );
        params.setMargins(0, 0, 0, dp(10));
        field.setLayoutParams(params);
        if (!singleLine) {
            field.post(() -> updateEditorHeight(field));
        }
        return field;
    }

    private void updateEditorHeight(EditText field) {
        int lines = Math.max(1, field.getLineCount());
        int height = dp(42) + (lines - 1) * dp(18);
        ViewGroup.LayoutParams params = field.getLayoutParams();
        if (params != null && params.height != height) {
            params.height = height;
            field.setLayoutParams(params);
        }
    }

    private EditText compactEdit(String value, Consumer<String> changed) {
        return compactEdit(value, "", changed);
    }

    private EditText compactEdit(String value, String hint, Consumer<String> changed) {
        EditText field = new EditText(getContext());
        field.setText(value == null ? "" : value);
        field.setHint(hint);
        field.setTextSize(11);
        field.setTextColor(COLOR_TEXT);
        field.setHintTextColor(COLOR_MUTED);
        field.setSingleLine(true);
        field.setPadding(dp(8), 0, dp(8), 0);
        field.setBackground(interactiveShape(0xFF111419, 0xFF20262D, 0xFF090B0E, 7));
        field.addTextChangedListener(new SimpleWatcher(changed));
        return field;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams inspectorRowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
        );
        params.setMargins(0, 0, 0, dp(9));
        return params;
    }

    private TextView action(String value, int color, Runnable runnable) {
        TextView view = text(getContext(), value, 13, COLOR_TEXT);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(7), dp(12), dp(7));
        view.setBackground(interactiveShape(
                color,
                blend(color, 0xFFFFFFFF, 0.12F),
                blend(color, 0xFF000000, 0.18F),
                8
        ));
        view.setClickable(true);
        view.setOnClickListener(ignored -> runnable.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
        );
        params.setMargins(0, 0, 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private TextView compactAction(String value, int color, Runnable runnable) {
        TextView view = text(getContext(), value, 12, COLOR_TEXT);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setBackground(interactiveShape(
                color,
                blend(color, 0xFFFFFFFF, 0.12F),
                blend(color, 0xFF000000, 0.18F),
                7
        ));
        view.setOnClickListener(ignored -> runnable.run());
        return view;
    }

    private TextView dropdown(String value, List<String> entries, Consumer<Integer> selected) {
        TextView view = text(getContext(), value + "  ▾", 12, COLOR_TEXT);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(9), 0, dp(8), 0);
        view.setClickable(true);
        view.setBackground(interactiveShape(0xFF111419, 0xFF252B33, 0xFF0B0D10, 7));
        view.setOnClickListener(ignored -> {
            PopupMenu popup = new PopupMenu(getContext(), view, Gravity.LEFT);
            for (int index = 0; index < entries.size(); index++) {
                popup.getMenu().add(0, index, index, entries.get(index));
            }
            popup.setOnMenuItemClickListener(item -> {
                selected.accept(item.getItemId());
                return true;
            });
            popup.show();
        });
        return view;
    }

    private TextView section(String value) {
        TextView view = text(getContext(), value, 16, COLOR_TEXT);
        view.setPadding(0, dp(8), 0, dp(12));
        return view;
    }

    private TextView label(String value) {
        TextView view = text(getContext(), value, 12, COLOR_MUTED);
        view.setPadding(dp(2), dp(5), 0, dp(5));
        return view;
    }

    private TextView paragraph(String value) {
        TextView view = text(getContext(), value, 13, COLOR_MUTED);
        view.setPadding(0, dp(8), 0, dp(12));
        return view;
    }

    private TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private ShapeDrawable shape(int color, int radiusDp, int strokeDp) {
        ShapeDrawable drawable = new ShapeDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), COLOR_BORDER);
        }
        return drawable;
    }

    private Drawable interactiveShape(int normal, int hovered, int pressed, int radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{R.attr.state_pressed}, shape(pressed, radiusDp, 1));
        states.addState(new int[]{R.attr.state_hovered}, shape(hovered, radiusDp, 1));
        states.addState(StateSet.WILD_CARD, shape(normal, radiusDp, 1));
        return states;
    }

    private int blend(int first, int second, float ratio) {
        float inverse = 1.0F - ratio;
        int alpha = Math.round(((first >>> 24) & 0xFF) * inverse + ((second >>> 24) & 0xFF) * ratio);
        int red = Math.round(((first >>> 16) & 0xFF) * inverse + ((second >>> 16) & 0xFF) * ratio);
        int green = Math.round(((first >>> 8) & 0xFF) * inverse + ((second >>> 8) & 0xFF) * ratio);
        int blue = Math.round((first & 0xFF) * inverse + (second & 0xFF) * ratio);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private int dp(int value) {
        return root != null ? root.dp(value) : Math.round(value);
    }

    private boolean canDelete(DialogueGraph.NodeData node) {
        return node != null
                && node.type != DialogueGraph.NodeType.ENTRY
                && node.type != DialogueGraph.NodeType.EXIT;
    }

    private String nodeTitle(DialogueGraph.NodeType type) {
        return switch (type) {
            case ENTRY -> "入口";
            case DIALOGUE -> "对话";
            case OPTION -> "选项";
            case BRANCH -> "分支";
            case EXIT -> "出口";
        };
    }

    private String conditionLabel(DialogueGraph.ConditionType type) {
        return switch (type) {
            case INVENTORY_ITEM -> "背包物品";
            case SCOREBOARD -> "计分板";
            case PLAYER_TAG -> "玩家标签";
            case PERMISSION -> "权限等级";
        };
    }

    private String conditionKeyHint(DialogueGraph.ConditionType type) {
        return switch (type) {
            case INVENTORY_ITEM -> "物品 ID";
            case SCOREBOARD -> "计分板目标";
            case PLAYER_TAG -> "玩家标签";
            case PERMISSION -> "";
        };
    }

    private String conditionValueHint(DialogueGraph.ConditionType type) {
        return switch (type) {
            case INVENTORY_ITEM -> "数量";
            case SCOREBOARD -> "分数";
            case PLAYER_TAG -> "";
            case PERMISSION -> "等级";
        };
    }

    private String outputLabel(DialogueGraph.NodeData node, int index) {
        return switch (node.type) {
            case ENTRY -> "开始";
            case DIALOGUE -> "继续";
            case OPTION -> "选项 " + (index + 1);
            case BRANCH -> index < node.branches.size() ? "分支 " + (index + 1) : "默认";
            case EXIT -> "";
        };
    }

    private int targetId(DialogueGraph.NodeData node, int index) {
        return switch (node.type) {
            case ENTRY, DIALOGUE -> node.nextNodeId;
            case OPTION -> index < node.options.size() ? node.options.get(index).targetNodeId : -1;
            case BRANCH -> index < node.branches.size()
                    ? node.branches.get(index).targetNodeId
                    : node.defaultNodeId;
            case EXIT -> -1;
        };
    }

    private int nodeColor(DialogueGraph.NodeType type) {
        return switch (type) {
            case ENTRY -> 0xFF5ED6A0;
            case DIALOGUE -> 0xFF5AA7D6;
            case OPTION -> 0xFFE0A45C;
            case BRANCH -> 0xFFC879A0;
            case EXIT -> 0xFFEA688B;
        };
    }

    private float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private FrameLayout.LayoutParams inspectorLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(INSPECTOR_WIDTH),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.RIGHT
        );
        params.setMargins(0, dp(TOOLBAR_HEIGHT), 0, 0);
        return params;
    }

    private FrameLayout.LayoutParams zoomLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(46),
                Gravity.LEFT | Gravity.BOTTOM
        );
        params.setMargins(dp(18), 0, 0, dp(18));
        return params;
    }

    private final class GraphViewport extends FrameLayout {
        GraphViewport(Context context) {
            super(context);
            setClipChildren(true);
            setBackground(shape(COLOR_BACKGROUND, 0, 0));
        }

        @Override
        public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                float delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (delta != 0) {
                    zoomAt(event.getX(), event.getY(), zoom * (float) Math.pow(1.16D, delta));
                    return true;
                }
            }
            return super.onGenericMotionEvent(event);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            Paint paint = Paint.obtain();
            paint.setAntiAlias(true);
            float spacing = dp(24) * zoom;
            while (spacing < dp(12)) {
                spacing *= 5.0F;
            }
            float offsetX = cameraX % spacing;
            float offsetY = cameraY % spacing;
            paint.setColor(0xFF252B33);
            float radius = Math.max(0.7F, Math.min(1.5F, zoom));
            for (float x = offsetX; x < getWidth(); x += spacing) {
                for (float y = offsetY; y < getHeight(); y += spacing) {
                    canvas.drawCircle(x, y, radius, paint);
                }
            }
            paint.recycle();
        }
    }

    private final class GraphLayer extends FrameLayout {
        private float dragRawX;
        private float dragRawY;
        private float dragCameraX;
        private float dragCameraY;
        private boolean panning;
        private SelectedConnection pressedConnection;
        GraphLayer(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(true);
            setClipChildren(false);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            Paint shadow = Paint.obtain();
            shadow.setAntiAlias(true);
            shadow.setStyle(Paint.STROKE);
            shadow.setStrokeCap(Paint.CAP_ROUND);
            shadow.setStrokeJoin(Paint.JOIN_ROUND);
            shadow.setStrokeWidth(Math.max(1.0F, dp(7) * zoom));
            shadow.setColor(0x55000000);
            Paint line = Paint.obtain();
            line.setAntiAlias(true);
            line.setStyle(Paint.STROKE);
            line.setStrokeCap(Paint.CAP_ROUND);
            line.setStrokeJoin(Paint.JOIN_ROUND);
            line.setStrokeWidth(Math.max(1.0F, dp(3) * zoom));
            Paint handle = Paint.obtain();
            handle.setAntiAlias(true);
            handle.setStyle(Paint.FILL);
            for (DialogueGraph.NodeData source : npc.dialogue.nodes) {
                NodeCard sourceCard = nodeCards.get(source.id);
                if (sourceCard == null) {
                    continue;
                }
                for (int index = 0; index < sourceCard.outputPorts.size(); index++) {
                    DialogueGraph.NodeData target = npc.dialogue.findNode(targetId(source, index));
                    NodeCard targetCard = target == null ? null : nodeCards.get(target.id);
                    if (targetCard == null || targetCard.inputPort == null) {
                        continue;
                    }
                    float[] start = centerInViewport(sourceCard.outputPorts.get(index));
                    float[] end = centerInViewport(targetCard.inputPort);
                    float[] path = connectionPath(start[0], start[1], end[0], end[1]);
                    canvas.drawLines(path, 0, path.length, true, shadow);
                    line.setColor(nodeColor(source.type));
                    canvas.drawLines(path, 0, path.length, true, line);
                    drawConnectionHandle(canvas, handle, source, index, start, end);
                }
            }
            if (connectionDragging && pendingPort != null) {
                DialogueGraph.NodeData source = npc.dialogue.findNode(pendingPort.nodeId);
                NodeCard sourceCard = source == null ? null : nodeCards.get(source.id);
                if (source != null
                        && sourceCard != null
                        && pendingPort.outputIndex >= 0
                        && pendingPort.outputIndex < sourceCard.outputPorts.size()) {
                    float[] start = centerInViewport(sourceCard.outputPorts.get(pendingPort.outputIndex));
                    float[] path = connectionPath(start[0], start[1], connectionX, connectionY);
                    canvas.drawLines(path, 0, path.length, true, shadow);
                    line.setColor(nodeColor(source.type));
                    canvas.drawLines(path, 0, path.length, true, line);
                }
            }
            shadow.recycle();
            line.recycle();
            handle.recycle();
        }

        private void drawConnectionHandle(
                Canvas canvas,
                Paint paint,
                DialogueGraph.NodeData source,
                int outputIndex,
                float[] start,
                float[] end
        ) {
            float[] midpoint = connectionMidpoint(start, end);
            float radius = clamp(dp(7) * zoom, dp(6), dp(10));
            paint.setColor(0xFF090C10);
            canvas.drawCircle(midpoint[0], midpoint[1], radius, paint);
            boolean active = selectedConnection != null
                    && selectedConnection.nodeId == source.id
                    && selectedConnection.outputIndex == outputIndex;
            paint.setColor(active ? COLOR_TEXT : nodeColor(source.type));
            canvas.drawCircle(midpoint[0], midpoint[1], radius * 0.55F, paint);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    dragRawX = event.getRawX();
                    dragRawY = event.getRawY();
                    dragCameraX = cameraX;
                    dragCameraY = cameraY;
                    panning = false;
                    pressedConnection = connectionAt(event.getX(), event.getY());
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float deltaX = event.getRawX() - dragRawX;
                    float deltaY = event.getRawY() - dragRawY;
                    if (panning || deltaX * deltaX + deltaY * deltaY >= touchSlop * touchSlop) {
                        panning = true;
                        pressedConnection = null;
                        cameraX = dragCameraX + deltaX;
                        cameraY = dragCameraY + deltaY;
                        applyTransform();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (!panning && pressedConnection != null) {
                        cancelConnection();
                        selectConnection(pressedConnection);
                    } else if (!panning) {
                        selectedNode = null;
                        selectedConnection = null;
                        nodeCards.values().forEach(NodeCard::updateBackground);
                        rebuildInspector();
                        cancelConnection();
                    } else {
                        cancelConnection();
                    }
                    panning = false;
                    pressedConnection = null;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    panning = false;
                    pressedConnection = null;
                    return true;
                }
                default -> {
                    return super.onTouchEvent(event);
                }
            }
        }

        private float[] connectionPath(float x1, float y1, float x2, float y2) {
            float distance = Math.abs(x2 - x1);
            float bend = Math.max(dp(70) * zoom, distance * 0.45F);
            int segments = 48;
            float[] points = new float[(segments + 1) * 2];
            for (int index = 0; index <= segments; index++) {
                float t = index / (float) segments;
                float inverse = 1.0F - t;
                points[index * 2] = inverse * inverse * inverse * x1
                        + 3.0F * inverse * inverse * t * (x1 + bend)
                        + 3.0F * inverse * t * t * (x2 - bend)
                        + t * t * t * x2;
                points[index * 2 + 1] = inverse * inverse * inverse * y1
                        + 3.0F * inverse * inverse * t * y1
                        + 3.0F * inverse * t * t * y2
                        + t * t * t * y2;
            }
            return points;
        }

        private float[] connectionMidpoint(float[] start, float[] end) {
            return new float[]{
                    (start[0] + end[0]) * 0.5F,
                    (start[1] + end[1]) * 0.5F
            };
        }

        @Nullable
        private SelectedConnection connectionAt(float x, float y) {
            for (int sourceIndex = npc.dialogue.nodes.size() - 1; sourceIndex >= 0; sourceIndex--) {
                DialogueGraph.NodeData source = npc.dialogue.nodes.get(sourceIndex);
                NodeCard sourceCard = nodeCards.get(source.id);
                if (sourceCard == null) {
                    continue;
                }
                for (int outputIndex = sourceCard.outputPorts.size() - 1; outputIndex >= 0; outputIndex--) {
                    DialogueGraph.NodeData target = npc.dialogue.findNode(targetId(source, outputIndex));
                    NodeCard targetCard = target == null ? null : nodeCards.get(target.id);
                    if (targetCard == null || targetCard.inputPort == null) {
                        continue;
                    }
                    float[] start = centerInViewport(sourceCard.outputPorts.get(outputIndex));
                    float[] end = centerInViewport(targetCard.inputPort);
                    float[] midpoint = connectionMidpoint(start, end);
                    float radius = dp(13);
                    float deltaX = x - midpoint[0];
                    float deltaY = y - midpoint[1];
                    if (deltaX * deltaX + deltaY * deltaY <= radius * radius) {
                        return new SelectedConnection(source.id, outputIndex);
                    }
                }
            }
            return null;
        }

        private float[] centerInViewport(View view) {
            float x = view.getWidth() * 0.5F;
            float y = view.getHeight() * 0.5F;
            View current = view;
            while (current != graphLayer) {
                x += current.getLeft();
                y += current.getTop();
                if (!(current.getParent() instanceof View parent)) {
                    break;
                }
                current = parent;
                if (current instanceof NodeCard card) {
                    return new float[]{
                            card.getLeft() + x,
                            card.getTop() + y
                    };
                }
            }
            return new float[]{x, y};
        }
    }

    private final class NodeCard extends FrameLayout {
        private final DialogueGraph.NodeData node;
        private final List<PortView> outputPorts = new ArrayList<>();
        private PortView inputPort;
        private EditText nodeTextEditor;
        private float dragRawX;
        private float dragRawY;
        private float dragWorldOffsetX;
        private float dragWorldOffsetY;
        private int viewportScreenX;
        private int viewportScreenY;
        private boolean dragging;
        private boolean refreshInspectorAfterDrag;

        private int nodeDp(int value) {
            return Math.max(1, Math.round(dp(value) * zoom));
        }

        private int nodePixels(int pixels) {
            return Math.max(1, Math.round(pixels * zoom));
        }

        private int nodePortSize() {
            return Math.max(dp(10), nodeDp(16));
        }

        private TextView nodeText(String value, float size, int color) {
            TextView view = text(getContext(), value, Math.max(1.0F, size * zoom), color);
            return view;
        }

        private ShapeDrawable nodeShape(int color, int radiusDp, int strokeDp) {
            ShapeDrawable drawable = new ShapeDrawable();
            drawable.setColor(color);
            drawable.setCornerRadius(nodeDp(radiusDp));
            if (strokeDp > 0) {
                drawable.setStroke(nodeDp(strokeDp), COLOR_BORDER);
            }
            return drawable;
        }

        private Drawable nodeInteractiveShape(int normal, int hovered, int pressed, int radiusDp) {
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{R.attr.state_pressed}, nodeShape(pressed, radiusDp, 1));
            states.addState(new int[]{R.attr.state_hovered}, nodeShape(hovered, radiusDp, 1));
            states.addState(StateSet.WILD_CARD, nodeShape(normal, radiusDp, 1));
            return states;
        }

        private EditText nodeCompactEdit(String value, String hint, Consumer<String> changed) {
            EditText field = new EditText(getContext());
            field.setText(value == null ? "" : value);
            field.setHint(hint);
            field.setTextSize(Math.max(1.0F, 11.0F * zoom));
            field.setTextColor(COLOR_TEXT);
            field.setHintTextColor(COLOR_MUTED);
            field.setSingleLine(true);
            field.setPadding(nodeDp(8), 0, nodeDp(8), 0);
            field.setBackground(nodeInteractiveShape(0xFF111419, 0xFF20262D, 0xFF090B0E, 7));
            field.addTextChangedListener(new SimpleWatcher(changed));
            return field;
        }

        private EditText nodeAutoEdit(String value, String hint, Consumer<String> changed) {
            EditText field = new EditText(getContext());
            field.setText(value == null ? "" : value);
            field.setHint(hint);
            field.setTextSize(Math.max(1.0F, 12.0F * zoom));
            field.setTextColor(COLOR_TEXT);
            field.setHintTextColor(COLOR_MUTED);
            field.setSingleLine(false);
            field.setHorizontallyScrolling(false);
            field.setMinLines(1);
            field.setMaxLines(Integer.MAX_VALUE);
            field.setGravity(Gravity.TOP);
            field.setPadding(nodeDp(10), nodeDp(8), nodeDp(10), nodeDp(8));
            field.setBackground(nodeInteractiveShape(0xFF111419, 0xFF20262D, 0xFF090B0E, 7));
            field.addTextChangedListener(new SimpleWatcher(valueText -> {
                changed.accept(valueText);
                field.post(() -> updateNodeEditorHeight(field));
            }));
            field.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    nodeDp(38)
            ));
            field.post(() -> updateNodeEditorHeight(field));
            return field;
        }

        private void updateNodeEditorHeight(EditText field) {
            int lines = Math.max(1, field.getLineCount());
            int height = nodeDp(38) + (lines - 1) * nodeDp(18);
            ViewGroup.LayoutParams params = field.getLayoutParams();
            if (params != null && params.height != height) {
                params.height = height;
                field.setLayoutParams(params);
            }
            requestLayout();
            graphLayer.invalidate();
        }

        private TextView nodeCompactAction(String value, int color, Runnable runnable) {
            TextView view = nodeText(value, 12, COLOR_TEXT);
            view.setGravity(Gravity.CENTER);
            view.setClickable(true);
            view.setBackground(nodeInteractiveShape(
                    color,
                    blend(color, 0xFFFFFFFF, 0.12F),
                    blend(color, 0xFF000000, 0.18F),
                    7
            ));
            view.setOnClickListener(ignored -> runnable.run());
            return view;
        }

        private TextView styleNodeDropdown(TextView view) {
            view.setTextSize(Math.max(1.0F, 12.0F * zoom));
            view.setPadding(nodeDp(9), 0, nodeDp(8), 0);
            view.setBackground(nodeInteractiveShape(0xFF111419, 0xFF252B33, 0xFF0B0D10, 7));
            return view;
        }
        NodeCard(Context context, DialogueGraph.NodeData node) {
            super(context);
            this.node = node;
            setClipChildren(false);
            setClickable(true);
            setElevation(nodeDp(10));
            setPadding(nodeDp(1), nodeDp(1), nodeDp(1), nodeDp(1));
            updateBackground();
            buildContent();
            setOnTouchListener(this::dragNode);
        }

        private void buildContent() {
            LinearLayout stack = new LinearLayout(getContext());
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setPadding(nodeDp(8), nodeDp(8), nodeDp(8), nodeDp(8));
            addView(stack, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            LinearLayout header = new LinearLayout(getContext());
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(nodeDp(8), 0, nodeDp(8), 0);
            header.setBackground(nodeShape(0xFF2A2E34, 9, 0));
            View leading;
            if (node.type == DialogueGraph.NodeType.ENTRY) {
                leading = new View(getContext());
                leading.setBackground(nodeShape(nodeColor(node.type), 5, 0));
            } else {
                inputPort = new PortView(getContext(), nodeColor(node.type));
                inputPort.setElevation(nodeDp(16));
                inputPort.setOnClickListener(ignored -> selectOnly());
                leading = inputPort;
            }
            int leadingSize = node.type == DialogueGraph.NodeType.ENTRY ? nodeDp(10) : nodePortSize();
            header.addView(leading, new LinearLayout.LayoutParams(leadingSize, leadingSize));
            TextView title = nodeText(nodeTitle(node.type), 14, COLOR_TEXT);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, nodeDp(40), 1);
            titleParams.setMargins(nodeDp(8), 0, 0, 0);
            header.addView(title, titleParams);
            TextView id = nodeText("#" + node.id, 11, COLOR_MUTED);
            header.addView(id, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    nodeDp(40)
            ));
            header.setClickable(true);
            header.setOnTouchListener(this::dragNode);
            stack.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    nodeDp(40)
            ));
            switch (node.type) {
                case ENTRY -> addOutputRow(stack, "工作流从这里开始", "开始", 0, 40);
                case DIALOGUE -> {
                    addNodeTextEditor(stack, "输入 NPC 对话内容");
                    addOutputRow(stack, "NPC 说完后", "继续", 0, 40);
                }
                case OPTION -> {
                    addNodeTextEditor(stack, "输入选项提示内容");
                    for (int index = 0; index < node.options.size(); index++) {
                        DialogueGraph.OptionData option = node.options.get(index);
                        int optionIndex = index;
                        addOptionConfigRow(stack, option, optionIndex);
                    }
                    addInlineAction(stack, "+ 添加选项", () -> addOption(node));
                }
                case BRANCH -> {
                    for (int index = 0; index < node.branches.size(); index++) {
                        DialogueGraph.BranchCase branch = node.branches.get(index);
                        addBranchConfigRow(stack, branch, index);
                    }
                    addOutputRow(stack, "所有条件均不满足", "默认", node.branches.size(), 42);
                    addInlineAction(stack, "+ 添加分支", () -> addBranch(node));
                }
                case EXIT -> addPreview(stack, "对话在这里结束");
            }
        }

        private void addNodeTextEditor(LinearLayout stack, String hint) {
            nodeTextEditor = nodeAutoEdit(node.text, hint, value -> node.text = value);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    nodeDp(38)
            );
            params.setMargins(0, nodeDp(7), 0, 0);
            stack.addView(nodeTextEditor, params);
            nodeTextEditor.post(() -> updateNodeEditorHeight(nodeTextEditor));
        }

        private void addPreview(LinearLayout stack, String value) {
            String normalized = value == null || value.isBlank() ? "尚未配置" : value;
            TextView preview = nodeText(normalized, 12, COLOR_MUTED);
            preview.setMaxLines(3);
            preview.setPadding(nodeDp(10), nodeDp(9), nodeDp(10), nodeDp(9));
            preview.setBackground(nodeShape(0xFF111419, 7, 0));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    nodeDp(58)
            );
            params.setMargins(0, nodeDp(7), 0, 0);
            stack.addView(preview, params);
        }

        private void addOutputRow(
                LinearLayout stack,
                String description,
                String portName,
                int outputIndex,
                int rowHeight
        ) {
            LinearLayout row = rowBase();
            TextView descriptionView = nodeText(description, 12, COLOR_MUTED);
            row.addView(descriptionView, new LinearLayout.LayoutParams(0, nodeDp(rowHeight), 1));
            TextView portNameView = nodeText(portName, 12, COLOR_TEXT);
            portNameView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(portNameView, new LinearLayout.LayoutParams(nodeDp(62), nodeDp(rowHeight)));
            addPort(row, outputIndex);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    nodeDp(rowHeight)
            );
            params.setMargins(0, nodeDp(6), 0, 0);
            stack.addView(row, params);
        }

        private void addOptionConfigRow(
                LinearLayout stack,
                DialogueGraph.OptionData option,
                int index
        ) {
            LinearLayout row = rowBase();
            TextView label = nodeText("选项 " + (index + 1), 11, COLOR_MUTED);
            row.addView(label, new LinearLayout.LayoutParams(nodeDp(52), nodeDp(42)));
            EditText field = nodeCompactEdit(option.text, "玩家看到的选项", value -> option.text = value);
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, nodeDp(34), 1);
            fieldParams.setMargins(nodeDp(4), 0, nodeDp(5), 0);
            row.addView(field, fieldParams);
            TextView delete = nodeCompactAction("×", 0xFF392128, () -> removeOption(node, index));
            row.addView(delete, new LinearLayout.LayoutParams(nodeDp(28), nodeDp(28)));
            TextView portName = nodeText("输出", 10, COLOR_MUTED);
            portName.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(portName, new LinearLayout.LayoutParams(nodeDp(38), nodeDp(42)));
            addPort(row, index);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    nodeDp(42)
            );
            params.setMargins(0, nodeDp(6), 0, 0);
            stack.addView(row, params);
        }

        private void addBranchConfigRow(
                LinearLayout stack,
                DialogueGraph.BranchCase branch,
                int index
        ) {
            LinearLayout row = rowBase();
            TextView label = nodeText("分支 " + (index + 1), 10, COLOR_MUTED);
            row.addView(label, new LinearLayout.LayoutParams(nodeDp(46), nodeDp(42)));
            TextView type = styleNodeDropdown(conditionDropdown(branch));
            LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(nodeDp(90), nodeDp(34));
            typeParams.setMargins(nodeDp(3), 0, 0, 0);
            row.addView(type, typeParams);
            if (branch.condition != DialogueGraph.ConditionType.PERMISSION) {
                EditText key = nodeCompactEdit(
                        branch.key,
                        conditionKeyHint(branch.condition),
                        value -> branch.key = value
                );
                LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0, nodeDp(34), 1);
                keyParams.setMargins(nodeDp(4), 0, 0, 0);
                row.addView(key, keyParams);
            }
            if (branch.condition == DialogueGraph.ConditionType.INVENTORY_ITEM
                    || branch.condition == DialogueGraph.ConditionType.SCOREBOARD) {
                TextView operator = styleNodeDropdown(operatorDropdown(branch));
                LinearLayout.LayoutParams operatorParams = new LinearLayout.LayoutParams(nodeDp(45), nodeDp(34));
                operatorParams.setMargins(nodeDp(4), 0, 0, 0);
                row.addView(operator, operatorParams);
            }
            if (branch.condition != DialogueGraph.ConditionType.PLAYER_TAG) {
                EditText value = nodeCompactEdit(
                        Integer.toString(branch.value),
                        conditionValueHint(branch.condition),
                        text -> updateBranchValueInline(branch, text)
                );
                LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(nodeDp(44), nodeDp(34));
                valueParams.setMargins(nodeDp(4), 0, 0, 0);
                row.addView(value, valueParams);
            }
            TextView delete = nodeCompactAction("×", 0xFF392128, () -> removeBranch(node, index));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(nodeDp(28), nodeDp(28));
            deleteParams.setMargins(nodeDp(4), 0, 0, 0);
            row.addView(delete, deleteParams);
            TextView portName = nodeText("输出", 10, COLOR_MUTED);
            portName.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(portName, new LinearLayout.LayoutParams(nodeDp(34), nodeDp(42)));
            addPort(row, index);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    nodeDp(42)
            );
            params.setMargins(0, nodeDp(6), 0, 0);
            stack.addView(row, params);
        }

        private LinearLayout rowBase() {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(nodeDp(7), 0, nodeDp(5), 0);
            row.setBackground(nodeShape(0xFF111419, 7, 0));
            return row;
        }

        private void addPort(LinearLayout row, int outputIndex) {
            PortView port = new PortView(getContext(), nodeColor(node.type));
            port.setElevation(nodeDp(16));
            port.setOnTouchListener((view, event) -> dragConnection(view, node, outputIndex, event));
            int portSize = nodePortSize();
            row.addView(port, new LinearLayout.LayoutParams(portSize, portSize));
            outputPorts.add(port);
        }

        private void addInlineAction(LinearLayout stack, String value, Runnable runnable) {
            TextView add = nodeText(value, 12, COLOR_ACCENT);
            add.setGravity(Gravity.CENTER);
            add.setClickable(true);
            add.setBackground(nodeInteractiveShape(0xFF151E25, 0xFF20313E, 0xFF0C151B, 7));
            add.setOnClickListener(ignored -> runnable.run());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    nodeDp(34)
            );
            params.setMargins(0, nodeDp(7), 0, 0);
            stack.addView(add, params);
        }

        private void selectOnly() {
            selectVisualOnly();
            rebuildInspector();
        }

        private void selectVisualOnly() {
            selectedNode = node;
            selectedConnection = null;
            graphLayer.bringChildToFront(this);
            nodeCards.values().forEach(NodeCard::updateBackground);
        }

        private void activate() {
            selectOnly();
        }

        private boolean dragNode(View ignored, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    refreshInspectorAfterDrag = selectedNode != node;
                    selectVisualOnly();
                    dragRawX = event.getRawX();
                    dragRawY = event.getRawY();
                    int[] viewportLocation = new int[2];
                    viewport.getLocationOnScreen(viewportLocation);
                    viewportScreenX = viewportLocation[0];
                    viewportScreenY = viewportLocation[1];
                    dragWorldOffsetX = viewportToWorldX(dragRawX - viewportScreenX) - node.x;
                    dragWorldOffsetY = viewportToWorldY(dragRawY - viewportScreenY) - node.y;
                    dragging = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float deltaX = event.getRawX() - dragRawX;
                    float deltaY = event.getRawY() - dragRawY;
                    if (dragging || deltaX * deltaX + deltaY * deltaY >= touchSlop * touchSlop) {
                        dragging = true;
                        node.x = Math.round(
                                viewportToWorldX(event.getRawX() - viewportScreenX) - dragWorldOffsetX
                        );
                        node.y = Math.round(
                                viewportToWorldY(event.getRawY() - viewportScreenY) - dragWorldOffsetY
                        );
                        clampNodePosition(node);
                        layoutNode(this);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    if (!dragging) {
                        activate();
                    } else if (refreshInspectorAfterDrag) {
                        rebuildInspector();
                    }
                    dragging = false;
                    refreshInspectorAfterDrag = false;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    if (refreshInspectorAfterDrag) {
                        rebuildInspector();
                    }
                    dragging = false;
                    refreshInspectorAfterDrag = false;
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private void updateBackground() {
            ShapeDrawable drawable = nodeShape(COLOR_SURFACE, 12, selectedNode == node ? 2 : 1);
            if (selectedNode == node) {
                drawable.setStroke(nodeDp(2), nodeColor(node.type));
            }
            setBackground(drawable);
        }

        private void updateNodeText(String value) {
            if (nodeTextEditor == null || nodeTextEditor.getText().toString().equals(value)) {
                return;
            }
            nodeTextEditor.setText(value);
            nodeTextEditor.post(() -> updateNodeEditorHeight(nodeTextEditor));
            requestLayout();
            graphLayer.invalidate();
        }

        private int cardWidth() {
            return nodePixels(nodeWidth(node));
        }
    }

    private final class PortView extends View {
        private final int color;
        PortView(Context context, int color) {
            super(context);
            this.color = color;
            setClickable(true);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            Paint paint = Paint.obtain();
            paint.setAntiAlias(true);
            paint.setColor(0xFF0B0D10);
            canvas.drawCircle(getWidth() * 0.5F, getHeight() * 0.5F, getWidth() * 0.5F, paint);
            paint.setColor(isPressed()
                    ? blend(color, 0xFF000000, 0.22F)
                    : isHovered()
                    ? blend(color, 0xFFFFFFFF, 0.30F)
                    : color);
            float radius = getWidth() * (isHovered() ? 0.40F : 0.32F);
            canvas.drawCircle(getWidth() * 0.5F, getHeight() * 0.5F, radius, paint);
            paint.recycle();
        }

        @Override
        public void onHoverChanged(boolean hovered) {
            super.onHoverChanged(hovered);
            invalidate();
        }
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Consumer<String> changed;
        SimpleWatcher(Consumer<String> changed) {
            this.changed = changed;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            changed.accept(s.toString());
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    }

    private record PendingPort(int nodeId, int outputIndex) {
    }

    private record SelectedConnection(int nodeId, int outputIndex) {
    }
}
