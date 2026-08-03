/*
 * Applies configured map seeds to Dynamic Dimensions level construction.
 * 将配置的地图种子应用到 Dynamic Dimensions 维度构造。
 *
 * Author: CreateGo
 * Date: 2026-08-03
 */

package com.longersausage.creatego.mixin;

import com.longersausage.creatego.server.DynamicDimensionSeedOverride;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Replaces the library's seed base only while CreateGo allocates seeded terrain.
 * 仅在 CreateGo 分配带种子地形时替换该库的种子基值。
 */
@Mixin(targets = "dev.galacticraft.dynamicdimensions.impl.DynamicDimensionRegistryImpl", remap = false)
public abstract class DynamicDimensionRegistryMixin {
    /**
     * Compensates for the dimension-ID hash appended by Dynamic Dimensions.
     * 抵消 Dynamic Dimensions 追加的维度 ID 哈希。
     *
     * @param overworld source overworld / 来源主世界
     * @return original or adjusted seed base / 原始或调整后的种子基值
     */
    @Redirect(
            method = "createDynamicLevel(Lnet/minecraft/resources/ResourceKey;"
                    + "Lnet/minecraft/world/level/storage/WorldData;"
                    + "Lnet/minecraft/world/level/dimension/LevelStem;"
                    + "Lnet/minecraft/server/level/ServerLevel;)"
                    + "Lnet/minecraft/server/level/ServerLevel;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getSeed()J"
            )
    )
    private long creatego$useConfiguredSeed(ServerLevel overworld) {
        return DynamicDimensionSeedOverride.adjustedBaseSeed(overworld.getSeed());
    }
}
