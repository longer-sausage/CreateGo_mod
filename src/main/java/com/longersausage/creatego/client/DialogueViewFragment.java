/*
 * Renders the Modern UI runtime NPC dialogue card.
 * 渲染基于 Modern UI 的 NPC 运行时对话卡片。
 *
 * Author: CreateGo
 * Date: 2026-07-30
 */

package com.longersausage.creatego.client;

import com.longersausage.creatego.server.DialogueRuntime;
import icyllis.modernui.R;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.core.Core;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.graphics.drawable.Drawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.graphics.drawable.StateListDrawable;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.util.StateSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;

/**
 * Displays dialogue content and choices in a responsive bottom card.
 * 在响应式底部卡片中显示对话内容和选项。
 */
public final class DialogueViewFragment extends Fragment {
    private static final int COLOR_PANEL = 0xF5191D24;
    private static final int COLOR_BUTTON = 0xE60D1117;
    private static final int COLOR_BUTTON_HOVER = 0xFF1A222D;
    private static final int COLOR_BUTTON_PRESS = 0xFF080B0F;
    private static final int COLOR_BORDER = 0xFF39424F;
    private static final int COLOR_TEXT = 0xFFF4F7FB;
    private static final int COLOR_MUTED = 0xFFA2ACB9;
    private static final int COLOR_ACCENT = 0xFF55B8E8;

    private DialogueRuntime.DialogueView dialogue;
    private final Runnable closeRequest;
    private FrameLayout root;

    /**
     * Creates a view for one resolved dialogue state.
     * 为一个已解析的对话状态创建视图。
     *
     * @param dialogue dialogue state / 对话状态
     * @param closeRequest idempotent close request / 幂等关闭请求
     */
    public DialogueViewFragment(DialogueRuntime.DialogueView dialogue, Runnable closeRequest) {
        this.dialogue = dialogue;
        this.closeRequest = closeRequest;
    }

    /**
     * Replaces the rendered dialogue state without recreating the owning screen or fragment.
     * 在不重建所属 Screen 或 Fragment 的前提下替换已渲染的对话状态。
     *
     * @param dialogue next server-resolved dialogue state / 下一个服务端解析的对话状态
     */
    public void update(DialogueRuntime.DialogueView dialogue) {
        this.dialogue = dialogue;
        FrameLayout currentRoot = root;
        if (currentRoot != null) {
            // Marshal mutations through the Modern UI queue. / 通过 Modern UI 队列执行视图变更。
            Core.executeOnUiThread(() -> {
                if (root == currentRoot) {
                    renderDialogue();
                }
            });
        }
    }

    /**
     * Builds the complete runtime dialogue hierarchy.
     * 构建完整的运行时对话视图层级。
     */
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable DataSet savedInstanceState
    ) {
        Context context = getContext();
        root = new FrameLayout(context);
        root.setBackground(shape(0x73070A0F, 0, 0));
        renderDialogue();
        return root;
    }

    /**
     * Rebuilds only the dialogue content inside the persistent root view.
     * 仅重建持久根视图内的对话内容。
     */
    private void renderDialogue() {
        Context context = getContext();
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(20), dp(24), dp(22));
        panel.setBackground(shape(COLOR_PANEL, 18, 1));
        panel.setElevation(dp(24));
        if (dialogue.nodeId() >= 0 && (dialogue.options() == null || dialogue.options().isEmpty())) {
            panel.setClickable(true);
            panel.setOnClickListener(ignored -> continueDialogue());
        }
        panel.addView(buildHeader(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        TextView body = text(
                context,
                dialogue.text() == null ? "" : dialogue.text(),
                16,
                COLOR_TEXT
        );
        body.setGravity(Gravity.TOP);
        body.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bodyParams.setMargins(0, dp(10), 0, dp(14));
        panel.addView(body, bodyParams);
        if (dialogue.options() != null && !dialogue.options().isEmpty()) {
            View actions = buildActions(context);
            boolean scrollChoices = dialogue.options().size() > 5;
            panel.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    scrollChoices ? dp(270) : ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                dp(720),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        panelParams.setMargins(dp(20), 0, dp(20), dp(28));
        root.addView(panel, panelParams);
        root.post(() -> {
            FrameLayout.LayoutParams responsive = (FrameLayout.LayoutParams) panel.getLayoutParams();
            int availableWidth = Math.max(dp(160), root.getWidth() - dp(40));
            responsive.width = Math.min(dp(720), availableWidth);
            panel.setLayoutParams(responsive);
        });
    }

    /**
     * Builds the NPC identity row and explicit close action.
     * 构建 NPC 标识行和明确的关闭操作。
     */
    private View buildHeader(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View accent = new View(context);
        accent.setBackground(shape(COLOR_ACCENT, 3, 0));
        row.addView(accent, new LinearLayout.LayoutParams(dp(5), dp(34)));
        TextView name = text(context, dialogue.npcName(), 19, COLOR_TEXT);
        name.setPadding(dp(12), 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView close = closeAction(context);
        row.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        return row;
    }

    /**
     * Builds a scrollable choice list.
     * 构建可滚动选项列表。
     */
    private View buildActions(Context context) {
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        for (int index = 0; index < dialogue.options().size(); index++) {
            int optionIndex = index;
            String option = dialogue.options().get(index);
            String label = option == null ? "" : option;
            actions.addView(action(context, label, false, () -> choose(optionIndex)), actionParams(index));
        }
        if (dialogue.options().size() <= 5) {
            return actions;
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(actions, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scroll;
    }

    /**
     * Creates spacing parameters for one dialogue action.
     * 创建单个对话操作的间距参数。
     */
    private LinearLayout.LayoutParams actionParams(int index) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        params.setMargins(0, index == 0 ? 0 : dp(8), 0, 0);
        return params;
    }

    /**
     * Creates a stateful modern dialogue action.
     * 创建带状态反馈的现代对话操作。
     */
    private TextView action(Context context, String label, boolean primary, Runnable action) {
        TextView view = text(context, label, 14, COLOR_TEXT);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), 0, dp(14), 0);
        int normal = primary ? 0xFF267EA8 : COLOR_BUTTON;
        int hovered = primary ? 0xFF3398C7 : COLOR_BUTTON_HOVER;
        int pressed = primary ? 0xFF1B6387 : COLOR_BUTTON_PRESS;
        view.setBackground(interactiveShape(normal, hovered, pressed, 11));
        view.setClickable(true);
        view.setOnClickListener(ignored -> action.run());
        return view;
    }

    /**
     * Creates a compact circular close control distinct from dialogue choices.
     * 创建与对话选项明确区分的精致圆形关闭控件。
     */
    private TextView closeAction(Context context) {
        TextView view = text(context, "✕", 14, COLOR_MUTED);
        view.setGravity(Gravity.CENTER);
        view.setBackground(interactiveShape(
                0x0011161D,
                0xFF303946,
                0xFF161B22,
                18
        ));
        view.setClickable(true);
        view.setOnClickListener(ignored -> closeDialogue());
        return view;
    }

    /**
     * Sends the continuation action.
     * 发送继续操作。
     */
    private void continueDialogue() {
        DialogueRuntime.DialogueChoice choice = new DialogueRuntime.DialogueChoice();
        choice.nodeId = dialogue.nodeId();
        ScreenHelper.send("dialogue_next", choice);
    }

    /**
     * Sends one selected option to the authoritative server.
     * 将一个已选选项发送到权威服务端。
     */
    private void choose(int optionIndex) {
        DialogueRuntime.DialogueChoice choice = new DialogueRuntime.DialogueChoice();
        choice.nodeId = dialogue.nodeId();
        choice.optionIndex = optionIndex;
        ScreenHelper.send("dialogue_choice", choice);
    }

    /**
     * Requests authoritative dialogue closure without reentrant screen mutation.
     * 请求服务端权威关闭对话，避免在点击回调中重入切换界面。
     */
    private void closeDialogue() {
        closeRequest.run();
    }

    /**
     * Creates a basic Modern UI text view.
     * 创建基础 Modern UI 文本视图。
     */
    private TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    /**
     * Creates a rounded solid background.
     * 创建圆角纯色背景。
     */
    private ShapeDrawable shape(int color, int radiusDp, int strokeDp) {
        ShapeDrawable drawable = new ShapeDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), COLOR_BORDER);
        }
        return drawable;
    }

    /**
     * Creates a normal, hover, and pressed background set.
     * 创建普通、悬停和按下状态背景组。
     */
    private Drawable interactiveShape(int normal, int hovered, int pressed, int radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{R.attr.state_pressed}, shape(pressed, radiusDp, 1));
        states.addState(new int[]{R.attr.state_hovered}, shape(hovered, radiusDp, 1));
        states.addState(StateSet.WILD_CARD, shape(normal, radiusDp, 1));
        return states;
    }

    /**
     * Converts density-independent units to physical pixels.
     * 将密度无关单位转换为物理像素。
     */
    private int dp(int value) {
        return root != null ? root.dp(value) : Math.round(value);
    }
}
