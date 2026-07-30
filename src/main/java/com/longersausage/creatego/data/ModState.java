/*
 * Defines the root document persisted by CreateGo.
 * 定义 CreateGo 持久化的根文档。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stores all persistent map definitions independently from transient dimensions.
 * 保存全部持久地图定义，与临时维度相互独立。
 */
public final class ModState {
    public Map<String, MapDefinition> maps = new LinkedHashMap<>();
}
