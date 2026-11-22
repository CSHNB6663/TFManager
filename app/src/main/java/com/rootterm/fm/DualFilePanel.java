package com.rootterm.fm;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * MT 管理器风格的双栏目录视图（左右两栏）。
 *
 * <p>每个 pane 是完整的 {@link FileBrowserPanel}（自带浅色头部：路径 + 统计 + ⋮，
 * 以及白底大图标列表）。底栏是本类提供的一条全局操作栏，作用于「当前活动栏」
 * （最近点过的那一栏）：‹ 后退、› 前进、＋ 新建、⇄ 交换两栏、↑ 上级目录。</p>
 *
 * <p>「单栏 / 双栏」切换与「交换」也可以通过任一栏的 ⋮ 菜单触发（走
 * {@link FileBrowserPanel.Host} 回调）。</p>
 */
public class DualFilePanel extends LinearLayout implements FileBrowserPanel.Host {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int F_BG = 0xFFFFFFFF;
    private static final int F_PAGE = 0xFFF7F8FA;
    private static final int F_TEXT = 0xFF222222;
    private static final int F_DIM = 0xFF9AA0A6;
    private static final int F_LINE = 0xFFF0F1F3;
    private static final int F_ICON = 0xFF191919;

    /** 记住上次的模式（切换 Tab 后仍保持）。 */
    private static boolean sDual = true;

    /** 左栏默认目录：内部存储根；右栏默认目录：/ 根目录。 */
    private static final String START_LEFT = "/storage/emulated/0";
    private static final String START_RIGHT = "/";

    private final FileBrowserPanel leftPane;
    private final FileBrowserPanel rightPane;
    private final View splitter;
    private final TextView backBtn;
    private final TextView forwardBtn;
    private final TextView plusBtn;
    private final TextView swapBtn;
    private final TextView upBtn;
    private FileBrowserPanel activePane;

    public DualFilePanel(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        setBackgroundColor(F_PAGE);

        // 左右两栏
        LinearLayout paneBox = new LinearLayout(ctx);
        paneBox.setOrientation(HORIZONTAL);
        paneBox.setLayoutParams(new LinearLayout.LayoutParams(MATCH, 0, 1f));

        leftPane = new FileBrowserPanel(ctx);
        leftPane.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH, 1f));
        leftPane.setHost(this);

        splitter = new View(ctx);
        splitter.setBackgroundColor(F_LINE);
        splitter.setLayoutParams(new LinearLayout.LayoutParams(Math.max(1, dp(1.5f)), MATCH));

        rightPane = new FileBrowserPanel(ctx);
        rightPane.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH, 1f));
        rightPane.setHost(this);

        paneBox.addView(leftPane);
        paneBox.addView(splitter);
        paneBox.addView(rightPane);
        addView(paneBox);

        // 全局底栏
        backBtn = navButton(ctx, "‹");
        forwardBtn = navButton(ctx, "›");
        plusBtn = navButton(ctx, "+");
        swapBtn = navButton(ctx, "⇄");
        upBtn = navButton(ctx, "↑");

        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(0x00000000);
        bottom.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(50)));
        bottom.setPadding(dp(4), 0, dp(4), 0);
        bottom.addView(backBtn);
        bottom.addView(forwardBtn);
        bottom.addView(plusBtn);
        bottom.addView(swapBtn);
        bottom.addView(upBtn);
        addView(bottom);
        addView(createTopLine(ctx), 0);

        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activePane != null) {
                    activePane.goBack();
                }
            }
        });
        forwardBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activePane != null) {
                    activePane.goForward();
                }
            }
        });
        plusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activePane != null) {
                    // ＋ 集合了原 ⋮ 的全部功能（新建 / 排序 / 隐藏 / 搜索 / 单双栏 / 交换）
                    activePane.showHeaderMenu();
                }
            }
        });
        swapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                swapPanes();
            }
        });
        upBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activePane != null) {
                    activePane.goParent();
                }
            }
        });

        // 左栏默认打开内部存储根目录，右栏默认打开 / 根目录
        try {
            leftPane.setPath(START_LEFT);
            rightPane.setPath(START_RIGHT);
        } catch (Throwable ignored) {
        }

        activePane = leftPane;
        applyMode();
        applyActive();
    }

    /** 底栏顶部那条细线。 */
    private View createTopLine(Context ctx) {
        View line = new View(ctx);
        line.setBackgroundColor(F_LINE);
        line.setLayoutParams(new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(0.7f))));
        return line;
    }

    private TextView navButton(Context ctx, String glyph) {
        TextView tv = new TextView(ctx);
        tv.setText(glyph);
        tv.setTextSize(19f);
        tv.setTextColor(F_ICON);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setClickable(true);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH, 1f));
        return tv;
    }

    // ------------------------------------------------------------ 模式 / 活动栏

    private void applyMode() {
        rightPane.setVisibility(sDual ? VISIBLE : GONE);
        splitter.setVisibility(sDual ? VISIBLE : GONE);
        if (!sDual && activePane == rightPane) {
            activePane = leftPane;
            applyActive();
        }
    }

    private void applyActive() {
        leftPane.setActive(activePane == leftPane);
        rightPane.setActive(activePane == rightPane);
    }

    public void setDual(boolean dual) {
        boolean was = sDual;
        sDual = dual;
        if (dual && !was) {
            try {
                String p = leftPane.getPath();
                if (p != null && !p.isEmpty() && rightPane.getPath() != null
                        && "/".equals(rightPane.getPath())) {
                    rightPane.setPath(p);
                }
            } catch (Throwable ignored) {
            }
        }
        applyMode();
    }

    /** 把「跳转终端执行」回调转发给两个栏。 */
    public void setTerminalRunner(FileBrowserPanel.Runner r) {
        if (leftPane != null) {
            leftPane.setTerminalRunner(r);
        }
        if (rightPane != null) {
            rightPane.setTerminalRunner(r);
        }
    }

    public boolean isDual() {
        return sDual;
    }

    private void swapPanes() {
        try {
            String a = leftPane.getPath();
            String b = rightPane.getPath();
            if (b == null || b.isEmpty()) {
                return;
            }
            if (!sDual) {
                setDual(true);
            }
            leftPane.setPath(b);
            if (a != null && !a.isEmpty()) {
                rightPane.setPath(a);
            }
            activePane = leftPane;
            applyActive();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ Host 回调

    @Override
    public void onPaneActivated(FileBrowserPanel pane) {
        if (pane == null) {
            return;
        }
        activePane = pane;
        applyActive();
    }

    @Override
    public void onToggleDual() {
        setDual(!sDual);
    }

    @Override
    public void onSwap() {
        swapPanes();
    }

    // ------------------------------------------------------------ 对外

    /** 返回键：双栏 → 先收成单栏；单栏 → 活动栏上退目录。 */
    public boolean navigateUp() {
        if (sDual) {
            setDual(false);
            return true;
        }
        if (activePane != null) {
            return activePane.goParent();
        }
        return false;
    }

    private int dp(float v) {
        return UiConfig.dp(getContext(), v);
    }
}
