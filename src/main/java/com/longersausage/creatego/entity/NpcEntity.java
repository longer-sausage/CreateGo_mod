/*
 * Implements a stationary player-shaped dialogue NPC.
 * 实现静止的玩家外形对话 NPC。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.entity;

import com.longersausage.creatego.CreateGo;
import com.longersausage.creatego.data.NpcData;
import com.longersausage.creatego.network.ModNetwork;
import com.longersausage.creatego.server.DimensionPool;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import java.util.UUID;

/**
 * Represents one invulnerable, immobile, map-bound NPC.
 * 表示一个无敌、静止、与地图绑定的 NPC。
 */
public final class NpcEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> MAP_ID = SynchedEntityData.defineId(
            NpcEntity.class,
            EntityDataSerializers.STRING
    );
    private static final EntityDataAccessor<String> NPC_ID = SynchedEntityData.defineId(
            NpcEntity.class,
            EntityDataSerializers.STRING
    );
    private static final EntityDataAccessor<String> SKIN_NAME = SynchedEntityData.defineId(
            NpcEntity.class,
            EntityDataSerializers.STRING
    );

    /**
     * Creates an NPC entity instance.
     * 创建 NPC 实体实例。
     *
     * @param type registered entity type / 已注册实体类型
     * @param level owning level / 所属世界
     */
    public NpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setNoAi(true);
        setNoGravity(true);
        setInvulnerable(true);
        setPersistenceRequired();
    }

    /**
     * Applies persistent data to this entity.
     * 将持久数据应用到实体。
     *
     * @param data NPC document / NPC 文档
     */
    public void applyData(NpcData data) {
        entityData.set(MAP_ID, data.mapId);
        entityData.set(NPC_ID, data.id.toString());
        entityData.set(SKIN_NAME, data.skinName == null ? "" : data.skinName);
        setCustomName(net.minecraft.network.chat.Component.literal(data.name));
        setCustomNameVisible(true);
        setYRot(data.yaw);
        setYHeadRot(data.yaw);
        setYBodyRot(data.yaw);
    }

    /**
     * Returns the map identifier owning this NPC.
     * 返回拥有该 NPC 的地图标识。
     *
     * @return map identifier / 地图标识
     */
    public String getMapId() {
        return entityData.get(MAP_ID);
    }

    /**
     * Returns the stable NPC identifier.
     * 返回稳定的 NPC 标识。
     *
     * @return NPC UUID / NPC UUID
     */
    public UUID getNpcId() {
        try {
            return UUID.fromString(entityData.get(NPC_ID));
        } catch (IllegalArgumentException exception) {
            return getUUID();
        }
    }

    /**
     * Returns the synchronized custom skin name.
     * 返回已同步的自定义皮肤名称。
     *
     * @return skin name without extension, or empty for a vanilla random skin / 皮肤名（不带后缀），空值表示随机原版皮肤
     */
    public String getSkinName() {
        return entityData.get(SKIN_NAME);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MAP_ID, "");
        builder.define(NPC_ID, UUID.randomUUID().toString());
        builder.define(SKIN_NAME, "");
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        boolean toolInHand = player.hasPermissions(2)
                && player.getMainHandItem().is(CreateGo.NPC_EDITOR_ITEM.get());
        if (level().isClientSide()) {
            return toolInHand ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (toolInHand && DimensionPool.isBoundTo(serverPlayer, getMapId())) {
            ModNetwork.openNpcUI(serverPlayer, this);
            return InteractionResult.CONSUME;
        }
        return ModNetwork.startDialogue(serverPlayer, this)
                ? InteractionResult.CONSUME
                : InteractionResult.PASS;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public void tick() {
        super.tick();
        setDeltaMovement(0.0D, 0.0D, 0.0D);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("CreateGoMap", getMapId());
        tag.putString("CreateGoNpc", getNpcId().toString());
        tag.putString("CreateGoSkin", getSkinName());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(MAP_ID, tag.getString("CreateGoMap"));
        entityData.set(NPC_ID, tag.getString("CreateGoNpc"));
        entityData.set(SKIN_NAME, tag.getString("CreateGoSkin"));
        setNoAi(true);
        setNoGravity(true);
        setInvulnerable(true);
    }
}
