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
import java.io.File;

import com.miuix.glass.MiuixGlass;
import com.miuix.icons.MiuixIcons;
import com.miuix.glassicons.MiuixGlassIcons;
import com.miuix.nav.MiuixNavBar;
import com.miuix.pref.MiuixPreference;
import com.miuix.squircle.Squircle;
import com.miuix.ui.MiuixCard;
import com.miuix.ui.MiuixTheme;
import com.miuix.window.MiuixWindow;
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
    private static final int TAB_PART = 2;
    private static final int TAB_SETTINGS = 3;
    private static final int TAB_COUNT = 4;

    private static int C_BG = 0xFFF7F8FA;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF191919;
    private static final int C_DIM = 0xFF8A8F98;
    private static final int C_LINE = 0xFFF0F1F3;
    private static int C_ACCENT = 0xFF3482FF;
    private static final int C_OK = 0xFF1FA35B;
    private static final int C_OK_BG = 0xFFE7F7EC;
    private static int C_NAV_ACTIVE_BG = 0xFFE8F0FF;

    private SwipeBox mPages;   // 实际是 SwipeBox
    private LinearLayout mHomePage;
    private LinearLayout mSettingsPage;
    private DualFilePanel mFiles;
    private int mTab = TAB_HOME;

    private MiuixNavBar mMiuixNav;
    private View mNavContainer;
    private PartitionPanel mPartPanel;
    private android.content.BroadcastReceiver mUsbReceiver;
    private boolean mForceUpdateShown;
    private long mLastUpdateCheck;
    private FrameLayout mFanqieCard;
    private FanqieWebLogin mFanqieLogin;
    private FrameLayout mPartCard;

    private LinearLayout mTermCard;
    private TerminalPanel mTerminal;
    private boolean mTermVisible;
    private SafPanel mSaf;
    private android.app.Dialog mSafDialog;

    private TextView mHomeBadge;
    private MiuixCard mHomeStatusCard;
    private MiuixGlass mHomeStatusBox;
    private TextView mHomeMode;
    private TextView mInfoKernelFull;
    private TextView mInfoModel;
    private TextView mInfoFingerprint;
    private TextView mInfoSeccomp;
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
        // 日志与崩溃捕获：尽早初始化
        AppLog.init(this);
        // 云端配置：先读缓存（极快），再后台刷新
        RemoteConfig.loadCache(this);
        FanqieFontMap.setOverride(RemoteConfig.fontMap());
        // 固定玻璃配色（已移除 Monet 动态取色）
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
        // 安装包签名校验（防重打包）
        String signErr = SignCheck.verify(this);
        if (signErr != null) {
            MiuixWindow.builder(this)
                    .title("无法启动")
                    .message("安装包校验失败：" + signErr
                            + "\n\n请从官方渠道重新安装。")
                    .cancelable(false)
                    .positive("退出", new MiuixWindow.OnAction() {
                        @Override
                        public void onClick(android.app.Dialog d) {
                            finish();
                        }
                    })
                    .show();
            return;
        }
        loadDeviceInfo();
        mLastUpdateCheck = System.currentTimeMillis();   // 标记已检查，避免 onResume 重复请求
        mPages.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    autoCheckUpdate();
                } catch (Throwable ignored) {
                }
            }
        }, 1500);
        loadSystemInfo();
        mPages.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshNavBackdrop();
            }
        }, 700);
        try {
            registerUsbReceiver();
        } catch (Throwable ignored) {
        }
        // 通知权限（API 33+）与状态栏常驻
        try {
            requestNotifyPermission();
        } catch (Throwable ignored) {
        }
        // 延迟拉起常驻服务，避免首次启动时前台服务被系统拒绝导致崩溃
        mPages.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    NotifyService.startIfEnabled(MainActivity.this);
                } catch (Throwable ignored) {
                }
            }
        }, 900);
        // 后台刷新云端配置（热更新）
        RemoteConfig.refresh(this, new RemoteConfig.Callback() {
            @Override
            public void onDone(boolean changed) {
                AppLog.log("CFG", "remote config refreshed, changed=" + changed);
                FanqieFontMap.setOverride(RemoteConfig.fontMap());
                if (changed) {
                    maybeShowNotice();
                }
            }
        });

        // 启动自动播放背景音乐
        mPages.postDelayed(new Runnable() {
            @Override
            public void run() {
                autoPlayBgm();
            }
        }, 1200);
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
        /** false 时完全不拦截触摸（WebView 内的滑块/拖动需要原始事件）。 */
        private boolean interceptEnabled = true;

        void setInterceptEnabled(boolean enabled) {
            interceptEnabled = enabled;
        }

        SwipeBox(Context ctx, Callback cb) {
            super(ctx);
            this.cb = cb;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (!interceptEnabled) {
                return false;
            }
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
        // 用 FrameLayout：页面铺满整屏，底栏浮在上面，
        // 页面与底栏之间不存在「交界」，也就不会有分割线。
        FrameLayout root = new FrameLayout(this);
        root.setBackground(ThemeColors.pageBackground());  // 唯一的渐变层

        mPages = new SwipeBox(this, new SwipeBox.Callback() {
            @Override
            public void onSwipe(int direction) {
                if (mTermVisible
                        || (mSafDialog != null && mSafDialog.isShowing())) {
                    return;
                }
                int next = mTab + direction;
                if (next >= TAB_HOME && next <= TAB_SETTINGS) {
                    selectTab(next);
                }
            }
        });
        mPages.setBackgroundColor(0x00000000);
        mPages.setClipToPadding(false);
        root.addView(mPages, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mHomePage = buildHomePage();
        mSettingsPage = buildSettingsPage();

        // 分区页
        mPartCard = new FrameLayout(this);
        mPartCard.setBackgroundColor(0x00000000);
        mPartCard.setVisibility(View.GONE);
        mPartPanel = new PartitionPanel(this);
        mPartCard.addView(mPartPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPages.addView(mPartCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mPages.addView(mHomePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPages.addView(mSettingsPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 全屏终端（覆盖整个页面区）
        mTermCard = buildTerminalCard();
        mTermCard.setVisibility(View.GONE);
        // 键盘弹出时给终端加底部内边距，让输入框浮在键盘上方
        mTermCard.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int ime = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                } else {
                    ime = insets.getSystemWindowInsetBottom();
                }
                if (mTerminal != null) {
                    mTerminal.onImeVisible(ime > 0);
                }
                int target = mTermVisible ? ime : 0;
                if (v.getPaddingBottom() != target) {
                    v.setPadding(0, 0, 0, target);
                    if (mTerminal != null) {
                        mTerminal.onImeChanged(target > 0);
                    }
                }
                return insets;
            }
        });
        mPages.addView(mTermCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 免 root 目录（SAF）：改为白色弹窗，见 showSaf()

        // 番茄登录浮层（挂在 mPages 之外，避免切页手势抢走滑块拖动）
        mFanqieCard = new FrameLayout(this);
        mFanqieCard.setBackgroundColor(0xFFFFFFFF);
        mFanqieCard.setVisibility(View.GONE);

        mNavContainer = buildBottomNav();   // 高度与 gravity 由 buildBottomNav 自己设定
        root.addView(mNavContainer);
        // miuix-nav 液态玻璃：持续以页面内容为磨砂底
        if (mMiuixNav != null) {
            mMiuixNav.autoBackdrop(mPages);
        }

        // 番茄登录浮层放最上层（不属于 SwipeBox，手势不被拦截）
        if (mFanqieCard != null) {
            root.addView(mFanqieCard, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        installInsets(root);
        return root;
    }

    /** 注册 USB 权限结果广播。 */
    private void registerUsbReceiver() {
        mUsbReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, Intent intent) {
                if (intent == null) {
                    return;
                }
                String act = intent.getAction();
                if (!com.rootterm.fm.fastboot.FastbootHost.ACTION_PERM.equals(act)) {
                    return;
                }
                boolean granted = intent.getBooleanExtra(
                        android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (mPartPanel != null) {
                    mPartPanel.onUsbPermission(granted);
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(com.rootterm.fm.fastboot.FastbootHost.ACTION_PERM);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mUsbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mUsbReceiver, f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.log("LIFE", "onResume");
        try {
            // 回到前台再检查一次（10 分钟节流），已弹过强制更新则不重复
            if (mForceUpdateShown || mPages == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - mLastUpdateCheck < 10 * 60 * 1000L) {
                return;
            }
            mLastUpdateCheck = now;
            mPages.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        autoCheckUpdate();
                    } catch (Throwable ignored) {
                    }
                }
            }, 800);
        } catch (Throwable t) {
            AppLog.log("LIFE", "onResume 异常", t);
        }
    }

    @Override
    protected void onDestroy() {
        AppLog.log("LIFE", "onDestroy");
        super.onDestroy();
        BgmPlayer.stop(this);
        try {
            if (mUsbReceiver != null) {
                unregisterReceiver(mUsbReceiver);
                mUsbReceiver = null;
            }
        } catch (Throwable ignored) {
        }
        if (mPartPanel != null) {
            mPartPanel.release();
        }
    }

    // ------------------------------------------------------------ 底部导航（miuix-nav 玻璃胶囊）

    private View buildBottomNav() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0x00000000);
        wrap.setClipToPadding(false);
        // 容器：底部留出胶囊高度 + 渐隐区
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120));
        wlp.gravity = Gravity.BOTTOM;
        wrap.setLayoutParams(wlp);

        // 底部渐隐遮罩：从完全透明渐变到页面底色（底部不透明 → 与背景无缝）
        View scrim = new View(this);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x00E6EDF9, 0x66E6EDF9, 0xD9E6EDF9, 0xFFE6EDF9});
        scrim.setBackground(gd);
        scrim.setClickable(false);
        scrim.setFocusable(false);
        wrap.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mMiuixNav = new MiuixNavBar(this);
        mMiuixNav.addItem("首页", resId("ic_nav_home"));
        mMiuixNav.addItem("目录管理", resId("ic_nav_folder"));
        mMiuixNav.addItem("分区", resId("ic_nav_root"));
        mMiuixNav.addItem("设置", resId("ic_nav_settings"));
        mMiuixNav.listener(new MiuixNavBar.Listener() {
            @Override
            public void onSelect(int index) {
                selectTab(index);
            }
        });
        mMiuixNav.colors(C_NAV_ACTIVE_BG, C_ACCENT, 0xFF3A3F45);
        mMiuixNav.installDrag();

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        plp.bottomMargin = dp(12) + Math.max(mInsetBottom, dp(16));
        mMiuixNav.setLayoutParams(plp);

        mNavPill = mMiuixNav;
        wrap.addView(mMiuixNav);
        return wrap;
    }

    private int resId(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    /** 刷新导航玻璃的磨砂底（抓取当前页面内容）。 */
    private void refreshNavBackdrop() {
        if (mMiuixNav == null || mPages == null) {
            return;
        }
        mPages.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mMiuixNav != null && mPages != null) {
                    mMiuixNav.autoBackdrop(mPages);
                }
            }
        }, 260);
    }

    /** inset 变化时更新胶囊底部间距。 */
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
        AppLog.log("NAV", "selectTab " + tab);
        try {
        if (tab == TAB_FILES) {
            ensureFiles();
        }
        if (tab == TAB_PART && mPartPanel != null) {
            mPartPanel.loadPartitions(false);
        }
        mTab = tab;
        mHomePage.setVisibility(tab == TAB_HOME ? View.VISIBLE : View.GONE);
        mSettingsPage.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        if (mPartCard != null) {
            mPartCard.setVisibility(tab == TAB_PART ? View.VISIBLE : View.GONE);
        }
        if (mFilesCard != null) {
            mFilesCard.setVisibility(tab == TAB_FILES ? View.VISIBLE : View.GONE);
        }
        if (mMiuixNav != null && mMiuixNav.selected() != tab) {
            mMiuixNav.select(tab, false);
        }
        refreshNavBackdrop();
        // 切换页面淡入 + 轻微上浮
        View shown = tab == TAB_HOME ? mHomePage
                : (tab == TAB_FILES ? mFilesCard
                : (tab == TAB_PART ? mPartCard : mSettingsPage));
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
        } catch (Throwable t) {
            AppLog.log("NAV", "selectTab 异常", t);
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

        // 文件管理：透明容器（背景由根渐变提供，避免硬边）
        mFilesCard = new FrameLayout(this);
        mFilesCard.setBackgroundColor(0x00000000);
        // 底栏浮在上面，文件页底部留出空间
        mFilesCard.setPadding(0, 0, 0, dp(112));
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
        mPages.bringChildToFront(mTermCard);
    }

    // ------------------------------------------------------------ 首页

    private LinearLayout buildHomePage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalFadingEdgeEnabled(false);
        sv.setBackgroundColor(0x00000000);
        sv.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0x00000000);
        page.setPadding(dp(20), dp(18), dp(20), dp(120));

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
        TextView name = UiConfig.text(this, "酷安@云中语", 26f, C_TEXT);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        TextView sub = UiConfig.text(this, "TF管理终端 · 文件管理 · root shell", 11.5f, C_DIM);
        sub.setPadding(0, dp(2), 0, 0);
        names.addView(name);
        names.addView(sub);
        head.addView(names, nlp);

        // 右上角长条按钮：番茄小说下载
        head.addView(buildNovelStrip());
        page.addView(head);

        // 状态卡：左右两个方块（左 = root 状态方块，右 = 终端方块）
        MiuixCard statusCard = new MiuixCard(this);
        statusCard.radiusDp(MiuixTheme.RADIUS_CARD);
        statusCard.fill(MiuixTheme.surface());
        statusCard.stroke(MiuixTheme.outline(), 0.8f);
        mHomeStatusCard = statusCard;

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusCard.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 左方块：工作中（绿底 + 大对勾） ----------
        MiuixGlass leftBox = new MiuixGlass(this);
        leftBox.radiusDp(MiuixTheme.RADIUS_MEDIUM);
        leftBox.tint(0xFFE9F8EE, 0xFFDFF3E6);
        leftBox.rim(0x33FFFFFF, 0x0D1FA35B);
        leftBox.specular(false);
        mHomeStatusBox = leftBox;

        mHomeCheckBadge = new CouiCheckBadge(this);
        mHomeCheckBadge.setBadgeColor(0xFF34C759);
        mHomeCheckBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(84), dp(84));
        blp.gravity = Gravity.END | Gravity.BOTTOM;
        blp.rightMargin = dp(4);
        blp.bottomMargin = dp(4);
        leftBox.addView(mHomeCheckBadge, blp);

        LinearLayout leftInner = new LinearLayout(this);
        leftInner.setOrientation(LinearLayout.VERTICAL);
        leftInner.setGravity(Gravity.TOP | Gravity.START);
        leftInner.setPadding(dp(14), dp(12), dp(14), dp(10));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        llp.gravity = Gravity.TOP | Gravity.START;
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
        hmp.topMargin = dp(2);
        leftInner.addView(mHomeMode, hmp);

        mHomeSub = UiConfig.text(this, "", 11f, 0xFF5E6A60);
        mHomeSub.setGravity(Gravity.START);
        LinearLayout.LayoutParams hsb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsb.topMargin = dp(2);
        leftInner.addView(mHomeSub, hsb);

        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                0, dp(150));
        leftParams.weight = 1f;
        status.addView(leftBox, leftParams);

        // ---------- 右方块：终端 ----------
        MiuixGlass rightBox = new MiuixGlass(this);
        rightBox.radiusDp(MiuixTheme.RADIUS_MEDIUM);
        rightBox.accentTint();
        rightBox.specular(false);
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
        cards.addView((View) storeCard.getTag(), c1);
        mHomeStore = (TextView) storeCard.getChildAt(0);
        mHomeStoreSub = (TextView) storeCard.getChildAt(1);

        LinearLayout memCard = makeMetricCard("运行内存", "…");
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c2.leftMargin = dp(6);
        cards.addView((View) memCard.getTag(), c2);
        mHomeMem = (TextView) memCard.getChildAt(0);
        mHomeMemSub = (TextView) memCard.getChildAt(1);

        // 设备信息卡：设备名 / 内核版本 / 系统版本（coui 卡片组件）
        MiuixCard devCardView = new MiuixCard(this);
        devCardView.radiusDp(MiuixTheme.RADIUS_CARD);
        devCardView.fill(MiuixTheme.surface());
        devCardView.stroke(MiuixTheme.outline(), 0.8f);
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
        mInfoModel = makeInfoRow(devCard, "设备型号", deviceModelText());
        mInfoFingerprint = makeWrapInfoRow(devCard, "系统指纹", Build.FINGERPRINT);
        mInfoSeccomp = makeInfoRow(devCard, "Seccomp 状态", "…");
        mHomeDevice = mInfoModel;
        makeInfoRow(devCard, "系统版本", "Android " + Build.VERSION.RELEASE
                + " · SDK " + Build.VERSION.SDK_INT);

        // 操作卡（miuix-glass 液态玻璃承载）
        MiuixCard actionsCard = MiuixCard.create(this);
        actionsCard.radiusDp(MiuixTheme.RADIUS_CARD);
        actionsCard.paddingDp(0, 0, 0, 0);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actionsCard.addView(actions, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(14);
        page.addView(actionsCard, alp);

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
        wrap.setBackgroundColor(0x00000000);
        wrap.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    private LinearLayout makeMetricCard(String label, String value) {
        MiuixCard host = new MiuixCard(this);
        host.radiusDp(MiuixTheme.RADIUS_CARD);
        host.fill(MiuixTheme.surface());
        host.stroke(MiuixTheme.outline(), 0.8f);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        host.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 用 tag 把宿主带出去，外层加的是 host
        card.setTag(host);
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

                        // Root 实现（仅用于首页 <KSU> 标记）
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
        // 云端配置优先（可在仓库 config.json 里随时切换更新源）
        String remote = RemoteConfig.updateFeed();
        if (remote != null && !remote.trim().isEmpty()) {
            return remote.trim();
        }
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
                AppLog.log("UPDATE", "check ok=" + r.ok + " remote=" + r.versionName
                        + " err=" + r.error);
                if (r.ok && isNewer(r)) {
                    showForceUpdate(r);
                }
            }
        });
    }

    /** 强制更新弹窗：不更新无法使用。 */
    private void showForceUpdate(final UpdateChecker.Result r) {
        if (mForceUpdateShown) {
            return;
        }
        mForceUpdateShown = true;

        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 500) {
            notes = notes.substring(0, 500) + "…";
        }
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：v").append(AppVersion.NAME).append('\n');
        msg.append("最新版本：v").append(r.versionName).append("\n\n");
        if (!notes.isEmpty()) {
            msg.append("更新内容：\n").append(notes).append("\n\n");
        }
        msg.append("本次为强制更新：更新完成后才能继续使用。");

        MiuixWindow.builder(this)
                .title("需要更新到 v" + r.versionName)
                .message(msg.toString())
                .cancelable(false)
                .positive("立即更新", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        openUpdate(r.downloadUrl);
                    }
                })
                .negative("退出应用", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        finish();
                    }
                })
                .show();
    }

    /** 打开下载地址（优先 APK 直链，其次更新源页）。 */
    private void openUpdate(String url) {
        String target = (url == null || url.trim().isEmpty()) ? updateFeed() : url.trim();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)));
        } catch (Throwable t) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", target));
                }
                toast("无法打开浏览器，下载链接已复制");
            } catch (Throwable ignored) {
                toast("无法打开下载链接");
            }
        }
    }

    /**
     * 判断远端版本是否比当前新。
     *
     * <p>优先按版本名语义比较（4.0.1 vs 4.0.0），相同再退回 versionCode。
     * 版本一致时返回 false —— 不弹更新窗。</p>
     */
    private boolean isNewer(UpdateChecker.Result r) {
        if (r == null) {
            return false;
        }
        String remote = r.versionName == null ? "" : r.versionName.trim();
        if (!remote.isEmpty()) {
            int c = compareVersion(remote, AppVersion.NAME);
            if (c != 0) {
                return c > 0;
            }
            return false; // 版本名相同 → 视为最新
        }
        if (r.versionCode > 0) {
            return r.versionCode > AppVersion.CODE;
        }
        return false;
    }

    /** 语义化比较：a>b 返回正数，a<b 返回负数，相同返回 0。 */
    private static int compareVersion(String a, String b) {
        int[] va = parseVersion(a);
        int[] vb = parseVersion(b);
        for (int i = 0; i < 3; i++) {
            if (va[i] != vb[i]) {
                return va[i] > vb[i] ? 1 : -1;
            }
        }
        return 0;
    }

    private static int[] parseVersion(String v) {
        int[] out = new int[]{0, 0, 0};
        if (v == null) {
            return out;
        }
        String s = v.trim().replaceFirst("^[vV]", "");
        String[] parts = s.split("[^0-9]+");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (Throwable ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    private void manualCheckUpdate() {
        final String feed = updateFeed();
        if (isWebPageFeed(feed)) {
            // 夸克网盘等分享页：直接打开
            MiuixWindow.builder(this)
                    .title("在线更新页面")
                    .message("当前更新源是网页/网盘分享页，无法自动比对版本。\n\n"
                            + "将打开浏览器查看最新版本：\n" + feed)
                    .positive("打开页面", new MiuixWindow.OnAction() {
                        @Override
                        public void onClick(android.app.Dialog d) {
                            openUpdatePage(feed);
                        }
                    })
                    .negative("取消", null)
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
                    mForceUpdateShown = false;
                    showForceUpdate(r);
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
        MiuixWindow.builder(this)
                .title("发现新版本 v" + r.versionName)
                .message(msg.toString())
                .positive("立即下载", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
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
                .neutral("复制链接", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager)
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
                .negative("稍后", null)
                .show();
    }

    private void promptUpdateFeed() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(updateFeed());
        et.setSingleLine(true);
        et.setHint("https://api.github.com/repos/你/仓库/releases/latest 或 JSON 清单地址");
        MiuixWindow.builder(this)
                .title("更新源地址")
                .message("支持 GitHub Releases API 或自定义 JSON 清单")
                .content(et)
                .scrollable(false)
                .positive("保存", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        uiPrefs().edit().putString("update_feed",
                                et.getText().toString().trim()).apply();
                        toast("已保存更新源");
                    }
                })
                .negative("取消", null)
                .show();
    }

    /** 首页右上角的长条按钮：番茄小说下载。 */
    private View buildNovelStrip() {
        MiuixGlass strip = new MiuixGlass(this);
        strip.radiusDp(20);
        strip.tint(0xFFEAF1FF, 0xFFDCE8FF);
        strip.rim(0x40FFFFFF, 0x143482FF);
        strip.specular(false);
        strip.paddingDp(12, 8, 14, 8);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        strip.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(this);
        int res = resId("ic_file_ebook");
        if (res != 0) {
            icon.setImageResource(res);
            icon.setColorFilter(ThemeColors.ACCENT);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView tv = new TextView(this);
        tv.setText("番茄小说");
        tv.setTextSize(13f);
        tv.setTextColor(ThemeColors.ACCENT);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(7);
        row.addView(tv, tlp);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(16f);
        arrow.setTextColor(ThemeColors.ACCENT);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(4);
        row.addView(arrow, alp);

        strip.setClickable(true);
        strip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFanqieDialog();
            }
        });
        return strip;
    }

    // ------------------------------------------------------------ 设置页

    private LinearLayout buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalFadingEdgeEnabled(false);
        sv.setBackgroundColor(0x00000000);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0x00000000);
        page.setPadding(dp(20), dp(22), dp(20), dp(120));

        TextView title = UiConfig.text(this, "设置", MiuixTheme.TEXT_TITLE, C_TEXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        page.addView(title);

        // ---------------- 外观（miuix-preference） ----------------
        page.addView(gap(14));
        MiuixPreference.Group look = MiuixPreference.group(this, "外观");
        look.infoRow("界面风格", "Miuix（玻璃）");
        look.infoRow("语言", "跟随系统");
        look.infoRow("主题配色", "固定玻璃蓝");
        page.addView(look.build());

        // ---------------- 状态栏常驻 ----------------
        page.addView(gap(14));
        MiuixPreference.Group status = MiuixPreference.group(this, "状态栏");
        status.switchRow("开机自启 · 常驻通知", "状态栏显示入口，点击可直接打开",
                NotifyService.enabled(this), new MiuixPreference.OnSwitch() {
                    @Override
                    public void onChanged(boolean checked) {
                        NotifyService.setEnabled(MainActivity.this, checked);
                        if (checked) {
                            requestNotifyPermission();
                            NotifyService.startIfEnabled(MainActivity.this);
                        } else {
                            NotifyService.stop(MainActivity.this);
                        }
                    }
                });
        page.addView(status.build());

        // ---------------- 背景音乐 ----------------
        page.addView(gap(14));
        MiuixPreference.Group bgm = MiuixPreference.group(this, "背景音乐");
        bgm.switchRow("启动自动播放", "打开 App 时自动播放背景音乐",
                BgmPlayer.enabled(this), new MiuixPreference.OnSwitch() {
                    @Override
                    public void onChanged(boolean checked) {
                        BgmPlayer.setEnabled(MainActivity.this, checked);
                        if (!checked) {
                            BgmPlayer.stop(MainActivity.this);
                        } else if (BgmPlayer.fileReady(MainActivity.this)) {
                            BgmPlayer.play(MainActivity.this);
                        }
                    }
                });
        bgm.clickRow("音乐文件", bgmLabel(), "", new MiuixPreference.OnClick() {
            @Override
            public void onClick(View v) {
                pickBgm();
            }
        });
        bgm.clickRow("播放 / 暂停", BgmPlayer.isPlaying() ? "正在播放" : "已暂停", "",
                new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        toggleBgm();
                    }
                });
        page.addView(bgm.build());

        // ---------------- 文件与权限 ----------------
        page.addView(gap(14));
        MiuixPreference.Group perms = MiuixPreference.group(this, "文件与权限");
        perms.iconClickRow(resId("ic_nav_folder"), C_ACCENT,
                "免 root 目录（SAF）", "选一个目录即可读写", "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        showSaf(true);
                    }
                });
        perms.iconClickRow(resId("ic_nav_root"), C_ACCENT,
                "所有文件访问权限", "点按去系统设置授予", "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        requestAllFilesAccess();
                    }
                });
        perms.iconClickRow(resId("ic_nav_root"), C_OK,
                "重新探测 root", "刷新 su 授权状态", "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        toast("正在重新探测 root…");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final boolean ok = Shell.detectRoot();
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        refreshHome();
                                        toast(ok ? "root 可用" : "未获取 root");
                                    }
                                });
                            }
                        }, "probe-root").start();
                    }
                });
        page.addView(perms.build());

        // ---------------- 工具 ----------------
        page.addView(gap(14));
        MiuixPreference.Group tools = MiuixPreference.group(this, "工具");
        tools.iconClickRow(resId("ic_nav_terminal"), C_ACCENT,
                "终端", "打开全屏终端", "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        showTerminal(true);
                    }
                });
        tools.infoRow("内置 bash", "首次运行脚本时自动释放");
        tools.iconClickRow(resId("ic_nav_about"), C_ACCENT,
                "关于 TF管理终端", "版本与使用说明", "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        showAbout();
                    }
                });
        page.addView(tools.build());

        // ---------------- 更新 ----------------
        page.addView(gap(14));
        page.addView(buildUpdateCard());

        // ---------------- 运行方式 ----------------
        page.addView(gap(14));
        MiuixPreference.Group runMode = MiuixPreference.group(this, "脚本运行");
        runMode.switchRow("兼容模式（推荐）",
                "不注入环境变量、用系统 sh，与常见终端一致；个别加载器仍需关闭",
                UiConfig.compatMode(this), new MiuixPreference.OnSwitch() {
                    @Override
                    public void onChanged(boolean checked) {
                        UiConfig.setCompatMode(MainActivity.this, checked);
                        toast(checked ? "已切换：系统 sh（兼容）" : "已切换：内置 bash + 环境注入");
                    }
                });
        page.addView(runMode.build());

        // ---------------- 云端配置 ----------------
        page.addView(gap(14));
        MiuixPreference.Group remote = MiuixPreference.group(this, "云端配置（热更新）");
        remote.clickRow("立即刷新", RemoteConfig.hasConfig() ? "已缓存配置" : "暂无缓存",
                "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        toast("正在刷新…");
                        RemoteConfig.refresh(MainActivity.this,
                                new RemoteConfig.Callback() {
                                    @Override
                                    public void onDone(boolean changed) {
                                        FanqieFontMap.setOverride(RemoteConfig.fontMap());
                                        toast(changed ? "配置已更新" : "配置无变化");
                                        if (changed) {
                                            maybeShowNotice();
                                        }
                                    }
                                });
                    }
                });
        remote.infoRow("可远端调整", "更新源 · 公告 · 字体映射 · 最低版本");
        page.addView(remote.build());

        // ---------------- 日志 ----------------
        page.addView(gap(14));
        MiuixPreference.Group logGroup = MiuixPreference.group(this, "日志");
        logGroup.iconClickRow(resId("ic_file_text"), MiuixTheme.primary(),
                "查看运行日志", "最近 300 行", "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        showLog();
                    }
                });
        logGroup.iconClickRow(resId("ic_file_unknown"), MiuixTheme.error(),
                "查看崩溃记录", AppLog.crashSummary().split("\n")[0], "",
                new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        showCrash();
                    }
                });
        logGroup.iconClickRow(resId("ic_file_archive"), MiuixTheme.onSurfaceVariant(),
                "清除日志", "删除 app.log 与崩溃文件", "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        AppLog.clear();
                        toast("日志已清除");
                    }
                });
        page.addView(logGroup.build());

        // ---------------- 关于 ----------------
        page.addView(gap(14));
        MiuixPreference.Group about = MiuixPreference.group(this, "关于");
        about.infoRow("版本", "v" + AppVersion.NAME + " (" + AppVersion.CODE + ")");
        about.infoRow("编译", "minSdk 26 / compileSdk 36");
        about.infoRow("目标", "targetSdk 37（Android 17）");
        page.addView(about.build());



        TextView foot = UiConfig.text(this, "TF管理终端 · Miuix 玻璃风格", 10.5f, 0xFFA0A4AB);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(18);
        page.addView(foot, flp);

        sv.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(0x00000000);
        wrap.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    /** 竖向间距占位。 */
    private View gap(int dpValue) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(dpValue)));
        return v;
    }

    /** 更新源显示文案。 */
    private String feedLabel() {
        String feed = updateFeed();
        if (isWebPageFeed(feed)) {
            return "网盘页面";
        }
        if (feed.contains("api.github.com")) {
            return "GitHub";
        }
        return "已设置";
    }

    /** 「更新」分组（miuix-preference）。 */
    private View buildUpdateCard() {
        MiuixPreference.Group g = MiuixPreference.group(this, "更新");

        g.clickRow("检查更新", "当前 v" + AppVersion.NAME, "", new MiuixPreference.OnClick() {
            @Override
            public void onClick(View v) {
                manualCheckUpdate();
            }
        });

        g.switchRow("自动检查更新", "启动时自动向更新源查询",
                uiPrefs().getBoolean("update_auto", true),
                new MiuixPreference.OnSwitch() {
                    @Override
                    public void onChanged(boolean checked) {
                        uiPrefs().edit().putBoolean("update_auto", checked).apply();
                    }
                });

        g.clickRow("更新源地址", "GitHub Releases / 自定义 JSON", feedLabel(),
                new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        promptUpdateFeed();
                    }
                });
        return g.build();
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

    /** 免 root 目录：白色背景弹窗。 */
    private void showSaf(boolean show) {
        if (!show) {
            if (mSafDialog != null) {
                mSafDialog.dismiss();
                mSafDialog = null;
            }
            return;
        }
        if (mSafDialog != null && mSafDialog.isShowing()) {
            return;
        }
        if (mSaf == null) {
            mSaf = new SafPanel(this);
            mSaf.setOnBack(new Runnable() {
                @Override
                public void run() {
                    showSaf(false);
                }
            });
        }
        // 从旧父容器摘下来
        if (mSaf.getParent() instanceof ViewGroup) {
            ((ViewGroup) mSaf.getParent()).removeView(mSaf);
        }

        android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        FrameLayout shell = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFFFFFFF);                 // 纯白背景
        bg.setCornerRadius(dp(20));
        shell.setBackground(bg);
        shell.setClipToOutline(true);
        shell.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(20));
            }
        });
        shell.addView(mSaf, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        d.setContentView(shell);

        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            lp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
            lp.dimAmount = 0.30f;
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
        }
        d.show();
        mSafDialog = d;
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
        // 终端全屏，隐藏浮层底栏，避免挡住输入框
        if (mNavContainer != null) {
            mNavContainer.setVisibility(show ? View.GONE : View.VISIBLE);
        }
        if (show && mTerminal != null) {
            mTerminal.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mTerminal != null) {
                        mTerminal.focusInput();
                    }
                }
            }, 250);
        }
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
            mHomeStatusCard.fill(MiuixTheme.surface());
        }
        // 左边状态方块：root 时绿色玻璃，否则灰色玻璃
        if (mHomeStatusBox != null) {
            if (root) {
                mHomeStatusBox.tint(0xFFE9F8EE, 0xFFDFF3E6);
                mHomeStatusBox.rim(0x33FFFFFF, 0x0D1FA35B);
            } else {
                mHomeStatusBox.tint(0xFFF4F5F7, 0xFFEDEFF2);
                mHomeStatusBox.rim(0x33FFFFFF, 0x0D000000);
            }
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
        if (mFanqieCard != null && mFanqieCard.getVisibility() == View.VISIBLE) {
            closeFanqieLogin();
            return;
        }
        if (mSafDialog != null && mSafDialog.isShowing()) {
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
    /** 赞赏（miuix-window 玻璃窗口）。 */
    private void showDonateDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView thanks = UiConfig.text(this, "感谢您支持本项目，我将努力开发！",
                14.5f, MiuixTheme.onSurface());
        thanks.setGravity(Gravity.CENTER);
        thanks.setPadding(0, dp(4), 0, dp(12));
        box.addView(thanks);

        ImageView qr = new ImageView(this);
        int qres = getResources().getIdentifier("donate", "drawable", getPackageName());
        if (qres != 0) {
            qr.setImageResource(qres);
        }
        qr.setAdjustViewBounds(true);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(qr, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        TextView tip = UiConfig.text(this, "「TF管理终端」的赞赏码", 11.5f,
                MiuixTheme.onSurfaceVariant());
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(8), 0, 0);
        box.addView(tip);

        MiuixWindow.builder(this)
                .title("赞赏")
                .content(box)
                .scrollable(false)
                .positive("关闭", null)
                .show();
    }

    /** 关于（miuix-window 桌面玻璃窗口 + miuix-glass + miuix-preference）。 */
    private void showAbout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 头部：miuix-glass-icons 玻璃图标 + 名称，整块坐在一张液态玻璃上
        MiuixGlass head = MiuixGlass.create(this);
        head.radiusDp(MiuixTheme.RADIUS_CARD);
        head.paddingDp(14, 14, 14, 14);
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(headRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout tile = MiuixGlassIcons.badge(this, resId("ic_nav_terminal"),
                MiuixTheme.onPrimary(), 48, 26);
        headRow.addView(tile);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView appName = UiConfig.text(this, "TF管理终端", 17f, MiuixTheme.onSurface());
        appName.setTypeface(appName.getTypeface(), android.graphics.Typeface.BOLD);
        names.addView(appName);
        names.addView(UiConfig.text(this, "v" + AppVersion.NAME + " (" + AppVersion.CODE + ")",
                11.5f, MiuixTheme.onSurfaceVariant()));
        names.addView(UiConfig.text(this, "Miuix 液态玻璃风格", 11f,
                MiuixTheme.primary()));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        np.leftMargin = dp(12);
        headRow.addView(names, np);
        box.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 模块清单（miuix-preference 信息行本身就是液态玻璃卡）
        MiuixPreference.Group mods = MiuixPreference.group(this, "Miuix 模块");
        mods.infoRow("基础 / 设置", "miuix-ui · miuix-preference");
        mods.infoRow("图标", "miuix-icons · miuix-glass-icons");
        mods.infoRow("材质 / 模糊", "miuix-material · miuix-blur");
        mods.infoRow("形状 / 玻璃", "miuix-squircle · miuix-glass");
        mods.infoRow("导航 / 窗口", "miuix-nav · miuix-window");
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(14);
        box.addView(mods.build(), mp);

        // 功能信息
        MiuixPreference.Group info = MiuixPreference.group(this, "应用信息");
        info.infoRow("导航", "首页 · 目录管理 · 分区 · 设置");
        info.infoRow("终端", "全屏 · 可交互 · 内置 bash");
        info.infoRow("编译", "minSdk 26 / targetSdk 37");
        info.infoRow("图标", "MDI（Apache-2.0）");
        info.infoRow("隐私", "纯本地，不收集数据");
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(14);
        box.addView(info.build(), ip);

        // 警示卡：也用一块玻璃承载
        MiuixGlass warnCard = MiuixGlass.create(this);
        warnCard.radiusDp(MiuixTheme.RADIUS_MEDIUM);
        warnCard.tint(0xF7FFF1F1, 0xE9FFE4E4);
        warnCard.rim(0x80FFFFFF, 0x26C0392B);
        warnCard.specular(false);
        warnCard.paddingDp(14, 12, 14, 12);
        TextView warn = UiConfig.text(this,
                "警告：root 权限下执行命令、删除文件不可撤销，请确认后再操作。"
                        + "本 App 不包含 root，需设备自行 root（Magisk / KernelSU / APatch）。",
                11f, 0xFFC0392B);
        warn.setLineSpacing(dp(2), 1f);
        warnCard.addView(warn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = dp(12);
        box.addView(warnCard, wp);

        // miuix-window：桌面式玻璃标题栏 + 液态玻璃窗口
        MiuixWindow.builder(this)
                .title("关于")
                .captionBar(true)
                .content(box)
                .positive("知道了", null)
                .show();
    }

    // ------------------------------------------------------------ 云端公告

    private void maybeShowNotice() {
        final String notice = RemoteConfig.notice();
        if (notice == null || notice.trim().isEmpty()) {
            return;
        }
        String shown = uiPrefs().getString("notice_shown", "");
        if (notice.equals(shown)) {
            return;
        }
        uiPrefs().edit().putString("notice_shown", notice).apply();
        MiuixWindow.builder(this)
                .title("公告")
                .message(notice)
                .positive("知道了", null)
                .show();
    }

    // ------------------------------------------------------------ 日志

    private void showLog() {
        String text = AppLog.tail(300);
        MiuixWindow.builder(this)
                .title("运行日志")
                .message(text)
                .neutral("复制", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        copyText(AppLog.tail(300));
                    }
                })
                .positive("关闭", null)
                .show();
    }

    private void showCrash() {
        String text = AppLog.latestCrash();
        if (text == null || text.trim().isEmpty()) {
            text = "（暂无崩溃记录）\n\n崩溃文件会保存在：\n"
                    + (AppLog.dir(this) == null ? "不可用" : AppLog.dir(this).getAbsolutePath());
        }
        MiuixWindow.builder(this)
                .title("崩溃记录")
                .message(text)
                .neutral("复制", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        copyText(AppLog.latestCrash());
                    }
                })
                .positive("关闭", null)
                .show();
    }

    private void copyText(String s) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && s != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", s));
                toast("已复制");
            }
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    // ------------------------------------------------------------ 状态栏常驻

    private void requestNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 背景音乐

    /** 音乐来源描述。 */
    private String bgmLabel() {
        String p = BgmPlayer.path(this);
        if (new File(p).isFile()) {
            return "自定义：" + new File(p).getName();
        }
        return "内置（发如雪）";
    }

    private void pickBgm() {
        ImagePicker.show(this, "选择背景音乐", "/sdcard/Music",
                new String[]{".mp3", ".m4a", ".flac", ".wav", ".ogg", ".aac"},
                "选择你自己的音频文件（本 App 不内置任何音乐）",
                new ImagePicker.OnPick() {
                    @Override
                    public void onPick(File f) {
                        BgmPlayer.setPath(MainActivity.this, f.getAbsolutePath());
                        toast("已选择：" + f.getName());
                        if (BgmPlayer.enabled(MainActivity.this)) {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        BgmPlayer.play(MainActivity.this);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }, "bgm-pick").start();
                        }
                    }
                });
    }

    private void toggleBgm() {
        if (BgmPlayer.isPlaying()) {
            BgmPlayer.pause();
            toast("已暂停");
            return;
        }
        if (!BgmPlayer.fileReady(this)) {
            MiuixWindow.builder(this)
                    .title("没有找到音乐文件")
                    .message("当前路径：\n" + BgmPlayer.path(this)
                            + "\n\n本 App 不内置任何音乐（避免版权问题）。\n"
                            + "请把你自己的音频文件放到该路径，"
                            + "或在设置里点「音乐文件」选择一个本地音频。")
                    .positive("选择文件", new MiuixWindow.OnAction() {
                        @Override
                        public void onClick(android.app.Dialog d) {
                            pickBgm();
                        }
                    })
                    .negative("知道了", null)
                    .show();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = BgmPlayer.play(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(ok ? "开始播放" : "播放失败（文件可能损坏或格式不支持）");
                    }
                });
            }
        }, "bgm-play").start();
    }

    /** 启动时自动播放背景音乐。 */
    private void autoPlayBgm() {
        if (!BgmPlayer.enabled(this)) {
            return;
        }
        // 关键：首次启动要解压内置音乐(4.5MB)+MediaPlayer.prepare()，
        // 在主线程做会被系统判定卡死 → 表现为「首次打开闪退、第二次正常」。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (BgmPlayer.fileReady(MainActivity.this)) {
                        BgmPlayer.play(MainActivity.this);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "bgm-auto").start();
    }

    // ------------------------------------------------------------ 番茄小说下载

    private void showFanqieDialog() {
        final android.widget.EditText linkEt = new android.widget.EditText(this);
        linkEt.setSingleLine(true);
        linkEt.setHint("粘贴番茄/常读分享链接，如 changdunovel.com/t/xxx");
        styleInput(linkEt);

        final android.widget.EditText numEt = new android.widget.EditText(this);
        numEt.setSingleLine(true);
        numEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        numEt.setText("50");
        numEt.setHint("导出章节数，0 = 全部（默认 50）");
        styleInput(numEt);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView tip = new TextView(this);
        tip.setText("支持两种链接：\n"
                + "· 番茄官网 fanqienovel.com/page/数字\n"
                + "· 分享短链 changdunovel.com/t/xxx（自动解析）\n"
                + "导出为 TXT，保存到「下载」目录（/sdcard/Download）。\n\n"
                + "说明：未登录只能拿到公开的试读片段，且番茄对部分字符做了字体混淆，"
                + "导出的 TXT 可能缺字或错字；完整内容请在番茄 App 内阅读。"
                + "本功能仅用于个人备份，请支持正版。");
        tip.setTextSize(11.5f);
        tip.setTextColor(0xFF8A5A00);
        tip.setLineSpacing(dp(2), 1f);
        box.addView(tip);

        // 登录状态行
        LinearLayout loginRow = new LinearLayout(this);
        loginRow.setOrientation(LinearLayout.HORIZONTAL);
        loginRow.setGravity(Gravity.CENTER_VERTICAL);
        loginRow.setPadding(0, dp(12), 0, 0);

        final TextView loginState = new TextView(this);
        loginState.setTextSize(11.5f);
        loginState.setTextColor(FanqieSession.loggedIn(this)
                ? MiuixTheme.success() : 0xFFC0392B);
        loginState.setText(FanqieSession.loggedIn(this)
                ? "✓ 已登录番茄账号（可获取全文）" : "未登录：只能拿到试读片段");
        loginRow.addView(loginState, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView loginBtn = new TextView(this);
        loginBtn.setText(FanqieSession.loggedIn(this) ? "重新登录" : "登录");
        loginBtn.setTextSize(11.5f);
        loginBtn.setTextColor(MiuixTheme.primary());
        loginBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        loginBtn.setPadding(dp(8), dp(4), dp(2), dp(4));
        loginBtn.setClickable(true);
        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFanqieLogin();
            }
        });
        loginRow.addView(loginBtn);
        box.addView(loginRow);

        box.addView(label2("书籍链接"));
        box.addView(linkEt);
        box.addView(label2("导出章节数"));
        box.addView(numEt);

        MiuixWindow.builder(this)
                .title("番茄小说下载")
                .content(box)
                .scrollable(false)
                .positive("开始下载", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        String link = linkEt.getText().toString().trim();
                        if (link.isEmpty()) {
                            toast("请粘贴书籍链接");
                            return;
                        }
                        int n = 50;
                        try {
                            n = Integer.parseInt(numEt.getText().toString().trim());
                        } catch (Throwable ignored) {
                        }
                        if (n < 0) {
                            n = 50;
                        }
                        if (n > 2000) {
                            n = 2000;
                        }
                        if (n == 0) {
                            n = 99999;   // 0 = 全部
                        }
                        startFanqieDownload(link, n, FanqieSession.cookie(MainActivity.this));
                    }
                })
                .negative("取消", null)
                .show();
    }

    /** 统一样式：黑字、深灰提示、浅底，保证在玻璃弹窗里可读。 */
    private void styleInput(android.widget.EditText et) {
        et.setTextColor(0xFF191919);
        et.setHintTextColor(0xFF9AA0A6);
        et.setTextSize(13f);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFFF2F4F8);
        g.setCornerRadius(dp(10));
        g.setStroke(Math.max(1, dp(0.8f)), 0xFFE3E7EE);
        et.setBackground(g);
    }

    private TextView label2(String t) {
        TextView v = UiConfig.text(this, t, 11.5f, MiuixTheme.onSurfaceVariant());
        v.setPadding(0, dp(10), 0, dp(3));
        return v;
    }

    /** 打开番茄登录 WebView。 */
    private void openFanqieLogin() {
        if (mFanqieCard == null) {
            return;
        }
        if (mFanqieLogin != null) {
            mFanqieLogin.destroy();
        }
        mFanqieLogin = new FanqieWebLogin(this);
        mFanqieLogin.setOnDone(new FanqieWebLogin.OnDone() {
            @Override
            public void onDone(String cookie) {
                closeFanqieLogin();
                if (cookie != null && cookie.contains("sessionid")) {
                    toast("登录成功，已保存");
                } else {
                    toast("未检测到登录态");
                }
            }
        });
        mFanqieCard.removeAllViews();
        mFanqieCard.addView(mFanqieLogin, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mFanqieCard.setVisibility(View.VISIBLE);
        mFanqieCard.bringToFront();
        if (mPages != null) {
            mPages.setInterceptEnabled(false);   // 让滑块验证能正常拖动
        }
    }

    private void closeFanqieLogin() {
        if (mPages != null) {
            mPages.setInterceptEnabled(true);
        }
        if (mFanqieCard != null) {
            mFanqieCard.setVisibility(View.GONE);
            mFanqieCard.removeAllViews();
        }
        if (mFanqieLogin != null) {
            mFanqieLogin.destroy();
            mFanqieLogin = null;
        }
    }

    private void startFanqieDownload(final String link, final int chapters,
                                     final String cookie) {
        final TextView status = new TextView(this);
        status.setText("正在准备…");
        status.setTextSize(12.5f);
        status.setTextColor(MiuixTheme.onSurface());
        status.setTextIsSelectable(true);

        final android.widget.ProgressBar bar = new android.widget.ProgressBar(this,
                null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        bp.topMargin = dp(10);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(status);
        box.addView(bar, bp);

        MiuixWindow.builder(this)
                .title("下载中")
                .content(box)
                .scrollable(false)
                .cancelable(false)
                .negative("后台继续", null)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File out = FanqieDownloader.download(link, chapters, cookie,
                            new FanqieDownloader.Progress() {
                                @Override
                                public void onProgress(final int done, final int total,
                                                       final String msg) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (total > 0) {
                                                bar.setProgress(done * 1000 / total);
                                            }
                                            if (msg != null) {
                                                status.setText(msg);
                                            }
                                        }
                                    });
                                }
                            });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            bar.setProgress(1000);
                            status.setText("已保存：\n" + out.getAbsolutePath()
                                    + "\n\n文件大小：" + (out.length() / 1024) + " KB");
                            status.setTextColor(MiuixTheme.success());
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("下载失败：" + t.getMessage());
                            status.setTextColor(MiuixTheme.error());
                        }
                    });
                }
            }
        }, "fanqie").start();
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
