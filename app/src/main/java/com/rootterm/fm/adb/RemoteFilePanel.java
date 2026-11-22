package com.rootterm.fm.adb;

import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.miuix.glass.MiuixGlass;
import com.miuix.pref.MiuixPreference;
import com.miuix.ui.MiuixCard;
import com.miuix.ui.MiuixTheme;
import com.miuix.window.MiuixWindow;
import com.rootterm.fm.ThemeColors;
import com.rootterm.fm.UiConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 系统模式：通过 ADB 管理另一台手机的文件。
 *
 * <p>连接后可以浏览、读取、修改、上传、下载、删除、重命名对方设备上的文件。</p>
 */
public class RemoteFilePanel extends LinearLayout {

    private static final String OUT_DIR = "/sdcard/Download/TF远端";

    private final Context ctx;
    private final UsbManager usb;

    private AdbHost.Session session;
    private UsbDevice device;

    private String cwd = "/sdcard";
    private final List<AdbHost.Entry> entries = new ArrayList<AdbHost.Entry>();

    private TextView mStatus;
    private TextView mInfo;
    private TextView mPath;
    private TextView mDiag;
    private LinearLayout mListHost;
    private boolean busy;
    private boolean permRequested;
    private boolean connecting;
    private android.os.Handler autoScan;
    private boolean autoRunning;

    public RemoteFilePanel(Context c) {
        super(c);
        ctx = c;
        usb = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        setOrientation(VERTICAL);
        build();
    }

    // ---------------------------------------------------------------- UI

    private void build() {
        MiuixGlass card = new MiuixGlass(ctx);
        card.radiusDp(MiuixTheme.RADIUS_MEDIUM);
        card.tint(0xF2FFFFFF, 0xE0FFFFFF);
        card.paddingDp(14, 13, 14, 13);
        addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout tile = new FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(MiuixTheme.success());
        bg.setCornerRadius(dp(11));
        tile.setBackground(bg);
        ImageView iv = new ImageView(ctx);
        int res = getResources().getIdentifier("ic_nav_folder", "drawable",
                ctx.getPackageName());
        if (res != 0) {
            iv.setImageResource(res);
            iv.setColorFilter(0xFFFFFFFF);
        }
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(18), dp(18));
        ip.gravity = Gravity.CENTER;
        tile.addView(iv, ip);
        head.addView(tile, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(VERTICAL);
        mStatus = new TextView(ctx);
        mStatus.setText("未连接");
        mStatus.setTextSize(15f);
        mStatus.setTextColor(MiuixTheme.onSurface());
        mStatus.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        names.addView(mStatus);
        mInfo = new TextView(ctx);
        mInfo.setText("对方手机需开启「USB 调试」");
        mInfo.setTextSize(10.5f);
        mInfo.setTextColor(MiuixTheme.onSurfaceVariant());
        mInfo.setSingleLine(true);
        mInfo.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        names.addView(mInfo);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = dp(11);
        head.addView(names, np);

        TextView scanBtn = new TextView(ctx);
        scanBtn.setText("扫描");
        scanBtn.setTextSize(12f);
        scanBtn.setTextColor(MiuixTheme.primary());
        scanBtn.setPadding(dp(8), dp(6), dp(4), dp(6));
        scanBtn.setClickable(true);
        scanBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                permRequested = false;
                setDetail("");
                scanInternal(true);
            }
        });
        head.addView(scanBtn);
        col.addView(head);

        // 路径栏
        LinearLayout pathBar = new LinearLayout(ctx);
        pathBar.setOrientation(HORIZONTAL);
        pathBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbp.topMargin = dp(11);
        col.addView(pathBar, pbp);

        TextView up = actionButton("‹ 上级", 0x14000000, MiuixTheme.onSurface(),
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        goUp();
                    }
                });
        pathBar.addView(up);
        pathBar.addView(actionButton("刷新", MiuixTheme.primaryContainer(),
                ThemeColors.ON_ACCENT_CONTAINER, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        browse(cwd);
                    }
                }));
        pathBar.addView(actionButton("上传", 0x14000000, MiuixTheme.onSurface(),
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        uploadDialog();
                    }
                }));

        mPath = new TextView(ctx);
        mPath.setText("/sdcard");
        mPath.setTextSize(11f);
        mPath.setTextColor(MiuixTheme.onSurfaceVariant());
        mPath.setSingleLine(true);
        mPath.setEllipsize(android.text.TextUtils.TruncateAt.START);
        mPath.setPadding(dp(2), dp(8), dp(2), 0);
        col.addView(mPath);

        mDiag = new TextView(ctx);
        mDiag.setTextSize(10f);
        mDiag.setTextColor(0xFF8A5A00);
        mDiag.setLineSpacing(dp(1), 1f);
        mDiag.setTextIsSelectable(true);
        mDiag.setVisibility(GONE);
        LinearLayout.LayoutParams dgp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dgp.topMargin = dp(6);
        col.addView(mDiag, dgp);

        // 列表
        mListHost = new LinearLayout(ctx);
        mListHost.setOrientation(VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        addView(mListHost, lp);

        post(new Runnable() {
            @Override
            public void run() {
                // 首次进入不主动弹权限框；若此前已授权则直接连
                permRequested = true;
                scanInternal(true);
                permRequested = false;
                startAutoScan();
            }
        });
    }

    /** 未连接时每 2.5s 自动重扫，插线即发现。 */
    private void startAutoScan() {
        if (autoRunning) {
            return;
        }
        autoRunning = true;
        autoScan = new android.os.Handler(android.os.Looper.getMainLooper());
        autoScan.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (getVisibility() != VISIBLE) {
                    autoScan.postDelayed(this, 2500);
                    return;
                }
                if (session == null && !connecting) {
                    scanInternal(false);
                }
                autoScan.postDelayed(this, 2500);
            }
        }, 2500);
    }

    private void stopAutoScan() {
        autoRunning = false;
        if (autoScan != null) {
            autoScan.removeCallbacksAndMessages(null);
            autoScan = null;
        }
    }

    private TextView actionButton(String text, int bg, int fg, OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(12f);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setPadding(dp(11), dp(7), dp(11), dp(7));
        b.setClickable(true);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(7);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    // ---------------------------------------------------------------- 连接

    /** 手动扫描（会请求权限 / 建立连接）。 */
    public void scan() {
        scanInternal(true);
    }

    /**
     * 扫描设备。
     *
     * @param userInitiated true=用户点「扫描」（允许请求权限/连接）；
     *                      false=后台自动探测（只更新状态，绝不再弹窗/不再连）
     */
    private void scanInternal(boolean userInitiated) {
        if (usb == null) {
            setStatus("本机不支持 USB Host");
            setDetail("× 没有 UsbManager，本机不具备 USB 主机能力");
            return;
        }
        List<UsbDevice> list = AdbHost.findDevices(usb);
        if (list.isEmpty()) {
            setStatus("未检测设备");
            if (userInitiated) {
                String dump = usbDump();
                setDetail(dump.isEmpty()
                        ? "未检测到任何 USB 设备\n· 确认 OTG 线/转接头可用\n· 拔插一次或换根线"
                        : "检测到 USB 设备但没有 ADB 接口：\n" + dump
                        + "\n请打开对方「USB 调试」并把 USB 用途改成「传输文件(MTP)」");
            } else {
                setDetail("");
            }
            return;
        }

        UsbDevice d = list.get(0);

        // 已连接：什么都不做
        if (session != null) {
            setStatus("已连接");
            return;
        }

        if (!usb.hasPermission(d)) {
            setStatus("等待授权");
            setDetail("· 已找到设备 " + AdbHost.describe(d) + "，等待 USB 授权");
            // 只有用户主动点扫描才请求权限，避免无限弹窗
            if (userInitiated && !permRequested) {
                permRequested = true;
                try {
                    PendingIntent pi = PendingIntent.getBroadcast(ctx, 1,
                            new Intent(AdbHost.ACTION_PERM).setPackage(ctx.getPackageName()),
                            PendingIntent.FLAG_MUTABLE);
                    usb.requestPermission(d, pi);
                    setDetail("· 已请求 USB 权限，请在弹窗点「允许」");
                } catch (Throwable t) {
                    setDetail("× 请求权限异常：" + t);
                    permRequested = false;
                }
                pollPermission(d);
            }
            return;
        }

        // 有权限但未连接：只有用户主动点扫描才去连（自动探测不连，避免反复弹授权）
        if (!userInitiated) {
            setStatus("已就绪");
            setDetail("· 检测到设备，点「扫描」建立连接");
            return;
        }
        if (connecting) {
            return;
        }
        setDetail("· 打开 ADB 会话…（对方若弹「允许 USB 调试」请点允许）");
        connect(d);
    }

    /** 状态文字（去重，避免闪烁）。 */
    private void setStatus(String s) {
        if (mStatus != null && !s.equals(mStatus.getText().toString())) {
            mStatus.setText(s);
        }
        if (mInfo != null) {
            String info = s;
            if (!info.equals(mInfo.getText().toString())) {
                mInfo.setText(info);
            }
        }
    }

    /** 详情（去重；空串则隐藏）。 */
    private void setDetail(String s) {
        if (mDiag == null) {
            return;
        }
        String cur = mDiag.getText().toString();
        if (s == null || s.isEmpty()) {
            if (mDiag.getVisibility() != GONE) {
                mDiag.setText("");
                mDiag.setVisibility(GONE);
            }
            return;
        }
        if (!s.equals(cur)) {
            mDiag.setText(s);
        }
        if (mDiag.getVisibility() != VISIBLE) {
            mDiag.setVisibility(VISIBLE);
        }
    }

    /** 轮询权限（不依赖广播，避免广播被拦）。 */
    private void pollPermission(final UsbDevice d) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 120; i++) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                    if (usb.hasPermission(d)) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                setDetail("· 权限已授予，正在连接…");
                                if (!connecting) {
                                    connect(d);
                                }
                            }
                        });
                        return;
                    }
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        permRequested = false;
                        setDetail("× 等待 USB 授权超时\n· 若没看到授权弹窗，把线拔了重插\n"
                                + "· 再点一次「扫描」");
                    }
                });
            }
        }, "adb-perm").start();
    }

    public void onPermissionResult(boolean granted) {
        if (!granted) {
            setStatus("授权被拒绝");
            setDetail("× USB 授权被拒绝，点「扫描」重试");
            permRequested = false;
            return;
        }
        if (device != null && !connecting) {
            setDetail("· 权限已授予，正在连接…");
            connect(device);
        }
    }

    private void connect(final UsbDevice d) {
        closeSession();
        connecting = true;
        setStatus("正在连接…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final AdbHost.Session s = AdbHost.open(usb, d,
                            new File(ctx.getFilesDir(), "adb"));
                    session = s;
                    device = d;
                    final String info = s.info();
                    post(new Runnable() {
                        @Override
                        public void run() {
                            connecting = false;
                            permRequested = false;
                            setStatus("已连接");
                            setDetail(info);
                            browse("/sdcard");
                        }
                    });
                } catch (final Throwable t) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            connecting = false;
                            setStatus("连接失败");
                            String msg = String.valueOf(t.getMessage());
                            setDetail("× " + msg);
                            permRequested = true;   // 不自动重试，等用户点「扫描」
                        }
                    });
                }
            }
        }, "adb-connect").start();
    }

    private void closeSession() {
        if (session != null) {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
            session = null;
        }
    }

    // ---------------------------------------------------------------- 浏览

    private void browse(final String path) {
        if (session == null) {
            toast("请先连接设备");
            scan();
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        mPath.setText("正在读取 " + path + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AdbHost.Entry> list = new ArrayList<AdbHost.Entry>();
                String err = null;
                try {
                    list.addAll(session.list(path));
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String ferr = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        cwd = path;
                        entries.clear();
                        entries.addAll(list);
                        mPath.setText(path);
                        render(ferr);
                    }
                });
            }
        }, "adb-list").start();
    }

    private void goUp() {
        if ("/".equals(cwd)) {
            return;
        }
        int i = cwd.lastIndexOf('/');
        browse(i <= 0 ? "/" : cwd.substring(0, i));
    }

    private void render(String err) {
        mListHost.removeAllViews();
        if (err != null) {
            mListHost.addView(hint("读取失败：" + err));
            return;
        }
        if (entries.isEmpty()) {
            mListHost.addView(hint("空目录或无权限"));
            return;
        }
        MiuixCard card = new MiuixCard(ctx);
        card.radiusDp(MiuixTheme.RADIUS_CARD);
        card.fill(MiuixTheme.surface());
        card.stroke(MiuixTheme.outline(), 0.8f);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int shown = 0;
        for (int i = 0; i < entries.size() && shown < 300; i++) {
            AdbHost.Entry e = entries.get(i);
            if (shown > 0) {
                View line = new View(ctx);
                line.setBackgroundColor(MiuixTheme.outline());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
                lp.leftMargin = dp(14);
                col.addView(line, lp);
            }
            col.addView(entryRow(e), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shown++;
        }
        mListHost.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View hint(String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(11.5f);
        t.setTextColor(MiuixTheme.onSurfaceVariant());
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        return t;
    }

    private View entryRow(final AdbHost.Entry e) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(12), dp(11));
        row.setClickable(true);

        FrameLayout tile = new FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(e.dir ? 0xFF3482FF : 0xFF7A8699);
        bg.setCornerRadius(dp(10));
        tile.setBackground(bg);
        ImageView iv = new ImageView(ctx);
        int res = getResources().getIdentifier(e.dir ? "ic_file_dir" : "ic_file_unknown",
                "drawable", ctx.getPackageName());
        if (res != 0) {
            iv.setImageResource(res);
            iv.setColorFilter(0xFFFFFFFF);
        }
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(18), dp(18));
        ip.gravity = Gravity.CENTER;
        tile.addView(iv, ip);
        row.addView(tile, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(VERTICAL);
        TextView name = new TextView(ctx);
        name.setText(e.name);
        name.setTextSize(14f);
        name.setTextColor(MiuixTheme.onSurface());
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);
        TextView sub = new TextView(ctx);
        sub.setText((e.dir ? "目录" : human(e.size)) + " · " + e.date);
        sub.setTextSize(10.5f);
        sub.setTextColor(MiuixTheme.onSurfaceVariant());
        texts.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(11);
        tp.rightMargin = dp(8);
        row.addView(texts, tp);

        if (!e.dir) {
            TextView dl = miniButton("下载", MiuixTheme.primaryContainer(),
                    ThemeColors.ON_ACCENT_CONTAINER);
            dl.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    download(e);
                }
            });
            row.addView(dl);
        }

        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (e.dir) {
                    browse(e.path);
                } else {
                    fileActions(e);
                }
            }
        });
        row.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                fileActions(e);
                return true;
            }
        });
        return row;
    }

    private TextView miniButton(String text, int bgColor, int fgColor) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(11.5f);
        b.setTextColor(fgColor);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setPadding(dp(11), dp(6), dp(11), dp(6));
        b.setClickable(true);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(bgColor);
        g.setCornerRadius(dp(11));
        b.setBackground(g);
        return b;
    }

    // ---------------------------------------------------------------- 文件操作

    private void fileActions(final AdbHost.Entry e) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        TextView info = new TextView(ctx);
        info.setText(e.path + "\n权限 " + e.perm + " · " + human(e.size));
        info.setTextSize(11.5f);
        info.setTextColor(MiuixTheme.onSurfaceVariant());
        info.setTextIsSelectable(true);
        box.addView(info);

        MiuixPreference.Group g = MiuixPreference.group(ctx, null);
        if (!e.dir) {
            g.clickRow("查看文本", "读取并预览（≤512KB）", "", new MiuixPreference.OnClick() {
                @Override
                public void onClick(View v) {
                    previewText(e);
                }
            });
            g.clickRow("下载到本机", OUT_DIR, "", new MiuixPreference.OnClick() {
                @Override
                public void onClick(View v) {
                    download(e);
                }
            });
            g.clickRow("编辑内容", "修改后写回对方设备", "", new MiuixPreference.OnClick() {
                @Override
                public void onClick(View v) {
                    editRemote(e);
                }
            });
        }
        g.clickRow("重命名", "", "", new MiuixPreference.OnClick() {
            @Override
            public void onClick(View v) {
                renameDialog(e);
            }
        });
        g.clickRow("删除", "不可恢复", "", new MiuixPreference.OnClick() {
            @Override
            public void onClick(View v) {
                deleteDialog(e);
            }
        });
        View card = g.build();
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(12);
        box.addView(card, cp);

        MiuixWindow.builder(ctx)
                .title(e.name)
                .content(box)
                .scrollable(false)
                .positive("关闭", null)
                .show();
    }

    private void download(final AdbHost.Entry e) {
        final File dst = new File(OUT_DIR, e.name);
        toast("正在下载…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    byte[] data = session.read(e.path);
                    new File(OUT_DIR).mkdirs();
                    FileOutputStream fo = new FileOutputStream(dst);
                    try {
                        fo.write(data);
                    } finally {
                        try {
                            fo.close();
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String ferr = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (ferr == null) {
                            MiuixWindow.builder(ctx)
                                    .title("下载完成")
                                    .message("已保存到：\n" + dst.getAbsolutePath())
                                    .positive("知道了", null)
                                    .show();
                        } else {
                            MiuixWindow.builder(ctx)
                                    .title("下载失败")
                                    .message(ferr)
                                    .positive("知道了", null)
                                    .show();
                        }
                    }
                });
            }
        }, "adb-download").start();
    }

    private void previewText(final AdbHost.Entry e) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    String out = session.shell("toybox head -c 524288 " + q(e.path)
                            + " 2>/dev/null");
                    text = out == null ? "(空)" : out;
                } catch (Throwable t) {
                    text = "读取失败：" + t.getMessage();
                }
                final String f = text;
                post(new Runnable() {
                    @Override
                    public void run() {
                        MiuixWindow.builder(ctx)
                                .title(e.name)
                                .message(f)
                                .positive("关闭", null)
                                .show();
                    }
                });
            }
        }, "adb-preview").start();
    }

    private void editRemote(final AdbHost.Entry e) {
        final EditText et = new EditText(ctx);
        et.setTextSize(12f);
        et.setMinLines(8);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setHint("加载中…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                String loaded = null;
                try {
                    byte[] b = session.read(e.path);
                    loaded = new String(b, "UTF-8");
                } catch (Throwable ignored) {
                }
                final String content = loaded;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (content == null) {
                            toast("读取失败");
                            return;
                        }
                        et.setText(content);
                        MiuixWindow.builder(ctx)
                                .title("编辑 " + e.name)
                                .content(et)
                                .scrollable(false)
                                .positive("保存", new MiuixWindow.OnAction() {
                                    @Override
                                    public void onClick(android.app.Dialog d) {
                                        saveRemote(e, et.getText().toString());
                                    }
                                })
                                .negative("取消", null)
                                .show();
                    }
                });
            }
        }, "adb-edit").start();
    }

    private void saveRemote(final AdbHost.Entry e, final String text) {
        toast("正在保存…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    session.write(e.path, text.getBytes("UTF-8"));
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String ferr = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (ferr == null) {
                            toast("已保存");
                            browse(cwd);
                        } else {
                            MiuixWindow.builder(ctx)
                                    .title("保存失败")
                                    .message(ferr)
                                    .positive("知道了", null)
                                    .show();
                        }
                    }
                });
            }
        }, "adb-save").start();
    }

    private void renameDialog(final AdbHost.Entry e) {
        final EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setText(e.name);
        MiuixWindow.builder(ctx)
                .title("重命名")
                .content(et)
                .scrollable(false)
                .positive("确认执行", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        String nn = et.getText().toString().trim();
                        if (nn.isEmpty() || nn.contains("/")) {
                            toast("名称无效");
                            return;
                        }
                        String to = "/".equals(cwd) ? "/" + nn : cwd + "/" + nn;
                        runOp("重命名", new Op() {
                            @Override
                            public void run() throws Exception {
                                session.rename(e.path, to);
                            }
                        });
                    }
                })
                .negative("取消", null)
                .show();
    }

    private void deleteDialog(final AdbHost.Entry e) {
        MiuixWindow.builder(ctx)
                .title("警告")
                .message("确定删除对方设备上的：\n" + e.path + "\n\n此操作不可撤销。")
                .positive("确认执行", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        runOp("删除", new Op() {
                            @Override
                            public void run() throws Exception {
                                session.delete(e.path);
                            }
                        });
                    }
                })
                .negative("取消", null)
                .show();
    }

    private void uploadDialog() {
        if (session == null) {
            toast("请先连接设备");
            return;
        }
        final EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setHint("/sdcard/Download/xxx.apk");

        MiuixWindow.builder(ctx)
                .title("上传到对方设备")
                .message("本机文件路径 → 将写入 " + cwd)
                .content(et)
                .scrollable(false)
                .positive("确认执行", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        String p = et.getText().toString().trim();
                        if (p.isEmpty()) {
                            toast("请填写路径");
                            return;
                        }
                        File f = new File(p);
                        if (!f.isFile()) {
                            toast("文件不存在");
                            return;
                        }
                        upload(f);
                    }
                })
                .negative("取消", null)
                .show();
    }

    private void upload(final File f) {
        toast("正在上传…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    byte[] data = readLocal(f);
                    String dst = "/".equals(cwd) ? "/" + f.getName() : cwd + "/" + f.getName();
                    session.write(dst, data);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String ferr = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (ferr == null) {
                            toast("上传完成");
                            browse(cwd);
                        } else {
                            MiuixWindow.builder(ctx)
                                    .title("上传失败")
                                    .message(ferr)
                                    .positive("知道了", null)
                                    .show();
                        }
                    }
                });
            }
        }, "adb-upload").start();
    }

    // ---------------------------------------------------------------- 异步操作

    private interface Op {
        void run() throws Exception;
    }

    private void runOp(final String name, final Op op) {
        if (session == null) {
            toast("请先连接设备");
            return;
        }
        toast("正在" + name + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    op.run();
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String ferr = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (ferr == null) {
                            toast(name + "成功");
                            browse(cwd);
                        } else {
                            MiuixWindow.builder(ctx)
                                    .title(name + "失败")
                                    .message(ferr)
                                    .positive("知道了", null)
                                    .show();
                        }
                    }
                });
            }
        }, "adb-op").start();
    }

    // ---------------------------------------------------------------- 工具

    private static byte[] readLocal(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String q(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String human(long b) {
        if (b <= 0) {
            return "0 B";
        }
        double d = b;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (d >= 1024 && i < u.length - 1) {
            d /= 1024;
            i++;
        }
        return String.format(Locale.US, d >= 100 ? "%.0f %s" : "%.1f %s", d, u[i]);
    }

    private int dp(float v) {
        return UiConfig.dp(ctx, v);
    }

    /** 列出所有 USB 设备及其接口类别，便于判断对方是否暴露 ADB。 */
    private String usbDump() {
        if (usb == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : usb.getDeviceList().values()) {
            sb.append(String.format(Locale.US, "· %04X:%04X",
                    d.getVendorId(), d.getProductId()));
            if (d.getProductName() != null) {
                sb.append(" ").append(d.getProductName());
            }
            sb.append('\n');
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                android.hardware.usb.UsbInterface itf = d.getInterface(i);
                sb.append(String.format(Locale.US, "    itf%d class=0x%02X sub=0x%02X proto=0x%02X",
                        i, itf.getInterfaceClass(), itf.getInterfaceSubclass(),
                        itf.getInterfaceProtocol()));
                sb.append(itf.getInterfaceClass() == 0xFF
                        && itf.getInterfaceSubclass() == 0x42
                        && itf.getInterfaceProtocol() == 0x01 ? "  ← ADB\n" : "\n");
            }
        }
        return sb.toString();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    public void release() {
        stopAutoScan();
        closeSession();
    }
}