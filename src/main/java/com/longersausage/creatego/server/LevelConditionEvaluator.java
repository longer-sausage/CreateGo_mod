/*
 * Evaluates server-authoritative level condition trees.
 * 计算服务端权威的关卡条件树。
 *
 * Author: CreateGo
 * Date: 2026-08-04
 */

package com.longersausage.creatego.server;

import com.longersausage.creatego.data.LevelDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates all supported predicates and produces leaf progress for the HUD.
 * 计算所有支持的谓词，并为 HUD 生成叶节点进度。
 */
public final class LevelConditionEvaluator {
    private static final int MAX_BLOCK_SEARCH_RADIUS = 48;
    private static final double EQUALITY_EPSILON = 0.0001D;

    private LevelConditionEvaluator() {
    }

    /**
     * Evaluates a root node against one player's current level.
     * 针对一个玩家的当前世界计算根节点。
     *
     * @param level current server level / 当前服务端世界
     * @param player challenger / 闯关者
     * @param root condition root / 条件根节点
     * @return aggregate match and leaf progress / 聚合结果与叶节点进度
     */
    public static Evaluation evaluate(ServerLevel level, ServerPlayer player, LevelDefinition.ConditionNode root) {
        List<Progress> progress = new ArrayList<>();
        boolean matched = root != null && evaluateNode(level, player, root, progress);
        return new Evaluation(matched, progress);
    }

    /**
     * Validates and normalizes a complete level definition received from a client.
     * 验证并规范化从客户端收到的完整关卡定义。
     *
     * @param definition incoming level definition / 传入的关卡定义
     */
    public static void validate(LevelDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("关卡配置不能为空。");
        }
        definition.timeLimitSeconds = Math.max(1, Math.min(definition.timeLimitSeconds, 24 * 60 * 60));
        validateNode(definition.completionCondition, 0, new int[]{0});
        definition.restrictions = definition.restrictions == null ? new ArrayList<>() : definition.restrictions;
        if (definition.restrictions.size() > 64) {
            throw new IllegalArgumentException("单个关卡最多允许 64 条限制规则。");
        }
        for (int index = 0; index < definition.restrictions.size(); index++) {
            LevelDefinition.RestrictionRule rule = definition.restrictions.get(index);
            if (rule == null) {
                throw new IllegalArgumentException("限制规则列表包含空项。");
            }
            if (rule.id == null || rule.id.isBlank()) {
                rule.id = java.util.UUID.randomUUID().toString();
            }
            rule.name = rule.name == null || rule.name.isBlank()
                    ? "限制 " + (index + 1)
                    : rule.name.strip().substring(0, Math.min(64, rule.name.strip().length()));
            rule.punishment = rule.punishment == null
                    ? LevelDefinition.Punishment.CONTINUOUS_DAMAGE
                    : rule.punishment;
            validateNode(rule.condition, 0, new int[]{0});
        }
    }

    private static void validateNode(LevelDefinition.ConditionNode node, int depth, int[] count) {
        if (node == null || node.type == null) {
            throw new IllegalArgumentException("条件树包含空节点或未知类型。");
        }
        if (depth > 12 || ++count[0] > 256) {
            throw new IllegalArgumentException("条件树最多允许 12 层、256 个节点。");
        }
        if (node.id == null || node.id.isBlank()) {
            node.id = java.util.UUID.randomUUID().toString();
        }
        if (!Double.isFinite(node.x) || !Double.isFinite(node.y)
                || !Double.isFinite(node.z) || !Double.isFinite(node.value)) {
            throw new IllegalArgumentException("条件参数必须是有限数字。");
        }
        node.children = node.children == null ? new ArrayList<>() : node.children;
        node.comparison = node.comparison == null ? LevelDefinition.Comparison.LESS_OR_EQUAL : node.comparison;
        node.axis = node.axis == null ? LevelDefinition.Axis.X : node.axis;
        node.entityMatch = node.entityMatch == null ? LevelDefinition.EntityMatch.ANY : node.entityMatch;
        node.subject = node.subject == null ? LevelDefinition.Subject.PLAYER : node.subject;
        node.entityType = normalizeIdentifier(node.entityType, "minecraft:pig", "实体类型");
        node.blockId = normalizeIdentifier(node.blockId, "minecraft:stone", "方块或流体类型");
        if (!node.isLogic()) {
            node.children.clear();
            validateRegistryReferences(node);
            return;
        }
        if (node.type == LevelDefinition.NodeType.NOT && node.children.size() > 1) {
            throw new IllegalArgumentException("NOT 节点最多只能有一个子节点。");
        }
        for (LevelDefinition.ConditionNode child : node.children) {
            validateNode(child, depth + 1, count);
        }
    }

    private static void validateRegistryReferences(LevelDefinition.ConditionNode node) {
        boolean needsEntity = node.type == LevelDefinition.NodeType.ENTITY_COUNT
                || node.type == LevelDefinition.NodeType.ENTITY_DISTANCE
                || node.type == LevelDefinition.NodeType.ENTITY_COORDINATE
                || node.type == LevelDefinition.NodeType.SUBJECT_BLOCK_DISTANCE
                && node.subject == LevelDefinition.Subject.ENTITY;
        ResourceLocation entityId = ResourceLocation.tryParse(node.entityType);
        if (needsEntity && (entityId == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(entityId))) {
            throw new IllegalArgumentException("实体类型不存在：" + node.entityType);
        }
        boolean needsBlock = node.type == LevelDefinition.NodeType.PLAYER_TOUCHING_BLOCK
                || node.type == LevelDefinition.NodeType.SUBJECT_BLOCK_DISTANCE;
        ResourceLocation blockId = ResourceLocation.tryParse(node.blockId);
        if (needsBlock && (blockId == null
                || !BuiltInRegistries.BLOCK.containsKey(blockId) && !BuiltInRegistries.FLUID.containsKey(blockId))) {
            throw new IllegalArgumentException("方块或流体类型不存在：" + node.blockId);
        }
    }

    private static String normalizeIdentifier(String raw, String fallback, String name) {
        String value = raw == null || raw.isBlank() ? fallback : raw.strip().toLowerCase(Locale.ROOT);
        if (ResourceLocation.tryParse(value) == null) {
            throw new IllegalArgumentException(name + "不是有效的资源标识：" + value);
        }
        return value;
    }

    private static boolean evaluateNode(
            ServerLevel level,
            ServerPlayer player,
            LevelDefinition.ConditionNode node,
            List<Progress> progress
    ) {
        if (node == null || node.type == null) {
            return false;
        }
        return switch (node.type) {
            case AND -> evaluateChildren(level, player, node.children, progress, true);
            case OR -> evaluateChildren(level, player, node.children, progress, false);
            case NOT -> node.children.size() == 1 && !evaluateNode(level, player, node.children.getFirst(), progress);
            default -> evaluatePredicate(level, player, node, progress);
        };
    }

    private static boolean evaluateChildren(
            ServerLevel level,
            ServerPlayer player,
            List<LevelDefinition.ConditionNode> children,
            List<Progress> progress,
            boolean requireAll
    ) {
        if (children.isEmpty()) {
            return false;
        }
        boolean result = requireAll;
        // Evaluate every branch so the HUD always receives complete leaf progress. / 计算每个分支，使 HUD 始终收到完整叶节点进度。
        for (LevelDefinition.ConditionNode child : children) {
            boolean matched = evaluateNode(level, player, child, progress);
            result = requireAll ? result && matched : result || matched;
        }
        return result;
    }

    private static boolean evaluatePredicate(
            ServerLevel level,
            ServerPlayer player,
            LevelDefinition.ConditionNode node,
            List<Progress> progress
    ) {
        PredicateValue result = switch (node.type) {
            case PLAYER_DISTANCE -> numeric(
                    player.position().distanceTo(new Vec3(node.x, node.y, node.z)),
                    node,
                    "玩家到坐标 " + formatPoint(node) + " 的距离"
            );
            case PLAYER_COORDINATE -> numeric(
                    coordinate(player, node.axis),
                    node,
                    "玩家 " + node.axis + " 坐标"
            );
            case ENTITY_COUNT -> numeric(
                    matchingEntities(level, node.entityType).size(),
                    node,
                    "实体 " + node.entityType + " 数量"
            );
            case ENTITY_DISTANCE -> entityAggregate(
                    matchingEntities(level, node.entityType),
                    node,
                    entity -> entity.position().distanceTo(new Vec3(node.x, node.y, node.z)),
                    "实体 " + node.entityType + " 到 " + formatPoint(node) + " 的距离"
            );
            case ENTITY_COORDINATE -> entityAggregate(
                    matchingEntities(level, node.entityType),
                    node,
                    entity -> coordinate(entity, node.axis),
                    "实体 " + node.entityType + " 的 " + node.axis + " 坐标"
            );
            case PLAYER_TOUCHING_BLOCK -> new PredicateValue(
                    isTouching(level, player.getBoundingBox(), node.blockId),
                    "玩家接触 " + node.blockId
            );
            case SUBJECT_BLOCK_DISTANCE -> blockDistance(level, player, node);
            default -> new PredicateValue(false, "不支持的条件");
        };
        progress.add(new Progress(node.id, result.description, result.matched));
        return result.matched;
    }

    private static PredicateValue blockDistance(
            ServerLevel level,
            ServerPlayer player,
            LevelDefinition.ConditionNode node
    ) {
        if (node.subject == LevelDefinition.Subject.PLAYER) {
            double distance = nearestBlockDistance(level, player.position(), node.blockId, node.value);
            return numeric(distance, node, "玩家到最近 " + node.blockId + " 的距离");
        }
        return entityAggregate(
                matchingEntities(level, node.entityType),
                node,
                entity -> nearestBlockDistance(level, entity.position(), node.blockId, node.value),
                "实体 " + node.entityType + " 到最近 " + node.blockId + " 的距离"
        );
    }

    private static PredicateValue entityAggregate(
            List<Entity> entities,
            LevelDefinition.ConditionNode node,
            java.util.function.ToDoubleFunction<Entity> valueFunction,
            String description
    ) {
        if (entities.isEmpty()) {
            return new PredicateValue(false, description + "（无目标）");
        }
        boolean matched = node.entityMatch == LevelDefinition.EntityMatch.ALL;
        double representative = node.entityMatch == LevelDefinition.EntityMatch.ALL
                ? Double.NEGATIVE_INFINITY
                : Double.POSITIVE_INFINITY;
        for (Entity entity : entities) {
            double value = valueFunction.applyAsDouble(entity);
            boolean current = Double.isFinite(value) && compare(value, node.comparison, node.value);
            if (node.entityMatch == LevelDefinition.EntityMatch.ANY) {
                matched |= current;
                representative = Math.min(representative, value);
            } else {
                matched &= current;
                representative = Math.max(representative, value);
            }
        }
        String scope = node.entityMatch == LevelDefinition.EntityMatch.ANY ? "至少一个" : "全部";
        return new PredicateValue(matched, scope + description + "，参考值 " + formatNumber(representative));
    }

    private static PredicateValue numeric(double actual, LevelDefinition.ConditionNode node, String description) {
        boolean matched = Double.isFinite(actual) && compare(actual, node.comparison, node.value);
        return new PredicateValue(
                matched,
                description + "：" + formatNumber(actual) + " " + comparisonSymbol(node.comparison)
                        + " " + formatNumber(node.value)
        );
    }

    private static boolean compare(double actual, LevelDefinition.Comparison comparison, double expected) {
        return switch (comparison) {
            case GREATER_OR_EQUAL -> actual >= expected;
            case GREATER -> actual > expected;
            case EQUAL -> Math.abs(actual - expected) <= EQUALITY_EPSILON;
            case NOT_EQUAL -> Math.abs(actual - expected) > EQUALITY_EPSILON;
            case LESS -> actual < expected;
            case LESS_OR_EQUAL -> actual <= expected;
        };
    }

    private static List<Entity> matchingEntities(ServerLevel level, String entityType) {
        ResourceLocation expected = ResourceLocation.tryParse(entityType);
        List<Entity> matches = new ArrayList<>();
        if (expected == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(expected)) {
            return matches;
        }
        for (Entity entity : level.getAllEntities()) {
            if (expected.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))) {
                matches.add(entity);
            }
        }
        return matches;
    }

    private static boolean isTouching(ServerLevel level, AABB bounds, String blockId) {
        int minX = (int) Math.floor(bounds.minX - 0.02D);
        int minY = (int) Math.floor(bounds.minY - 0.02D);
        int minZ = (int) Math.floor(bounds.minZ - 0.02D);
        int maxX = (int) Math.floor(bounds.maxX + 0.02D);
        int maxY = (int) Math.floor(bounds.maxY + 0.02D);
        int maxZ = (int) Math.floor(bounds.maxZ + 0.02D);
        for (BlockPos position : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (matchesBlockOrFluid(level, position, blockId)) {
                return true;
            }
        }
        return false;
    }

    private static double nearestBlockDistance(ServerLevel level, Vec3 origin, String blockId, double expected) {
        int radius = Math.min(MAX_BLOCK_SEARCH_RADIUS, Math.max(8, (int) Math.ceil(Math.abs(expected)) + 2));
        BlockPos center = BlockPos.containing(origin);
        return BlockPos.findClosestMatch(
                center,
                radius,
                radius,
                position -> matchesBlockOrFluid(level, position, blockId)
        ).map(position -> origin.distanceTo(Vec3.atCenterOf(position))).orElse(Double.NaN);
    }

    private static boolean matchesBlockOrFluid(ServerLevel level, BlockPos position, String wantedId) {
        ResourceLocation wanted = ResourceLocation.tryParse(wantedId);
        if (wanted == null) {
            return false;
        }
        BlockState blockState = level.getBlockState(position);
        if (wanted.equals(BuiltInRegistries.BLOCK.getKey(blockState.getBlock()))) {
            return true;
        }
        FluidState fluidState = level.getFluidState(position);
        if (fluidState.isEmpty()) {
            return false;
        }
        if (wanted.equals(BuiltInRegistries.FLUID.getKey(fluidState.getType()))) {
            return true;
        }
        return wanted.equals(ResourceLocation.withDefaultNamespace("water")) && fluidState.is(FluidTags.WATER)
                || wanted.equals(ResourceLocation.withDefaultNamespace("lava")) && fluidState.is(FluidTags.LAVA);
    }

    private static double coordinate(Entity entity, LevelDefinition.Axis axis) {
        return switch (axis) {
            case X -> entity.getX();
            case Y -> entity.getY();
            case Z -> entity.getZ();
        };
    }

    private static String formatPoint(LevelDefinition.ConditionNode node) {
        return "(" + formatNumber(node.x) + ", " + formatNumber(node.y) + ", " + formatNumber(node.z) + ")";
    }

    private static String formatNumber(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "未找到";
    }

    private static String comparisonSymbol(LevelDefinition.Comparison comparison) {
        return switch (comparison) {
            case GREATER_OR_EQUAL -> "≥";
            case GREATER -> ">";
            case EQUAL -> "=";
            case NOT_EQUAL -> "≠";
            case LESS -> "<";
            case LESS_OR_EQUAL -> "≤";
        };
    }

    /**
     * Contains the aggregate result and every evaluated predicate.
     * 包含聚合结果和每个已计算谓词。
     *
     * @param matched aggregate tree result / 条件树聚合结果
     * @param progress leaf progress / 叶节点进度
     */
    public record Evaluation(boolean matched, List<Progress> progress) {
    }

    /**
     * Describes one predicate for compact HUD rendering.
     * 描述一个供紧凑 HUD 渲染的谓词。
     *
     * @param id persistent node identifier / 持久节点标识
     * @param description current readable state / 当前可读状态
     * @param matched whether the predicate currently matches / 谓词当前是否匹配
     */
    public record Progress(String id, String description, boolean matched) {
    }

    private record PredicateValue(boolean matched, String description) {
    }
}
