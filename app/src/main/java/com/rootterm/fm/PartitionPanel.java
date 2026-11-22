package com.rootterm.fm;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.miuix.glass.MiuixGlass;
import com.miuix.pref.MiuixPreference;
import com.miuix.ui.MiuixCard;
import com.miuix.ui.MiuixTheme;
import com.miuix.window.MiuixWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 分区管理：读取分区表、提取镜像、提取分区内文件、刷写分区。
 *
 * <p>需要 root。分区来源优先 {@code /dev/block/by-name}，其次 platform by-name 目录。</p>
 */
public class PartitionPanel extends LinearLayout {

    /** 备份/提取输出根目录。 */
    private static final String OUT_BACKUP = "/sdcard/Download/TF分区备份";
    private static final String OUT_EXTRACT = "/sdcard/Download/TF分区提取";
    /** 只读挂载点根目录。 */
    private static final String MOUNT_ROOT = "/data/local/tmp/tfpart";

    private static final class Part {
        String name = "";
        long size;
        String path = "";
        String type = "";
    }

    private final Context ctx;
    private final List<Part> parts = new ArrayList<Part>();
    private TextView mHint;
    private LinearLayout mListHost;
    private LinearLayout mLocalBox;
    private com.rootterm.fm.fastboot.FastbootPanel mFastboot;
    private TextView mTabLocal;
    private TextView mTabFastboot;
    private boolean mFastbootMode = true;
    private boolean loading;

    public PartitionPanel(Context c) {
        super(c);
        ctx = c;
        setOrientation(VERTICAL);
        setBackgroundColor(MiuixTheme.background());
        build();
    }

    // ---------------------------------------------------------------- UI

    private void build() {
        ScrollView sv = new ScrollView(ctx);
        sv.setBackgroundColor(MiuixTheme.background());

        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(18));

        // 标题 + 刷新
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("分区");
        title.setTextSize(MiuixTheme.TEXT_TITLE);
        title.setTextColor(MiuixTheme.onSurface());
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refresh = new TextView(ctx);
        refresh.setText("刷新");
        refresh.setTextSize(13f);
        refresh.setTextColor(MiuixTheme.primary());
        refresh.setPadding(dp(10), dp(6), dp(6), dp(6));
        refresh.setClickable(true);
        refresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                loadPartitions(true);
            }
        });
        head.addView(refresh);
        page.addView(head);

        // 模式切换：数据线（Fastboot）/ 本机（root）
        page.addView(gap(12));
        LinearLayout modeBar = new LinearLayout(ctx);
        modeBar.setOrientation(HORIZONTAL);
        mTabFastboot = modeButton("数据线刷写", true);
        mTabLocal = modeButton("本机分区", false);
        LinearLayout.LayoutParams mb1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mb1.rightMargin = dp(4);
        modeBar.addView(mTabFastboot, mb1);
        LinearLayout.LayoutParams mb2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mb2.leftMargin = dp(4);
        modeBar.addView(mTabLocal, mb2);
        page.addView(modeBar);

        mTabFastboot.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(true);
            }
        });
        mTabLocal.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(false);
            }
        });

        // ---------- 数据线（Fastboot） ----------
        mFastboot = new com.rootterm.fm.fastboot.FastbootPanel(ctx);
        LinearLayout.LayoutParams fbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fbp.topMargin = dp(14);
        page.addView(mFastboot, fbp);

        // ---------- 本机（root dd） ----------
        mLocalBox = new LinearLayout(ctx);
        mLocalBox.setOrientation(VERTICAL);
        page.addView(mLocalBox);

        mHint = new TextView(ctx);
        mHint.setText("正在读取分区…");
        mHint.setTextSize(11.5f);
        mHint.setTextColor(MiuixTheme.onSurfaceVariant());
        mHint.setPadding(0, dp(6), 0, 0);
        mLocalBox.addView(mHint);

        // 说明卡
        mLocalBox.addView(gap(14));
        MiuixGlass tip = new MiuixGlass(ctx);
        tip.radiusDp(MiuixTheme.RADIUS_MEDIUM);
        tip.tint(0xE8F2F7FF, 0xD9E6EFFF);
        tip.rim(0x99FFFFFF, 0x143482FF);
        tip.paddingDp(14, 12, 14, 12);
        TextView tipText = new TextView(ctx);
        tipText.setText("读取分区与刷写需要 root。刷写会覆盖分区，操作不可撤销——"
                + "请先「提取镜像」备份，再执行刷写。");
        tipText.setTextSize(11.5f);
        tipText.setTextColor(0xFF2A4A7F);
        tipText.setLineSpacing(dp(2), 1f);
        tip.addView(tipText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mLocalBox.addView(tip);

        // 列表
        mLocalBox.addView(gap(14));
        mListHost = new LinearLayout(ctx);
        mListHost.setOrientation(VERTICAL);
        mLocalBox.addView(mListHost);

        sv.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setMode(true);
    }

    /** 模式按钮。 */
    private TextView modeButton(String text, boolean active) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(9), dp(12), dp(9));
        b.setClickable(true);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(active ? MiuixTheme.primary() : 0x14000000);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setTextColor(active ? MiuixTheme.onPrimary() : MiuixTheme.onSurface());
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return b;
    }

    /** 切换模式。 */
    private void setMode(boolean fastboot) {
        mFastbootMode = fastboot;
        if (mFastboot != null) {
            mFastboot.setVisibility(fastboot ? VISIBLE : GONE);
        }
        if (mLocalBox != null) {
            mLocalBox.setVisibility(fastboot ? GONE : VISIBLE);
        }
        styleModeButton(mTabFastboot, fastboot);
        styleModeButton(mTabLocal, !fastboot);
        if (fastboot) {
            if (mFastboot != null) {
                mFastboot.scan();
            }
        } else {
            loadPartitions(false);
        }
    }

    private void styleModeButton(TextView b, boolean active) {
        if (b == null) {
            return;
        }
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(active ? MiuixTheme.primary() : 0x14000000);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setTextColor(active ? MiuixTheme.onPrimary() : MiuixTheme.onSurface());
    }

    /** USB 权限结果（由 MainActivity 转发）。 */
    public void onUsbPermission(boolean granted) {
        if (mFastboot != null) {
            mFastboot.onPermissionResult(granted);
        }
    }

    /** 释放资源。 */
    public void release() {
        if (mFastboot != null) {
            mFastboot.release();
        }
    }

    /** 当前是否数据线模式。 */
    public boolean isFastbootMode() {
        return mFastbootMode;
    }

    private View gap(int v) {
        View g = new View(ctx);
        g.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(v)));
        return g;
    }

    // ---------------------------------------------------------------- 读取分区

    /** 读取分区列表。 */
    public void loadPartitions(boolean manual) {
        if (loading) {
            return;
        }
        loading = true;
        if (manual) {
            mHint.setText("正在重新读取分区…");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Part> list = new ArrayList<Part>();
                String script = ""
                        + "for d in /dev/block/by-name /dev/block/platform/*/by-name "
                        + "/dev/block/mapper; do "
                        + "  [ -d $d ] || continue; "
                        + "  for f in $d/*; do "
                        + "    [ -e $f ] || continue; "
                        + "    n=$(basename $f); "
                        + "    p=$(readlink -f $f 2>/dev/null); "
                        + "    s=$(blockdev --getsize64 $p 2>/dev/null); "
                        + "    [ -z \"$s\" ] && s=0; "
                        + "    echo \"$n|$s|$p\"; "
                        + "  done; "
                        + "done | sort -u -t'|' -k1,1";
                String out = Shell.execSync(script, 25000);
                if (out != null) {
                    for (String line : out.split("\n")) {
                        String t = line.trim();
                        if (t.isEmpty()) {
                            continue;
                        }
                        String[] c = t.split("\\|");
                        if (c.length < 3) {
                            continue;
                        }
                        Part p = new Part();
                        p.name = c[0];
                        try {
                            p.size = Long.parseLong(c[1]);
                        } catch (Throwable ignored) {
                            p.size = 0;
                        }
                        p.path = c[2];
                        p.type = guessType(p.name);
                        list.add(p);
                    }
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        parts.clear();
                        parts.addAll(list);
                        renderList();
                    }
                });
            }
        }, "part-load").start();
    }

    private static String guessType(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith("boot") || n.contains("kernel") || n.contains("dtbo")
                || n.contains("vendor_boot") || n.contains("init_boot")) {
            return "boot";
        }
        if (n.contains("super") || n.contains("system") || n.contains("vendor")
                || n.contains("product") || n.contains("odm")) {
            return "system";
        }
        if (n.contains("userdata") || n.contains("data") || n.contains("cache")) {
            return "data";
        }
        if (n.contains("modem") || n.contains("radio") || n.contains("misc")
                || n.contains("persist") || n.contains("efs")) {
            return "firmware";
        }
        return "other";
    }

    private void renderList() {
        mListHost.removeAllViews();
        if (!Shell.isRoot()) {
            mHint.setText("未获取 root：分区读写需要 root 权限");
            return;
        }
        if (parts.isEmpty()) {
            mHint.setText("没有读到分区（可能是分区布局特殊或需要 root）");
            return;
        }
        long total = 0;
        for (Part p : parts) {
            total += p.size;
        }
        mHint.setText("共 " + parts.size() + " 个分区 · 合计 " + human(total)
                + " · 点按分区可提取 / 刷写");

        MiuixCard card = new MiuixCard(ctx);
        card.radiusDp(MiuixTheme.RADIUS_CARD);
        card.fill(MiuixTheme.surface());
        card.stroke(MiuixTheme.outline(), 0.8f);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < parts.size(); i++) {
            final Part p = parts.get(i);
            if (i > 0) {
                View line = new View(ctx);
                line.setBackgroundColor(MiuixTheme.outline());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
                lp.leftMargin = dp(16);
                col.addView(line, lp);
            }
            col.addView(partRow(p), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mListHost.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View partRow(final Part p) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(12), dp(11));
        row.setClickable(true);

        // 彩色类型图标
        FrameLayout tile = new FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(tileColor(p.type));
        bg.setCornerRadius(dp(11));
        tile.setBackground(bg);
        ImageView iv = new ImageView(ctx);
        int res = getResources().getIdentifier("ic_file_db", "drawable",
                ctx.getPackageName());
        if (res != 0) {
            iv.setImageResource(res);
            iv.setColorFilter(0xFFFFFFFF);
        }
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(19), dp(19));
        ip.gravity = Gravity.CENTER;
        tile.addView(iv, ip);
        row.addView(tile, new LinearLayout.LayoutParams(dp(36), dp(36)));

        // 名称 + 大小/节点
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
        sub.setText(human(p.size) + " · " + p.path);
        sub.setTextSize(10.5f);
        sub.setTextColor(MiuixTheme.onSurfaceVariant());
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        tp.rightMargin = dp(8);
        row.addView(texts, tp);

        // 右侧：备份 / 刷写 两个小按钮
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);

        TextView backup = miniButton("备份", MiuixTheme.primaryContainer(),
                ThemeColors.ON_ACCENT_CONTAINER);
        backup.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                extractImage(p);
            }
        });
        btns.addView(backup);

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
        btns.addView(flash, fp);

        row.addView(btns);

        // 点整行 → 更多（提取分区内文件 / 详情）
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showActions(p);
            }
        });
        return row;
    }

    /** 行内小按钮。 */
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
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private int tileColor(String type) {
        if ("boot".equals(type)) {
            return 0xFFF0A23C;
        }
        if ("system".equals(type)) {
            return 0xFF3482FF;
        }
        if ("data".equals(type)) {
            return 0xFF1FA35B;
        }
        if ("firmware".equals(type)) {
            return 0xFF9B59B6;
        }
        return 0xFF7A8699;
    }

    // ---------------------------------------------------------------- 操作菜单

    private void showActions(final Part p) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        TextView info = new TextView(ctx);
        info.setText("分区：" + p.name + "\n设备：" + p.path + "\n大小：" + human(p.size)
                + (p.size > 0 ? "（" + p.size + " 字节）" : ""));
        info.setTextSize(12f);
        info.setTextColor(MiuixTheme.onSurfaceVariant());
        info.setLineSpacing(dp(2), 1f);
        info.setTextIsSelectable(true);
        box.addView(info);

        MiuixPreference.Group g = MiuixPreference.group(ctx, null);
        g.clickRow("提取镜像", "备份整个分区到 .img 文件", "",
                new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        extractImage(p);
                    }
                });
        g.clickRow("提取分区内文件", "只读挂载后浏览并复制文件", "",
                new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        browseMounted(p);
                    }
                });
        g.clickRow("刷写分区", "把 .img 写入该分区（危险）", "",
                new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        flashPartition(p);
                    }
                });
        View card = g.build();
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(12);
        box.addView(card, cp);

        MiuixWindow.builder(ctx)
                .title(p.name)
                .content(box)
                .scrollable(false)
                .positive("关闭", null)
                .show();
    }

    // ---------------------------------------------------------------- 提取镜像

    private void extractImage(final Part p) {
        if (!Shell.isRoot()) {
            toast("需要 root");
            return;
        }
        final String out = OUT_BACKUP + "/" + p.name + "-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new java.util.Date()) + ".img";

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        TextView warn = new TextView(ctx);
        warn.setText("即将备份分区 " + p.name + "\n\n"
                + "读取量：约 " + human(p.size) + "\n"
                + "输出到：" + out + "\n\n"
                + "过程中请勿断开存储或退出应用。");
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
                        runDd(p, out, true);
                    }
                })
                .negative("取消", null)
                .show();
    }

    /** 执行 dd（备份或刷写）。 */
    private void runDd(final Part p, final String file, final boolean isBackup) {
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        final TextView status = new TextView(ctx);
        status.setText(isBackup ? "正在提取…" : "正在刷写…");
        status.setTextSize(12.5f);
        status.setTextColor(MiuixTheme.onSurface());
        box.addView(status);

        final TextView log = new TextView(ctx);
        log.setTextSize(10.5f);
        log.setTextColor(MiuixTheme.onSurfaceVariant());
        log.setMaxLines(8);
        log.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.addView(log, lp);

        final android.app.Dialog dlg = MiuixWindow.builder(ctx)
                .title(isBackup ? "提取中" : "刷写中")
                .content(box)
                .scrollable(false)
                .cancelable(false)
                .negative("后台继续", null)
                .show();

        final String cmd;
        if (isBackup) {
            cmd = "mkdir -p " + Shell.quote(OUT_BACKUP)
                    + " && dd if=" + Shell.quote(p.path) + " of=" + Shell.quote(file)
                    + " bs=4M 2>&1; echo \"[dd-exit:$?]\"";
        } else {
            cmd = "dd if=" + Shell.quote(file) + " of=" + Shell.quote(p.path)
                    + " bs=4M 2>&1; echo \"[dd-exit:$?]\"; sync";
        }

        Shell.exec(cmd, new Shell.Callback() {
            @Override
            public void onStart() {
            }

            @Override
            public void onLine(final String line) {
                if (line == null) {
                    return;
                }
                log.post(new Runnable() {
                    @Override
                    public void run() {
                        log.setText(line);
                    }
                });
                if (line.contains("[dd-exit:")) {
                    final String code = line.replaceAll(".*\\[dd-exit:(\\d+)\\].*", "$1");
                    status.post(new Runnable() {
                        @Override
                        public void run() {
                            if ("0".equals(code)) {
                                status.setText(isBackup
                                        ? "提取完成：" + file
                                        : "刷写完成，请重启设备");
                                status.setTextColor(MiuixTheme.success());
                            } else {
                                status.setText("失败（dd 退出码 " + code + "）");
                                status.setTextColor(MiuixTheme.error());
                            }
                        }
                    });
                }
            }

            @Override
            public void onDone(final int exitCode) {
                status.post(new Runnable() {
                    @Override
                    public void run() {
                        if (status.getText().toString().contains("正在")) {
                            status.setText(exitCode == 0
                                    ? (isBackup ? "提取完成：" + file : "刷写完成，请重启设备")
                                    : "失败（退出码 " + exitCode + "）");
                            status.setTextColor(exitCode == 0
                                    ? MiuixTheme.success() : MiuixTheme.error());
                        }
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- 刷写

    private void flashPartition(final Part p) {
        if (!Shell.isRoot()) {
            toast("需要 root");
            return;
        }
        final android.widget.EditText et = new android.widget.EditText(ctx);
        et.setSingleLine(true);
        et.setHint("/sdcard/Download/TF分区备份/xxx.img");
        et.setText(lastBackupFor(p.name));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        TextView warn = new TextView(ctx);
        warn.setText("刷写会用镜像内容覆盖分区 " + p.name + "（" + human(p.size) + "）。\n\n"
                + "· 目标设备：" + p.path + "\n"
                + "· 写错分区会导致设备无法开机\n"
                + "· 请确认镜像与分区匹配，并已提前备份\n\n"
                + "此操作不可撤销。");
        warn.setTextSize(12f);
        warn.setTextColor(0xFFC0392B);
        warn.setLineSpacing(dp(2), 1f);
        box.addView(warn);

        TextView label = new TextView(ctx);
        label.setText("镜像文件路径");
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
                        String chk = Shell.execSync("[ -f " + Shell.quote(path)
                                + " ] && echo yes || echo no", 8000);
                        if (chk == null || !chk.contains("yes")) {
                            toast("镜像文件不存在");
                            return;
                        }
                        runDd(p, path, false);
                    }
                })
                .negative("取消", null)
                .show();
    }

    private String lastBackupFor(String name) {
        String out = Shell.execSync("ls -t " + Shell.quote(OUT_BACKUP) + "/" + name
                + "-*.img 2>/dev/null | head -1", 8000);
        return out == null ? "" : out.trim();
    }

    // ---------------------------------------------------------------- 提取分区内文件

    /** 只读挂载分区，然后浏览/复制文件。 */
    private void browseMounted(final Part p) {
        if (!Shell.isRoot()) {
            toast("需要 root");
            return;
        }
        toast("正在挂载…");
        final String mnt = MOUNT_ROOT + "/" + p.name;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String r = Shell.execSync(
                        "mkdir -p " + Shell.quote(mnt)
                                + " && (mount -o ro " + Shell.quote(p.path) + " "
                                + Shell.quote(mnt) + " 2>&1 || "
                                + "mount -t ext4 -o ro " + Shell.quote(p.path) + " "
                                + Shell.quote(mnt) + " 2>&1 || "
                                + "mount -t f2fs -o ro " + Shell.quote(p.path) + " "
                                + Shell.quote(mnt) + " 2>&1); echo \"[m-exit:$?]\"; "
                                + "ls -1 " + Shell.quote(mnt) + " 2>/dev/null | head -3",
                        30000);
                final boolean ok = r != null && r.contains("[m-exit:0]");
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            MiuixWindow.builder(ctx)
                                    .title("无法挂载")
                                    .message("该分区无法只读挂载（常见于加密分区、"
                                            + "动态分区或文件系统不被内核支持）。\n\n"
                                            + "可以改用「提取镜像」把整分区备份成 .img，"
                                            + "再在电脑上挂载提取文件。")
                                    .positive("知道了", null)
                                    .show();
                            return;
                        }
                        showMountBrowser(p, mnt, "");
                    }
                });
            }
        }, "part-mount").start();
    }

    /** 挂载点内的文件浏览（可下钻、可复制）。 */
    private void showMountBrowser(final Part p, final String mnt, final String rel) {
        final String dir = rel.isEmpty() ? mnt : mnt + "/" + rel;
        String out = Shell.execSync("ls -1 " + Shell.quote(dir) + " 2>/dev/null", 15000);
        final List<String> names = new ArrayList<String>();
        if (out != null) {
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !".".equals(t) && !"..".equals(t)) {
                    names.add(t);
                }
            }
        }

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);

        TextView pathView = new TextView(ctx);
        pathView.setText("/" + rel);
        pathView.setTextSize(11f);
        pathView.setTextColor(MiuixTheme.onSurfaceVariant());
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        box.addView(pathView);

        ListView lv = new ListView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        lp.topMargin = dp(8);
        box.addView(lv, lp);

        final ArrayAdapterSimple ad = new ArrayAdapterSimple(names, mnt, dir);
        lv.setAdapter(ad);

        final android.app.Dialog dlg = MiuixWindow.builder(ctx)
                .title(p.name + " 内文件")
                .content(box)
                .scrollable(false)
                .positive("全部复制出来", new MiuixWindow.OnAction() {
                    @Override
                    public void onClick(android.app.Dialog d) {
                        copyFromMount(p, mnt, rel, true, null);
                    }
                })
                .neutral("返回上级", null)
                .negative("关闭", null)
                .show();

        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                String nm = names.get(position);
                String child = rel.isEmpty() ? nm : rel + "/" + nm;
                String isDir = Shell.execSync("[ -d " + Shell.quote(mnt + "/" + child)
                        + " ] && echo d || echo f", 8000);
                dlg.dismiss();
                if (isDir != null && isDir.contains("d")) {
                    showMountBrowser(p, mnt, child);
                } else {
                    copyFromMount(p, mnt, child, false, child);
                }
            }
        });
    }

    /** 从挂载点复制文件/目录到 /sdcard/Download/TF分区提取/。 */
    private void copyFromMount(final Part p, final String mnt, final String rel,
                               final boolean all, final String single) {
        final String dst = OUT_EXTRACT + "/" + p.name;
        final String src = all ? mnt + (rel.isEmpty() ? "" : "/" + rel)
                : mnt + "/" + single;
        toast("正在复制…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String cmd = "mkdir -p " + Shell.quote(dst) + " && cp -rf "
                        + Shell.quote(src) + " " + Shell.quote(dst) + "/ 2>&1; "
                        + "echo \"[c-exit:$?]\"";
                final String r = Shell.execSync(cmd, 120000);
                final boolean ok = r != null && r.contains("[c-exit:0]");
                post(new Runnable() {
                    @Override
                    public void run() {
                        MiuixWindow.builder(ctx)
                                .title(ok ? "复制完成" : "复制失败")
                                .message(ok ? "已复制到：\n" + dst
                                        : "复制失败：\n" + (r == null ? "" : r))
                                .positive("知道了", null)
                                .show();
                    }
                });
            }
        }, "part-copy").start();
    }

    // ---------------------------------------------------------------- 简单列表适配器

    private final class ArrayAdapterSimple extends BaseAdapter {
        private final List<String> data;
        private final String mnt;
        private final String dir;

        ArrayAdapterSimple(List<String> d, String mnt, String dir) {
            this.data = d;
            this.mnt = mnt;
            this.dir = dir;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(9), dp(4), dp(9));

            String nm = data.get(position);
            String isDir = Shell.execSync("[ -d " + Shell.quote(mnt + "/" + nm)
                    + " ] && echo d || echo f", 6000);
            boolean d = isDir != null && isDir.contains("d");

            ImageView iv = new ImageView(ctx);
            int res = getResources().getIdentifier(d ? "ic_file_dir" : "ic_file_unknown",
                    "drawable", ctx.getPackageName());
            if (res != 0) {
                iv.setImageResource(res);
            }
            row.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));

            TextView tv = new TextView(ctx);
            tv.setText(nm);
            tv.setTextSize(13f);
            tv.setTextColor(MiuixTheme.onSurface());
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = dp(10);
            row.addView(tv, tp);
            return row;
        }
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

    private void postUi(Runnable r) {
        if (r != null) {
            post(r);
        }
    }
}