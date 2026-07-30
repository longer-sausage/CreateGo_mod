/*
 * Executes NPC dialogue graphs on the logical server.
 * 在逻辑服务端执行 NPC 对话图。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.server;

import com.longersausage.creatego.data.DialogueGraph;
import com.longersausage.creatego.data.ModStore;
import com.longersausage.creatego.data.NpcData;
import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Maintains short-lived conversation cursors and evaluates every branch on the server.
 * 维护短期对话游标，并在服务端计算每个分支。
 */
public final class DialogueRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(DialogueRuntime.class);
    private static final Map<UUID, ActiveDialogueSession> SESSIONS = new HashMap<>();

    private DialogueRuntime() {
    }

    /**
     * Starts a conversation at the configured root node.
     * 从配置的根节点开始对话。
     *
     * @param player interacting player / 交互玩家
     * @param npc selected NPC / 已选择 NPC
     * @return whether a reachable conversation was started / 是否启动了可达对话
     */
    public static boolean start(ServerPlayer player, NpcData npc) {
        SESSIONS.remove(player.getUUID());
        if (npc.dialogue == null) {
            return false;
        }
        npc.dialogue.ensureEntryNode();
        if (!hasReachableDialogue(npc.dialogue)) {
            return false;
        }
        ActiveDialogueSession session = new ActiveDialogueSession(npc);
        SESSIONS.put(player.getUUID(), session);
        LOGGER.info("玩家 [{}] 与 NPC [{}] 开始对话（NPC ID: {}）", player.getScoreboardName(), npc.name, npc.id);
        show(player, session, npc.dialogue.rootNodeId);
        return true;
    }

    /**
     * Handles a continue, option selection, or close operation.
     * 处理继续、选项选择或关闭操作。
     *
     * @param player sending player / 发送操作的玩家
     * @param action command identifier / 命令标识
     * @param json JSON command body / JSON 命令内容
     */
    public static void handle(ServerPlayer player, String action, String json) {
        if (action.equals("dialogue_close")) {
            SESSIONS.remove(player.getUUID());
            ModNetwork.send(player, "dialogue_close", "{}");
            return;
        }
        ActiveDialogueSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        DialogueChoice choice = ModStore.fromJson(json, DialogueChoice.class);
        if (choice.nodeId != session.currentNodeId) {
            return;
        }
        DialogueGraph.NodeData node = session.npc.dialogue.findNode(session.currentNodeId);
        if (node == null) {
            finish(player);
            return;
        }
        int target = -1;
        if (node.type == DialogueGraph.NodeType.DIALOGUE && action.equals("dialogue_next")) {
            target = node.nextNodeId;
        } else if (node.type == DialogueGraph.NodeType.OPTION
                && action.equals("dialogue_choice")
                && choice.optionIndex >= 0
                && choice.optionIndex < node.options.size()) {
            target = node.options.get(choice.optionIndex).targetNodeId;
        }
        if (target < 0) {
            finish(player);
        } else {
            show(player, session, target);
        }
    }

    private static void show(ServerPlayer player, ActiveDialogueSession session, int nodeId) {
        Set<Integer> traversed = new HashSet<>();
        int cursor = nodeId;
        int traversalLimit = Math.max(1, session.npc.dialogue.nodes.size() + 1);
        for (int step = 0; step < traversalLimit; step++) {
            DialogueGraph.NodeData node = session.npc.dialogue.findNode(cursor);
            if (node == null) {
                finish(player);
                return;
            }
            if (node.type == DialogueGraph.NodeType.BRANCH || node.type == DialogueGraph.NodeType.ENTRY) {
                if (!traversed.add(node.id)) {
                    LOGGER.warn("玩家 [{}] 与 NPC [{}] 的对话分支形成死循环 [节点 ID: {}]", player.getScoreboardName(), session.npc.name, node.id);
                    ModNetwork.error(player, "对话分支形成无交互死循环。");
                    finish(player);
                    return;
                }
                cursor = node.type == DialogueGraph.NodeType.ENTRY
                        ? node.nextNodeId
                        : branchTarget(player, node);
                if (cursor < 0) {
                    finish(player);
                    return;
                }
                continue;
            }
            if (node.type == DialogueGraph.NodeType.EXIT) {
                finish(player);
                return;
            }
            session.currentNodeId = node.id;
            List<String> options = node.type == DialogueGraph.NodeType.OPTION
                    ? node.options.stream().map(option -> option.text).toList()
            : List.of();
            ModNetwork.send(player, "dialogue_view", ModStore.toJson(
                    new DialogueView(session.npc.id.toString(), session.npc.name, node.text, node.id, options)
            ));
            return;
        }
        LOGGER.warn("玩家 [{}] 与 NPC [{}] 的对话分支遍历超过上限", player.getScoreboardName(), session.npc.name);
        ModNetwork.error(player, "对话分支超过安全遍历上限。");
        finish(player);
    }

    /**
     * Checks whether the entry can reach at least one player-visible node.
     * 检查入口是否至少能够到达一个玩家可见节点。
     *
     * @param graph normalized dialogue graph / 已规范化的对话图
     * @return whether right-click interaction should open a screen / 右键交互是否应打开界面
     */
    private static boolean hasReachableDialogue(DialogueGraph graph) {
        Deque<Integer> pending = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        pending.add(graph.rootNodeId);
        while (!pending.isEmpty()) {
            DialogueGraph.NodeData node = graph.findNode(pending.removeFirst());
            if (node == null || !visited.add(node.id)) {
                continue;
            }
            switch (node.type) {
                case DIALOGUE, OPTION -> {
                    return true;
                }
                case ENTRY -> addTarget(pending, node.nextNodeId);
                case BRANCH -> {
                    node.branches.forEach(branch -> addTarget(pending, branch.targetNodeId));
                    addTarget(pending, node.defaultNodeId);
                }
                case EXIT -> {
                    // Legacy exits are removed during normalization. / 旧版出口会在规范化时被移除。
                }
            }
        }
        return false;
    }

    /**
     * Adds a connected target to a traversal queue.
     * 将已连接目标加入遍历队列。
     *
     * @param pending traversal queue / 遍历队列
     * @param targetId target node identifier / 目标节点标识
     */
    private static void addTarget(Deque<Integer> pending, int targetId) {
        if (targetId >= 0) {
            pending.addLast(targetId);
        }
    }

    /**
     * Resolves the first matching branch or the default output.
     * 解析第一个满足的分支，或返回默认输出。
     */
    private static int branchTarget(ServerPlayer player, DialogueGraph.NodeData node) {
        for (DialogueGraph.BranchCase branch : node.branches) {
            if (matches(player, branch)) {
                return branch.targetNodeId;
            }
        }
        return node.defaultNodeId;
    }

    /**
     * Removes any conversation state owned by a leaving player.
     * 清理离线玩家持有的对话状态。
     */
    public static void stop(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    private static boolean matches(ServerPlayer player, DialogueGraph.BranchCase branch) {
        int actual;
        switch (branch.condition) {
            case INVENTORY_ITEM -> actual = countItem(player, branch.key);
            case SCOREBOARD -> actual = readScore(player, branch.key);
            case PLAYER_TAG -> actual = player.getTags().contains(branch.key) ? 1 : 0;
            case PERMISSION -> actual = player.hasPermissions(branch.value) ? branch.value : -1;
            default -> actual = 0;
        }
        return compare(actual, branch.operator, branch.value);
    }

    private static int countItem(ServerPlayer player, String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (item == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int readScore(ServerPlayer player, String objectiveName) {
        Objective objective = player.getScoreboard().getObjective(objectiveName);
        if (objective == null) {
            return 0;
        }
        ReadOnlyScoreInfo info = player.getScoreboard().getPlayerScoreInfo(player, objective);
        return info == null ? 0 : info.value();
    }

    private static boolean compare(int actual, String operator, int expected) {
        return switch (operator == null ? "≥" : operator) {
            case "=" -> actual == expected;
            case "≠" -> actual != expected;
            case ">" -> actual > expected;
            case "<" -> actual < expected;
            case "≤" -> actual <= expected;
            default -> actual >= expected;
        };
    }

    private static void finish(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
        ModNetwork.send(player, "dialogue_close", "{}");
    }

    private static final class ActiveDialogueSession {
        private final NpcData npc;
        private int currentNodeId = -1;

        private ActiveDialogueSession(NpcData npc) {
            this.npc = npc;
        }
    }

    /**
     * Defines a client dialogue action.
     * 定义客户端对话操作。
     */
    public static final class DialogueChoice {
        public int nodeId = -1;
        public int optionIndex = -1;
    }

    /**
     * Defines one rendered runtime dialogue state.
     * 定义一个已渲染的运行时对话状态。
     *
     * @param npcId NPC identifier / NPC 标识
     * @param npcName display name / 显示名称
     * @param text dialogue or prompt text / 对话或提示文本
     * @param nodeId current node identifier / 当前节点标识
     * @param options selectable options / 可选选项
     */
    public record DialogueView(String npcId, String npcName, String text, int nodeId, List<String> options) {
    }
}
