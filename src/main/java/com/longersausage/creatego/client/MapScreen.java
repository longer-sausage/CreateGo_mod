/*
 * Implements map selection, configuration, and isolated-session controls.
 * 实现地图选择、配置与隔离会话控制。
 *
 * Author: CreateGo
 * Date: 2026-07-31
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.client.ui.BaseScreen;
import com.longersausage.creatego.client.ui.ModernButton;
import com.longersausage.creatego.client.ui.ModernEditBox;
import com.longersausage.creatego.client.ui.UITheme;
import com.longersausage.creatego.data.MapDefinition;
import com.longersausage.creatego.data.ModState;
import com.longersausage.creatego.network.ModNetwork;
import com.longersausage.creatego.server.ModService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Presents a paginated catalog, persistent map configuration, or isolated session actions.
 * 显示分页目录、持久地图配置或隔离编辑会话操作。
 */
public final class MapScreen extends BaseScreen {
    private static final int MAPS_PER_PAGE = 5;
    private static final int LAYERS_PER_PAGE = 4;
    private static final int STRUCTURES_PER_PAGE = 4;

    private ModState state;
    private String boundMapId;
    private String selectedMapId = "";
    private String configuredMapId = "";
    private List<MapDefinition> maps = new ArrayList<>();
    private List<Path> schematics = List.of();
    private List<MapDefinition.StructureData> structures = new ArrayList<>();
    private int pageIndex;
    private int schematicIndex;
    private int structurePageIndex;
    private String selectedStructureName = "";
    private MapDefinition.Direction direction = MapDefinition.Direction.SOUTH;
    private EditBox idField;
    private EditBox spawnXField;
    private EditBox spawnYField;
    private EditBox spawnZField;
    private Button schematicButton;
    private Button directionButton;
    private Button enterButton;
    private Button configureButton;
    private Button deleteButton;
    private Button saveNpcButton;
    private Button exitMapButton;
    private boolean deleteConfirmation;
    private boolean structureDeleteConfirmation;

    private boolean isTerrainConfiguring;
    private boolean isAdvancedConfiguring;
    private boolean isStructureConfiguring;
    private boolean isStructureDetailConfiguring;
    private int terrainPageIndex;
    private List<MapDefinition.FlatLayer> editingFlatLayers = new ArrayList<>();
    private List<EditBox> layerBlockFields = new ArrayList<>();
    private List<EditBox> layerCountFields = new ArrayList<>();
    private EditBox originXField;
    private EditBox originYField;
    private EditBox originZField;

    /**
     * Creates the default catalog or active-session screen.
     * 创建默认目录或活动会话界面。
     *
     * @param view synchronized state and player binding / 已同步状态与玩家绑定
     */
    public MapScreen(ModNetwork.MapEditorView view) {
        this(view, "");
    }

    /**
     * Creates a screen that may open directly in normal-mode map configuration.
     * 创建可直接打开普通模式地图配置的界面。
     *
     * @param view synchronized state and player binding / 已同步状态与玩家绑定
     * @param configuredMapId map selected for configuration / 选择配置的地图标识
     */
    public MapScreen(ModNetwork.MapEditorView view, String configuredMapId) {
        super(Component.literal("CreateGo 地图编辑器"));
        state = view.state;
        boundMapId = view.boundMapId;
        this.configuredMapId = boundMapId.isEmpty()
                ? (configuredMapId == null ? "" : configuredMapId)
                : boundMapId;
    }

    /**
     * Applies a personalized server update while preserving local catalog selection.
     * 应用个性化服务端更新，同时保留本地目录选择。
     *
     * @param view latest synchronized view / 最新同步视图
     */
    public void updateView(ModNetwork.MapEditorView view) {
        state = view.state;
        boundMapId = view.boundMapId;
        if (!boundMapId.isEmpty()) {
            configuredMapId = boundMapId;
        }
        deleteConfirmation = false;
        structureDeleteConfirmation = false;
        rebuildCollections();
        if (minecraft != null) {
            rebuildWidgets();
        }
    }

    /**
     * Initializes display collections and widgets.
     * 初始化显示集合与控件。
     */
    @Override
    protected void init() {
        rebuildCollections();
        rebuildWidgets();
    }

    /**
     * Rebuilds widgets for the catalog or shared configuration screen.
     * 为地图目录或共用配置界面重建控件。
     */
    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        resetWidgetReferences();
        if (isConfiguring()) {
            if (isStructureConfiguring) {
                if (isStructureDetailConfiguring) {
                    buildStructureDetailWidgets();
                } else {
                    buildStructureWidgets();
                }
            } else if (isTerrainConfiguring) {
                buildTerrainWidgets();
            } else if (isAdvancedConfiguring) {
                buildAdvancedWidgets();
            } else {
                buildConfigurationWidgets();
            }
        } else {
            buildCatalogWidgets();
        }
    }

    /**
     * Clears mode-specific widget references before rebuilding.
     * 在重建前清空模式专属控件引用。
     */
    private void resetWidgetReferences() {
        idField = null;
        spawnXField = null;
        spawnYField = null;
        spawnZField = null;
        originXField = null;
        originYField = null;
        originZField = null;
        schematicButton = null;
        directionButton = null;
        enterButton = null;
        configureButton = null;
        deleteButton = null;
        saveNpcButton = null;
        exitMapButton = null;
        layerBlockFields.clear();
        layerCountFields.clear();
    }

    /**
     * Builds paginated map buttons plus enter, configure, and create actions.
     * 构建分页地图按钮以及进入、配置与新建操作。
     */
    private void buildCatalogWidgets() {
        int left = width / 2 - 190;
        int top = height / 2 - 130;
        int first = pageIndex * MAPS_PER_PAGE;
        int last = Math.min(maps.size(), first + MAPS_PER_PAGE);
        for (int index = first; index < last; index++) {
            MapDefinition map = maps.get(index);
            int row = index - first;
            ModernButton button = ModernButton.create(
                    Component.literal(map.id),
                    ignored -> selectMap(map.id)
            ).bounds(left, top + 28 + row * 25, 380, 20).build();
            if (map.id.equals(selectedMapId)) {
                button.variant(ModernButton.Variant.PRIMARY);
            }
            addRenderableWidget(button);
        }
        Button previous = addRenderableWidget(ModernButton.create(
                Component.literal("上一页"),
                ignored -> changePage(-1)
        ).bounds(left, top + 158, 90, 20).build());
        previous.active = pageIndex > 0;
        Button next = addRenderableWidget(ModernButton.create(
                Component.literal("下一页"),
                ignored -> changePage(1)
        ).bounds(left + 290, top + 158, 90, 20).build());
        next.active = pageIndex + 1 < pageCount();
        enterButton = addRenderableWidget(ModernButton.create(
                Component.literal("进入所选地图"),
                ignored -> enterSelectedMap()
        ).bounds(left, top + 188, 185, 20).variant(ModernButton.Variant.PRIMARY).build());
        configureButton = addRenderableWidget(ModernButton.create(
                Component.literal("配置所选地图"),
                ignored -> configureSelectedMap()
        ).bounds(left + 195, top + 188, 185, 20).build());
        enterButton.active = !selectedMapId.isEmpty();
        configureButton.active = !selectedMapId.isEmpty();
        idField = addRenderableWidget(new ModernEditBox(
                font,
                left + 80,
                top + 218,
                180,
                20,
                Component.literal("新地图 ID")
        ));
        idField.setMaxLength(48);
        idField.setFilter(value -> value.matches("[A-Za-z0-9_-]*"));
        addRenderableWidget(ModernButton.create(
                Component.literal("新建地图"),
                ignored -> createMap()
        ).bounds(left + 270, top + 218, 110, 20).build());
        addRenderableWidget(ModernButton.create(
                Component.literal("关闭"),
                ignored -> onClose()
        ).bounds(left, top + 248, 380, 20).variant(ModernButton.Variant.GHOST).build());
    }

    /**
     * Builds persistent metadata and entries for specialized configuration screens.
     * 构建持久元数据以及专用配置界面的入口。
     */
    private void buildConfigurationWidgets() {
        int left = width / 2 - 190;
        int top = height / 2 - 125;
        MapDefinition map = configuredMap();
        spawnXField = addRenderableWidget(new ModernEditBox(
                font, left + 80, top + 35, 70, 20, Component.literal("出生 X")
        ));
        spawnYField = addRenderableWidget(new ModernEditBox(
                font, left + 185, top + 35, 70, 20, Component.literal("出生 Y")
        ));
        spawnZField = addRenderableWidget(new ModernEditBox(
                font, left + 290, top + 35, 70, 20, Component.literal("出生 Z")
        ));
        directionButton = addRenderableWidget(ModernButton.create(
                Component.empty(),
                ignored -> cycleDirection()
        ).bounds(left, top + 65, 185, 20).build());
        addRenderableWidget(ModernButton.create(
                Component.literal("配置结构"),
                ignored -> openStructureConfig()
        ).bounds(left + 195, top + 65, 185, 20).build());
        addRenderableWidget(ModernButton.create(
                Component.literal("高级设置"),
                ignored -> openAdvancedConfig()
        ).bounds(left, top + 95, 185, 20).build());
        saveNpcButton = addRenderableWidget(ModernButton.create(
                Component.literal("保存 NPC"),
                ignored -> saveAllNpcs()
        ).bounds(left + 195, top + 95, 185, 20).variant(ModernButton.Variant.PRIMARY).build());
        saveNpcButton.active = isEditing();
        exitMapButton = addRenderableWidget(ModernButton.create(
                Component.literal("退出地图"),
                ignored -> exitMap()
        ).bounds(left, top + 125, 185, 20).variant(ModernButton.Variant.DANGER).build());
        exitMapButton.active = isEditing();
        deleteButton = addRenderableWidget(ModernButton.create(
                Component.literal("删除地图"),
                ignored -> confirmDeleteMap()
        ).bounds(left + 195, top + 125, 185, 20).variant(ModernButton.Variant.DANGER).build());
        addRenderableWidget(ModernButton.create(
                Component.literal("完成"),
                ignored -> finishConfiguration()
        ).bounds(left, top + 155, 380, 20).variant(ModernButton.Variant.GHOST).build());
        loadConfigurationFields(map);
        refreshConfigurationLabels();
    }

    /**
     * Builds advanced configuration widgets for terrain settings.
     * 构建用于地形设置的高级配置控件。
     */
    private void buildAdvancedWidgets() {
        int left = width / 2 - 190;
        int top = height / 2 - 125;
        addRenderableWidget(ModernButton.create(
                Component.literal("配置地形"),
                ignored -> openTerrainConfig()
        ).bounds(left, top + 35, 380, 20).build());
        addRenderableWidget(ModernButton.create(
                Component.literal("返回"),
                ignored -> closeAdvancedConfig()
        ).bounds(left, top + 75, 380, 20).variant(ModernButton.Variant.GHOST).build());
    }

    /**
     * Opens advanced terrain settings while preserving visible general fields.
     * 打开高级地形设置，同时保留可见的常规字段。
     */
    private void openAdvancedConfig() {
        try {
            syncConfigurationFieldsToMap();
            isAdvancedConfiguring = true;
            rebuildWidgets();
        } catch (IllegalArgumentException exception) {
            ScreenHelper.message(exception.getMessage());
        }
    }

    /**
     * Returns from advanced settings to the shared map form.
     * 从高级设置返回共用地图表单。
     */
    private void closeAdvancedConfig() {
        isAdvancedConfiguring = false;
        rebuildWidgets();
    }

    /**
     * Copies visible general fields into the client snapshot before changing sub-screens.
     * 在切换子界面前将可见常规字段复制到客户端快照。
     */
    private void syncConfigurationFieldsToMap() {
        MapDefinition map = configuredMap();
        if (map == null || spawnXField == null) {
            return;
        }
        map.spawnX = ScreenHelper.parseInt(spawnXField.getValue(), "出生 X");
        map.spawnY = ScreenHelper.parseInt(spawnYField.getValue(), "出生 Y");
        map.spawnZ = ScreenHelper.parseInt(spawnZField.getValue(), "出生 Z");
        map.direction = direction;
    }

    /**
     * Builds the dedicated multi-structure configuration screen.
     * 构建专用的多结构配置界面。
     */
    private void buildStructureWidgets() {
        int left = width / 2 - 190;
        int top = height / 2 - 145;
        int first = structurePageIndex * STRUCTURES_PER_PAGE;
        int last = Math.min(structures.size(), first + STRUCTURES_PER_PAGE);
        for (int index = first; index < last; index++) {
            MapDefinition.StructureData structure = structures.get(index);
            int row = index - first;
            addRenderableWidget(ModernButton.create(
                    Component.literal(structure.name),
                    ignored -> openStructureDetail(structure.name)
            ).bounds(left, top + 25 + row * 25, 300, 20).build());
            boolean confirming = structureDeleteConfirmation
                    && structure.name.equals(selectedStructureName);
            addRenderableWidget(ModernButton.create(
                    Component.literal(confirming ? "确认" : "删除"),
                    ignored -> confirmDeleteStructure(structure.name)
            ).bounds(left + 310, top + 25 + row * 25, 70, 20)
                    .variant(ModernButton.Variant.DANGER)
                    .build());
        }

        Button previous = addRenderableWidget(ModernButton.create(
                Component.literal("上一页"),
                ignored -> changeStructurePage(-1)
        ).bounds(left, top + 130, 90, 20).build());
        previous.active = structurePageIndex > 0;
        Button next = addRenderableWidget(ModernButton.create(
                Component.literal("下一页"),
                ignored -> changeStructurePage(1)
        ).bounds(left + 290, top + 130, 90, 20).build());
        next.active = (structurePageIndex + 1) * STRUCTURES_PER_PAGE < structures.size();

        schematicButton = addRenderableWidget(ModernButton.create(
                Component.empty(),
                ignored -> ScreenHelper.message("请将鼠标悬停在蓝图选择框上滚动滚轮。")
        ).bounds(left, top + 160, 260, 20).build());
        addRenderableWidget(ModernButton.create(
                Component.literal("上传蓝图"),
                ignored -> uploadSchematic()
        ).bounds(left + 270, top + 160, 110, 20).variant(ModernButton.Variant.PRIMARY).build());
        addRenderableWidget(ModernButton.create(
                Component.literal("返回地图配置"),
                ignored -> closeStructureConfig()
        ).bounds(left, top + 205, 380, 20).variant(ModernButton.Variant.GHOST).build());
        refreshStructureLabels();
    }

    /**
     * Builds the detailed settings screen for one uploaded structure.
     * 构建一个已上传结构的详细设置界面。
     */
    private void buildStructureDetailWidgets() {
        int left = width / 2 - 190;
        int top = height / 2 - 100;
        MapDefinition.StructureData structure = selectedStructure();
        if (structure == null) {
            isStructureDetailConfiguring = false;
            buildStructureWidgets();
            return;
        }
        originXField = addRenderableWidget(new ModernEditBox(
                font, left + 80, top + 35, 70, 20, Component.literal("原点 X")
        ));
        originYField = addRenderableWidget(new ModernEditBox(
                font, left + 185, top + 35, 70, 20, Component.literal("原点 Y")
        ));
        originZField = addRenderableWidget(new ModernEditBox(
                font, left + 290, top + 35, 70, 20, Component.literal("原点 Z")
        ));
        originXField.setValue(Integer.toString(structure.originX));
        originYField.setValue(Integer.toString(structure.originY));
        originZField.setValue(Integer.toString(structure.originZ));
        addRenderableWidget(ModernButton.create(
                Component.literal("完成"),
                ignored -> finishStructureDetail()
        ).bounds(left, top + 70, 380, 20).variant(ModernButton.Variant.PRIMARY).build());
    }

    /**
     * Opens structure configuration after saving general map metadata.
     * 保存地图常规元数据后打开结构配置。
     */
    private void openStructureConfig() {
        try {
            ScreenHelper.send("save_map", parseConfigurationForm());
            isStructureConfiguring = true;
            isStructureDetailConfiguring = false;
            structureDeleteConfirmation = false;
            selectedStructureName = "";
            rebuildWidgets();
        } catch (IllegalArgumentException exception) {
            ScreenHelper.message(exception.getMessage());
        }
    }

    /**
     * Returns from the structure overview to the shared map form.
     * 从结构总览返回共用地图表单。
     */
    private void closeStructureConfig() {
        isStructureConfiguring = false;
        isStructureDetailConfiguring = false;
        structureDeleteConfirmation = false;
        selectedStructureName = "";
        rebuildWidgets();
    }

    /**
     * Opens detailed settings for one uploaded structure.
     * 打开一个已上传结构的详细设置。
     *
     * @param structureName exact structure name / 完整结构名
     */
    private void openStructureDetail(String structureName) {
        selectedStructureName = structureName;
        isStructureDetailConfiguring = true;
        structureDeleteConfirmation = false;
        rebuildWidgets();
    }

    /**
     * Returns from one structure's settings to the structure overview.
     * 从单个结构设置返回结构总览。
     */
    private void closeStructureDetail() {
        isStructureDetailConfiguring = false;
        structureDeleteConfirmation = false;
        selectedStructureName = "";
        rebuildWidgets();
    }

    /**
     * Changes the bounded structure list page.
     * 切换受限的结构列表页。
     *
     * @param delta signed page delta / 有符号页差
     */
    private void changeStructurePage(int delta) {
        int pageCount = Math.max(1, (structures.size() + STRUCTURES_PER_PAGE - 1) / STRUCTURES_PER_PAGE);
        structurePageIndex = Math.max(0, Math.min(pageCount - 1, structurePageIndex + delta));
        rebuildWidgets();
    }

    /**
     * Saves origin coordinates for the selected structure only.
     * 仅保存所选结构的原点坐标。
     *
     * @return true if saved successfully / 保存成功返回 true
     */
    private boolean saveStructureConfiguration() {
        if (selectedStructure() == null) {
            return false;
        }
        try {
            ModNetwork.StructureConfigurationForm form = new ModNetwork.StructureConfigurationForm();
            form.mapId = configuredMapId;
            form.structureName = selectedStructureName;
            form.originX = ScreenHelper.parseInt(originXField.getValue(), "结构原点 X");
            form.originY = ScreenHelper.parseInt(originYField.getValue(), "结构原点 Y");
            form.originZ = ScreenHelper.parseInt(originZField.getValue(), "结构原点 Z");
            ScreenHelper.send("save_structure_configuration", form);
            return true;
        } catch (IllegalArgumentException exception) {
            ScreenHelper.message(exception.getMessage());
            return false;
        }
    }

    /**
     * Saves structure origin coordinates and returns to structure list.
     * 保存结构原点坐标并返回结构列表。
     */
    private void finishStructureDetail() {
        if (saveStructureConfiguration()) {
            closeStructureDetail();
        }
    }

    /**
     * Requires a second click before deleting one structure from the overview.
     * 从总览删除一个结构前要求再次点击确认。
     *
     * @param structureName exact structure name / 完整结构名
     */
    private void confirmDeleteStructure(String structureName) {
        MapDefinition.StructureData structure = structures.stream()
                .filter(candidate -> candidate.name.equals(structureName))
                .findFirst()
                .orElse(null);
        if (structure == null) {
            return;
        }
        if (!structureDeleteConfirmation || !structureName.equals(selectedStructureName)) {
            selectedStructureName = structureName;
            structureDeleteConfirmation = true;
            rebuildWidgets();
            return;
        }
        ModNetwork.StructureRequest request = new ModNetwork.StructureRequest();
        request.mapId = configuredMapId;
        request.structureName = structure.name;
        selectedStructureName = "";
        structureDeleteConfirmation = false;
        ScreenHelper.send("delete_structure", request);
        rebuildWidgets();
    }

    /**
     * Refreshes the local schematic selector used for structure upload.
     * 刷新用于结构上传的本地蓝图选择器。
     */
    private void refreshStructureLabels() {
        if (schematicButton == null) {
            return;
        }
        if (schematics.isEmpty()) {
            schematicButton.setMessage(Component.literal("未找到 schematics/*.nbt"));
        } else {
            schematicButton.setMessage(Component.literal(
                    "[" + (schematicIndex + 1) + "/" + schematics.size() + "] "
                            + schematics.get(schematicIndex).getFileName()
            ));
        }
    }

    /**
     * Builds superflat terrain layer configuration widgets.
     * 构建超平坦地层配置控件。
     */
    private void buildTerrainWidgets() {
        int left = width / 2 - 190;
        int top = height / 2 - 125;
        addRenderableWidget(ModernButton.create(
                Component.literal("+ 新增地层"),
                ignored -> addFlatLayer()
        ).bounds(left + 270, top + 5, 110, 20).variant(ModernButton.Variant.PRIMARY).build());

        int total = editingFlatLayers.size();
        int first = terrainPageIndex * LAYERS_PER_PAGE;
        int last = Math.min(total, first + LAYERS_PER_PAGE);

        for (int uiIndex = first; uiIndex < last; uiIndex++) {
            int row = uiIndex - first;
            int rowY = top + 35 + row * 28;
            int listIndex = total - 1 - uiIndex;
            MapDefinition.FlatLayer layer = editingFlatLayers.get(listIndex);
            final int targetIndex = listIndex;

            ModernEditBox blockBox = addRenderableWidget(new ModernEditBox(
                    font, left + 55, rowY, 175, 20, Component.literal("方块 ID")
            ));
            blockBox.setMaxLength(64);
            blockBox.setValue(layer.blockId);
            layerBlockFields.add(blockBox);

            ModernEditBox countBox = addRenderableWidget(new ModernEditBox(
                    font, left + 240, rowY, 65, 20, Component.literal("层数")
            ));
            countBox.setMaxLength(5);
            countBox.setFilter(value -> value.matches("[0-9]*"));
            countBox.setValue(Integer.toString(layer.count));
            layerCountFields.add(countBox);

            addRenderableWidget(ModernButton.create(
                    Component.literal("删除"),
                    ignored -> removeFlatLayer(targetIndex)
            ).bounds(left + 315, rowY, 65, 20).variant(ModernButton.Variant.DANGER).build());
        }

        Button previous = addRenderableWidget(ModernButton.create(
                Component.literal("上一页"),
                ignored -> changeTerrainPage(-1)
        ).bounds(left, top + 155, 90, 20).build());
        previous.active = terrainPageIndex > 0;

        Button next = addRenderableWidget(ModernButton.create(
                Component.literal("下一页"),
                ignored -> changeTerrainPage(1)
        ).bounds(left + 290, top + 155, 90, 20).build());
        next.active = (terrainPageIndex + 1) * LAYERS_PER_PAGE < total;

        addRenderableWidget(ModernButton.create(
                Component.literal("确定保存地层"),
                ignored -> saveTerrainConfig()
        ).bounds(left, top + 185, 185, 20).variant(ModernButton.Variant.PRIMARY).build());

        addRenderableWidget(ModernButton.create(
                Component.literal("取消"),
                ignored -> cancelTerrainConfig()
        ).bounds(left + 195, top + 185, 185, 20).variant(ModernButton.Variant.GHOST).build());
    }

    private void openTerrainConfig() {
        MapDefinition map = configuredMap();
        if (map == null) {
            return;
        }
        editingFlatLayers = new ArrayList<>();
        if (map.flatLayers != null) {
            for (MapDefinition.FlatLayer layer : map.flatLayers) {
                editingFlatLayers.add(new MapDefinition.FlatLayer(layer.blockId, layer.count));
            }
        }
        isTerrainConfiguring = true;
        terrainPageIndex = 0;
        rebuildWidgets();
    }

    private void syncTerrainFieldsFromUI() {
        int total = editingFlatLayers.size();
        int first = terrainPageIndex * LAYERS_PER_PAGE;
        for (int i = 0; i < layerBlockFields.size() && (first + i) < total; i++) {
            int listIndex = total - 1 - (first + i);
            MapDefinition.FlatLayer layer = editingFlatLayers.get(listIndex);
            layer.blockId = layerBlockFields.get(i).getValue().strip();
            int countVal = 1;
            try {
                countVal = Math.max(1, Integer.parseInt(layerCountFields.get(i).getValue().trim()));
            } catch (NumberFormatException ignored) {
            }
            layer.count = countVal;
        }
    }

    private void addFlatLayer() {
        syncTerrainFieldsFromUI();
        editingFlatLayers.add(new MapDefinition.FlatLayer("minecraft:dirt", 1));
        terrainPageIndex = 0;
        rebuildWidgets();
    }

    private void removeFlatLayer(int index) {
        syncTerrainFieldsFromUI();
        if (index >= 0 && index < editingFlatLayers.size()) {
            editingFlatLayers.remove(index);
        }
        int maxPage = Math.max(0, (editingFlatLayers.size() - 1) / LAYERS_PER_PAGE);
        terrainPageIndex = Math.min(terrainPageIndex, maxPage);
        rebuildWidgets();
    }

    private void changeTerrainPage(int delta) {
        syncTerrainFieldsFromUI();
        int maxPage = Math.max(0, (editingFlatLayers.size() - 1) / LAYERS_PER_PAGE);
        terrainPageIndex = Math.max(0, Math.min(maxPage, terrainPageIndex + delta));
        rebuildWidgets();
    }

    private void saveTerrainConfig() {
        syncTerrainFieldsFromUI();
        for (int i = 0; i < editingFlatLayers.size(); i++) {
            MapDefinition.FlatLayer layer = editingFlatLayers.get(i);
            String id = layer.blockId == null ? "" : layer.blockId.strip().toLowerCase(java.util.Locale.ROOT);
            if (id.isEmpty()) {
                ScreenHelper.message("第 " + (i + 1) + " 层方块 ID 不能为空。");
                return;
            }
            if (!id.contains(":")) {
                id = "minecraft:" + id;
            }
            layer.blockId = id;
            if (layer.count < 1) {
                ScreenHelper.message("第 " + (i + 1) + " 层数必须大于 0。");
                return;
            }
        }
        MapDefinition map = configuredMap();
        if (map != null) {
            map.flatLayers = new ArrayList<>(editingFlatLayers);
        }
        isTerrainConfiguring = false;
        rebuildWidgets();
    }

    private void cancelTerrainConfig() {
        isTerrainConfiguring = false;
        editingFlatLayers.clear();
        rebuildWidgets();
    }

    /**
     * Rebuilds sorted maps and locally available schematic files.
     * 重建排序后的地图与本地可用蓝图文件。
     */
    private void rebuildCollections() {
        maps = new ArrayList<>(state.maps.values());
        maps.sort(Comparator.comparing(map -> map.id));
        pageIndex = Math.max(0, Math.min(pageIndex, pageCount() - 1));
        schematics = ClientUploads.listSchematics();
        schematicIndex = Math.min(schematicIndex, Math.max(0, schematics.size() - 1));
        if (!selectedMapId.isEmpty() && !state.maps.containsKey(selectedMapId)) {
            selectedMapId = "";
        }
        if (!configuredMapId.isEmpty() && !state.maps.containsKey(configuredMapId)) {
            configuredMapId = "";
        }
        MapDefinition map = configuredMap();
        structures = map == null || map.structures == null
                ? new ArrayList<>()
                : new ArrayList<>(map.structures);
        structures.removeIf(java.util.Objects::isNull);
        structures.sort(Comparator.comparing(structure -> structure.name));
        int structurePageCount = Math.max(
                1,
                (structures.size() + STRUCTURES_PER_PAGE - 1) / STRUCTURES_PER_PAGE
        );
        structurePageIndex = Math.max(0, Math.min(structurePageIndex, structurePageCount - 1));
        if (!selectedStructureName.isEmpty() && selectedStructure() == null) {
            selectedStructureName = "";
        } else if (!selectedStructureName.isEmpty()) {
            for (int index = 0; index < structures.size(); index++) {
                if (selectedStructureName.equals(structures.get(index).name)) {
                    structurePageIndex = index / STRUCTURES_PER_PAGE;
                    break;
                }
            }
        }
    }

    /**
     * Returns whether the player owns an active isolated editor session.
     * 返回玩家是否拥有活动隔离编辑会话。
     *
     * @return whether editing mode is active / 编辑模式是否活动
     */
    private boolean isEditing() {
        return !boundMapId.isEmpty() && state.maps.containsKey(boundMapId);
    }

    /**
     * Returns whether normal-mode configuration is open.
     * 返回是否打开了普通模式配置。
     *
     * @return whether configuration mode is active / 配置模式是否活动
     */
    private boolean isConfiguring() {
        return !configuredMapId.isEmpty() && state.maps.containsKey(configuredMapId);
    }

    /**
     * Returns the current map page count.
     * 返回当前地图页数。
     *
     * @return at least one page / 至少一页
     */
    private int pageCount() {
        return Math.max(1, (maps.size() + MAPS_PER_PAGE - 1) / MAPS_PER_PAGE);
    }

    /**
     * Selects a catalog map without entering or configuring it yet.
     * 选择目录地图，但暂不进入或配置。
     *
     * @param mapId selected map identifier / 所选地图标识
     */
    private void selectMap(String mapId) {
        selectedMapId = mapId;
        rebuildWidgets();
    }

    /**
     * Changes the bounded catalog page.
     * 切换受限的目录页。
     *
     * @param delta signed page delta / 有符号页差
     */
    private void changePage(int delta) {
        pageIndex = Math.max(0, Math.min(pageCount() - 1, pageIndex + delta));
        rebuildWidgets();
    }

    /**
     * Requests a fresh isolated dimension for the selected map.
     * 请求为所选地图创建全新隔离维度。
     */
    private void enterSelectedMap() {
        if (selectedMapId.isEmpty()) {
            return;
        }
        ModService.MapIdRequest request = new ModService.MapIdRequest();
        request.mapId = selectedMapId;
        ScreenHelper.send("load_map", request);
    }

    /**
     * Opens persistent configuration for the selected map without binding it.
     * 在不绑定地图的情况下打开所选地图的持久配置。
     */
    private void configureSelectedMap() {
        if (selectedMapId.isEmpty()) {
            return;
        }
        configuredMapId = selectedMapId;
        isAdvancedConfiguring = false;
        isTerrainConfiguring = false;
        isStructureConfiguring = false;
        isStructureDetailConfiguring = false;
        deleteConfirmation = false;
        structureDeleteConfirmation = false;
        selectedStructureName = "";
        structurePageIndex = 0;
        rebuildCollections();
        rebuildWidgets();
    }

    /**
     * Cycles the configured player spawn direction.
     * 循环切换配置的玩家出生朝向。
     */
    private void cycleDirection() {
        MapDefinition.Direction[] values = MapDefinition.Direction.values();
        direction = values[(direction.ordinal() + 1) % values.length];
        refreshConfigurationLabels();
    }

    /**
     * Loads persistent configuration into fields.
     * 将持久配置载入字段。
     *
     * @param map configured map / 配置地图
     */
    private void loadConfigurationFields(MapDefinition map) {
        if (map == null || spawnXField == null) {
            return;
        }
        spawnXField.setValue(Integer.toString(map.spawnX));
        spawnYField.setValue(Integer.toString(map.spawnY));
        spawnZField.setValue(Integer.toString(map.spawnZ));
        direction = map.direction;
    }

    /**
     * Refreshes the player spawn direction label.
     * 刷新玩家出生朝向标签。
     */
    private void refreshConfigurationLabels() {
        if (directionButton == null) {
            return;
        }
        directionButton.setMessage(Component.literal("朝向：" + direction.name()));
    }

    /**
     * Reads validated persistent map configuration.
     * 读取经过校验的持久地图配置。
     *
     * @return map form / 地图表单
     */
    private ModNetwork.MapMetadataForm parseConfigurationForm() {
        ModNetwork.MapMetadataForm form = new ModNetwork.MapMetadataForm();
        form.id = configuredMapId;
        form.spawnX = ScreenHelper.parseInt(spawnXField.getValue(), "出生 X");
        form.spawnY = ScreenHelper.parseInt(spawnYField.getValue(), "出生 Y");
        form.spawnZ = ScreenHelper.parseInt(spawnZField.getValue(), "出生 Z");
        form.direction = direction;
        MapDefinition map = configuredMap();
        if (map != null) {
            if (map.flatLayers != null) {
                form.flatLayers = new ArrayList<>(map.flatLayers);
            }
        }
        return form;
    }

    /**
     * Creates a new persistent map and waits for the server to open its configuration.
     * 创建新的持久地图，并等待服务端打开其配置。
     */
    private void createMap() {
        try {
            ModNetwork.MapMetadataForm form = new ModNetwork.MapMetadataForm();
            form.id = ModService.normalizeMapId(idField.getValue());
            ScreenHelper.send("create_map", form);
        } catch (IllegalArgumentException exception) {
            ScreenHelper.message(exception.getMessage());
        }
    }

    /**
     * Saves non-NPC map metadata and closes the editor.
     * 保存非 NPC 地图配置并关闭编辑器。
     */
    private void finishConfiguration() {
        try {
            ScreenHelper.send("save_map", parseConfigurationForm());
            onClose();
        } catch (IllegalArgumentException exception) {
            ScreenHelper.message(exception.getMessage());
        }
    }

    /**
     * Uploads the wheel-selected Create schematic as the persistent map structure.
     * 将滚轮选择的机械动力蓝图上传为持久地图结构。
     */
    private void uploadSchematic() {
        if (schematics.isEmpty()) {
            ScreenHelper.message("请先用机械动力“蓝图与笔”保存结构，并把 .nbt 放在 schematics 目录。");
            return;
        }
        try {
            Path schematic = schematics.get(schematicIndex);
            selectedStructureName = schematic.getFileName().toString();
            ClientUploads.upload("SCHEMATIC", configuredMapId, schematic);
            ScreenHelper.message("蓝图正在上传；同名结构会被覆盖，完成后只影响新建的编辑维度。");
        } catch (IOException exception) {
            ScreenHelper.message("蓝图上传失败：" + exception.getMessage());
        }
    }

    /**
     * Saves every live NPC without closing the isolated editor dimension.
     * 保存全部实时 NPC，但不关闭隔离编辑维度。
     */
    private void saveAllNpcs() {
        ScreenHelper.send("save_all_npcs", new Object());
    }

    /**
     * Discards unsaved NPC drafts, returns the player, and deletes the isolated dimension.
     * 丢弃未保存的 NPC 草稿、送回玩家并删除隔离维度。
     */
    private void exitMap() {
        ScreenHelper.send("exit_map", new Object());
    }

    /**
     * Requires a second click before permanently deleting the configured map.
     * 永久删除配置地图前要求第二次点击确认。
     */
    private void confirmDeleteMap() {
        if (!deleteConfirmation) {
            deleteConfirmation = true;
            deleteButton.setMessage(Component.literal("再次点击确认永久删除"));
            return;
        }
        ModService.MapIdRequest request = new ModService.MapIdRequest();
        request.mapId = configuredMapId;
        ScreenHelper.send("delete_map", request);
    }

    /**
     * Returns the map being configured.
     * 返回正在配置的地图。
     *
     * @return configured map, or {@code null} / 配置地图，不存在时返回 {@code null}
     */
    private MapDefinition configuredMap() {
        return state.maps.get(configuredMapId);
    }

    /**
     * Returns the selected structure from the latest synchronized map snapshot.
     * 从最新同步地图快照中返回所选结构。
     *
     * @return selected structure, or {@code null} / 所选结构，不存在时返回 {@code null}
     */
    private MapDefinition.StructureData selectedStructure() {
        return structures.stream()
                .filter(structure -> structure.name.equals(selectedStructureName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Handles Create-style wheel selection while hovering the schematic field.
     * 在悬停蓝图字段时处理机械动力风格滚轮选择。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isStructureConfiguring
                && schematicButton != null
                && schematicButton.isMouseOver(mouseX, mouseY)
                && !schematics.isEmpty()) {
            double amount = scrollY == 0.0D ? scrollX : scrollY;
            int step = hasShiftDown() ? 5 : 1;
            int delta = amount > 0.0D ? -step : step;
            int previous = schematicIndex;
            schematicIndex = Math.max(0, Math.min(schematics.size() - 1, schematicIndex + delta));
            if (previous != schematicIndex) {
                refreshStructureLabels();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * Draws the active mode panel before rendering widgets.
     * 在渲染控件前绘制活动模式面板。
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        UITheme.drawBackground(graphics, width, height);
        if (isConfiguring()) {
            if (isStructureConfiguring) {
                if (isStructureDetailConfiguring) {
                    renderStructureDetailBackground(graphics);
                } else {
                    renderStructureConfigBackground(graphics);
                }
            } else if (isTerrainConfiguring) {
                renderTerrainConfigBackground(graphics);
            } else if (isAdvancedConfiguring) {
                renderAdvancedConfigBackground(graphics);
            } else {
                renderConfigurationBackground(graphics);
            }
        } else {
            renderCatalogBackground(graphics);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Draws the catalog panel and page status.
     * 绘制目录面板与页码状态。
     */
    private void renderCatalogBackground(GuiGraphics graphics) {
        int left = width / 2 - 190;
        int top = height / 2 - 130;
        drawPanel(graphics, left - 18, top - 30, 416, 318);
        graphics.drawCenteredString(font, title, width / 2, top - 20, UITheme.TEXT);
        graphics.drawString(font, "先选择地图，再决定进入或配置", left, top + 4, UITheme.TEXT_MUTED, false);
        if (maps.isEmpty()) {
            graphics.drawCenteredString(font, "还没有地图，请在下方新建。", width / 2, top + 82, UITheme.TEXT_DIM);
        }
        graphics.drawCenteredString(
                font,
                "第 " + (pageIndex + 1) + " / " + pageCount() + " 页",
                width / 2,
                top + 164,
                UITheme.TEXT_MUTED
        );
        graphics.drawString(font, "地图 ID", left, top + 224, UITheme.TEXT_MUTED, false);
    }

    /**
     * Draws persistent map configuration labels.
     * 绘制持久地图配置标签。
     */
    private void renderConfigurationBackground(GuiGraphics graphics) {
        int left = width / 2 - 190;
        int top = height / 2 - 125;
        drawPanel(graphics, left - 18, top - 30, 416, 240);
        String heading = isEditing() ? "编辑中配置地图：" : "配置地图：";
        graphics.drawCenteredString(font, heading + configuredMapId, width / 2, top - 20, UITheme.TEXT);
        graphics.drawString(font, "出生点", left, top + 41, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "X", left + 65, top + 41, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "Y", left + 170, top + 41, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "Z", left + 275, top + 41, UITheme.TEXT_MUTED, false);
        if (isEditing()) {
            graphics.drawString(
                    font,
                    "仅“保存 NPC”会写入配置；直接退出地图、跨维或登出会丢弃未保存修改",
                    left,
                    top + 190,
                    UITheme.TEXT_MUTED,
                    false
            );
        }
    }

    /**
     * Draws the structure overview containing upload, list, and delete actions.
     * 绘制包含上传、列表和删除操作的结构总览。
     *
     * @param graphics GUI drawing context / GUI 绘制上下文
     */
    private void renderStructureConfigBackground(GuiGraphics graphics) {
        int left = width / 2 - 190;
        int top = height / 2 - 145;
        drawPanel(graphics, left - 18, top - 30, 416, 285);
        graphics.drawCenteredString(font, "配置结构：" + configuredMapId, width / 2, top - 20, UITheme.TEXT);
        graphics.drawString(
                font,
                "地图结构（点击结构名配置，结构名与蓝图文件名一致）：",
                left,
                top + 5,
                UITheme.TEXT_MUTED,
                false
        );
        if (structures.isEmpty()) {
            graphics.drawCenteredString(font, "暂无结构，请从本地 schematics 目录上传蓝图。", width / 2, top + 72, UITheme.TEXT_DIM);
        }
        int pageCount = Math.max(1, (structures.size() + STRUCTURES_PER_PAGE - 1) / STRUCTURES_PER_PAGE);
        graphics.drawCenteredString(
                font,
                "第 " + (structurePageIndex + 1) + " / " + pageCount + " 页",
                width / 2,
                top + 136,
                UITheme.TEXT_MUTED
        );
        graphics.drawString(
                font,
                "悬停蓝图框滚轮选择；上传同名蓝图会覆盖原结构文件",
                left,
                top + 182,
                UITheme.ACCENT,
                false
        );
    }

    /**
     * Draws detailed configuration labels for one uploaded structure.
     * 绘制一个已上传结构的详细配置标签。
     *
     * @param graphics GUI drawing context / GUI 绘制上下文
     */
    private void renderStructureDetailBackground(GuiGraphics graphics) {
        int left = width / 2 - 190;
        int top = height / 2 - 100;
        drawPanel(graphics, left - 18, top - 30, 416, 140);
        graphics.drawCenteredString(font, "结构配置项：" + selectedStructureName, width / 2, top - 20, UITheme.TEXT);
        graphics.drawString(font, "结构原点", left, top + 41, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "X", left + 65, top + 41, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "Y", left + 170, top + 41, UITheme.TEXT_MUTED, false);
        graphics.drawString(font, "Z", left + 275, top + 41, UITheme.TEXT_MUTED, false);
    }

    /**
     * Draws superflat terrain configuration panel and section headers.
     * 绘制超平坦地形配置面板与栏目标题。
     */
    private void renderTerrainConfigBackground(GuiGraphics graphics) {
        int left = width / 2 - 190;
        int top = height / 2 - 125;
        drawPanel(graphics, left - 18, top - 30, 416, 270);
        graphics.drawCenteredString(font, "配置超平坦地形：" + configuredMapId, width / 2, top - 20, UITheme.TEXT);
        graphics.drawString(font, "地层列表（从顶层到底层排列）：", left, top + 10, UITheme.TEXT_MUTED, false);
        int total = editingFlatLayers.size();
        int first = terrainPageIndex * LAYERS_PER_PAGE;
        int last = Math.min(total, first + LAYERS_PER_PAGE);
        for (int uiIndex = first; uiIndex < last; uiIndex++) {
            int row = uiIndex - first;
            int rowY = top + 35 + row * 28;
            int layerNumber = total - uiIndex;
            graphics.drawString(font, "第 " + layerNumber + " 层", left, rowY + 5, UITheme.TEXT_MUTED, false);
        }
        if (editingFlatLayers.isEmpty()) {
            graphics.drawCenteredString(font, "暂无地层配置，请点击右上角加号新增。", width / 2, top + 75, UITheme.TEXT_DIM);
        }
        int totalPages = Math.max(1, (editingFlatLayers.size() + LAYERS_PER_PAGE - 1) / LAYERS_PER_PAGE);
        graphics.drawCenteredString(
                font,
                "第 " + (terrainPageIndex + 1) + " / " + totalPages + " 页",
                width / 2,
                top + 160,
                UITheme.TEXT_MUTED
        );
    }

    /**
     * Draws advanced map configuration panel and section headers.
     * 绘制高级地图配置面板与栏目标题。
     */
    private void renderAdvancedConfigBackground(GuiGraphics graphics) {
        int left = width / 2 - 190;
        int top = height / 2 - 125;
        drawPanel(graphics, left - 18, top - 30, 416, 270);
        graphics.drawCenteredString(font, "高级设置：" + configuredMapId, width / 2, top - 20, UITheme.TEXT);
    }

    /**
     * Draws a shared rounded editor panel.
     * 绘制共用圆角面板。
     */
    private void drawPanel(GuiGraphics graphics, int x, int y, int panelWidth, int panelHeight) {
        UITheme.shadow(graphics, x, y, panelWidth, panelHeight, 8);
        UITheme.roundedPanel(
                graphics,
                x,
                y,
                panelWidth,
                panelHeight,
                8,
                UITheme.BORDER,
                UITheme.SURFACE
        );
    }

    /**
     * Keeps gameplay running while the screen is open.
     * 界面打开时保持游戏继续运行。
     *
     * @return always false / 始终返回 false
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
