package com.rootterm.fm.fastboot;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.miuix.glass.MiuixGlass;
import com.miuix.pref.MiuixPreference;
import com.miuix.ui.MiuixCard;
import com.miuix.ui.MiuixTheme;
import com.miuix.window.MiuixWindow;
import com.rootterm.fm.ThemeColors;
import com.rootterm.fm.UiConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 数据线刷写（Fastboot 主机模式）。
 *
 * <p>通过 OTG 连接另一台处于 fastboot 模式的手机，读取分区、刷写镜像、尝试备份分区。</p>
 */
public class FastbootPanel extends LinearLayout {

    private static final String OUT_BACKUP = "/sdcard/Download/TF分区备份";

    private final Context ctx;
    private final UsbManager usb;

    private FastbootHost.Device session;
    private UsbDevice device;
    private final List<FastbootHost.Part> parts = new ArrayList<FastbootHost.Part>();

    private TextView mStatus;
    private TextView mInfo;
    private LinearLayout mListHost;
    private TextView mHint;
    private LinearLayout mQuickHost;
    private boolean busy;

    public FastbootPanel(Context c) {
        super(c);
        ctx = c;
        usb = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        setOrientation(VERTICAL);
        build();
    }

    // ---------------------------------------------------------------- UI

    private void build() {
        // 状态玻璃卡
        MiuixGlass card = new MiuixGlass(ctx);
        card.radiusDp(MiuixTheme.RADIUS_MEDIUM);
        card.tint(0xF2FFFFFF, 0xE0FFFFFF);
        card.paddingDp(14, 13, 14, 13);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addView(card, cp);

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
        bg.setColor(MiuixTheme.primary());
        bg.setCornerRadius(dp(11));
        tile.setBackground(bg);
        ImageView iv = new ImageView(ctx);
        int res = getResources().getIdentifier("ic_nav_terminal", "drawable",
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
        mInfo.setText("用 OTG 线连接另一台手机的 fastboot 模式");
        mInfo.setTextSize(10.5f);
        mInfo.setTextColor(MiuixTheme.onSurfaceVariant());
        mInfo.setSingleLine(true);
        mInfo.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        names.addView(mInfo);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = dp(11);
        head.addView(names, np);

        TextView refresh = new TextView(ctx);
        refresh.setText("扫描");
        refresh.setTextSize(12f);
        refresh.setTextColor(MiuixTheme.primary());
        refresh.setPadding(dp(8), dp(6), dp(4), dp(6));
        refresh.setClickable(true);
        refresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        });
        head.addView(refresh);
        col.addView(head);

        // 操作按钮行
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(11);
        col.addView(btns, bp);

        btns.addView(actionButton("读取分区", MiuixTheme.primaryContainer(),
                ThemeColors.ON_ACCENT_CONTAINER, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadPartitions();
                    }
                }));
        btns.addView(actionButton("刷写分区…", 0x14E8481F, MiuixTheme.error(),
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        customFlashDialog();
                    }
                }));
        btns.addView(actionButton("重启", 0x14000000, MiuixTheme.onSurface(),
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        reboot("");
                    }
                }));

        // 提示
        mHint = new TextView(ctx);
        mHint.setText("");
        mHint.setTextSize(10.5f);
        mHint.setTextColor(MiuixTheme.onSurfaceVariant());
        mHint.setPadding(dp(2), dp(10), dp(2), 0);
        mHint.setLineSpacing(dp(1), 1f);
        addView(mHint);

        // 分区列表
        mListHost = new LinearLayout(ctx);
        mListHost.setOrientation(VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        addView(mListHost, lp);

        // 快捷命令
        TextView quickTitle = new TextView(ctx);
        quickTitle.setText("快捷命令");
        quickTitle.setTextSize(12.5f);
        quickTitle.setTextColor(MiuixTheme.onSurfaceVariant());
        quickTitle.setPadding(dp(6), dp(16), dp(6), dp(6));
        addView(quickTitle);

        mQuickHost = new LinearLayout(ctx);
        mQuickHost.setOrientation(VERTICAL);
        addView(mQuickHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderQuickCommands();

        post(new Runnable() {
            @Override
            public void run() {
                scan();
            }
        });
    }

    private TextView actionButton(String text, int bg, int fg, OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(12f);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setClickable(true);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    // ---------------------------------------------------------------- 设备连接

    /** 扫描并连接。 */
    public void scan() {
        if (usb == null) {
            mStatus.setText("本机不支持 USB Host");
            mInfo.setText("缺少 USB 主机能力");
            return;
        }
        List<UsbDevice> list = FastbootHost.findDevices(usb);
        if (list.isEmpty()) {
            mStatus.setText("未连接");
            mInfo.setText("请用 OTG 线连接处于 fastboot 模式的手机");
            mHint.setText("提示：另一台手机先「音量下 + 电源」进 fastboot/bootloader，"
                    + "再用 OTG 线接到本机。");
            return;
        }
        UsbDevice d = list.get(0);
        if (!usb.hasPermission(d)) {
            device = d;
            mStatus.setText("等待授权");
            mInfo.setText(FastbootHost.describe(d) + " · 请允许 USB 权限");
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                    new Intent(FastbootHost.ACTION_PERM).setPackage(ctx.getPackageName()),
                    PendingIntent.FLAG_MUTABLE);
            usb.requestPermission(d, pi);
            return;
        }
        connect(d);
    }

    /** USB 权限结果回调（由 MainActivity 转发）。 */
    public void onPermissionResult(boolean granted) {
        if (!granted) {
            mStatus.setText("授权被拒绝");
            mInfo.setText("无法访问 USB 设备");
            return;
        }
        if (device != null) {
            connect(device);
        } else {
            scan();
        }
    }

    private void connect(final UsbDevice d) {
        closeSession();
        mStatus.setText("正在连接…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final FastbootHost.Device dev = FastbootHost.open(usb, d);
                    dev.prepare();
                    final String info = dev.info();
                    session = dev;
                    device = d;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            mStatus.setText("已连接");
                            mInfo.setText(FastbootHost.describe(d)
                                    + (info.isEmpty() ? "" : " · " + firstLine(info)));
                            mHint.setText(info);
                            loadPartitions();
                        }
                    });
                } catch (final Throwable t) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            mStatus.setText("连接失败");
                            mInfo.setText(String.valueOf(t.getMessage()));
                        }
                    });
                }
            }
        }, "fb-connect").start();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
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

    // ---------------------------------------------------------------- 分区

    private void loadPartitions() {
        if (session == null) {
            toast("请先连接设备");
            scan();
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        mHint.setText("正在读取分区…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<FastbootHost.Part> list = new ArrayList<FastbootHost.Part>();
                String err = null;
                try {
                    list.addAll(session.partitions());
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final String ferr = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        parts.clear();
                        parts.addAll(list);
                        renderParts(ferr);
                    }
                });
            }
        }, "fb-parts").start();
    }

    private void renderParts(String err) {
        mListHost.removeAllViews();
        if (err != null) {
            mHint.setText("读取分区失败：" + err);
            return;
        }
        if (parts.isEmpty()) {
            mHint.setText("未读到分区信息（该 bootloader 不支持 getvar all）\n"
                    + "已切换为「常用分区」模式：点右侧「刷写」直接刷，"
                    + "或用上方「刷写分区…」自定义分区名。");
            renderPresetParts();
            return;
        }
        long total = 0;
        for (FastbootHost.Part p : parts) {
            total += p.size;
        }
        mHint.setText("共 " + parts.size() + " 个分区 · 合计 " + human(total)
                + " · 备份/刷写均通过 USB 操作对方设备");

        MiuixCard card = new MiuixCard(ctx);
        card.radiusDp(MiuixTheme.RADIUS_CARD);
        card.fill(MiuixTheme.surface());
        card.stroke(MiuixTheme.outline(), 0.8f);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < parts.size(); i++) {
            final FastbootHost.Part p = parts.get(i);
            if (i > 0) {
                View line = new View(ctx);
                line.setBackgroundColor(MiuixTheme.outline());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
                lp.leftMargin = dp(14);
                col.addView(line, lp);
            }
            col.addView(partRow(p), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mListHost.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 常用分区名（getvar all 不可用时兜底）。 */
    private static final String[] PRESET_PARTS = {
            "boot", "init_boot", "vendor_boot", "dtbo", "vbmeta", "vbmeta_system",
            "recovery", "super", "system", "vendor", "product", "odm",
            "userdata", "metadata", "modem", "persist", "misc", "cache",
            "boot_a", "boot_b", "init_boot_a", "init_boot_b", "vbmeta_a", "vbmeta_b",
    };

    /** 渲染常用分区（只提供刷写，不提供备份）。 */
    private void renderPresetParts() {
        mListHost.removeAllViews();
        MiuixCard card = new MiuixCard(ctx);
        card.radiusDp(MiuixTheme.RADIUS_CARD);
        card.fill(MiuixTheme.surface());
        card.stroke(MiuixTheme.outline(), 0.8f);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < PRESET_PARTS.length; i++) {
            final FastbootHost.Part p = new FastbootHost.Part();
            p.name = PRESET_PARTS[i];
            if (i > 0) {
                View line = new View(ctx);
                line.setBackgroundColor(MiuixTheme.outline());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
                lp.leftMargin = dp(14);
                col.addView(line, lp);
            }
            col.addView(partRow(p), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mListHost.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 自定义分区名刷写。 */
    private void customFlashDialog() {
        if (session == null) {
            toast("请先连接设备");
            scan();
            return;
        }
        final EditText partEt = new EditText(ctx);
        partEt.setSingleLine(true);
        partEt.setHint("分区名，如 boot / init_boot / vbmeta");

        final EditText imgEt = new EditText(ctx);
        imgEt.setSingleLine(true);
        imgEt.setHint("/sdcard/Download/xxx.img");

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        TextView warn = new TextView(ctx);
        warn.setText("通过 USB 把镜像写入对方设备的分区。\n"
                + "· 分区名必须准确（可用「读取分区」或 getvar all 确认）\n"
                + "· 刷错分区会导致对方设备无法开机\n"
                + "· 过程中不要拔线");
        warn.setTextSize(12f);
        warn.setTextColor(0xFFC0392B);
        warn.setLineSpacing(dp(2), 1f);
        box.addView(warn);

        TextView l1 = label("分区名");
        box.addView(l1);
        box.addView(partEt);
        TextView l2 = label("镜像文件路径（本机）");
        box.addView(l2);
        box.addView(imgEt);

        MiuixWindow.builder(ctx)
                .title("刷写分区")
                .content(box)
                .scrollable(false)
                .positive("确认执行", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        String name = partEt.getText().toString().trim();
                        String path = imgEt.getText().toString().trim();
                        if (name.isEmpty()) {
                            toast("请填写分区名");
                            return;
                        }
                        if (path.isEmpty()) {
                            toast("请填写镜像路径");
                            return;
                        }
                        File f = new File(path);
                        if (!f.isFile()) {
                            toast("镜像文件不存在");
                            return;
                        }
                        FastbootHost.Part p = new FastbootHost.Part();
                        p.name = name;
                        doFlash(p, f);
                    }
                })
                .negative("取消", null)
                .show();
    }

    private TextView label(String t) {
        TextView v = new TextView(ctx);
        v.setText(t);
        v.setTextSize(11.5f);
        v.setTextColor(MiuixTheme.onSurfaceVariant());
        v.setPadding(0, dp(10), 0, dp(3));
        return v;
    }

    /** 快捷命令列表。 */
    private void renderQuickCommands() {
        if (mQuickHost == null) {
            return;
        }
        mQuickHost.removeAllViews();

        final String[][] cmds = {
                {"重启到系统", "reboot", "对方设备正常重启"},
                {"重启到 bootloader", "reboot-bootloader", "回到 fastboot 模式"},
                {"查询当前槽位", "getvar:current-slot", "A/B 设备当前 slot"},
                {"查询解锁状态", "getvar:unlocked", "是否已解锁 bootloader"},
                {"查询设备信息", "getvar:all", "输出全部变量（含分区大小）"},
                {"切换到 A 槽", "set_active:a", "A/B 设备切 slot a"},
                {"切换到 B 槽", "set_active:b", "A/B 设备切 slot b"},
                {"擦除 misc", "erase:misc", "清除 misc 分区"},
                {"临时启动镜像", "boot", "需要镜像路径，重启后失效"},
                {"解锁 bootloader", "oem unlock", "高危，会清空数据"},
        };

        MiuixCard card = new MiuixCard(ctx);
        card.radiusDp(MiuixTheme.RADIUS_CARD);
        card.fill(MiuixTheme.surface());
        card.stroke(MiuixTheme.outline(), 0.8f);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < cmds.length; i++) {
            final String title = cmds[i][0];
            final String cmd = cmds[i][1];
            final String desc = cmds[i][2];
            if (i > 0) {
                View line = new View(ctx);
                line.setBackgroundColor(MiuixTheme.outline());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
                lp.leftMargin = dp(14);
                col.addView(line, lp);
            }
            col.addView(quickRow(title, cmd, desc), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mQuickHost.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View quickRow(final String title, final String cmd, String desc) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(12), dp(11));
        row.setClickable(true);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(14f);
        t.setTextColor(MiuixTheme.onSurface());
        texts.addView(t);
        TextView d = new TextView(ctx);
        d.setText(cmd + "  ·  " + desc);
        d.setTextSize(10.5f);
        d.setTextColor(MiuixTheme.onSurfaceVariant());
        d.setSingleLine(true);
        d.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(d);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView run = miniButton("执行", MiuixTheme.primaryContainer(),
                ThemeColors.ON_ACCENT_CONTAINER);
        row.addView(run);

        OnClickListener l = new OnClickListener() {
            @Override
            public void onClick(View v) {
                runQuick(title, cmd);
            }
        };
        run.setOnClickListener(l);
        row.setOnClickListener(l);
        return row;
    }

    /** 执行快捷命令。 */
    private void runQuick(final String title, final String cmd) {
        if (session == null) {
            toast("请先连接设备");
            scan();
            return;
        }
        boolean danger = cmd.startsWith("erase:") || cmd.contains("unlock");
        MiuixWindow.builder(ctx)
                .title(title)
                .message("即将执行：\n" + cmd
                        + (danger ? "\n\n⚠ 此操作有风险，确认继续？" : "\n\n确认执行？"))
                .positive("确认执行", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        if ("boot".equals(cmd)) {
                            bootDialog();
                            return;
                        }
                        execCommand(title, cmd);
                    }
                })
                .negative("取消", null)
                .show();
    }

    /** 临时启动镜像。 */
    private void bootDialog() {
        final EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setHint("/sdcard/Download/boot.img");
        MiuixWindow.builder(ctx)
                .title("临时启动镜像")
                .message("把镜像临时加载到内存启动，不写入分区，重启后失效。")
                .content(et)
                .scrollable(false)
                .positive("确认执行", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        String p = et.getText().toString().trim();
                        File f = new File(p);
                        if (!f.isFile()) {
                            toast("镜像文件不存在");
                            return;
                        }
                        bootImage(f);
                    }
                })
                .negative("取消", null)
                .show();
    }

    private void bootImage(final File img) {
        final TextView status = new TextView(ctx);
        status.setText("正在下载并启动…");
        status.setTextSize(12.5f);
        status.setTextColor(MiuixTheme.onSurface());
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(status);
        MiuixWindow.builder(ctx)
                .title("临时启动")
                .content(box)
                .scrollable(false)
                .cancelable(false)
                .negative("后台继续", null)
                .show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                boolean ok = false;
                try {
                    session.download(img, new FastbootHost.Progress() {
                        @Override
                        public void onProgress(long done, long total, String m) {
                            final String s2 = m != null ? m
                                    : ("已传输 " + human(done) + " / " + human(total));
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    status.setText(s2);
                                }
                            });
                        }
                    });
                    FastbootHost.Resp r = session.exec("boot");
                    ok = r.okay;
                    msg = ok ? "已发送 boot 命令，设备正在启动" : ("失败：" + r.payload);
                } catch (Throwable t) {
                    msg = "失败：" + t.getMessage();
                }
                final String fm = msg;
                final boolean fok = ok;
                post(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(fm);
                        status.setTextColor(fok ? MiuixTheme.success() : MiuixTheme.error());
                    }
                });
            }
        }, "fb-boot").start();
    }

    /** 执行并展示结果。 */
    private void execCommand(final String title, final String cmd) {
        final TextView status = new TextView(ctx);
        status.setText("正在执行 " + cmd + " …");
        status.setTextSize(12f);
        status.setTextColor(MiuixTheme.onSurface());
        status.setTextIsSelectable(true);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(status);
        MiuixWindow.builder(ctx)
                .title(title)
                .content(box)
                .scrollable(false)
                .positive("关闭", null)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String out;
                boolean ok = false;
                try {
                    FastbootHost.Resp r = session.exec(cmd);
                    ok = r.okay;
                    String body = (r.log == null ? "" : r.log)
                            + (r.payload == null ? "" : r.payload);
                    out = (ok ? "OK" : "FAIL") + (body.trim().isEmpty() ? "" : "\n\n" + body.trim());
                } catch (Throwable t) {
                    out = "异常：" + t.getMessage();
                }
                final String fo = out;
                final boolean fok = ok;
                post(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(fo);
                        status.setTextColor(fok ? MiuixTheme.success() : MiuixTheme.error());
                    }
                });
            }
        }, "fb-cmd").start();
    }

    private View partRow(final FastbootHost.Part p) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(12), dp(11));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(VERTICAL);
        TextView name = new TextView(ctx);
        name.setText(p.name);
        name.setTextSize(14.5f);
        name.setTextColor(MiuixTheme.onSurface());
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView sub = new TextView(ctx);
        sub.setText(p.size > 0
                ? (human(p.size) + (p.type.isEmpty() ? "" : " · " + p.type))
                : "常用分区 · 点「刷写」选择镜像");
        sub.setTextSize(10.5f);
        sub.setTextColor(MiuixTheme.onSurfaceVariant());
        texts.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.rightMargin = dp(8);
        row.addView(texts, tp);

        if (p.size > 0) {
            TextView backup = miniButton("备份", MiuixTheme.primaryContainer(),
                    ThemeColors.ON_ACCENT_CONTAINER);
            backup.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    backupPartition(p);
                }
            });
            row.addView(backup);
        }

        TextView flash = miniButton("刷写", 0x14E8481F, MiuixTheme.error());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.leftMargin = dp(6);
        flash.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                flashPartition(p);
            }
        });
        row.addView(flash, fp);
        return row;
    }

    private TextView miniButton(String text, int bgColor, int fgColor) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(11.5f);
        b.setTextColor(fgColor);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setClickable(true);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(bgColor);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        return b;
    }

    // ---------------------------------------------------------------- 刷写

    private void flashPartition(final FastbootHost.Part p) {
        if (session == null) {
            toast("请先连接设备");
            return;
        }
        final EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setHint("/sdcard/Download/TF分区备份/" + p.name + ".img");
        et.setText(defaultImageFor(p.name));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        TextView warn = new TextView(ctx);
        warn.setText("通过 USB 把镜像写入对方设备的分区 " + p.name
                + "（" + human(p.size) + "）。\n\n"
                + "· 刷错分区会导致对方设备无法开机\n"
                + "· 请确认镜像与分区匹配，并已提前备份\n"
                + "· 过程中不要拔线\n\n此操作不可撤销。");
        warn.setTextSize(12f);
        warn.setTextColor(0xFFC0392B);
        warn.setLineSpacing(dp(2), 1f);
        box.addView(warn);

        TextView label = new TextView(ctx);
        label.setText("镜像文件路径（本机）");
        label.setTextSize(11.5f);
        label.setTextColor(MiuixTheme.onSurfaceVariant());
        label.setPadding(0, dp(12), 0, dp(4));
        box.addView(label);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MiuixWindow.builder(ctx)
                .title("警告")
                .content(box)
                .scrollable(false)
                .positive("确认执行", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        String path = et.getText().toString().trim();
                        if (path.isEmpty()) {
                            toast("请填写镜像路径");
                            return;
                        }
                        File f = new File(path);
                        if (!f.isFile()) {
                            toast("镜像文件不存在");
                            return;
                        }
                        doFlash(p, f);
                    }
                })
                .negative("取消", null)
                .show();
    }

    private String defaultImageFor(String name) {
        File dir = new File(OUT_BACKUP);
        File[] fs = dir.listFiles();
        if (fs == null) {
            return "";
        }
        File best = null;
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith(name)) {
                continue;
            }
            if (best == null || f.lastModified() > best.lastModified()) {
                best = f;
            }
        }
        return best == null ? "" : best.getAbsolutePath();
    }

    private void doFlash(final FastbootHost.Part p, final File img) {
        final TextView status = new TextView(ctx);
        status.setText("正在下载镜像到设备…");
        status.setTextSize(12.5f);
        status.setTextColor(MiuixTheme.onSurface());

        final ProgressBar bar = new ProgressBar(ctx, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        bp.topMargin = dp(10);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(status);
        box.addView(bar, bp);

        MiuixWindow.builder(ctx)
                .title("刷写 " + p.name)
                .content(box)
                .scrollable(false)
                .cancelable(false)
                .negative("后台继续", null)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    session.flash(p.name, img, new FastbootHost.Progress() {
                        @Override
                        public void onProgress(long done, long total, String msg) {
                            final int pct = total <= 0 ? 0 : (int) (done * 1000 / total);
                            final String m = msg != null ? msg
                                    : ("已传输 " + human(done) + " / " + human(total));
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    bar.setProgress(pct);
                                    status.setText(m);
                                }
                            });
                        }
                    });
                    post(new Runnable() {
                        @Override
                        public void run() {
                            bar.setProgress(1000);
                            status.setText("刷写完成。可点「重启到系统」。");
                            status.setTextColor(MiuixTheme.success());
                        }
                    });
                } catch (final Throwable t) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("刷写失败：" + t.getMessage());
                            status.setTextColor(MiuixTheme.error());
                        }
                    });
                }
            }
        }, "fb-flash").start();
    }

    // ---------------------------------------------------------------- 备份

    private void backupPartition(final FastbootHost.Part p) {
        if (session == null) {
            toast("请先连接设备");
            return;
        }
        final String out = OUT_BACKUP + "/" + p.name + "-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new java.util.Date()) + ".img";

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        TextView warn = new TextView(ctx);
        warn.setText("将尝试从对方设备读回分区 " + p.name + "（" + human(p.size) + "）"
                + "并保存到：\n" + out + "\n\n"
                + "注意：fastboot 标准协议没有「读分区」命令，"
                + "只有部分 bootloader 提供 oem dump / fetch 等扩展。"
                + "若不支持会提示改用本机 root 备份。");
        warn.setTextSize(12f);
        warn.setTextColor(0xFF8A5A00);
        warn.setLineSpacing(dp(2), 1f);
        warn.setTextIsSelectable(true);
        box.addView(warn);

        MiuixWindow.builder(ctx)
                .title("警告")
                .content(box)
                .scrollable(false)
                .positive("确认执行", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        doBackup(p, out);
                    }
                })
                .negative("取消", null)
                .show();
    }

    private void doBackup(final FastbootHost.Part p, final String out) {
        final TextView status = new TextView(ctx);
        status.setText("正在读回分区…");
        status.setTextSize(12.5f);
        status.setTextColor(MiuixTheme.onSurface());

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(status);

        MiuixWindow.builder(ctx)
                .title("备份 " + p.name)
                .content(box)
                .scrollable(false)
                .cancelable(false)
                .negative("后台继续", null)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new File(OUT_BACKUP).mkdirs();
                    session.readPartition(p.name, new File(out),
                            new FastbootHost.Progress() {
                                @Override
                                public void onProgress(long done, long total, String msg) {
                                    final String m = msg == null
                                            ? ("已读取 " + human(done)) : msg;
                                    post(new Runnable() {
                                        @Override
                                        public void run() {
                                            status.setText(m);
                                        }
                                    });
                                }
                            });
                    post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("备份完成：\n" + out);
                            status.setTextColor(MiuixTheme.success());
                        }
                    });
                } catch (final Throwable t) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText(String.valueOf(t.getMessage()));
                            status.setTextColor(MiuixTheme.error());
                        }
                    });
                }
            }
        }, "fb-backup").start();
    }

    // ---------------------------------------------------------------- 重启

    private void reboot(final String target) {
        if (session == null) {
            toast("请先连接设备");
            return;
        }
        MiuixWindow.builder(ctx)
                .title("重启对方设备")
                .message(target.isEmpty() ? "重启到系统？" : "重启到 bootloader？")
                .positive("确认执行", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    session.reboot(target);
                                } catch (Throwable ignored) {
                                }
                                post(new Runnable() {
                                    @Override
                                    public void run() {
                                        closeSession();
                                        scan();
                                    }
                                });
                            }
                        }, "fb-reboot").start();
                    }
                })
                .negative("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 工具

    private static String human(long bytes) {
        if (bytes <= 0) {
            return "未知";
        }
        double b = bytes;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (b >= 1024 && i < u.length - 1) {
            b /= 1024;
            i++;
        }
        return String.format(Locale.US, b >= 100 ? "%.0f %s" : "%.1f %s", b, u[i]);
    }

    private int dp(float v) {
        return UiConfig.dp(ctx, v);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 释放会话（面板销毁时调用）。 */
    public void release() {
        closeSession();
    }
}