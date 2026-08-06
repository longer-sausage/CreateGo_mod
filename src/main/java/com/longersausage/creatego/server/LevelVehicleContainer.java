/*
 * Bridges CreateGo level portals to Create Aeronautics: Toolgun vehicle containers.
 * 将 CreateGo 关卡门户连接到 Create Aeronautics: Toolgun 的载具收纳器。
 *
 * Author: CreateGo
 * Date: 2026-08-05
 */

package com.longersausage.creatego.server;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

/**
 * Identifies NBT-bound portal containers and creates native one-use challenge copies.
 * 识别绑定 NBT 的门户收纳器，并创建原生的一次性挑战副本。
 */
public final class LevelVehicleContainer {
    private static final ResourceLocation PORTAL_CONTAINER_ID = ResourceLocation.fromNamespaceAndPath(
            "create_aeronautics_toolgun",
            "portable_structure_container"
    );
    private static final ResourceLocation TRIAL_CONTAINER_ID = ResourceLocation.fromNamespaceAndPath(
            "create_aeronautics_toolgun",
            "disposable_vehicle_container"
    );
    private static final String LEVEL_TAG = "Level";
    private static final String STORED_BLUEPRINT_TAG = "StoredBlueprint";
    private static final String CONTAINER_ID_TAG = "ContainerId";

    private LevelVehicleContainer() {
    }

    /**
     * Checks whether a stack is the reusable spatial container used as a level portal.
     * 检查物品堆是否为用作关卡门户的可复用空间收纳器。
     *
     * @param stack candidate item stack / 候选物品堆
     * @return whether the item is a spatial container / 该物品是否为空间收纳器
     */
    public static boolean isPortalContainer(ItemStack stack) {
        return stack.is(BuiltInRegistries.ITEM.get(PORTAL_CONTAINER_ID));
    }

    /**
     * Checks whether the native container contains a non-empty vehicle blueprint.
     * 检查原生收纳器是否包含非空载具蓝图。
     *
     * @param stack container item stack / 收纳器物品堆
     * @return whether stored vehicle data is present / 是否存在已收纳载具数据
     */
    public static boolean hasStoredVehicle(ItemStack stack) {
        CompoundTag root = customData(stack);
        return root.contains(STORED_BLUEPRINT_TAG, Tag.TAG_BYTE_ARRAY)
                && root.getByteArray(STORED_BLUEPRINT_TAG).length > 0;
    }

    /**
     * Returns the trimmed level identifier bound to a portal container.
     * 返回绑定到门户收纳器的去除首尾空白后的关卡标识。
     *
     * @param stack portal container / 门户收纳器
     * @return level identifier, or an empty string / 关卡标识，不存在时为空字符串
     */
    public static String getLevelId(ItemStack stack) {
        return customData(stack).getString(LEVEL_TAG).strip();
    }

    /**
     * Converts a filled spatial container into an independent native one-use vehicle container.
     * 将已装载的空间收纳器转换为独立的原生一次性载具收纳器。
     *
     * @param source filled portal container / 已装载的门户收纳器
     * @return filled one-use vehicle container / 已装载的一次性载具收纳器
     * @throws IllegalArgumentException when the source has no valid vehicle blueprint / 来源没有有效载具蓝图时抛出
     */
    public static ItemStack createTrialContainer(ItemStack source) {
        if (!isPortalContainer(source) || !hasStoredVehicle(source)) {
            throw new IllegalArgumentException("空间收纳器中没有有效的载具数据。");
        }
        Item disposableItem = BuiltInRegistries.ITEM.get(TRIAL_CONTAINER_ID);
        ItemStack trial = source.transmuteCopy(disposableItem, 1);
        CompoundTag root = customData(trial);
        // The portal user receives a one-use identity without mutating the reusable source container.
        // 门户使用者获得独立的一次性标识，且不会修改可复用的来源收纳器。
        root.remove(LEVEL_TAG);
        root.putString(CONTAINER_ID_TAG, UUID.randomUUID().toString());
        trial.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return trial;
    }

    /**
     * Copies the custom-data component without mutating the source stack.
     * 复制自定义数据组件且不修改来源物品堆。
     *
     * @param stack source item stack / 来源物品堆
     * @return copied custom data / 复制后的自定义数据
     */
    private static CompoundTag customData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }
}
