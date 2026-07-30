/*
 * Defines metadata for one CreateGo map.
 * 定义一张 CreateGo 地图的元数据。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.data;

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
    public Direction direction = Direction.SOUTH;
    public int sizeX;
    public int sizeY;
    public int sizeZ;
    public String schematicName = "";
    public List<NpcData> npcs = new ArrayList<>();

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
