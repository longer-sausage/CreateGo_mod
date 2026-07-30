/*
 * Defines the serializable node graph used by NPC conversations.
 * 定义 NPC 对话使用的可序列化节点图。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores an editable and executable dialogue graph.
 * 保存可编辑、可执行的对话图。
 */
public final class DialogueGraph {
    public int rootNodeId = -1;
    public List<NodeData> nodes = new ArrayList<>();

    /**
     * Finds a node by its stable numeric identifier.
     * 按稳定的数字标识查找节点。
     *
     * @param nodeId node identifier / 节点标识
     * @return matching node, or {@code null} / 匹配节点，不存在时返回 {@code null}
     */
    public NodeData findNode(int nodeId) {
        if (nodes == null) {
            return null;
        }
        return nodes.stream()
                .filter(node -> node != null && node.id == nodeId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the smallest available positive node identifier.
     * 返回最小的可用正节点标识。
     *
     * @return unused positive identifier / 未使用的正标识
     */
    public int nextNodeId() {
        Set<Integer> used = new HashSet<>();
        if (nodes == null) {
            nodes = new ArrayList<>();
        }
        for (NodeData node : nodes) {
            if (node != null && node.id > 0) {
                used.add(node.id);
            }
        }
        int candidate = 1;
        while (used.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    /**
     * Ensures the graph owns one entry and migrates away legacy exit nodes.
     * 确保对话图只拥有一个入口，并迁移掉旧版出口节点。
     *
     * <p>Every edge that previously targeted an exit is cleared. An unconnected output
     * now means “finish the conversation safely”.</p>
     * <p>所有原本指向出口的连接都会被清空；未连接的输出现在表示“安全结束对话”。</p>
     */
    public void ensureEntryNode() {
        normalizeForUse();
        int previousRoot = rootNodeId;
        Set<Integer> removedIds = new HashSet<>();
        nodes.stream()
                .filter(node -> node.type == NodeType.EXIT)
                .map(node -> node.id)
                .forEach(removedIds::add);
        nodes.removeIf(node -> node.type == NodeType.EXIT);
        clearTargets(removedIds);
        NodeData entry = nodes.stream()
                .filter(node -> node.type == NodeType.ENTRY && node.id == previousRoot)
                .findFirst()
                .orElseGet(() -> nodes.stream()
                        .filter(node -> node.type == NodeType.ENTRY)
                        .findFirst()
                        .orElse(null));
        if (entry == null) {
            entry = new NodeData();
            entry.id = nextNodeId();
            entry.type = NodeType.ENTRY;
            entry.x = -260;
            entry.y = 40;
            entry.text = "对话入口";
            entry.nextNodeId = findNode(previousRoot) == null ? -1 : previousRoot;
            nodes.add(entry);
        }
        NodeData retainedEntry = entry;
        Set<Integer> duplicateEntryIds = new HashSet<>();
        nodes.stream()
                .filter(node -> node.type == NodeType.ENTRY && node != retainedEntry)
                .map(node -> node.id)
                .forEach(duplicateEntryIds::add);
        nodes.removeIf(node -> node.type == NodeType.ENTRY && node != retainedEntry);
        clearTargets(duplicateEntryIds);
        rootNodeId = entry.id;
    }

    /**
     * Repairs nullable legacy collections before the graph is rendered or executed.
     * 在渲染或执行对话图前修复旧数据中的可空集合。
     */
    private void normalizeForUse() {
        if (nodes == null) {
            nodes = new ArrayList<>();
            return;
        }
        nodes.removeIf(node -> node == null);
        for (NodeData node : nodes) {
            if (node.type == null) {
                node.type = NodeType.DIALOGUE;
            }
            if (node.text == null) {
                node.text = "";
            }
            if (node.options == null) {
                node.options = new ArrayList<>();
            } else {
                node.options.removeIf(option -> option == null);
                node.options.forEach(option -> {
                    if (option.text == null) {
                        option.text = "";
                    }
                });
            }
            if (node.branches == null) {
                node.branches = new ArrayList<>();
            } else {
                node.branches.removeIf(branch -> branch == null);
                node.branches.forEach(branch -> {
                    if (branch.condition == null) {
                        branch.condition = ConditionType.INVENTORY_ITEM;
                    }
                    if (branch.key == null) {
                        branch.key = "";
                    }
                    if (branch.operator == null) {
                        branch.operator = "≥";
                    }
                });
            }
        }
    }

    /**
     * Clears every edge that points to a removed legacy node.
     * 清除所有指向已移除旧节点的连接。
     *
     * @param removedIds removed node identifiers / 已移除的节点标识
     */
    private void clearTargets(Set<Integer> removedIds) {
        if (removedIds.isEmpty()) {
            return;
        }
        for (NodeData node : nodes) {
            if (removedIds.contains(node.nextNodeId)) {
                node.nextNodeId = -1;
            }
            node.options.forEach(option -> {
                if (removedIds.contains(option.targetNodeId)) {
                    option.targetNodeId = -1;
                }
            });
            node.branches.forEach(branch -> {
                if (removedIds.contains(branch.targetNodeId)) {
                    branch.targetNodeId = -1;
                }
            });
            if (removedIds.contains(node.defaultNodeId)) {
                node.defaultNodeId = -1;
            }
        }
    }

    /**
     * Describes one graph node and all of its output ports.
     * 描述一个图节点及其全部输出端口。
     */
    public static final class NodeData {
        public int id;
        public NodeType type = NodeType.DIALOGUE;
        public int x;
        public int y;
        public String text = "";
        public int nextNodeId = -1;
        public List<OptionData> options = new ArrayList<>();
        public List<BranchCase> branches = new ArrayList<>();
        public int defaultNodeId = -1;
    }

    /**
     * Describes one option and its independent output port.
     * 描述一个选项及其独立输出端口。
     */
    public static final class OptionData {
        public String text = "";
        public int targetNodeId = -1;
    }

    /**
     * Describes one ordered branch case and its output port.
     * 描述一个有序分支条件及其输出端口。
     */
    public static final class BranchCase {
        public ConditionType condition = ConditionType.INVENTORY_ITEM;
        public String key = "";
        public String operator = "≥";
        public int value = 1;
        public int targetNodeId = -1;
    }

    /**
     * Enumerates supported node types, including the legacy exit migration marker.
     * 枚举支持的节点类型，其中出口仅用于迁移旧数据。
     */
    public enum NodeType {
        ENTRY,
        DIALOGUE,
        OPTION,
        BRANCH,
        EXIT
    }

    /**
     * Enumerates server-side branch predicates.
     * 枚举服务端分支判断条件。
     */
    public enum ConditionType {
        INVENTORY_ITEM,
        SCOREBOARD,
        PLAYER_TAG,
        PERMISSION
    }
}
