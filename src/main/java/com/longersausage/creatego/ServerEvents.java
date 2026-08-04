/*
 * Handles server lifecycle synchronization for the mod.
 * 处理模组的服务端生命周期同步。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego;

import com.longersausage.creatego.network.ModNetwork;
import com.longersausage.creatego.server.DialogueRuntime;
import com.longersausage.creatego.server.DimensionPool;
import com.longersausage.creatego.server.ModService;
import com.longersausage.creatego.server.LevelRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronizes mod state when a player joins.
 * 在玩家加入时同步模组状态。
 */
public final class ServerEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerEvents.class);

    private ServerEvents() {
    }

    /**
     * Sends the latest map catalog to a newly connected client.
     * 向刚连接的客户端发送最新地图目录。
     *
     * @param event login event / 登录事件
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LOGGER.debug("玩家 [{}] 登录，同步模组数据状态", player.getScoreboardName());
            LevelRuntime.restorePendingBackup(player);
            DimensionPool.bindOnEntry(player, net.minecraft.world.level.Level.OVERWORLD);
            ModNetwork.syncState(player);
        }
    }

    /**
     * Captures the exact source position before any cross-dimension map entry.
     * 在以任意方式跨维度进入地图前捕获准确来源位置。
     *
     * @param event pre-travel event / 跨维度前事件
     */
    @SubscribeEvent
    public static void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DimensionPool.prepareEntry(player, event.getDimension());
        }
    }

    /**
     * Clears transient dialogue state and checks dimension cleanup when a player disconnects.
     * 玩家断开连接时清理临时对话状态，并检查所在维度是否需要销毁与解绑。
     *
     * @param event logout event / 登出事件
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LOGGER.info("玩家 [{}] 登出，清理对话状态与检查维度清理", player.getScoreboardName());
            DialogueRuntime.stop(player);
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> currentDimension = player.serverLevel().dimension();
            // LoggedOut fires before PlayerList saves player NBT. / LoggedOut 在 PlayerList 保存玩家 NBT 前触发。
            if (!LevelRuntime.onLogout(player)) {
                ModService.closeSession(player, true);
            }
            DimensionPool.checkAndCleanupDimension(player.server, currentDimension, player.getUUID());
        }
    }

    /**
     * Binds after entering and cleans up after leaving a shared map dimension by any route.
     * 玩家以任意方式进入共享地图维度后绑定，并在离开后执行清理。
     *
     * @param event dimension-change event / 维度切换事件
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> fromDimension = event.getFrom();
            DialogueRuntime.stop(player);
            LevelRuntime.onDimensionChanged(player, fromDimension);
            DimensionPool.Session fromSession = DimensionPool.sessionForDimension(player.server, fromDimension);
            DimensionPool.Session enteredSession = DimensionPool.bindOnEntry(player, fromDimension);
            if (fromSession != null && !fromDimension.equals(player.serverLevel().dimension())) {
                LOGGER.info("玩家 [{}] 离开地图维度 [{}]", player.getScoreboardName(), fromDimension.location());
                DimensionPool.checkAndCleanupDimension(player.server, fromDimension, player.getUUID());
            }
            if (enteredSession != null || fromSession != null) {
                ModNetwork.syncState(player);
            }
        }
    }

    /**
     * Revalidates transient binding after respawn because death may recreate the player entity.
     * 玩家重生后重新校验临时绑定，因为死亡可能重建玩家实体。
     *
     * @param event respawn event / 重生事件
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DimensionPool.bindOnEntry(player, net.minecraft.world.level.Level.OVERWORLD);
            ModNetwork.syncState(player);
        }
    }

    /**
     * Completes dynamic dimension entries and retains Sable structures during active editing sessions.
     * 完成动态维度进入流程，并在活动编辑会话期间保持 Sable 物理结构驻留。
     *
     * @param event server post-tick event / 服务端刻结束事件
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ModService.tickPendingEntries(event.getServer());
        LevelRuntime.tick(event.getServer());
    }

    /**
     * Cancels normal death and converts it into whole-team challenge failure.
     * 取消正常死亡，并将其转换为全队挑战失败。
     *
     * @param event living death event / 生物死亡事件
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && LevelRuntime.failOnDeath(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Prevents challenge participants from attacking any entity.
     * 阻止挑战参与者攻击任何实体。
     *
     * @param event attack event / 攻击事件
     */
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && LevelRuntime.isChallengeParticipant(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Cancels direct and projectile damage caused by challenge participants.
     * 取消挑战参与者造成的直接伤害与弹射物伤害。
     *
     * @param event incoming damage event / 传入伤害事件
     */
    @SubscribeEvent
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer player
                && LevelRuntime.isChallengeParticipant(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Prevents challenge participants from breaking blocks.
     * 阻止挑战参与者破坏方块。
     *
     * @param event block break event / 方块破坏事件
     */
    @SubscribeEvent
    public static void onBreakBlock(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && LevelRuntime.isChallengeParticipant(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Prevents challenge participants from placing blocks while preserving pocket structure release.
     * 阻止挑战参与者放置方块，同时保留四次元口袋释放物理结构的能力。
     *
     * @param event block placement event / 方块放置事件
     */
    @SubscribeEvent
    public static void onPlaceBlock(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && LevelRuntime.isChallengeParticipant(player)) {
            event.setCanceled(true);
        }
    }
}
