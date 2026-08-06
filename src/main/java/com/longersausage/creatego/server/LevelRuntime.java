/*
 * Runs editor simulations plus isolated preview and team challenge sessions for CreateGo levels.
 * 运行 CreateGo 关卡编辑器模拟，以及相互隔离的预览与团队挑战会话。
 *
 * Author: CreateGo
 * Date: 2026-08-05
 */

package com.longersausage.creatego.server;

import com.longersausage.creatego.data.LevelDefinition;
import com.longersausage.creatego.data.MapDefinition;
import com.longersausage.creatego.data.ModStore;
import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Owns formal level sessions and guarantees recoverable player-state isolation.
 * 管理正式关卡会话，并保证玩家状态隔离可恢复。
 */
public final class LevelRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(LevelRuntime.class);
    private static final String BACKUP_DIRECTORY = "level_player_backups";
    private static final Map<MinecraftServer, RuntimeState> STATES = new WeakHashMap<>();

    private LevelRuntime() {
    }

    /**
     * Enumerates supported isolated session modes.
     * 枚举支持的隔离会话模式。
     */
    public enum Mode {
        PREVIEW,
        CHALLENGE
    }

    /**
     * Starts an editor-only simulation inside the player's currently bound map dimension.
     * 在玩家当前绑定的地图维度内开始仅供编辑器使用的模拟。
     *
     * @param player requesting editor / 请求模拟的编辑者
     */
    public static synchronized void startSimulation(ServerPlayer player) {
        RuntimeState state = state(player.server);
        if (state.byPlayer.containsKey(player.getUUID())) {
            throw new IllegalStateException("请先退出当前关卡会话。");
        }
        if (state.simulations.containsKey(player.getUUID())) {
            throw new IllegalStateException("当前已在模拟关卡。");
        }
        DimensionPool.Session mapSession = DimensionPool.activeSession(player);
        if (mapSession == null) {
            throw new IllegalArgumentException("请先进入并绑定一张地图。");
        }
        MapDefinition map = ModStore.get(player.server).state().maps.get(mapSession.mapId());
        if (map == null || map.level == null) {
            throw new IllegalArgumentException("当前地图尚未注册为关卡。");
        }
        LevelConditionEvaluator.validate(map.level);
        EditorSimulation simulation = new EditorSimulation(
                player.getUUID(),
                map.id,
                player.serverLevel().dimension(),
                player.server.getTickCount(),
                Math.max(20, map.level.timeLimitSeconds * 20)
        );
        state.simulations.put(player.getUUID(), simulation);
        player.setHealth(player.getMaxHealth());
        player.teleportTo(
                player.serverLevel(),
                map.spawnX + 0.5D,
                map.spawnY,
                map.spawnZ + 0.5D,
                Set.of(),
                map.direction.yaw,
                0.0F
        );
        LevelConditionEvaluator.Evaluation completion = LevelConditionEvaluator.evaluate(
                player.serverLevel(), player, map.level.completionCondition
        );
        sendSimulationStatus(player, simulation, map, completion, "");
        LOGGER.info(
                "玩家 [{}] 开始编辑器关卡模拟 [地图: {}, 时限: {} 秒]",
                player.getScoreboardName(),
                map.id,
                map.level.timeLimitSeconds
        );
    }

    /**
     * Stops the player's editor simulation without affecting a formal level session.
     * 停止玩家的编辑器模拟，且不影响正式关卡会话。
     *
     * @param player target editor / 目标编辑者
     */
    public static synchronized void stopSimulation(ServerPlayer player) {
        EditorSimulation removed = state(player.server).simulations.remove(player.getUUID());
        if (removed == null) {
            return;
        }
        finishSimulation(player, removed, false, "已停止模拟");
    }

    /**
     * Starts a preview or challenge after validating the portal and all online team members.
     * 校验门户与全部在线队员后开始预览或挑战。
     *
     * @param player requesting player / 请求玩家
     * @param rawMapId requested map identifier / 请求的地图标识
     * @param mode requested mode / 请求模式
     */
    public static synchronized void begin(ServerPlayer player, String rawMapId, Mode mode) {
        String mapId = rawMapId == null ? "" : rawMapId.strip();
        MapDefinition map = ModStore.get(player.server).state().maps.get(mapId);
        if (map == null || map.level == null) {
            throw new IllegalArgumentException("关卡不存在：" + mapId);
        }
        LevelConditionEvaluator.validate(map.level);
        ItemStack portal = findPortalContainer(player, mapId, false);
        if (portal.isEmpty()) {
            throw new IllegalArgumentException("请手持或携带绑定此关卡的空间收纳器。");
        }
        if (mode == Mode.CHALLENGE && !LevelVehicleContainer.hasStoredVehicle(portal)) {
            portal = findPortalContainer(player, mapId, true);
            if (portal.isEmpty()) {
                throw new IllegalArgumentException("开始挑战需要空间收纳器中装有载具。");
            }
        }
        List<ServerPlayer> members = mode == Mode.CHALLENGE ? onlineTeamMembers(player) : List.of(player);
        validateMembers(player.server, members);
        DimensionPool.Session dimensionSession = ModService.requestLevelMap(player, mapId);
        GroupSession group = new GroupSession(
                UUID.randomUUID(),
                mode,
                mapId,
                player.getUUID(),
                members.stream().map(ServerPlayer::getUUID).toList(),
                dimensionSession.dimensionKey(),
                portal.copyWithCount(1),
                Math.max(20, map.level.timeLimitSeconds * 20)
        );
        RuntimeState state = state(player.server);
        state.byDimension.put(group.dimensionKey, group);
        for (UUID memberId : group.memberIds) {
            state.byPlayer.put(memberId, group);
        }
        LOGGER.info(
                "玩家 [{}] 创建关卡会话 [地图: {}, 模式: {}, 成员数: {}]",
                player.getScoreboardName(),
                mapId,
                mode,
                group.memberIds.size()
        );
    }

    /**
     * Converts a populated map dimension into a formal preview or challenge session.
     * 将已填充的地图维度转换为正式预览或挑战会话。
     *
     * @param owner dimension owner / 维度所有者
     * @param level populated level / 已填充世界
     * @param dimensionSession map dimension session / 地图维度会话
     * @param map map definition / 地图定义
     * @return whether the dimension belongs to a formal level session / 该维度是否属于正式关卡会话
     * @throws IOException when a backup or trial container cannot be created / 无法创建备份或试用收纳器时抛出
     */
    public static synchronized boolean onMapPopulated(
            ServerPlayer owner,
            ServerLevel level,
            DimensionPool.Session dimensionSession,
            MapDefinition map
    ) throws IOException {
        GroupSession group = state(owner.server).byDimension.get(dimensionSession.dimensionKey());
        if (group == null) {
            return false;
        }
        group.transitioning = true;
        List<ServerPlayer> members = resolveOnlineMembers(owner.server, group);
        if (members.size() != group.memberIds.size()) {
            removeGroup(owner.server, group);
            throw new IOException("队伍成员在地图准备期间离线，挑战已取消。");
        }
        try {
            if (group.mode == Mode.CHALLENGE) {
                group.trialContainer = LevelVehicleContainer.createTrialContainer(group.portalTemplate);
            }
            for (ServerPlayer member : members) {
                writeBackup(member);
            }
            for (ServerPlayer member : members) {
                resetToCleanPlayer(member);
                // Survival preserves ordinary right-click item use; server events still forbid combat and block edits.
                // 生存模式保留普通右键物品使用；服务端事件仍会禁止战斗和方块编辑。
                member.setGameMode(group.mode == Mode.PREVIEW ? GameType.SPECTATOR : GameType.SURVIVAL);
                if (group.mode == Mode.CHALLENGE && member.getUUID().equals(group.leaderId)) {
                    member.getInventory().setItem(0, group.trialContainer.copy());
                    member.getInventory().selected = 0;
                }
                DimensionPool.prepareEntry(member, group.dimensionKey);
                member.teleportTo(
                        level,
                        map.spawnX + 0.5D,
                        map.spawnY,
                        map.spawnZ + 0.5D,
                        Set.of(),
                        map.direction.yaw,
                        0.0F
                );
            }
            group.startedTick = owner.server.getTickCount();
            group.active = true;
            group.transitioning = false;
            Map<UUID, LevelConditionEvaluator.Evaluation> initialEvaluations = new LinkedHashMap<>();
            for (ServerPlayer member : members) {
                initialEvaluations.put(
                        member.getUUID(),
                        LevelConditionEvaluator.evaluate(level, member, map.level.completionCondition)
                );
            }
            sendStatuses(owner.server, group, map, initialEvaluations);
            for (ServerPlayer member : members) {
                ModNetwork.send(member, "close_screen", "{}");
            }
            LOGGER.info("关卡会话已进入地图 [会话: {}, 地图: {}, 模式: {}]", group.id, group.mapId, group.mode);
            return true;
        } catch (Exception exception) {
            group.transitioning = false;
            restoreMembers(owner.server, group);
            discardTrialContainers(group);
            removeGroup(owner.server, group);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("关卡会话初始化失败。", exception);
        }
    }

    /**
     * Evaluates every active team challenge after each server tick.
     * 在每个服务端刻结束后计算全部活动团队挑战。
     *
     * @param server running server / 运行中的服务端
     */
    public static synchronized void tick(MinecraftServer server) {
        List<GroupSession> groups = new ArrayList<>(state(server).byDimension.values());
        for (GroupSession group : groups) {
            if (!group.active || group.finishing) {
                continue;
            }
            MapDefinition map = ModStore.get(server).state().maps.get(group.mapId);
            ServerLevel level = server.getLevel(group.dimensionKey);
            List<ServerPlayer> members = resolveOnlineMembers(server, group);
            if (map == null || map.level == null || level == null || members.size() != group.memberIds.size()) {
                finish(server, group, false, "队伍成员离线或关卡地图不可用", false);
                continue;
            }
            if (members.stream().anyMatch(member -> !member.serverLevel().dimension().equals(group.dimensionKey))) {
                finish(server, group, false, "有队员离开了关卡地图", false);
                continue;
            }
            int elapsed = server.getTickCount() - group.startedTick;
            if (group.mode == Mode.CHALLENGE && elapsed >= group.totalTicks) {
                finish(server, group, false, "时间耗尽", false);
                continue;
            }
            if (elapsed % 5 != 0) {
                continue;
            }
            Map<UUID, LevelConditionEvaluator.Evaluation> evaluations = new LinkedHashMap<>();
            boolean allCompleted = true;
            String failure = null;
            for (ServerPlayer member : members) {
                LevelConditionEvaluator.Evaluation completion = LevelConditionEvaluator.evaluate(
                        level,
                        member,
                        map.level.completionCondition
                );
                evaluations.put(member.getUUID(), completion);
                allCompleted &= completion.matched();
                if (group.mode == Mode.PREVIEW) {
                    continue;
                }
                int continuousDamageCount = 0;
                for (LevelDefinition.RestrictionRule rule : map.level.restrictions) {
                    boolean matched = LevelConditionEvaluator.evaluate(level, member, rule.condition).matched();
                    if (!matched) {
                        continue;
                    }
                    if (rule.punishment == LevelDefinition.Punishment.IMMEDIATE_FAILURE) {
                        failure = member.getScoreboardName() + " 触发限制：“" + rule.name + "”";
                        break;
                    }
                    continuousDamageCount++;
                }
                if (failure != null) {
                    break;
                }
                if (continuousDamageCount > 0 && elapsed % 10 == 0) {
                    member.hurt(member.damageSources().lava(), 4.0F * continuousDamageCount);
                }
            }
            if (group.finishing) {
                continue;
            }
            if (group.mode == Mode.PREVIEW) {
                sendStatuses(server, group, map, evaluations);
            } else if (failure != null) {
                finish(server, group, false, failure, false);
            } else if (allCompleted) {
                finish(server, group, true, "全队完成关卡", false);
            } else {
                sendStatuses(server, group, map, evaluations);
            }
        }
        tickSimulations(server);
    }

    /**
     * Evaluates every editor simulation without applying formal challenge isolation or rewards.
     * 计算全部编辑器模拟，且不应用正式挑战的隔离与奖励。
     *
     * @param server running server / 正在运行的服务端
     */
    private static void tickSimulations(MinecraftServer server) {
        RuntimeState state = state(server);
        for (EditorSimulation simulation : new ArrayList<>(state.simulations.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(simulation.playerId);
            MapDefinition map = ModStore.get(server).state().maps.get(simulation.mapId);
            if (player == null) {
                state.simulations.remove(simulation.playerId, simulation);
                continue;
            }
            if (map == null || map.level == null
                    || !player.serverLevel().dimension().equals(simulation.dimensionKey)) {
                state.simulations.remove(simulation.playerId, simulation);
                finishSimulation(player, simulation, false, "模拟已中断");
                continue;
            }
            if (player.isDeadOrDying()) {
                state.simulations.remove(simulation.playerId, simulation);
                finishSimulation(player, simulation, false, "闯关者死亡");
                continue;
            }
            int elapsed = server.getTickCount() - simulation.startedTick;
            if (elapsed >= simulation.totalTicks) {
                state.simulations.remove(simulation.playerId, simulation);
                finishSimulation(player, simulation, false, "时间耗尽");
                continue;
            }
            // Complex predicates are sampled four times per second to protect server tick time.
            // 复杂谓词每秒采样四次，以保护服务端刻耗时。
            if (elapsed % 5 != 0) {
                continue;
            }
            LevelConditionEvaluator.Evaluation completion = LevelConditionEvaluator.evaluate(
                    player.serverLevel(), player, map.level.completionCondition
            );
            if (completion.matched()) {
                state.simulations.remove(simulation.playerId, simulation);
                finishSimulation(player, simulation, true, "关卡完成");
                continue;
            }
            String failedRestriction = null;
            int continuousDamageCount = 0;
            for (LevelDefinition.RestrictionRule rule : map.level.restrictions) {
                boolean matched = LevelConditionEvaluator.evaluate(player.serverLevel(), player, rule.condition).matched();
                if (!matched) {
                    continue;
                }
                if (rule.punishment == LevelDefinition.Punishment.IMMEDIATE_FAILURE) {
                    failedRestriction = rule.name;
                    break;
                }
                continuousDamageCount++;
            }
            if (failedRestriction != null) {
                state.simulations.remove(simulation.playerId, simulation);
                finishSimulation(player, simulation, false, "触发限制：“" + failedRestriction + "”");
                continue;
            }
            if (continuousDamageCount > 0 && elapsed % 10 == 0) {
                player.hurt(player.damageSources().lava(), 4.0F * continuousDamageCount);
            }
            sendSimulationStatus(player, simulation, map, completion, "");
        }
    }

    /**
     * Sends one editor simulation status using the current formal status payload shape.
     * 使用当前正式状态载荷格式发送一条编辑器模拟状态。
     *
     * @param player target editor / 目标编辑者
     * @param simulation active simulation / 活动模拟
     * @param map current map definition / 当前地图定义
     * @param completion current completion evaluation / 当前过关条件计算结果
     * @param result optional result text / 可选结果文本
     */
    private static void sendSimulationStatus(
            ServerPlayer player,
            EditorSimulation simulation,
            MapDefinition map,
            LevelConditionEvaluator.Evaluation completion,
            String result
    ) {
        int elapsed = player.server.getTickCount() - simulation.startedTick;
        int remaining = Math.max(0, simulation.totalTicks - elapsed);
        List<ModNetwork.RuleProgress> restrictions = map.level.restrictions.stream()
                .map(rule -> new ModNetwork.RuleProgress(
                        rule.name,
                        rule.punishment.name(),
                        LevelConditionEvaluator.evaluate(player.serverLevel(), player, rule.condition).matched()
                ))
                .toList();
        ModNetwork.send(player, "level_play_status", ModStore.toJson(new ModNetwork.LevelPlayStatus(
                true,
                simulation.mapId,
                "SIMULATION",
                result,
                remaining,
                simulation.totalTicks,
                completion.matched(),
                completion.progress(),
                restrictions,
                List.of(new ModNetwork.MemberProgress(player.getScoreboardName(), completion.matched()))
        )));
    }

    /**
     * Completes one editor simulation and reports its final result.
     * 完成一次编辑器模拟并报告最终结果。
     *
     * @param player target editor / 目标编辑者
     * @param simulation completed simulation / 已完成模拟
     * @param success whether the simulation succeeded / 模拟是否成功
     * @param result final result text / 最终结果文本
     */
    private static void finishSimulation(
            ServerPlayer player,
            EditorSimulation simulation,
            boolean success,
            String result
    ) {
        ModNetwork.send(player, "level_play_status", ModStore.toJson(new ModNetwork.LevelPlayStatus(
                false,
                simulation.mapId,
                "SIMULATION",
                result,
                0,
                simulation.totalTicks,
                success,
                List.of(),
                List.of(),
                List.of(new ModNetwork.MemberProgress(player.getScoreboardName(), success))
        )));
        LOGGER.info(
                "玩家 [{}] 编辑器关卡模拟结束 [地图: {}, 成功: {}, 结果: {}]",
                player.getScoreboardName(),
                simulation.mapId,
                success,
                result
        );
    }

    /**
     * Restarts the caller's whole session from a fresh map copy.
     * 从全新地图副本重新开始调用者所在的完整会话。
     *
     * @param player requesting participant / 请求参与者
     */
    public static synchronized void restart(ServerPlayer player) {
        GroupSession group = state(player.server).byPlayer.get(player.getUUID());
        if (group == null) {
            EditorSimulation simulation = state(player.server).simulations.remove(player.getUUID());
            if (simulation == null) {
                throw new IllegalArgumentException("当前没有可重新开始的关卡会话。");
            }
            startSimulation(player);
            return;
        }
        if (!group.active) {
            throw new IllegalArgumentException("当前关卡会话尚未进入地图。");
        }
        ServerPlayer leader = player.server.getPlayerList().getPlayer(group.leaderId);
        if (leader == null) {
            throw new IllegalStateException("关卡发起者已离线，无法重新开始。");
        }
        String mapId = group.mapId;
        Mode mode = group.mode;
        finish(player.server, group, false, "重新开始关卡", true);
        begin(leader, mapId, mode);
    }

    /**
     * Exits a preview or fails and exits the caller's whole team challenge.
     * 退出预览，或判定调用者所在的完整团队挑战失败并退出。
     *
     * @param player requesting participant / 请求参与者
     */
    public static synchronized void exit(ServerPlayer player) {
        GroupSession group = state(player.server).byPlayer.get(player.getUUID());
        if (group == null) {
            EditorSimulation simulation = state(player.server).simulations.remove(player.getUUID());
            if (simulation == null) {
                throw new IllegalArgumentException("当前没有活动关卡会话。");
            }
            finishSimulation(player, simulation, false, "已停止模拟");
            return;
        }
        String reason = group.mode == Mode.PREVIEW ? "已退出关卡预览" : player.getScoreboardName() + " 退出了挑战";
        finish(player.server, group, false, reason, false);
    }

    /**
     * Reports whether a player is protected by challenge interaction restrictions.
     * 返回玩家是否受挑战交互限制保护。
     *
     * @param player target player / 目标玩家
     * @return whether the player is in an active challenge / 玩家是否处于活动挑战中
     */
    public static synchronized boolean isChallengeParticipant(ServerPlayer player) {
        GroupSession group = state(player.server).byPlayer.get(player.getUUID());
        return group != null && group.active && !group.finishing && group.mode == Mode.CHALLENGE;
    }

    /**
     * Converts a participant death into immediate whole-team failure.
     * 将参与者死亡转换为立即全队失败。
     *
     * @param player dying participant / 即将死亡的参与者
     * @return whether normal death should be cancelled / 是否应取消正常死亡
     */
    public static synchronized boolean failOnDeath(ServerPlayer player) {
        GroupSession group = state(player.server).byPlayer.get(player.getUUID());
        if (group == null || !group.active || group.finishing) {
            return false;
        }
        finish(player.server, group, false, player.getScoreboardName() + " 死亡", false);
        return true;
    }

    /**
     * Handles dimension changes and treats every unsanctioned departure as failure or exit.
     * 处理维度变化，并将任何未授权离场判定为失败或退出。
     *
     * @param player moving participant / 正在移动的参与者
     * @param fromDimension source dimension / 来源维度
     */
    public static synchronized void onDimensionChanged(ServerPlayer player, ResourceKey<Level> fromDimension) {
        GroupSession group = state(player.server).byPlayer.get(player.getUUID());
        if (group == null || group.finishing || group.transitioning || !group.active) {
            return;
        }
        if (fromDimension.equals(group.dimensionKey)
                && !player.serverLevel().dimension().equals(group.dimensionKey)) {
            finish(player.server, group, false, player.getScoreboardName() + " 离开了关卡地图", false);
        }
    }

    /**
     * Restores a disconnecting participant before vanilla persists the player entity.
     * 在原版持久化玩家实体前恢复正在断线的参与者。
     *
     * @param player disconnecting player / 断线玩家
     * @return whether a formal level session was handled / 是否处理了正式关卡会话
     */
    public static synchronized boolean onLogout(ServerPlayer player) {
        GroupSession group = state(player.server).byPlayer.get(player.getUUID());
        if (group == null) {
            state(player.server).simulations.remove(player.getUUID());
            return false;
        }
        finish(player.server, group, false, player.getScoreboardName() + " 离线", false);
        return true;
    }

    /**
     * Restores a crash-surviving disk backup when a player next logs in.
     * 玩家下次登录时恢复由崩溃遗留在磁盘上的备份。
     *
     * @param player logging-in player / 登录玩家
     */
    public static synchronized void restorePendingBackup(ServerPlayer player) {
        Path path = backupPath(player.server, player.getUUID());
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            restorePlayer(player);
            LOGGER.warn("玩家 [{}] 已从遗留的关卡备份中恢复。", player.getScoreboardName());
        } catch (Exception exception) {
            LOGGER.error("玩家 [{}] 的遗留关卡备份恢复失败。", player.getScoreboardName(), exception);
        }
    }

    /**
     * Finalizes one whole group, restores players, rewards success, and deletes the dimension.
     * 结束一个完整小组，恢复玩家、奖励成功并删除维度。
     */
    private static void finish(
            MinecraftServer server,
            GroupSession group,
            boolean success,
            String result,
            boolean restarting
    ) {
        if (group.finishing) {
            return;
        }
        group.finishing = true;
        group.active = false;
        List<ServerPlayer> members = resolveOnlineMembers(server, group);
        for (ServerPlayer member : members) {
            ModNetwork.send(member, "level_play_status", ModStore.toJson(new ModNetwork.LevelPlayStatus(
                    false,
                    group.mapId,
                    group.mode.name(),
                    result,
                    0,
                    group.totalTicks,
                    success,
                    List.of(),
                    List.of(),
                    List.of()
            )));
        }
        restoreMembers(server, group);
        if (success) {
            grantStage(server, group, members);
        }
        discardTrialContainers(group);
        for (UUID memberId : group.memberIds) {
            DimensionPool.unbind(server, memberId, group.dimensionKey);
        }
        DimensionPool.Session detached = DimensionPool.detachDimension(server, group.dimensionKey);
        if (detached != null) {
            DimensionPool.deleteDimension(server, detached);
        }
        removeGroup(server, group);
        if (!restarting) {
            LOGGER.info(
                    "关卡会话结束 [会话: {}, 地图: {}, 成功: {}, 结果: {}]",
                    group.id,
                    group.mapId,
                    success,
                    result
            );
        }
    }

    /**
     * Sends personalized live progress to every online participant.
     * 向每名在线参与者发送个性化实时进度。
     */
    private static void sendStatuses(
            MinecraftServer server,
            GroupSession group,
            MapDefinition map,
            Map<UUID, LevelConditionEvaluator.Evaluation> evaluations
    ) {
        int remaining = group.mode == Mode.PREVIEW
                ? 0
                : Math.max(0, group.totalTicks - (server.getTickCount() - group.startedTick));
        List<ModNetwork.MemberProgress> memberProgress = new ArrayList<>();
        for (UUID memberId : group.memberIds) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            LevelConditionEvaluator.Evaluation evaluation = evaluations.get(memberId);
            memberProgress.add(new ModNetwork.MemberProgress(
                    member == null ? memberId.toString() : member.getScoreboardName(),
                    evaluation != null && evaluation.matched()
            ));
        }
        for (ServerPlayer member : resolveOnlineMembers(server, group)) {
            LevelConditionEvaluator.Evaluation completion = evaluations.get(member.getUUID());
            List<ModNetwork.RuleProgress> restrictions = new ArrayList<>();
            for (LevelDefinition.RestrictionRule rule : map.level.restrictions) {
                boolean matched = LevelConditionEvaluator.evaluate(
                        member.serverLevel(),
                        member,
                        rule.condition
                ).matched();
                restrictions.add(new ModNetwork.RuleProgress(rule.name, rule.punishment.name(), matched));
            }
            ModNetwork.send(member, "level_play_status", ModStore.toJson(new ModNetwork.LevelPlayStatus(
                    true,
                    group.mapId,
                    group.mode.name(),
                    "",
                    remaining,
                    group.mode == Mode.PREVIEW ? 0 : group.totalTicks,
                    completion != null && completion.matched(),
                    completion == null ? List.of() : completion.progress(),
                    restrictions,
                    memberProgress
            )));
        }
    }

    /**
     * Atomically persists one complete pre-entry player tag.
     * 原子持久化一份完整的玩家进入前标签。
     *
     * @param player player being backed up / 要备份的玩家
     * @throws IOException when persistence fails / 持久化失败时抛出
     */
    private static void writeBackup(ServerPlayer player) throws IOException {
        Path path = backupPath(player.server, player.getUUID());
        if (Files.exists(path)) {
            throw new IOException("玩家 " + player.getScoreboardName() + " 存在尚未恢复的关卡备份。");
        }
        CompoundTag envelope = new CompoundTag();
        envelope.put("Player", player.saveWithoutId(new CompoundTag()));
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            NbtIo.writeCompressed(envelope, temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        LOGGER.info("已备份玩家完整关卡前状态 [玩家: {}, 文件: {}]", player.getScoreboardName(), path);
    }

    /**
     * Resets one existing player from a synthetic clean-player tag.
     * 使用合成的干净玩家标签重置现有玩家。
     *
     * @param player participant to reset / 要重置的参与者
     */
    private static void resetToCleanPlayer(ServerPlayer player) {
        player.closeContainer();
        player.removeAllEffects();
        ServerPlayer cleanPlayer = new ServerPlayer(
                player.server,
                player.serverLevel(),
                player.getGameProfile(),
                player.clientInformation()
        );
        CompoundTag clean = cleanPlayer.saveWithoutId(new CompoundTag());
        player.load(clean);
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        for (String key : new ArrayList<>(player.getPersistentData().getAllKeys())) {
            player.getPersistentData().remove(key);
        }
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0.0F);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.getFoodData().setExhaustion(0.0F);
        player.experienceLevel = 0;
        player.totalExperience = 0;
        player.experienceProgress = 0.0F;
        player.getTags().clear();
        player.onUpdateAbilities();
        player.inventoryMenu.broadcastFullState();
    }

    /**
     * Restores every currently online member while retaining failed disk backups.
     * 恢复全部当前在线成员，并保留恢复失败的磁盘备份。
     */
    private static void restoreMembers(MinecraftServer server, GroupSession group) {
        for (UUID memberId : group.memberIds) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null && Files.isRegularFile(backupPath(server, memberId))) {
                try {
                    restorePlayer(member);
                } catch (Exception exception) {
                    LOGGER.error("玩家 [{}] 的关卡前状态恢复失败，磁盘备份已保留。", member.getScoreboardName(), exception);
                }
            }
        }
    }

    /**
     * Loads one complete player backup and returns the player to its recorded dimension and position.
     * 加载一份完整玩家备份，并将玩家送回记录的维度与位置。
     */
    private static void restorePlayer(ServerPlayer player) throws IOException {
        Path path = backupPath(player.server, player.getUUID());
        CompoundTag envelope = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        CompoundTag saved = envelope.getCompound("Player");
        if (saved.isEmpty()) {
            throw new IOException("玩家备份内容为空。");
        }
        player.closeContainer();
        player.removeAllEffects();
        ResourceKey<Level> targetKey = readDimension(saved);
        ListTag position = saved.getList("Pos", Tag.TAG_DOUBLE);
        double x = position.size() >= 3 ? position.getDouble(0) : player.server.overworld().getSharedSpawnPos().getX() + 0.5D;
        double y = position.size() >= 3 ? position.getDouble(1) : player.server.overworld().getSharedSpawnPos().getY();
        double z = position.size() >= 3 ? position.getDouble(2) : player.server.overworld().getSharedSpawnPos().getZ() + 0.5D;
        float yaw = saved.getList("Rotation", Tag.TAG_FLOAT).size() >= 2
                ? saved.getList("Rotation", Tag.TAG_FLOAT).getFloat(0)
                : 0.0F;
        float pitch = saved.getList("Rotation", Tag.TAG_FLOAT).size() >= 2
                ? saved.getList("Rotation", Tag.TAG_FLOAT).getFloat(1)
                : 0.0F;
        player.load(saved);
        ServerLevel target = player.server.getLevel(targetKey);
        if (target == null || DimensionPool.sessionForDimension(player.server, targetKey) != null) {
            target = player.server.overworld();
        }
        player.teleportTo(target, x, y, z, Set.of(), yaw, pitch);
        synchronizeRestoredPlayer(player, saved);
        Files.delete(path);
        LOGGER.info("已完整恢复玩家关卡前状态 [玩家: {}]", player.getScoreboardName());
    }

    /**
     * Parses a saved player dimension with a safe overworld fallback.
     * 解析已保存的玩家维度，并安全回退到主世界。
     */
    private static ResourceKey<Level> readDimension(CompoundTag playerTag) {
        ResourceLocation location = ResourceLocation.tryParse(playerTag.getString("Dimension"));
        return location == null
                ? Level.OVERWORLD
                : ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location);
    }

    /**
     * Resynchronizes client-visible state that raw NBT loading does not broadcast.
     * 重新同步原始 NBT 加载不会主动广播的客户端可见状态。
     */
    private static void synchronizeRestoredPlayer(ServerPlayer player, CompoundTag saved) {
        GameType restoredGameType = GameType.byId(saved.getInt("playerGameType"));
        player.setGameMode(restoredGameType);
        player.connection.send(new ClientboundGameEventPacket(
                ClientboundGameEventPacket.CHANGE_GAME_MODE,
                restoredGameType.getId()
        ));
        player.onUpdateAbilities();
        player.connection.send(new ClientboundSetExperiencePacket(
                player.experienceProgress,
                player.totalExperience,
                player.experienceLevel
        ));
        player.connection.send(new ClientboundSetHealthPacket(
                player.getHealth(),
                player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel()
        ));
        for (var effect : player.getActiveEffects()) {
            player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), effect, false));
        }
        player.inventoryMenu.broadcastFullState();
        synchronizeStages(player);
    }

    /**
     * Finds a matching portal container in normal inventory or offhand slots.
     * 在普通背包或副手槽中查找匹配的门户收纳器。
     *
     * @param player portal user / 门户使用者
     * @param mapId expected level identifier / 预期关卡标识
     * @param requireStoredVehicle whether the container must hold a vehicle / 收纳器是否必须包含载具
     * @return matching container, or an empty stack / 匹配的收纳器，不存在时为空物品堆
     */
    private static ItemStack findPortalContainer(ServerPlayer player, String mapId, boolean requireStoredVehicle) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (LevelVehicleContainer.isPortalContainer(stack)
                    && mapId.equals(LevelVehicleContainer.getLevelId(stack))
                    && (!requireStoredVehicle || LevelVehicleContainer.hasStoredVehicle(stack))) {
                return stack;
            }
        }
        ItemStack offhand = player.getOffhandItem();
        return LevelVehicleContainer.isPortalContainer(offhand)
                && mapId.equals(LevelVehicleContainer.getLevelId(offhand))
                && (!requireStoredVehicle || LevelVehicleContainer.hasStoredVehicle(offhand))
                ? offhand
                : ItemStack.EMPTY;
    }

    /**
     * Resolves all online FTB Teams members, falling back to the requester alone.
     * 解析全部在线 FTB Teams 成员，并在不可用时回退为仅请求者。
     */
    private static List<ServerPlayer> onlineTeamMembers(ServerPlayer player) {
        try {
            Class<?> apiClass = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            Object api = apiClass.getMethod("api").invoke(null);
            if (!(boolean) api.getClass().getMethod("isManagerLoaded").invoke(api)) {
                return List.of(player);
            }
            Object manager = api.getClass().getMethod("getManager").invoke(api);
            Object optional = manager.getClass().getMethod("getTeamForPlayer", ServerPlayer.class).invoke(manager, player);
            Object team = optional.getClass().getMethod("orElse", Object.class).invoke(optional, new Object[]{null});
            if (team == null) {
                return List.of(player);
            }
            Collection<?> onlineMembers = (Collection<?>) team.getClass().getMethod("getOnlineMembers").invoke(team);
            Map<UUID, ServerPlayer> result = new LinkedHashMap<>();
            // Keep the actual portal user first and resolve every teammate by UUID to avoid stale entity references.
            // 固定将实际门户使用者放在首位，并按 UUID 解析全部队员以避免过期实体引用。
            result.put(player.getUUID(), player);
            for (Object member : onlineMembers) {
                if (member instanceof ServerPlayer onlinePlayer) {
                    ServerPlayer currentPlayer = player.server.getPlayerList().getPlayer(onlinePlayer.getUUID());
                    if (currentPlayer != null) {
                        result.put(currentPlayer.getUUID(), currentPlayer);
                    }
                }
            }
            return List.copyOf(result.values());
        } catch (ClassNotFoundException ignored) {
            return List.of(player);
        } catch (ReflectiveOperationException exception) {
            LOGGER.warn("读取 FTB Teams 在线成员失败，回退为单人挑战。", exception);
            return List.of(player);
        }
    }

    /**
     * Ensures no selected team member is already isolated or awaiting restoration.
     * 确保所选队员均未处于隔离会话或等待恢复状态。
     */
    private static void validateMembers(MinecraftServer server, List<ServerPlayer> members) {
        for (ServerPlayer member : members) {
            if (state(server).byPlayer.containsKey(member.getUUID())) {
                throw new IllegalArgumentException("队员 " + member.getScoreboardName() + " 已处于关卡会话中。");
            }
            if (state(server).simulations.containsKey(member.getUUID())) {
                throw new IllegalArgumentException("队员 " + member.getScoreboardName() + " 正在模拟关卡。");
            }
            if (DimensionPool.activeSession(member) != null) {
                throw new IllegalArgumentException("队员 " + member.getScoreboardName() + " 正在其他地图会话中。");
            }
            if (Files.exists(backupPath(server, member.getUUID()))) {
                throw new IllegalStateException("队员 " + member.getScoreboardName() + " 存在尚未恢复的关卡备份，请重新登录后再试。");
            }
        }
    }

    /**
     * Grants the map identifier as both a player and optional FTB team game stage.
     * 将地图标识作为玩家游戏阶段及可选 FTB 队伍游戏阶段授予。
     */
    private static void grantStage(MinecraftServer server, GroupSession group, List<ServerPlayer> members) {
        for (ServerPlayer member : members) {
            addPlayerStage(member, group.mapId);
        }
        if (members.isEmpty()) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            Object api = apiClass.getMethod("api").invoke(null);
            Object manager = api.getClass().getMethod("getManager").invoke(api);
            Object optional = manager.getClass().getMethod("getTeamForPlayer", ServerPlayer.class).invoke(manager, members.getFirst());
            Object team = optional.getClass().getMethod("orElse", Object.class).invoke(optional, new Object[]{null});
            if (team != null) {
                Class<?> teamClass = Class.forName("dev.ftb.mods.ftbteams.api.Team");
                Class<?> helperClass = Class.forName("dev.ftb.mods.ftbteams.api.TeamStagesHelper");
                Method addStage = helperClass.getMethod("addTeamStage", teamClass, String.class);
                addStage.invoke(null, team, group.mapId);
            }
        } catch (ClassNotFoundException ignored) {
            // Player scoreboard tags remain the KubeJS stage fallback. / 玩家记分板标签仍作为 KubeJS 游戏阶段回退实现。
        } catch (ReflectiveOperationException exception) {
            LOGGER.warn("同步 FTB Teams 队伍游戏阶段失败，已保留逐玩家阶段。", exception);
        }
        LOGGER.info("向成功队伍授予同名游戏阶段 [关卡: {}, 玩家数: {}]", group.mapId, members.size());
    }

    /**
     * Adds and synchronizes one KubeJS player stage with a scoreboard-tag fallback.
     * 添加并同步一个 KubeJS 玩家阶段，并以记分板标签作为回退。
     */
    private static void addPlayerStage(ServerPlayer player, String stage) {
        try {
            Object stages = player.getClass().getMethod("kjs$getStages").invoke(player);
            stages.getClass().getMethod("add", String.class).invoke(stages, stage);
        } catch (ReflectiveOperationException exception) {
            player.addTag(stage);
            LOGGER.debug("KubeJS 游戏阶段接口不可用，已使用玩家标签回退 [玩家: {}, 阶段: {}]", player.getScoreboardName(), stage);
        }
    }

    /**
     * Requests KubeJS stage synchronization after raw player restoration.
     * 在原始玩家恢复后请求 KubeJS 阶段同步。
     */
    private static void synchronizeStages(ServerPlayer player) {
        try {
            Object stages = player.getClass().getMethod("kjs$getStages").invoke(player);
            stages.getClass().getMethod("sync").invoke(stages);
        } catch (ReflectiveOperationException exception) {
            LOGGER.debug("玩家 [{}] 的 KubeJS 游戏阶段无需或无法显式同步。", player.getScoreboardName());
        }
    }

    /**
     * Resolves currently connected players in stable group order.
     * 按稳定小组顺序解析当前已连接玩家。
     */
    private static List<ServerPlayer> resolveOnlineMembers(MinecraftServer server, GroupSession group) {
        List<ServerPlayer> players = new ArrayList<>();
        for (UUID memberId : group.memberIds) {
            ServerPlayer player = server.getPlayerList().getPlayer(memberId);
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    /**
     * Releases references to remaining one-use containers owned by a session.
     * 释放会话持有的全部剩余一次性收纳器引用。
     */
    private static void discardTrialContainers(GroupSession group) {
        group.trialContainer = ItemStack.EMPTY;
    }

    /**
     * Resolves the durable backup path for one player.
     * 解析一名玩家的持久备份路径。
     */
    private static Path backupPath(MinecraftServer server, UUID playerId) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("creatego")
                .resolve(BACKUP_DIRECTORY)
                .resolve(playerId + ".nbt");
    }

    /**
     * Returns the mutable runtime indices for one server.
     * 返回一个服务端的可变运行时索引。
     */
    private static RuntimeState state(MinecraftServer server) {
        return STATES.computeIfAbsent(server, ignored -> new RuntimeState());
    }

    /**
     * Removes all dimension and member indices for a completed group.
     * 移除已结束小组的全部维度与成员索引。
     */
    private static void removeGroup(MinecraftServer server, GroupSession group) {
        RuntimeState state = state(server);
        state.byDimension.remove(group.dimensionKey);
        for (UUID memberId : group.memberIds) {
            state.byPlayer.remove(memberId, group);
        }
    }

    /**
     * Stores all runtime indices for one server.
     * 保存一个服务端的全部运行时索引。
     */
    private static final class RuntimeState {
        private final Map<ResourceKey<Level>, GroupSession> byDimension = new HashMap<>();
        private final Map<UUID, GroupSession> byPlayer = new HashMap<>();
        private final Map<UUID, EditorSimulation> simulations = new HashMap<>();
    }

    /**
     * Stores immutable identity and timing for one editor simulation.
     * 保存一次编辑器模拟的不可变身份与计时信息。
     */
    private static final class EditorSimulation {
        private final UUID playerId;
        private final String mapId;
        private final ResourceKey<Level> dimensionKey;
        private final int startedTick;
        private final int totalTicks;

        /**
         * Creates one editor simulation descriptor.
         * 创建一条编辑器模拟描述。
         *
         * @param playerId editor UUID / 编辑者 UUID
         * @param mapId simulated map identifier / 模拟地图标识
         * @param dimensionKey simulated dimension / 模拟维度
         * @param startedTick start server tick / 开始服务端刻
         * @param totalTicks total duration / 总持续刻数
         */
        private EditorSimulation(
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

    /**
     * Stores one pending or active preview/challenge group.
     * 保存一个待进入或活动中的预览/挑战小组。
     */
    private static final class GroupSession {
        private final UUID id;
        private final Mode mode;
        private final String mapId;
        private final UUID leaderId;
        private final List<UUID> memberIds;
        private final ResourceKey<Level> dimensionKey;
        private final ItemStack portalTemplate;
        private final int totalTicks;
        private ItemStack trialContainer = ItemStack.EMPTY;
        private int startedTick;
        private boolean transitioning;
        private boolean active;
        private boolean finishing;

        private GroupSession(
                UUID id,
                Mode mode,
                String mapId,
                UUID leaderId,
                List<UUID> memberIds,
                ResourceKey<Level> dimensionKey,
                ItemStack portalTemplate,
                int totalTicks
        ) {
            this.id = id;
            this.mode = mode;
            this.mapId = mapId;
            this.leaderId = leaderId;
            this.memberIds = memberIds;
            this.dimensionKey = dimensionKey;
            this.portalTemplate = portalTemplate;
            this.totalTicks = totalTicks;
        }
    }
}
