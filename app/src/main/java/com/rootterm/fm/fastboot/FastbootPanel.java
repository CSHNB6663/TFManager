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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 数据线刷写（Fastboot 主机模式）。
 *
 * <p>通过 OTG 连接另一台处于 fastboot 模式的手机，刷写镜像、执行快捷命令。</p>
 */
public class FastbootPanel extends LinearLayout {

    private static final String OUT_BACKUP = "/sdcard/Download";

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
        mListHost.setVisibility(GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        addView(mListHost, lp);

        // 自定义命令行
        TextView cmdTitle = new TextView(ctx);
        cmdTitle.setText("命令行");
        cmdTitle.setTextSize(12.5f);
        cmdTitle.setTextColor(MiuixTheme.onSurfaceVariant());
        cmdTitle.setPadding(dp(6), dp(16), dp(6), dp(6));
        addView(cmdTitle);

        MiuixCard cmdCard = new MiuixCard(ctx);
        cmdCard.radiusDp(MiuixTheme.RADIUS_CARD);
        cmdCard.fill(MiuixTheme.surface());
        cmdCard.stroke(MiuixTheme.outline(), 0.8f);
        LinearLayout cmdBox = new LinearLayout(ctx);
        cmdBox.setOrientation(LinearLayout.VERTICAL);
        cmdCard.addView(cmdBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout cmdRow = new LinearLayout(ctx);
        cmdRow.setOrientation(LinearLayout.HORIZONTAL);
        cmdRow.setGravity(Gravity.CENTER_VERTICAL);
        cmdRow.setPadding(dp(12), dp(10), dp(12), dp(10));

        final EditText cmdEt = new EditText(ctx);
        cmdEt.setSingleLine(true);
        cmdEt.setHint("输入 fastboot 命令，如 getvar all");
        cmdEt.setTextSize(13f);
        cmdEt.setTextColor(MiuixTheme.onSurface());
        cmdEt.setHintTextColor(0xFF9AA0A6);
        android.graphics.drawable.GradientDrawable ig =
                new android.graphics.drawable.GradientDrawable();
        ig.setColor(0xFFF2F4F8);
        ig.setCornerRadius(dp(10));
        ig.setStroke(Math.max(1, dp(0.8f)), 0xFFE3E7EE);
        cmdEt.setBackground(ig);
        cmdEt.setPadding(dp(10), dp(8), dp(10), dp(8));
        cmdRow.addView(cmdEt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView runBtn = miniButton("执行", MiuixTheme.primary(), 0xFFFFFFFF);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = dp(8);
        runBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                String cmd = cmdEt.getText().toString().trim();
                if (cmd.isEmpty()) {
                    toast("请输入命令");
                    return;
                }
                if (session == null) {
                    toast("请先连接设备");
                    scan();
                    return;
                }
                execCommand("执行命令", cmd);
            }
        });
        cmdRow.addView(runBtn, rp);
        cmdBox.addView(cmdRow);

        // 常用前缀提示
        TextView cmdTip = new TextView(ctx);
        cmdTip.setText("常用：getvar all · oem device-info · flashing unlock · "
                + "reboot · reboot-bootloader · erase:misc · set_active:a");
        cmdTip.setTextSize(10f);
        cmdTip.setTextColor(MiuixTheme.onSurfaceVariant());
        cmdTip.setLineSpacing(dp(1), 1f);
        cmdTip.setPadding(dp(14), 0, dp(14), dp(10));
        cmdBox.addView(cmdTip);

        addView(cmdCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

    // ---------------------------------------------------------------- 自定义分区刷写

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
                + "· 分区名必须准确（如 boot / init_boot / vbmeta）\n"
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
                {"刷写 boot", "FLASH:boot", "选镜像后直接刷入 boot 分区"},
                {"刷写 init_boot", "FLASH:init_boot", "选镜像后直接刷入 init_boot 分区"},
                {"刷写 vbmeta", "FLASH:vbmeta", "选镜像后直接刷入 vbmeta 分区"},
                {"刷写 recovery", "FLASH:recovery", "选镜像后直接刷入 recovery 分区"},
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
        if (cmd != null && cmd.startsWith("FLASH:")) {
            pickImageDialog(cmd.substring(6), "/sdcard/Download");
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

    // ---------------------------------------------------------------- 选镜像并刷写

    /** 内置文件选择器：选 .img/.bin 后直接刷写指定分区。 */
    private void pickImageDialog(final String part, final String startDir) {
        if (session == null) {
            toast("请先连接设备");
            scan();
            return;
        }
        final String dir = startDir == null || startDir.isEmpty() ? "/sdcard" : startDir;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        final TextView pathView = new TextView(ctx);
        pathView.setText(dir);
        pathView.setTextSize(11f);
        pathView.setTextColor(MiuixTheme.onSurfaceVariant());
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        box.addView(pathView);

        TextView tip = new TextView(ctx);
        tip.setText("选择要刷入 " + part + " 分区的镜像（.img / .bin）");
        tip.setTextSize(11f);
        tip.setTextColor(MiuixTheme.onSurfaceVariant());
        tip.setPadding(0, dp(4), 0, dp(6));
        box.addView(tip);

        ScrollView sv = new ScrollView(ctx);
        LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(VERTICAL);
        sv.addView(listBox);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        final android.app.Dialog dlg = MiuixWindow.builder(ctx)
                .title("选择镜像 · " + part)
                .content(box)
                .scrollable(false)
                .negative("取消", null)
                .show();

        fillPicker(listBox, pathView, dir, part, dlg);
    }

    /** 填充选择器内容。 */
    private void fillPicker(final LinearLayout listBox, final TextView pathView,
                            final String dir, final String part,
                            final android.app.Dialog dlg) {
        listBox.removeAllViews();
        pathView.setText(dir);

        if (!"/".equals(dir) && !"/sdcard".equals(dir)) {
            listBox.addView(pickerRow("⬆  上级目录", true, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    int i = dir.lastIndexOf('/');
                    fillPicker(listBox, pathView, i <= 0 ? "/" : dir.substring(0, i),
                            part, dlg);
                }
            }));
        }
        if ("/sdcard/Download".equals(dir) || "/sdcard".equals(dir)) {
            listBox.addView(pickerRow("📥 下载目录", true, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    fillPicker(listBox, pathView, "/sdcard/Download", part, dlg);
                }
            }));
        }

        File d = new File(dir);
        File[] fs = d.listFiles();
        if (fs == null) {
            listBox.addView(pickerRow("（无法读取该目录）", false, null));
            return;
        }
        java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        int shown = 0;
        for (final File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) {
                continue;
            }
            if (f.isDirectory()) {
                if (shown++ > 200) {
                    break;
                }
                listBox.addView(pickerRow("📁 " + n, true, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        fillPicker(listBox, pathView, f.getAbsolutePath(), part, dlg);
                    }
                }));
            } else {
                String ln = n.toLowerCase(Locale.US);
                if (!ln.endsWith(".img") && !ln.endsWith(".bin")) {
                    continue;
                }
                if (shown++ > 200) {
                    break;
                }
                listBox.addView(pickerRow("💾 " + n + "   " + human(f.length()), true,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                dlg.dismiss();
                                confirmFlashFile(part, f);
                            }
                        }));
            }
        }
        if (shown == 0) {
            listBox.addView(pickerRow("（此目录没有 .img / .bin 镜像）", false, null));
        }
    }

    private View pickerRow(String text, boolean clickable, OnClickListener l) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(clickable ? MiuixTheme.onSurface() : MiuixTheme.onSurfaceVariant());
        t.setPadding(dp(6), dp(10), dp(6), dp(10));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        if (clickable && l != null) {
            t.setClickable(true);
            t.setOnClickListener(l);
        }
        return t;
    }

    /** 选好文件后：警告 + 确认执行。 */
    private void confirmFlashFile(final String part, final File img) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        TextView warn = new TextView(ctx);
        warn.setText("即将把\n" + img.getAbsolutePath() + "\n（" + human(img.length())
                + "）\n\n写入对方设备的分区  " + part + "\n\n"
                + "· 刷错分区会导致对方设备无法开机\n"
                + "· 过程中不要拔线、不要压到数据线\n"
                + "· 此操作不可撤销");
        warn.setTextSize(12f);
        warn.setTextColor(0xFFC0392B);
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
                        FastbootHost.Part p = new FastbootHost.Part();
                        p.name = part;
                        doFlash(p, img);
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
        et.setHint("/sdcard/Download/" + p.name + ".img");
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
                        doReboot(target);
                    }
                })
                .negative("取消", null)
                .show();
    }

    /** 执行重启并展示结果。 */
    private void doReboot(final String target) {
        final TextView status = new TextView(ctx);
        status.setText("正在发送重启命令…");
        status.setTextSize(12.5f);
        status.setTextColor(MiuixTheme.onSurface());
        status.setTextIsSelectable(true);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(status);

        MiuixWindow.builder(ctx)
                .title(target.isEmpty() ? "重启到系统" : "重启到 bootloader")
                .content(box)
                .scrollable(false)
                .positive("关闭", null)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String m = null;
                boolean ok = true;
                try {
                    m = session.reboot(target);
                    ok = m != null && !m.startsWith("重启命令均被拒绝");
                } catch (Throwable t) {
                    m = "异常：" + t.getMessage();
                    ok = false;
                }
                final String msg = m == null ? "无结果" : m;
                final boolean fok = ok;
                post(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(msg);
                        status.setTextColor(fok ? MiuixTheme.success() : MiuixTheme.error());
                        closeSession();
                        mStatus.setText("已发送重启");
                    }
                });
            }
        }, "fb-reboot").start();
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