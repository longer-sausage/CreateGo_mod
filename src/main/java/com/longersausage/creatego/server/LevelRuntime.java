/*
 * Runs server-authoritative CreateGo level simulations.
 * 运行服务端权威的 CreateGo 关卡模拟。
 *
 * Author: CreateGo
 * Date: 2026-08-04
 */

package com.longersausage.creatego.server;

import com.longersausage.creatego.data.LevelDefinition;
import com.longersausage.creatego.data.MapDefinition;
import com.longersausage.creatego.data.ModStore;
import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Owns transient play sessions and evaluates level rules on server ticks.
 * 管理临时游玩会话，并在服务端刻计算关卡规则。
 */
public final class LevelRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(LevelRuntime.class);
    private static final Map<MinecraftServer, Map<UUID, PlaySession>> SESSIONS = new WeakHashMap<>();

    private LevelRuntime() {
    }

    /**
     * Starts a clean simulation for the requesting player in the bound map dimension.
     * 为请求玩家在绑定地图维度中启动一次全新模拟。
     *
     * @param player requesting challenger / 请求闯关者
     */
    public static synchronized void start(ServerPlayer player) {
        DimensionPool.Session mapSession = DimensionPool.activeSession(player);
        if (mapSession == null) {
            throw new IllegalArgumentException("请先进入并绑定一张地图。");
        }
        MapDefinition map = ModStore.get(player.server).state().maps.get(mapSession.mapId());
        if (map == null || map.level == null) {
            throw new IllegalArgumentException("当前地图尚未注册为关卡。");
        }
        LevelConditionEvaluator.validate(map.level);
        int totalTicks = Math.max(20, map.level.timeLimitSeconds * 20);
        PlaySession session = new PlaySession(
                player.getUUID(),
                map.id,
                player.serverLevel().dimension(),
                player.server.getTickCount(),
                totalTicks
        );
        sessions(player.server).put(player.getUUID(), session);
        player.setHealth(player.getMaxHealth());
        player.teleportTo(
                player.serverLevel(),
                map.spawnX + 0.5D,
                map.spawnY,
                map.spawnZ + 0.5D,
                java.util.Set.of(),
                map.direction.yaw,
                0.0F
        );
        LOGGER.info("玩家 [{}] 开始模拟关卡 [地图: {}, 时限: {} 秒]", player.getScoreboardName(), map.id, map.level.timeLimitSeconds);
        sendStatus(
                player,
                session,
                LevelConditionEvaluator.evaluate(player.serverLevel(), player, map.level.completionCondition),
                ""
        );
    }

    /**
     * Stops one player's simulation without producing a result banner.
     * 停止一个玩家的模拟且不生成结果横幅。
     *
     * @param player target player / 目标玩家
     */
    public static synchronized void stop(ServerPlayer player) {
        PlaySession removed = sessions(player.server).remove(player.getUUID());
        if (removed != null) {
            ModNetwork.send(player, "level_play_status", ModStore.toJson(
                    new ModNetwork.LevelPlayStatus(false, "已停止模拟", 0, removed.totalTicks, false, java.util.List.of())
            ));
            LOGGER.info("玩家 [{}] 停止模拟关卡 [地图: {}]", player.getScoreboardName(), removed.mapId);
        }
    }

    /**
     * Removes transient state when a player disconnects.
     * 在玩家断开连接时移除临时状态。
     *
     * @param player disconnected player / 断开连接的玩家
     */
    public static synchronized void discard(ServerPlayer player) {
        sessions(player.server).remove(player.getUUID());
    }

    /**
     * Evaluates all active simulations after each server tick.
     * 在每个服务端刻结束后计算全部活动模拟。
     *
     * @param server running server / 正在运行的服务端
     */
    public static synchronized void tick(MinecraftServer server) {
        Iterator<PlaySession> iterator = sessions(server).values().iterator();
        while (iterator.hasNext()) {
            PlaySession session = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
            MapDefinition map = ModStore.get(server).state().maps.get(session.mapId);
            if (player == null || map == null || map.level == null
                    || !player.serverLevel().dimension().equals(session.dimensionKey)) {
                iterator.remove();
                if (player != null) {
                    finish(player, session, false, "模拟已中断");
                }
                continue;
            }
            if (player.isDeadOrDying()) {
                iterator.remove();
                finish(player, session, false, "闯关者死亡");
                continue;
            }
            int elapsed = server.getTickCount() - session.startedTick;
            int remaining = Math.max(0, session.totalTicks - elapsed);
            if (remaining <= 0) {
                iterator.remove();
                finish(player, session, false, "时间耗尽");
                continue;
            }
            // Complex entity and block predicates are sampled four times per second to protect tick time. / 复杂实体与方块谓词每秒采样四次，以保护服务端刻时长。
            if (elapsed % 5 != 0) {
                continue;
            }
            LevelConditionEvaluator.Evaluation completion = LevelConditionEvaluator.evaluate(
                    player.serverLevel(), player, map.level.completionCondition
            );
            if (completion.matched()) {
                iterator.remove();
                finish(player, session, true, "关卡完成");
                continue;
            }
            String failedRestriction = null;
            int continuousDamageCount = 0;
            for (LevelDefinition.RestrictionRule rule : map.level.restrictions) {
                LevelConditionEvaluator.Evaluation restriction = LevelConditionEvaluator.evaluate(
                        player.serverLevel(), player, rule.condition
                );
                if (!restriction.matched()) {
                    continue;
                }
                if (rule.punishment == LevelDefinition.Punishment.IMMEDIATE_FAILURE) {
                    failedRestriction = rule.name;
                    break;
                }
                continuousDamageCount++;
            }
            if (failedRestriction != null) {
                iterator.remove();
                finish(player, session, false, "触发限制：“" + failedRestriction + "”");
                continue;
            }
            if (continuousDamageCount > 0 && elapsed % 10 == 0) {
                // Every matching rule contributes one lava-sized damage unit. / 每条成立规则独立贡献一次熔岩等量伤害。
                player.hurt(player.damageSources().lava(), 4.0F * continuousDamageCount);
            }
            sendStatus(player, session, completion, "");
        }
    }

    private static void sendStatus(
            ServerPlayer player,
            PlaySession session,
            LevelConditionEvaluator.Evaluation completion,
            String result
    ) {
        int elapsed = player.server.getTickCount() - session.startedTick;
        int remaining = Math.max(0, session.totalTicks - elapsed);
        ModNetwork.send(player, "level_play_status", ModStore.toJson(new ModNetwork.LevelPlayStatus(
                true,
                result,
                remaining,
                session.totalTicks,
                completion.matched(),
                completion.progress()
        )));
    }

    private static void finish(ServerPlayer player, PlaySession session, boolean success, String result) {
        LevelConditionEvaluator.Evaluation completion = success
                ? new LevelConditionEvaluator.Evaluation(true, java.util.List.of())
                : new LevelConditionEvaluator.Evaluation(false, java.util.List.of());
        ModNetwork.send(player, "level_play_status", ModStore.toJson(new ModNetwork.LevelPlayStatus(
                false,
                result,
                0,
                session.totalTicks,
                completion.matched(),
                completion.progress()
        )));
        if (success) {
            LOGGER.info("玩家 [{}] 完成关卡模拟 [地图: {}]", player.getScoreboardName(), session.mapId);
        } else {
            LOGGER.info("玩家 [{}] 关卡模拟失败 [地图: {}, 原因: {}]", player.getScoreboardName(), session.mapId, result);
        }
    }

    private static Map<UUID, PlaySession> sessions(MinecraftServer server) {
        return SESSIONS.computeIfAbsent(server, ignored -> new HashMap<>());
    }

    /**
     * Stores immutable runtime identity and timing information.
     * 保存不可变的运行时身份与计时信息。
     */
    private static final class PlaySession {
        private final UUID playerId;
        private final String mapId;
        private final ResourceKey<Level> dimensionKey;
        private final int startedTick;
        private final int totalTicks;

        private PlaySession(
                UUID playerId,
                String mapId,
                ResourceKey<Level> dimensionKey,
                int startedTick,
                int totalTicks
        ) {
            this.playerId = playerId;
            this.mapId = mapId;
            this.dimensionKey = dimensionKey;
            this.startedTick = startedTick;
            this.totalTicks = totalTicks;
        }
    }
}
