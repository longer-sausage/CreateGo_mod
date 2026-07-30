/*
 * Maintains dynamic custom NPC textures on the client.
 * 在客户端维护动态自定义 NPC 纹理。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.CreateGo;
import com.longersausage.creatego.entity.NpcEntity;
import com.longersausage.creatego.server.ModService;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves random vanilla skins immediately and downloads custom skins by skin name.
 * 立即解析随机原版皮肤，并按皮肤名下载自定义皮肤。
 */
public final class SkinCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkinCache.class);
    private static final String[] VANILLA_SKINS = {
            "ari", "alex", "efe", "kai", "steve", "makena", "noor", "sunny", "zuri"
    };

    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    private static final Set<String> REQUESTED = new HashSet<>();

    private SkinCache() {
    }

    /**
     * Resolves a texture for an NPC and requests missing custom data once.
     * 解析 NPC 纹理，并对缺失的自定义数据只请求一次。
     *
     * @param entity rendered NPC / 正在渲染的 NPC
     * @return usable texture location / 可用纹理位置
     */
    public static ResourceLocation texture(NpcEntity entity) {
        String skinName = entity.getSkinName();
        if (skinName != null && !skinName.isBlank()) {
            ResourceLocation cached = TEXTURES.get(skinName);
            if (cached != null) {
                return cached;
            }
            if (REQUESTED.add(skinName)) {
                ModService.SkinRequest request = new ModService.SkinRequest();
                request.name = skinName;
                ScreenHelper.send("request_skin", request);
            }
        }
        int index = Math.floorMod(entity.getNpcId().hashCode(), VANILLA_SKINS.length);
        return ResourceLocation.withDefaultNamespace(
                "textures/entity/player/wide/" + VANILLA_SKINS[index] + ".png"
        );
    }

    /**
     * Validates and registers downloaded PNG data.
     * 验证并注册下载的 PNG 数据。
     *
     * @param skinName skin name without extension / 皮肤名（不带后缀）
     * @param png PNG bytes / PNG 字节
     */
    public static void accept(String skinName, byte[] png) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
            if (image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
                image.close();
                LOGGER.warn("客户端跳过非标准尺寸皮肤数据 [皮肤名: {}, 尺寸: {}x{}]", skinName, image.getWidth(), image.getHeight());
                return;
            }
            ResourceLocation location = CreateGo.id("dynamic_skin/" + skinName);
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
            TEXTURES.put(skinName, location);
            LOGGER.info("客户端成功注册动态 NPC 皮肤 [皮肤名: {}]", skinName);
        } catch (IOException exception) {
            REQUESTED.remove(skinName);
            LOGGER.error("客户端解析下载的皮肤数据失败 [皮肤名: {}]", skinName, exception);
        }
    }
}
