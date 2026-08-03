/*
 * Defines the rules of a playable CreateGo level.
 * 定义可游玩 CreateGo 关卡的规则。
 *
 * Author: CreateGo
 * Date: 2026-08-04
 */

package com.longersausage.creatego.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stores completion rules, restriction rules, punishment, and the time limit.
 * 保存过关规则、限制规则、惩罚和时间限制。
 */
public final class LevelDefinition {
    public ConditionNode completionCondition = ConditionNode.group(NodeType.AND);
    public List<RestrictionRule> restrictions = new ArrayList<>();
    public int timeLimitSeconds = 300;

    /**
     * Creates a normalized default level definition.
     * 创建规范化的默认关卡定义。
     *
     * @return new level definition / 新关卡定义
     */
    public static LevelDefinition createDefault() {
        return new LevelDefinition();
    }

    /**
     * Stores one independently evaluated restriction and its own punishment.
     * 保存一条独立计算且拥有独立惩罚的限制规则。
     */
    public static final class RestrictionRule {
        public String id = UUID.randomUUID().toString();
        public String name = "新限制";
        public ConditionNode condition = ConditionNode.group(NodeType.OR);
        public Punishment punishment = Punishment.CONTINUOUS_DAMAGE;
    }

    /**
     * Stores one logical or predicate node in a condition tree.
     * 保存条件树中的一个逻辑或谓词节点。
     */
    public static final class ConditionNode {
        public String id = UUID.randomUUID().toString();
        public NodeType type = NodeType.PLAYER_DISTANCE;
        public List<ConditionNode> children = new ArrayList<>();
        public Comparison comparison = Comparison.LESS_OR_EQUAL;
        public Axis axis = Axis.X;
        public EntityMatch entityMatch = EntityMatch.ANY;
        public Subject subject = Subject.PLAYER;
        public String entityType = "minecraft:pig";
        public String blockId = "minecraft:stone";
        public double x;
        public double y = 64.0D;
        public double z;
        public double value = 1.0D;

        /**
         * Creates one logic group node.
         * 创建一个逻辑分组节点。
         *
         * @param type AND, OR, or NOT / AND、OR 或 NOT
         * @return group node / 分组节点
         */
        public static ConditionNode group(NodeType type) {
            ConditionNode node = new ConditionNode();
            node.type = type;
            return node;
        }

        /**
         * Returns whether this node is a logic operator.
         * 返回此节点是否为逻辑运算符。
         *
         * @return whether the node owns children / 节点是否拥有子节点
         */
        public boolean isLogic() {
            return type == NodeType.AND || type == NodeType.OR || type == NodeType.NOT;
        }
    }

    /**
     * Enumerates logic operators and supported concrete predicates.
     * 枚举逻辑运算符和支持的具体谓词。
     */
    public enum NodeType {
        AND,
        OR,
        NOT,
        PLAYER_DISTANCE,
        PLAYER_COORDINATE,
        ENTITY_COUNT,
        ENTITY_DISTANCE,
        ENTITY_COORDINATE,
        PLAYER_TOUCHING_BLOCK,
        SUBJECT_BLOCK_DISTANCE
    }

    /**
     * Enumerates numeric comparisons.
     * 枚举数值比较运算。
     */
    public enum Comparison {
        GREATER_OR_EQUAL,
        GREATER,
        EQUAL,
        NOT_EQUAL,
        LESS,
        LESS_OR_EQUAL
    }

    /**
     * Enumerates coordinate axes.
     * 枚举坐标轴。
     */
    public enum Axis {
        X,
        Y,
        Z
    }

    /**
     * Chooses existential or universal matching for selected entities.
     * 为指定实体选择至少一个或全部匹配语义。
     */
    public enum EntityMatch {
        ANY,
        ALL
    }

    /**
     * Selects the subject used by a block-distance predicate.
     * 选择方块距离谓词使用的主体。
     */
    public enum Subject {
        PLAYER,
        ENTITY
    }

    /**
     * Enumerates punishments triggered by the restriction tree.
     * 枚举限制条件树触发的惩罚。
     */
    public enum Punishment {
        CONTINUOUS_DAMAGE,
        IMMEDIATE_FAILURE
    }
}
