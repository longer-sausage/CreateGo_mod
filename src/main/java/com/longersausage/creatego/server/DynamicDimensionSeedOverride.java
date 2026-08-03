/*
 * Carries one configured terrain seed through dynamic dimension construction.
 * 在动态维度构造期间传递一个已配置的地形种子。
 *
 * Author: CreateGo
 * Date: 2026-08-03
 */

package com.longersausage.creatego.server;

import net.minecraft.resources.ResourceLocation;

/**
 * Provides a server-thread seed override consumed by the Dynamic Dimensions compatibility mixin.
 * 提供由 Dynamic Dimensions 兼容 Mixin 使用的服务端线程种子覆盖。
 */
public final class DynamicDimensionSeedOverride {
    private static final ThreadLocal<SeedOverride> ACTIVE_OVERRIDE = new ThreadLocal<>();

    private DynamicDimensionSeedOverride() {
    }

    /**
     * Begins one synchronous dynamic-dimension construction scope.
     * 开始一个同步动态维度构造作用域。
     *
     * @param dimensionId created dimension identifier / 创建的维度标识
     * @param seed exact configured terrain seed / 配置的准确地形种子
     */
    public static void begin(ResourceLocation dimensionId, long seed) {
        ACTIVE_OVERRIDE.set(new SeedOverride(dimensionId, seed));
    }

    /**
     * Returns the base seed that produces the configured seed after the library adds its ID hash.
     * 返回在库加入维度 ID 哈希后可得到配置种子的基础种子。
     *
     * @param fallbackSeed original overworld seed / 原始主世界种子
     * @return adjusted base seed / 调整后的基础种子
     */
    public static long adjustedBaseSeed(long fallbackSeed) {
        SeedOverride override = ACTIVE_OVERRIDE.get();
        return override == null ? fallbackSeed : override.seed - override.dimensionId.hashCode();
    }

    /**
     * Clears the current construction scope even when dimension creation fails.
     * 即使维度创建失败也清除当前构造作用域。
     */
    public static void clear() {
        ACTIVE_OVERRIDE.remove();
    }

    /**
     * Stores one exact seed and its dynamic dimension identifier.
     * 保存一个准确种子及其动态维度标识。
     *
     * @param dimensionId dynamic dimension identifier / 动态维度标识
     * @param seed configured seed / 配置种子
     */
    private record SeedOverride(ResourceLocation dimensionId, long seed) {
    }
}
