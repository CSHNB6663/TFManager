package com.rootterm.fm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.coui.widget.CouiCardView;
import com.coui.widget.CouiCheckBadge;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面（MIUIX / HyperOS 风）：
 *
 * <ul>
 *   <li>底部浮动胶囊导航：首页 · <b>目录管理（中间）</b> · 设置；</li>
 *   <li>「目录管理」页就是原来那套文件管理器（左右双栏、列表、底栏都不变）；</li>
 *   <li>终端从首页 / 设置里的「终端」打开（全屏页，左上角 ← 返回）；UI 已适配 MIUIX 深色。</li>
 * </ul>
 */
public class MainActivity extends Activity {

    private static final int TAB_HOME = 0;
    private static final int TAB_FILES = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int TAB_COUNT = 3;

    private static int C_BG = 0xFFF7F8FA;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF191919;
    private static final int C_DIM = 0xFF8A8F98;
    private static final int C_LINE = 0xFFF0F1F3;
    private static int C_ACCENT = 0xFF3482FF;
    private static final int C_OK = 0xFF1FA35B;
    private static final int C_OK_BG = 0xFFE7F7EC;
    private static int C_NAV_ACTIVE_BG = 0xFFE8F0FF;

    private FrameLayout mPages;   // 实际是 SwipeBox
    private LinearLayout mHomePage;
    private LinearLayout mSettingsPage;
    private DualFilePanel mFiles;
    private int mTab = TAB_HOME;

    private final LinearLayout[] mNavItems = new LinearLayout[TAB_COUNT];
    private final ImageView[] mNavIcons = new ImageView[TAB_COUNT];
    private final TextView[] mNavLabels = new TextView[TAB_COUNT];

    private LinearLayout mTermCard;
    private TerminalPanel mTerminal;
    private boolean mTermVisible;
    private FrameLayout mSafCard;
    private SafPanel mSaf;

    private TextView mHomeBadge;
    private CouiCardView mHomeStatusCard;
    private CouiCardView mHomeStatusBox;
    private TextView mHomeMode;
    private TextView mInfoKernelFull;
    private TextView mInfoHook;
    private TextView mInfoZygisk;
    private TextView mInfoModel;
    private TextView mInfoFingerprint;
    private TextView mInfoSelinux;
    private TextView mInfoSeccomp;
    private TextView mInfoRootImpl;
    private CouiCheckBadge mHomeCheckBadge;
    private TextView mHomeStatus;
    private TextView mHomeSub;
    private TextView mHomeStore;
    private TextView mHomeStoreSub;
    private TextView mHomeMem;
    private TextView mHomeMemSub;
    private TextView mHomeDevice;
    private TextView mHomeKernel;

    private View mNavPill;
    private FrameLayout mFilesCard;
    private GestureDetector mGesture;

    private int mInsetLeft;
    private int mInsetTop;
    private int mInsetRight;
    private int mInsetBottom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Miuix + Monet 动态取色（Android 12+ 跟随系统/壁纸）
        ThemeColors.init(this);
        C_BG = ThemeColors.SURFACE;
        C_ACCENT = ThemeColors.ACCENT;
        C_NAV_ACTIVE_BG = ThemeColors.ACCENT_CONTAINER;
        UiConfig.ACCENT = ThemeColors.ACCENT;
        UiConfig.ON_ACCENT = ThemeColors.ON_ACCENT;
        setupWindow();
        setContentView(buildRoot());
        selectTab(TAB_HOME);
        Shell.detectRootAsync(new Runnable() {
            @Override
            public void run() {
                refreshHome();
            }
        });
        registerBackCallback();
        loadDeviceInfo();
        mPages.postDelayed(new Runnable() {
            @Override
            public void run() {
                autoCheckUpdate();
            }
        }, 1500);
        loadSystemInfo();
    }

    // ------------------------------------------------------------ 窗口

    private void setupWindow() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 35) {
            w.setStatusBarColor(0x00000000);
            w.setNavigationBarColor(0x00000000);
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
            }
        } else {
            w.setStatusBarColor(C_BG);
            w.setNavigationBarColor(C_BG);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
        }
    }

    private void updateSystemBars() {
        boolean darkBg = false; // 所有页面都是浅色底
        if (Build.VERSION.SDK_INT < 30) {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            if (!darkBg && Build.VERSION.SDK_INT >= 23) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
            return;
        }
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) {
            return;
        }
        int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        c.setSystemBarsAppearance(darkBg ? 0 : mask, mask);
    }

    /** 支持横向滑动切页的容器：在子 View 之前拦截横滑手势（类似 ViewPager）。 */
    private static final class SwipeBox extends FrameLayout {
        interface Callback {
            void onSwipe(int direction);
        }

        private final Callback cb;
        private float downX;
        private float downY;
        private boolean handled;

        SwipeBox(Context ctx, Callback cb) {
            super(ctx);
            this.cb = cb;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    handled = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!handled) {
                        float dx = ev.getX() - downX;
                        float dy = ev.getY() - downY;
                        int slop = android.view.ViewConfiguration.get(getContext())
                                .getScaledTouchSlop();
                        if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                            handled = true;
                            if (cb != null) {
                                cb.onSwipe(dx < 0 ? 1 : -1);
                            }
                            return true;   // 拦截：后续事件给本容器
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handled = false;
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (handled) {
                if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    handled = false;
                }
                return true;   // 消费掉，避免子 View 再处理
            }
            return super.onTouchEvent(ev);
        }
    }

    // ------------------------------------------------------------ 根布局

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        mPages = new SwipeBox(this, new SwipeBox.Callback() {
            @Override
            public void onSwipe(int direction) {
                if (mTermVisible
                        || (mSafCard != null && mSafCard.getVisibility() == View.VISIBLE)) {
                    return;
                }
                int next = mTab + direction;
                if (next >= TAB_HOME && next <= TAB_SETTINGS) {
                    selectTab(next);
                }
            }
        });
        mPages.setBackgroundColor(C_BG);
        root.addView(mPages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mHomePage = buildHomePage();
        mSettingsPage = buildSettingsPage();
        mPages.addView(mHomePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPages.addView(mSettingsPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 全屏终端（覆盖整个页面区）
        mTermCard = buildTerminalCard();
        mTermCard.setVisibility(View.GONE);
        mPages.addView(mTermCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 免 root 目录（SAF）
        mSafCard = new FrameLayout(this);
        mSafCard.setBackgroundColor(C_BG);
        mSafCard.setVisibility(View.GONE);
        mSaf = new SafPanel(this);
        mSaf.setOnBack(new Runnable() {
            @Override
            public void run() {
                showSaf(false);
            }
        });
        mSafCard.addView(mSaf, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPages.addView(mSafCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildBottomNav());

        installInsets(root);
        return root;
    }

    // ------------------------------------------------------------ 底部导航（Miuix 居中胶囊）

    private View buildBottomNav() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0x00000000);
        wrap.setClipToPadding(false);

        CouiCardView pillCard = new CouiCardView(this);
        pillCard.setCardCornerRadiusDp(34);
        pillCard.setCardBackgroundColor(0xF7FFFFFF);
        pillCard.setCardBorderWidth(Math.max(1, dp(0.8f)));
        pillCard.setCardBorderColor(0xFFEDEFF2);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(8), dp(7), dp(8), dp(7));
        pillCard.addView(pill, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pill.addView(makeNavItem(0, "ic_nav_home", "首页", TAB_HOME));
        pill.addView(makeNavItem(1, "ic_nav_folder", "目录管理", TAB_FILES));
        pill.addView(makeNavItem(2, "ic_nav_settings", "设置", TAB_SETTINGS));

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        plp.bottomMargin = dp(12) + Math.max(mInsetBottom, dp(16));
        pillCard.setLayoutParams(plp);

        mNavPill = pillCard;
        wrap.addView(pillCard);
        installCapsuleDrag(pill);
        return wrap;
    }

    /** 胶囊可左右滑动：手指移到哪个选项就预览高亮，松手停在哪就切到哪页。 */
    private void installCapsuleDrag(final LinearLayout pill) {
        pill.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private int preview = -1;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        preview = mTab;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (Math.abs(e.getX() - downX) > dp(6)) {
                            dragging = true;
                        }
                        if (dragging) {
                            int idx = navIndexAt(e.getX());
                            if (idx >= 0 && idx != preview) {
                                preview = idx;
                                previewNavStyle(idx);
                                v.performHapticFeedback(android.view.HapticFeedbackConstants
                                        .CLOCK_TICK);
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        int idx = navIndexAt(e.getX());
                        if (idx >= 0 && idx != mTab) {
                            selectTab(idx);
                        } else {
                            restoreNavStyle();
                        }
                        preview = -1;
                        dragging = false;
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        restoreNavStyle();
                        preview = -1;
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 手指 x 落在第几个导航项上（相对胶囊坐标）；不在任何项上返回最近的。 */
    private int navIndexAt(float x) {
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < TAB_COUNT; i++) {
            LinearLayout item = mNavItems[i];
            if (item == null) {
                continue;
            }
            float left = item.getLeft();
            float right = item.getRight();
            if (x >= left && x <= right) {
                return i;
            }
            float center = (left + right) / 2f;
            float d = Math.abs(x - center);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private void previewNavStyle(int index) {
        for (int i = 0; i < TAB_COUNT; i++) {
            styleNavItem(i, i == index);
        }
    }

    private void restoreNavStyle() {
        for (int i = 0; i < TAB_COUNT; i++) {
            styleNavItem(i, i == mTab);
        }
    }

    private View makeNavItem(final int index, String iconName, String label, final int tab) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(14), dp(6), dp(14), dp(6));
        item.setClickable(true);
        item.setMinimumWidth(dp(74));

        ImageView icon = new ImageView(this);
        int resId = getResources().getIdentifier(iconName, "drawable", getPackageName());
        if (resId != 0) {
            icon.setImageResource(resId);
        }
        item.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView tv = UiConfig.text(this, label, 10.5f, C_DIM);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(3);
        item.addView(tv, tlp);

        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(tab);
            }
        });

        mNavItems[index] = item;
        mNavIcons[index] = icon;
        mNavLabels[index] = tv;
        return item;
    }

    private void styleNavItem(int index, boolean active) {
        LinearLayout item = mNavItems[index];
        ImageView icon = mNavIcons[index];
        TextView label = mNavLabels[index];
        if (item == null) {
            return;
        }
        int color = active ? C_ACCENT : 0xFF3A3F45;
        if (active) {
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(C_NAV_ACTIVE_BG);
            g.setCornerRadius(dp(22));
            item.setBackground(g);
        } else {
            item.setBackground(null);
        }
        if (icon != null) {
            icon.setColorFilter(color);
        }
        if (label != null) {
            label.setTextColor(color);
            label.setTypeface(null, active ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }
    }

    /** inset 变化时更新胶囊底部间距（避免被手势条裁掉）。 */
    private void updateNavBarInsets() {
        if (mNavPill == null) {
            return;
        }
        ViewGroup.LayoutParams lp = mNavPill.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) lp;
            p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            p.bottomMargin = dp(12) + Math.max(mInsetBottom, dp(16));
            mNavPill.setLayoutParams(p);
        }
    }

    // ------------------------------------------------------------ 页面切换

    private void selectTab(int tab) {
        if (tab == TAB_FILES) {
            ensureFiles();
        }
        mTab = tab;
        mHomePage.setVisibility(tab == TAB_HOME ? View.VISIBLE : View.GONE);
        mSettingsPage.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        if (mFilesCard != null) {
            mFilesCard.setVisibility(tab == TAB_FILES ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < TAB_COUNT; i++) {
            styleNavItem(i, i == tab);
        }
        // 切换页面淡入 + 轻微上浮
        View shown = tab == TAB_HOME ? mHomePage
                : (tab == TAB_FILES ? mFilesCard : mSettingsPage);
        if (shown != null) {
            shown.setAlpha(0.92f);
            shown.setTranslationY(dp(8));
            final View pageView = shown;
            shown.animate().alpha(1f).translationY(0f).setDuration(160)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            pageView.setAlpha(1f);
                            pageView.setTranslationY(0f);
                        }
                    }).start();
        }
        if (tab == TAB_HOME) {
            refreshHome();
        }
    }

    private void ensureFiles() {
        if (mFiles != null) {
            return;
        }
        mFiles = new DualFilePanel(this);
        mFiles.setTerminalRunner(new FileBrowserPanel.Runner() {
            @Override
            public void runInTerminal(String cmd) {
                showTerminal(true);
                if (mTerminal != null) {
                    mTerminal.runExternalCommand(cmd);
                }
            }
        });
        mFiles.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 文件管理：白底 + 四角圆形卡片（无阴影）
        mFilesCard = new FrameLayout(this);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xFFFFFFFF);
        card.setCornerRadius(dp(22));
        mFilesCard.setBackground(card);
        mFilesCard.setClipToOutline(true);
        mFilesCard.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(22));
            }
        });
        mFilesCard.addView(mFiles);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(10), dp(8), dp(10), dp(8));
        mPages.addView(mFilesCard, lp);

        // 终端 / SAF 覆盖层保持最上层
        mPages.bringChildToFront(mSafCard);
        mPages.bringChildToFront(mTermCard);
    }

    // ------------------------------------------------------------ 首页

    private LinearLayout buildHomePage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(C_BG);
        page.setPadding(dp(20), dp(18), dp(20), dp(18));

        // 头像 + 应用名
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(buildAvatar(dp(46)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(12);
        TextView name = UiConfig.text(this, "TF管理终端", 30f, C_TEXT);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        TextView sub = UiConfig.text(this, "文件管理终端 · root shell", 11.5f, C_DIM);
        sub.setPadding(0, dp(2), 0, 0);
        names.addView(name);
        names.addView(sub);
        head.addView(names, nlp);
        page.addView(head);

        // 状态卡：左右两个方块（左 = root 状态方块，右 = 终端方块）
        CouiCardView statusCard = new CouiCardView(this);
        statusCard.setCardCornerRadiusDp(20);
        statusCard.setCardBackgroundColor(C_CARD);
        statusCard.setCardBorderWidth(dp(0.8f));
        statusCard.setCardBorderColor(C_LINE);
        mHomeStatusCard = statusCard;

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusCard.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 左方块：工作中（绿底 + 大对勾） ----------
        CouiCardView leftBox = new CouiCardView(this);
        leftBox.setCardCornerRadiusDp(16);
        leftBox.setCardBackgroundColor(C_OK_BG);
        leftBox.setCardBorderWidth(0);
        mHomeStatusBox = leftBox;

        mHomeCheckBadge = new CouiCheckBadge(this);
        mHomeCheckBadge.setBadgeColor(0xFF34C759);
        mHomeCheckBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(112), dp(112));
        blp.gravity = Gravity.END | Gravity.BOTTOM;
        blp.rightMargin = -dp(18);
        blp.bottomMargin = -dp(16);
        leftBox.addView(mHomeCheckBadge, blp);

        LinearLayout leftInner = new LinearLayout(this);
        leftInner.setOrientation(LinearLayout.VERTICAL);
        leftInner.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        leftInner.setPadding(dp(14), dp(14), dp(14), dp(14));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        llp.gravity = Gravity.CENTER;
        leftBox.addView(leftInner, llp);

        // 三行文字左对齐（仿 KernelSU：工作中 / <LKM> / 版本）
        mHomeBadge = null;

        mHomeStatus = UiConfig.text(this, "正在探测", 17.5f, C_TEXT);
        mHomeStatus.setTypeface(mHomeStatus.getTypeface(), android.graphics.Typeface.BOLD);
        mHomeStatus.setGravity(Gravity.START);
        leftInner.addView(mHomeStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mHomeMode = UiConfig.text(this, "<LKM>", 15f, C_TEXT);
        mHomeMode.setTypeface(mHomeMode.getTypeface(), android.graphics.Typeface.BOLD);
        mHomeMode.setGravity(Gravity.START);
        LinearLayout.LayoutParams hmp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hmp.topMargin = dp(3);
        leftInner.addView(mHomeMode, hmp);

        mHomeSub = UiConfig.text(this, "", 11f, 0xFF5E6A60);
        mHomeSub.setGravity(Gravity.START);
        LinearLayout.LayoutParams hsb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsb.topMargin = dp(3);
        leftInner.addView(mHomeSub, hsb);

        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                0, dp(150));
        leftParams.weight = 1f;
        status.addView(leftBox, leftParams);

        // ---------- 右方块：终端 ----------
        CouiCardView rightBox = new CouiCardView(this);
        rightBox.setCardCornerRadiusDp(16);
        rightBox.setCardBackgroundColor(C_ACCENT);
        rightBox.setCardBorderWidth(0);
        rightBox.setClickable(true);
        rightBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTerminal(true);
            }
        });

        LinearLayout rightInner = new LinearLayout(this);
        rightInner.setOrientation(LinearLayout.VERTICAL);
        rightInner.setGravity(Gravity.CENTER);
        rightInner.setPadding(dp(10), dp(10), dp(10), dp(10));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.gravity = Gravity.CENTER;
        rightBox.addView(rightInner, rlp);

        ImageView termIcon = new ImageView(this);
        int termRes = getResources().getIdentifier("ic_nav_terminal", "drawable", getPackageName());
        if (termRes != 0) {
            termIcon.setImageResource(termRes);
            termIcon.setColorFilter(ThemeColors.ON_ACCENT);
        }
        rightInner.addView(termIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView termLabel = UiConfig.text(this, "终端", 15f, ThemeColors.ON_ACCENT);
        termLabel.setTypeface(termLabel.getTypeface(), android.graphics.Typeface.BOLD);
        termLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        rightInner.addView(termLabel, tlp);

        TextView termSub = UiConfig.text(this, "打开", 11f, ThemeColors.ON_ACCENT);
        termSub.setAlpha(0.85f);
        termSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsp.topMargin = dp(2);
        rightInner.addView(termSub, tsp);

        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                0, dp(150));
        rightParams.weight = 1f;
        rightParams.leftMargin = dp(12);
        status.addView(rightBox, rightParams);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(18);
        page.addView(statusCard, slp);

        // 两张小卡：总存储 / 运行内存
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(12);
        page.addView(cards, clp);

        LinearLayout storeCard = makeMetricCard("总存储", "…");
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c1.rightMargin = dp(6);
        cards.addView(storeCard, c1);
        mHomeStore = (TextView) storeCard.getChildAt(0);
        mHomeStoreSub = (TextView) storeCard.getChildAt(1);

        LinearLayout memCard = makeMetricCard("运行内存", "…");
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c2.leftMargin = dp(6);
        cards.addView(memCard, c2);
        mHomeMem = (TextView) memCard.getChildAt(0);
        mHomeMemSub = (TextView) memCard.getChildAt(1);

        // 设备信息卡：设备名 / 内核版本 / 系统版本（coui 卡片组件）
        CouiCardView devCardView = new CouiCardView(this);
        devCardView.setCardCornerRadiusDp(20);
        devCardView.setCardBackgroundColor(C_CARD);
        devCardView.setCardBorderColor(C_LINE);
        LinearLayout devCard = new LinearLayout(this);
        devCard.setOrientation(LinearLayout.VERTICAL);
        devCardView.addView(devCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(12);
        page.addView(devCardView, dlp);

        // 管理器版本（本 App）
        makeInfoRow(devCard, "管理器版本",
                "v" + AppVersion.NAME + " (" + AppVersion.CODE + ")");
        mHomeKernel = makeInfoRow(devCard, "内核版本", "…");
        mInfoKernelFull = makeWrapInfoRow(devCard, "内核完整版本", "…");
        mInfoHook = makeInfoRow(devCard, "钩子类型", "…");
        mInfoZygisk = makeInfoRow(devCard, "Zygisk 实现", "…");
        mInfoModel = makeInfoRow(devCard, "设备型号", deviceModelText());
        mInfoFingerprint = makeWrapInfoRow(devCard, "系统指纹", Build.FINGERPRINT);
        mInfoSelinux = makeInfoRow(devCard, "SELinux 状态", "…");
        mInfoSeccomp = makeInfoRow(devCard, "Seccomp 状态", "…");
        mInfoRootImpl = makeInfoRow(devCard, "Root 实现", "…");
        mHomeDevice = mInfoModel;
        makeInfoRow(devCard, "系统版本", "Android " + Build.VERSION.RELEASE
                + " · SDK " + Build.VERSION.SDK_INT);

        // 操作卡
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setBackground(cardBgStroke(C_CARD, 18, C_LINE));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(14);
        page.addView(actions, alp);

        actions.addView(makeActionRow("ic_nav_terminal", "终端", "打开全屏终端", new Runnable() {
            @Override
            public void run() {
                showTerminal(true);
            }
        }));
        actions.addView(lightLine());
        actions.addView(makeActionRow("ic_nav_root", "所有文件访问权限", "授予 /sdcard 读写", new Runnable() {
            @Override
            public void run() {
                requestAllFilesAccess();
            }
        }));
        actions.addView(lightLine());
        actions.addView(makeActionRow("ic_nav_donate", "捐赠", "感谢支持，扫码赞赏", new Runnable() {
            @Override
            public void run() {
                showDonateDialog();
            }
        }));

        sv.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(C_BG);
        wrap.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    private LinearLayout makeMetricCard(String label, String value) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBgStroke(C_CARD, 18, C_LINE));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView v = UiConfig.text(this, value, 19f, C_TEXT);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        TextView l = UiConfig.text(this, label, 11f, C_DIM);
        l.setPadding(0, dp(3), 0, 0);
        card.addView(v);
        card.addView(l);
        return card;
    }

    /** 设备信息卡里的一行：左标题 + 右值。 */
    private TextView makeInfoRow(LinearLayout card, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));

        TextView t = UiConfig.text(this, label, 14f, C_TEXT);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = UiConfig.text(this, value, 12.5f, C_DIM);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(dp(150),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (card.getChildCount() > 0) {
            card.addView(lightLine());
        }
        card.addView(row);
        return v;
    }

    /** 长值行：标签在上、值在下（可换行，用于指纹 / 完整内核版本）。 */
    private TextView makeWrapInfoRow(LinearLayout card, String label, String value) {
        if (card.getChildCount() > 0) {
            card.addView(lightLine());
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        row.addView(UiConfig.text(this, label, 14f, C_TEXT));
        TextView v = UiConfig.text(this, value, 11.5f, C_DIM);
        v.setPadding(0, dp(4), 0, 0);
        v.setTextIsSelectable(true);
        row.addView(v);
        card.addView(row);
        return v;
    }

    /** 设备型号：避免「Xiaomi REDMI K80」这种重复。 */
    private static String deviceModelText() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String mf = stripBrand(Build.MANUFACTURER);
        if (model.isEmpty()) {
            return mf;
        }
        if (!mf.isEmpty() && model.toLowerCase().startsWith(mf.toLowerCase())) {
            return model;
        }
        return (mf + " " + model).trim();
    }

    private static String stripBrand(String manufacturer) {
        if (manufacturer == null) {
            return "";
        }
        String m = manufacturer.trim();
        if (m.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(m.charAt(0)) + m.substring(1);
    }

    /** 后台读取系统信息（内核 / 钩子 / Zygisk / SELinux / Seccomp / Root 实现）。 */
    private void loadSystemInfo() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String script = ""
                        + "echo kv=$(uname -r 2>/dev/null)\n"
                        + "echo kf=$(cat /proc/version 2>/dev/null)\n"
                        + "echo se=$(/system/bin/getenforce 2>/dev/null || getenforce 2>/dev/null)\n"
                        + "echo sc=$(awk '/^Seccomp:/{print $2}' /proc/self/status 2>/dev/null)\n"
                        + "echo ksv=$(sed -n 's/^version=//p' /data/adb/ksu/module.prop 2>/dev/null | head -1)\n"
                        + "echo apv=$(cat /data/adb/ap/version 2>/dev/null | head -1)\n"
                        + "echo mgk=$(/data/adb/magisk/magisk -V 2>/dev/null | head -1)\n"
                        + "echo zyn=$(sed -n 's/^name=//p' /data/adb/modules/zygisksu/module.prop 2>/dev/null | head -1)\n"
                        + "echo zyv=$(sed -n 's/^version=//p' /data/adb/modules/zygisksu/module.prop 2>/dev/null | head -1)\n"
                        + "echo hk1=$(cat /sys/module/kernelsu/parameters/hook_type 2>/dev/null)\n"
                        + "echo hk2=$(cat /proc/ksu_hook_type 2>/dev/null)\n"
                        + "echo hk3=$(grep -aom1 -E 'hook[a-z_]*=[a-z_]+' /proc/cmdline 2>/dev/null)\n"
                        + "echo ksud=$(/data/adb/ksu/bin/ksud --version 2>/dev/null | head -1)\n"
                        + "echo kmod=$(cat /sys/module/kernelsu/version 2>/dev/null)\n"
                        + "echo ksu_dir=$([ -d /data/adb/ksu ] && echo yes)\n"
                        + "echo tp=$(grep -ac 'ksu_tracepoint' /proc/kallsyms 2>/dev/null)\n"
                        + "echo kp=$(grep -ac 'ksu_kprobe' /proc/kallsyms 2>/dev/null)\n"
                        + "echo sy=$(grep -ac 'ksu_syscall' /proc/kallsyms 2>/dev/null)\n";
                String out = Shell.execSync(script, 12000);
                final java.util.HashMap<String, String> m = new java.util.HashMap<String, String>();
                if (out != null) {
                    for (String line : out.split("\n")) {
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                        }
                    }
                }
                final String kernel = m.get("kv");
                final String kernelFull = m.get("kf");
                final String se = m.get("se");
                final String sc = m.get("sc");
                final String ksv = m.get("ksv");
                final String apv = m.get("apv");
                final String mgk = m.get("mgk");
                final String zyn = m.get("zyn");
                final String zyv = m.get("zyv");
                final String hk1 = m.get("hk1");
                final String hk2 = m.get("hk2");
                final String hk3 = m.get("hk3");
                final String ksud = m.get("ksud");
                final String kmod = m.get("kmod");
                final String ksuDir = m.get("ksu_dir");
                final String tp = m.get("tp");
                final String kp = m.get("kp");
                final String sy = m.get("sy");

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (notEmpty(kernelFull) && mInfoKernelFull != null) {
                            mInfoKernelFull.setText(kernelFull);
                        } else if (notEmpty(kernel) && mInfoKernelFull != null) {
                            mInfoKernelFull.setText(kernel);
                        }

                        // 钩子类型：优先内核参数/proc 文件，其次 cmdline，最后按内核版本标记推断
                        String hook = "";
                        if (notEmpty(hk1)) {
                            hook = hk1;
                        } else if (notEmpty(hk2)) {
                            hook = hk2;
                        } else if (notEmpty(hk3)) {
                            hook = hk3;
                        } else if (count(tp) > 0 || count(sy) > 0) {
                            StringBuilder hb = new StringBuilder();
                            if (count(tp) > 0) {
                                hb.append("Tracepoint");
                            }
                            if (count(sy) > 0) {
                                if (hb.length() > 0) {
                                    hb.append(' ');
                                }
                                hb.append("Syscall Redirect");
                            }
                            hook = hb.toString();
                        } else if (count(kp) > 0) {
                            hook = "Kprobe";
                        } else if (kernel != null) {
                            String k = kernel.toLowerCase();
                            if (k.contains("kprobe")) {
                                hook = "Kprobe";
                            } else if (k.contains("tracepoint")) {
                                hook = "Tracepoint";
                            } else if (k.contains("syscall")) {
                                hook = "Syscall Redirect";
                            }
                        }
                        if (mInfoHook != null) {
                            mInfoHook.setText(hook.isEmpty() ? "未检测到" : hook);
                        }

                        // Zygisk 实现
                        String zyg = "未检测到";
                        if (notEmpty(zyn)) {
                            zyg = zyn + (notEmpty(zyv) ? " " + zyv : "");
                        }
                        if (mInfoZygisk != null) {
                            mInfoZygisk.setText(zyg);
                        }

                        // Root 实现
                        String root = "未检测到";
                        if (notEmpty(ksv)) {
                            root = "KernelSU " + ksv;
                        } else if (notEmpty(ksud) || notEmpty(kmod) || "yes".equals(ksuDir)) {
                            String v = notEmpty(ksud) ? ksud : kmod;
                            root = "KernelSU" + (notEmpty(v) ? " " + v : "");
                        } else if (notEmpty(apv)) {
                            root = "APatch " + apv;
                        } else if (notEmpty(mgk)) {
                            root = "Magisk " + mgk;
                        }
                        if (mInfoRootImpl != null) {
                            mInfoRootImpl.setText(root);
                        }
                        if (mHomeMode != null) {
                            String mode;
                            if (root.startsWith("KernelSU")) {
                                mode = "<KSU>";
                            } else if (root.startsWith("APatch")) {
                                mode = "<APATCH>";
                            } else if (root.startsWith("Magisk")) {
                                mode = "<MAGISK>";
                            } else {
                                mode = "<USER>";
                            }
                            mHomeMode.setText(mode);
                        }

                        // SELinux
                        String sel = "未知";
                        if ("Enforcing".equalsIgnoreCase(se)) {
                            sel = "强制执行";
                        } else if ("Permissive".equalsIgnoreCase(se)) {
                            sel = "宽容模式";
                        } else if ("Disabled".equalsIgnoreCase(se)) {
                            sel = "已关闭";
                        } else if (notEmpty(se)) {
                            sel = se;
                        }
                        if (mInfoSelinux != null) {
                            mInfoSelinux.setText(sel);
                        }

                        // Seccomp
                        String sec = "未知";
                        if ("0".equals(sc)) {
                            sec = "已关闭";
                        } else if ("1".equals(sc)) {
                            sec = "严格模式";
                        } else if ("2".equals(sc)) {
                            sec = "过滤模式";
                        } else if (notEmpty(sc)) {
                            sec = sc;
                        }
                        if (mInfoSeccomp != null) {
                            mInfoSeccomp.setText(sec);
                        }
                    }
                });
            }
        }, "sys-info").start();
    }

    private static int count(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty() && !"-".equals(s);
    }

    /** 后台读取：内核版本 / 总存储 / 运行内存。 */
    private void loadDeviceInfo() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String kernel = Shell.execSync("uname -r 2>/dev/null", 8000).trim();
                final String df = Shell.execSync(
                        "df -h /storage/emulated/0 2>/dev/null | tail -n 1", 8000);
                final String mem = Shell.execSync(
                        "head -n 5 /proc/meminfo 2>/dev/null", 8000);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mHomeKernel != null && kernel != null && !kernel.isEmpty()) {
                            mHomeKernel.setText(kernel.split("\n")[0]);
                        }
                        String[] cols = df == null ? new String[0] : df.trim().split("\\s+");
                        if (mHomeStore != null && cols.length >= 4) {
                            String total = cols[1];
                            String avail = cols[3];
                            mHomeStore.setText(total);
                            if (mHomeStoreSub != null) {
                                mHomeStoreSub.setText("总存储 · 可用 " + avail);
                            }
                        }
                        long totalKb = 0;
                        long availKb = 0;
                        if (mem != null) {
                            for (String line : mem.split("\n")) {
                                if (line.startsWith("MemTotal:")) {
                                    totalKb = parseKb(line);
                                } else if (line.startsWith("MemAvailable:")) {
                                    availKb = parseKb(line);
                                }
                            }
                        }
                        if (mHomeMem != null && totalKb > 0) {
                            double totalGb = totalKb / 1024.0 / 1024.0;
                            double availGb = availKb / 1024.0 / 1024.0;
                            mHomeMem.setText(String.format(java.util.Locale.US,
                                    "%.1fG", totalGb));
                            if (mHomeMemSub != null) {
                                mHomeMemSub.setText(String.format(java.util.Locale.US,
                                        "运行内存 · 可用 %.1fG", availGb));
                            }
                        }
                    }
                });
            }
        }, "home-info").start();
    }

    private static long parseKb(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            return Long.parseLong(parts[1]);
        } catch (Throwable t) {
            return 0;
        }
    }

    private View makeActionRow(String iconName, String title, String subtitle, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });

        ImageView icon = new ImageView(this);
        int resId = getResources().getIdentifier(iconName, "drawable", getPackageName());
        if (resId != 0) {
            icon.setImageResource(resId);
            icon.setColorFilter(C_TEXT);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(14);
        TextView t = UiConfig.text(this, title, 15f, C_TEXT);
        TextView st = UiConfig.text(this, subtitle, 11f, C_DIM);
        st.setPadding(0, dp(2), 0, 0);
        texts.addView(t);
        texts.addView(st);
        row.addView(texts, tlp);

        TextView chev = UiConfig.text(this, "›", 18f, 0xFFB6BBC3);
        row.addView(chev);
        return row;
    }

    // ------------------------------------------------------------ 版本更新

    /** 默认在线更新页面（夸克网盘分享）。JSON/GitHub 更新源可在设置里覆盖。 */
    private static final String DEFAULT_UPDATE_PAGE =
            "https://pan.quark.cn/s/3a946401d243";

    /** 默认自动更新源（GitHub Releases API，国内 api.github.com 可直连）。 */
    private static final String DEFAULT_UPDATE_FEED =
            "https://api.github.com/repos/CSHNB6663/TFManager/releases/latest";

    private String updateFeed() {
        String f = uiPrefs().getString("update_feed", "");
        if (f == null || f.trim().isEmpty()) {
            return DEFAULT_UPDATE_FEED;
        }
        return f.trim();
    }

    /** 是不是「网页型」更新源（网盘分享页），这种只打开浏览器。 */
    private static boolean isWebPageFeed(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        return u.contains("pan.quark.cn") || u.contains("pan.baidu.com")
                || u.contains("aliyundrive") || u.contains("lanzou");
    }

    private void openUpdatePage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Throwable t) {
            toast("无法打开浏览器");
        }
    }

    private android.content.SharedPreferences uiPrefs() {
        return getSharedPreferences("ui", Context.MODE_PRIVATE);
    }

    /** 自动检查（启动时调用）。 */
    private void autoCheckUpdate() {
        if (!uiPrefs().getBoolean("update_auto", true)) {
            return;
        }
        final String feed = updateFeed();
        if (isWebPageFeed(feed)) {
            return; // 网盘页无法比对版本，启动时不打扰，可在设置里手动打开
        }
        UpdateChecker.check(feed, new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.Result r) {
                if (r.ok && isNewer(r)) {
                    showUpdateDialog(r);
                }
            }
        });
    }

    private boolean isNewer(UpdateChecker.Result r) {
        if (r.versionCode > 0) {
            return r.versionCode > AppVersion.CODE;
        }
        return !r.versionName.equals(AppVersion.NAME);
    }

    private void manualCheckUpdate() {
        final String feed = updateFeed();
        if (isWebPageFeed(feed)) {
            // 夸克网盘等分享页：直接打开
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("在线更新页面")
                    .setMessage("当前更新源是网页/网盘分享页，无法自动比对版本。\n"
                            + "将打开浏览器查看最新版本：\n" + feed)
                    .setPositiveButton("打开页面", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            openUpdatePage(feed);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        toast("正在检查更新…");
        UpdateChecker.check(feed, new UpdateChecker.Callback() {
            @Override
            public void onResult(UpdateChecker.Result r) {
                if (!r.ok) {
                    if (r.error != null && r.error.contains("网页")) {
                        openUpdatePage(feed);
                    } else {
                        toast("检查失败：" + r.error);
                    }
                    return;
                }
                if (isNewer(r)) {
                    showUpdateDialog(r);
                } else {
                    toast("当前已是最新版本 v" + AppVersion.NAME);
                }
            }
        });
    }

    private void showUpdateDialog(final UpdateChecker.Result r) {
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 600) {
            notes = notes.substring(0, 600) + "…";
        }
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：v").append(AppVersion.NAME).append("\n");
        msg.append("最新版本：v").append(r.versionName).append("\n\n");
        if (!notes.isEmpty()) {
            msg.append("更新内容：\n").append(notes).append("\n\n");
        }
        if (!r.downloadUrl.isEmpty()) {
            msg.append("下载地址：\n").append(r.downloadUrl);
        } else {
            msg.append("（更新源未提供下载地址）");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("发现新版本 v" + r.versionName)
                .setMessage(msg.toString())
                .setPositiveButton("立即下载", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (r.downloadUrl.isEmpty()) {
                            toast("没有下载地址");
                            return;
                        }
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse(r.downloadUrl)));
                        } catch (Throwable t) {
                            toast("无法打开浏览器，请手动复制链接");
                        }
                    }
                })
                .setNeutralButton("复制链接", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                        "url", r.downloadUrl));
                            }
                            toast("已复制下载链接");
                        } catch (Throwable t) {
                            toast("复制失败");
                        }
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void promptUpdateFeed() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(updateFeed());
        et.setSingleLine(true);
        et.setHint("https://api.github.com/repos/你/仓库/releases/latest 或 JSON 清单地址");
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("更新源地址")
                .setMessage("支持 GitHub Releases API 或自定义 JSON 清单")
                .setView(et)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        uiPrefs().edit().putString("update_feed",
                                et.getText().toString().trim()).apply();
                        toast("已保存更新源");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 设置页

    private LinearLayout buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(C_BG);
        page.setPadding(dp(20), dp(22), dp(20), dp(18));

        TextView title = UiConfig.text(this, "设置", 32f, C_TEXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        page.addView(title);

        page.addView(makeSettingsCard(new String[][]{
                {"界面风格", "MIUIX（HyperOS）"},
                {"语言", "跟随系统"},
                {"主题强调色", "Monet 自动取色"},
        }));
        page.addView(makeSettingsCard(new String[][]{
                {"免 root 目录（SAF）", "选一个目录即可读写，无需权限"},
                {"所有文件访问权限", "点按去系统设置授予"},
                {"重新探测 root", "刷新 su 授权状态"},
                {"终端", "打开全屏终端"},
                {"关于 TF管理终端", "版本与使用说明"},
                {"内置 bash", "首次运行脚本时自动释放"},
        }));
        page.addView(buildUpdateCard());
        page.addView(makeSettingsCard(new String[][]{
                {"版本", "v" + AppVersion.NAME + " (targetSdk 37)"},
                {"编译", "minSdk 26 / compileSdk 36"},
        }));

        TextView foot = UiConfig.text(this, "TF管理终端 · MIUIX 风格文件管理器", 10.5f, 0xFFA0A4AB);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(18);
        page.addView(foot, flp);

        sv.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(C_BG);
        wrap.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    /** 「更新」设置卡：检查更新 / 自动检查（开关）/ 更新源地址。 */
    private View buildUpdateCard() {
        CouiCardView cardView = new CouiCardView(this);
        cardView.setCardCornerRadiusDp(20);
        cardView.setCardBackgroundColor(C_CARD);
        cardView.setCardBorderColor(C_LINE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14);
        cardView.setLayoutParams(clp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        cardView.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 检查更新
        LinearLayout row1 = updateRow(card, "检查更新", "当前 v" + AppVersion.NAME + "，点按立即检查");
        row1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualCheckUpdate();
            }
        });
        card.addView(lightLine());

        // 自动检查（开关）
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.addView(UiConfig.text(this, "自动检查更新", 15f, C_TEXT));
        TextView sub = UiConfig.text(this, "启动时自动向更新源查询", 11f, C_DIM);
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub);
        row2.addView(texts, tlp);
        final Switch sw = new Switch(this);
        sw.setChecked(uiPrefs().getBoolean("update_auto", true));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                uiPrefs().edit().putBoolean("update_auto", checked).apply();
            }
        });
        row2.addView(sw);
        row2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        card.addView(row2);
        card.addView(lightLine());

        // 更新源地址
        final TextView feedValue = UiConfig.text(this, feedLabel(), 12f, C_DIM);
        LinearLayout row3 = updateRow(card, "更新源地址", null);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.leftMargin = dp(8);
        row3.addView(feedValue, flp);
        row3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptUpdateFeed();
                feedValue.setText(feedLabel());
            }
        });
        card.addView(lightLine());
        LinearLayout row4 = updateRow(card, "在线更新页面", "夸克网盘分享（打开浏览器）");
        TextView openChev = UiConfig.text(this, "›", 18f, 0xFFB6BBC3);
        LinearLayout.LayoutParams ocp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ocp.leftMargin = dp(8);
        row4.addView(openChev, ocp);
        row4.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUpdatePage(DEFAULT_UPDATE_PAGE);
            }
        });
        card.addView(row4);
        card.addView(lightLine());

        feedValue.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                feedValue.setText(feedLabel());
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
        return cardView;
    }

    private String feedLabel() {
        String feed = updateFeed();
        if (isWebPageFeed(feed)) {
            return "夸克网盘 ›";
        }
        return "已设置 ›";
    }

    /** 一行：标题 + 副标题，右侧留空（由调用方追加）。 */
    private LinearLayout updateRow(LinearLayout parent, String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(UiConfig.text(this, title, 15f, C_TEXT));
        if (desc != null) {
            TextView d = UiConfig.text(this, desc, 11f, C_DIM);
            d.setPadding(0, dp(2), 0, 0);
            texts.addView(d);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, tlp);
        return row;
    }

    private View makeSettingsCard(String[][] rows) {
        CouiCardView cardView = new CouiCardView(this);
        cardView.setCardCornerRadiusDp(20);
        cardView.setCardBackgroundColor(C_CARD);
        cardView.setCardBorderColor(C_LINE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14);
        cardView.setLayoutParams(clp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        cardView.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < rows.length; i++) {
            final String key = rows[i][0];
            final String value = rows[i][1];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(15), dp(16), dp(15));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onSettingsRow(key);
                }
            });
            TextView t = UiConfig.text(this, key, 15f, C_TEXT);
            row.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView val = UiConfig.text(this, value, 12.5f, C_DIM);
            val.setSingleLine(true);
            row.addView(val);
            TextView chev = UiConfig.text(this, "›", 18f, 0xFFB6BBC3);
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chp.leftMargin = dp(6);
            row.addView(chev, chp);
            card.addView(row);
            if (i != rows.length - 1) {
                card.addView(lightLine());
            }
        }
        return cardView;
    }

    private void onSettingsRow(String key) {
        if ("免 root 目录（SAF）".equals(key)) {
            showSaf(true);
        } else if ("所有文件访问权限".equals(key)) {
            requestAllFilesAccess();
        } else if ("重新探测 root".equals(key)) {
            toast("正在重新探测 root…");
            Shell.detectRootAsync(new Runnable() {
                @Override
                public void run() {
                    refreshHome();
                    toast(Shell.isRoot() ? "root 权限已获取" : "仍未获取 root");
                }
            });
        } else if ("终端".equals(key)) {
            showTerminal(true);
        } else if ("关于 TF管理终端".equals(key)) {
            showAbout();
        } else {
            toast(key);
        }
    }

    // ------------------------------------------------------------ 终端（全屏）

    private LinearLayout buildTerminalCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFFFFFFFF);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFFF7F8FA);
        header.setPadding(dp(6), 0, dp(12), 0);

        // 左上角返回键（MIUIX 风格）—— 代替原来的右上 ✕
        ImageView back = new ImageView(this);
        int backRes = getResources().getIdentifier("ic_nav_back", "drawable", getPackageName());
        if (backRes != 0) {
            back.setImageResource(backRes);
            back.setColorFilter(0xFF191919);
        }
        back.setClickable(true);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTerminal(false);
            }
        });

        TextView title = UiConfig.text(this, "终端", 17f, 0xFF191919);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(6);
        header.addView(title, tlp);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        View line = new View(this);
        line.setBackgroundColor(0xFFEDEFF2);

        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        card.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f))));

        mTerminal = new TerminalPanel(this);
        mTerminal.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        card.addView(mTerminal);
        return card;
    }

    private void showSaf(boolean show) {
        mSaf.setVisibility(show ? View.VISIBLE : View.GONE);
        mSafCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            mSafCard.bringToFront();
            mSaf.bringToFront();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mSaf != null) {
            mSaf.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void showTerminal(boolean show) {
        mTermVisible = show;
        mTermCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            mTermCard.requestFocus();
            mTerminal.onPanelShown();
        }
        updateSystemBars();
    }

    // ------------------------------------------------------------ 首页状态刷新

    private void refreshHome() {
        boolean root = Shell.isRoot();
        if (mHomeBadge != null) {
            mHomeBadge.setText(root ? "ROOT" : "USER");
            mHomeBadge.setTextColor(root ? C_OK : 0xFF7A7A7A);
            GradientDrawable g = new GradientDrawable();
            g.setColor(root ? 0x221FA35B : 0xFFEDEFF2);
            g.setCornerRadius(dp(8));
            mHomeBadge.setBackground(g);
        }
        if (mHomeStatus != null) {
            mHomeStatus.setText(root ? "工作中" : "未获取 root");
        }
        if (mHomeStatusCard != null) {
            mHomeStatusCard.setCardBackgroundColor(C_CARD);
            mHomeStatusCard.setCardBorderColor(C_LINE);
        }
        // 左边状态方块：root 时绿底，否则浅灰底
        if (mHomeStatusBox != null) {
            mHomeStatusBox.setCardBackgroundColor(root ? C_OK_BG : 0xFFF2F3F5);
        }
        if (mHomeCheckBadge != null) {
            mHomeCheckBadge.setVisibility(root ? View.VISIBLE : View.GONE);
        }
        if (mHomeSub != null) {
            mHomeSub.setText("版本: " + AppVersion.NAME);
        }
    }

    private void loadStorageInfo() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = Shell.execSync("df -h /storage/emulated/0 2>/dev/null | tail -n 1", 8000);
                String avail = parseDfAvail(out);
                final String text = avail.isEmpty() ? "—" : avail;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mHomeStore != null) {
                            mHomeStore.setText(text);
                        }
                    }
                });
            }
        }, "home-df").start();
    }

    private static String parseDfAvail(String out) {
        if (out == null || out.trim().isEmpty()) {
            return "";
        }
        String[] parts = out.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].endsWith("G") || parts[i].endsWith("M") || parts[i].endsWith("K")) {
                if (i + 1 < parts.length) {
                    String used = parts[i];
                    String avail = parts[i + 1];
                    return avail + " 可用";
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------ 返回键

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            goBack();
                        }
                    });
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    private void goBack() {
        if (mSafCard != null && mSafCard.getVisibility() == View.VISIBLE) {
            if (mSaf != null && mSaf.goUp()) {
                return;
            }
            showSaf(false);
            return;
        }
        if (mTermVisible) {
            showTerminal(false);
            return;
        }
        if (mTab == TAB_FILES) {
            if (mFiles != null && mFiles.navigateUp()) {
                return;
            }
            selectTab(TAB_HOME);
            return;
        }
        if (mTab != TAB_HOME) {
            selectTab(TAB_HOME);
            return;
        }
        finish();
    }

    // ------------------------------------------------------------ insets

    private void installInsets(View root) {
        if (Build.VERSION.SDK_INT < 35) {
            root.setPadding(0, 0, 0, 0);
            return;
        }
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
                    Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    mInsetLeft = bars.left;
                    mInsetTop = bars.top;
                    mInsetRight = bars.right;
                    mInsetBottom = Math.max(bars.bottom, ime.bottom);
                }
                if (mInsetBottom == 0) {
                    mInsetBottom = dp(24);   // 兜底：个别 ROM 手势条 inset 报 0
                }
                v.setPadding(mInsetLeft, mInsetTop, mInsetRight, 0);
                updateNavBarInsets();
                return insets;
            }
        });
    }

    // ------------------------------------------------------------ 头像 / 权限 / 关于

    private ImageView buildAvatar(int sizePx) {
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(sizePx, sizePx));
        iv.setContentDescription("头像");
        try {
            int resId = getResources().getIdentifier("avatar", "drawable", getPackageName());
            if (resId != 0) {
                Bitmap src = BitmapFactory.decodeResource(getResources(), resId);
                if (src != null) {
                    iv.setImageBitmap(circleCrop(src, sizePx));
                }
            }
        } catch (Throwable ignored) {
        }
        return iv;
    }

    private static Bitmap circleCrop(Bitmap src, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        float scale = Math.max(size / (float) src.getWidth(), size / (float) src.getHeight());
        m.setScale(scale, scale);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return out;
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                toast("已拥有「所有文件访问」权限");
                return;
            }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                toast("请打开「允许访问所有文件」后返回");
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Throwable t2) {
                    toast("无法打开权限设置，请到系统设置手动授权");
                }
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                toast("存储权限已授予");
                return;
            }
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
        }
    }

    /** 捐赠弹窗：感谢语 + 赞赏码。 */
    private void showDonateDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        TextView thanks = UiConfig.text(this, "感谢您支持本项目，我将努力开发！", 14.5f, 0xFF191919);
        thanks.setGravity(Gravity.CENTER);
        thanks.setPadding(0, dp(6), 0, dp(12));
        box.addView(thanks);

        ImageView qr = new ImageView(this);
        int resId = getResources().getIdentifier("donate", "drawable", getPackageName());
        if (resId != 0) {
            qr.setImageResource(resId);
        }
        qr.setAdjustViewBounds(true);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(qr, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tip = UiConfig.text(this, "「TF管理终端」的赞赏码", 11.5f, 0xFF8A8F98);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(10), 0, dp(2));
        box.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFFFFFFF);
        sv.addView(box);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showAbout() {
        String msg = "TF管理终端 v" + AppVersion.NAME + "\n\n"
                + "· 底部导航：首页 · 目录管理 · 设置\n"
                + "· 目录管理：左右双栏、浅色 MIUIX 列表、‹ › ＋ ⇄ ↑ 底栏\n"
                + "· 终端：首页/设置里「终端」打开，全屏，左上角 ← 返回\n"
                + "· 图标：MDI（Apache-2.0）矢量字形 + MIUIX 色块\n"
                + "· 编译目标：minSdk 26（Android 8.0），targetSdk 37（Android 17）\n"
                + "· 纯本地：不联网、不收集数据、无第三方 SDK\n\n"
                + "警告：root 权限下执行命令/删除文件不可撤销，请确认后再操作。\n"
                + "本 App 不包含 root，需设备自行 root（Magisk / KernelSU）。";
        new AlertDialog.Builder(this)
                .setTitle("关于 TF管理终端")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ------------------------------------------------------------ 小工具

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return UiConfig.dp(this, v);
    }

    private View lightLine() {
        View v = new View(this);
        v.setBackgroundColor(C_LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f))));
        return v;
    }

    private GradientDrawable roundBg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(UiConfig.dp(this, radiusDp));
        return g;
    }

    private GradientDrawable cardBgStroke(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(UiConfig.dp(this, radiusDp));
        g.setStroke(Math.max(1, dp(0.8f)), stroke);
        return g;
    }
}
