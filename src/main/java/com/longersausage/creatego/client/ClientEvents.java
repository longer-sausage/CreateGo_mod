/*
 * Listens to client-side game events (level load, client tick, etc.) for compatibility.
 * 监听客户端游戏事件（世界加载、客户端 Tick 等）以保证兼容性。
 *
 * Author: CreateGo
 * Date: 2026-07-31
 */

package com.longersausage.creatego.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

/**
 * Handles client-side dimension initialization and mod compatibility hooks.
 * 处理客户端维度初始化及模组兼容性钩子。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientEvents.class);

    private ClientEvents() {
    }

    /**
     * Ensures client-side data structures (such as Simulated physics staff locks) are initialized when a level loads.
     * 在世界加载时确保客户端数据结构（如 Simulated 物理法杖锁列表）已初始化。
     *
     * @param event level load event / 世界加载事件
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof Level level) {
            ensureSimulatedLocksInitialized(level);
        }
    }

    /**
     * Periodically verifies client level compatibility state on tick.
     * 在客户端 Tick 时定期校验当前世界的兼容性状态。
     *
     * @param event client tick event / 客户端 Tick 事件
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            ensureSimulatedLocksInitialized(minecraft.level);
        }
    }

    /**
     * Prevents Simulated mod from throwing NPE in PhysicsStaffRenderHandler by initializing locks list for level.
     * 通过反射初始化维度的锁列表，防止 Simulated 模组在 PhysicsStaffRenderHandler 中抛出空指针异常崩溃。
     *
     * @param level target level / 目标世界
     */
    public static void ensureSimulatedLocksInitialized(Level level) {
        if (level == null) {
            return;
        }
        try {
            if (ModList.get().isLoaded("simulated")) {
                Class<?> clientClass = Class.forName("dev.simulated_team.simulated.SimulatedClient");
                Field handlerField = clientClass.getField("PHYSICS_STAFF_CLIENT_HANDLER");
                Object handler = handlerField.get(null);
                if (handler != null) {
                    Field locksField = handler.getClass().getDeclaredField("locks");
                    locksField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> locks = (Map<Object, Object>) locksField.get(handler);
                    if (locks != null) {
                        locks.computeIfAbsent(level.dimension(), k -> new ArrayList<>());
                    }
                }
            }
        } catch (Throwable throwable) {
            LOGGER.debug("未能为 Simulated 初始化客户端物理锁列表：{}", throwable.getMessage());
        }
    }
}
