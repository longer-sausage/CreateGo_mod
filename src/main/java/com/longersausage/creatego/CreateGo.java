/*
 * Bootstraps the CreateGo map and NPC mod.
 * 启动 CreateGo 地图与 NPC 模组。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego;

import com.longersausage.creatego.entity.NpcEntity;
import com.longersausage.creatego.item.FourthDimensionalPocketItem;
import com.longersausage.creatego.item.MapEditorItem;
import com.longersausage.creatego.item.NpcEditorItem;
import com.longersausage.creatego.item.LevelEditorItem;
import com.longersausage.creatego.network.ModNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers mod content, networking, and game events.
 * 注册模组内容、网络与游戏事件。
 */
@Mod(CreateGo.MOD_ID)
public final class CreateGo {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateGo.class);
    public static final String MOD_ID = "creatego";
    public static final int ORIGIN_X = 0;
    public static final int ORIGIN_Z = 0;
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    public static final DeferredHolder<Item, MapEditorItem> MAP_EDITOR_ITEM = ITEMS.register(
            "map_editor",
            () -> new MapEditorItem(new Item.Properties().stacksTo(1))
    );
    public static final DeferredHolder<Item, NpcEditorItem> NPC_EDITOR_ITEM = ITEMS.register(
            "npc_editor",
            () -> new NpcEditorItem(new Item.Properties().stacksTo(1))
    );
    public static final DeferredHolder<Item, LevelEditorItem> LEVEL_EDITOR_ITEM = ITEMS.register(
            "level_editor",
            () -> new LevelEditorItem(new Item.Properties().stacksTo(1))
    );
    public static final DeferredHolder<Item, FourthDimensionalPocketItem> FOURTH_DIMENSIONAL_POCKET_ITEM = ITEMS.register(
            "fourth_dimensional_pocket",
            () -> new FourthDimensionalPocketItem(new Item.Properties().stacksTo(1))
    );
    public static final DeferredHolder<EntityType<?>, EntityType<NpcEntity>> NPC_ENTITY = ENTITIES.register(
            "npc",
            () -> EntityType.Builder.of(NpcEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .updateInterval(3)
                    .build(id("npc").toString())
    );

    /**
     * Creates and wires the mod.
     * 创建并连接模组。
     *
     * @param modBus mod lifecycle event bus / 模组生命周期事件总线
     */
    public CreateGo(IEventBus modBus) {
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        modBus.addListener(ModNetwork::register);
        modBus.addListener(this::registerAttributes);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.longersausage.creatego.client.ClientModEvents.register(modBus);
            NeoForge.EVENT_BUS.register(com.longersausage.creatego.client.ClientEvents.class);
        }
        NeoForge.EVENT_BUS.register(ServerEvents.class);
        LOGGER.info("CreateGo 地图与 NPC 模组初始化完成。");
    }

    /**
     * Creates a resource identifier in this mod namespace.
     * 在本模组命名空间中创建资源标识。
     *
     * @param path resource path / 资源路径
     * @return namespaced identifier / 带命名空间的标识
     */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(NPC_ENTITY.get(), Mob.createMobAttributes().build());
    }
}
