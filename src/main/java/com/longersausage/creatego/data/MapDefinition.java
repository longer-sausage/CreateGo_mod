/*
 * Defines metadata for one CreateGo map.
 * 定义一张 CreateGo 地图的元数据。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.data;

import com.longersausage.creatego.CreateGo;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores map metadata, structure bounds, and bound NPCs.
 * 保存地图元数据、结构范围和绑定的 NPC。
 */
public final class MapDefinition {
    public String id = "";
    public int spawnX;
    public int spawnY = 65;
    public int spawnZ;
    public int originX = CreateGo.ORIGIN_X;
    public int originY;
    public int originZ = CreateGo.ORIGIN_Z;
    public Direction direction = Direction.SOUTH;
    public int sizeX;
    public int sizeY;
    public int sizeZ;
    public String schematicName = "";
    public List<NpcData> npcs = new ArrayList<>();
    public List<FlatLayer> flatLayers = new ArrayList<>();

    /**
     * Defines configuration for one superflat block layer.
     * 定义一个超平坦方块地层的配置。
     */
    public static final class FlatLayer {
        public String blockId = "minecraft:dirt";
        public int count = 1;

        /**
         * Creates a default layer configuration.
         * 创建默认地层配置。
         */
        public FlatLayer() {
        }

        /**
         * Creates a specified layer configuration.
         * 创建指定的地层配置。
         *
         * @param blockId block identifier / 方块标识
         * @param count layer count / 层数
         */
        public FlatLayer(String blockId, int count) {
            this.blockId = blockId;
            this.count = count;
        }
    }

    /**
     * Enumerates the four supported spawn directions.
     * 枚举支持的四种出生朝向。
     */
    public enum Direction {
        NORTH(180.0F),
        EAST(-90.0F),
        SOUTH(0.0F),
        WEST(90.0F);
        public final float yaw;
        Direction(float yaw) {
            this.yaw = yaw;
        }
    }
}
