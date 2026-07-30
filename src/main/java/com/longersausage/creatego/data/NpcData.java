/*
 * Defines persistent NPC data with absolute dimension coordinates.
 * 定义使用维度绝对坐标保存的 NPC 持久数据。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.data;

import java.util.UUID;

/**
 * Stores one map-bound NPC.
 * 保存一个与地图绑定的 NPC。
 */
public final class NpcData {
    public UUID id = UUID.randomUUID();
    public String mapId = "";
    public String name = "NPC";
    public String skinName = "";
    public double x;
    public double y;
    public double z;
    public float yaw;
    public DialogueGraph dialogue = new DialogueGraph();
}
