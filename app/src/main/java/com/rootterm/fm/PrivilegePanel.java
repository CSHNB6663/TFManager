package com.rootterm.fm;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

import java.util.Locale;

/**
 * 提权页：检测本机环境、判断内核是否属于已知可提权构建，并提供引导入口。
 *
 * <p><b>本页不内置任何内核提权载荷</b>。原因：载荷属第三方私有代码，
 * 且与具体内核构建号死绑，误用会导致内核 panic / 无法开机；
 * 公开分发漏洞利用代码亦有合规风险。这里只做「环境检测 + 兼容性判断 + 引导」。</p>
 */
public class PrivilegePanel extends LinearLayout {

    private final Context ctx;

    private TextView mModel;
    private TextView mKernel;
    private TextView mSelinux;
    private TextView mRootState;
    private TextView mSuPath;
    private TextView mCompat;
    private TextView mToolState;
    private boolean checked;

    public PrivilegePanel(Context c) {
        super(c);
        ctx = c;
        setOrientation(VERTICAL);
        setBackgroundColor(0x00000000);
        build();
    }

    // ---------------------------------------------------------------- UI

    private void build() {
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalFadingEdgeEnabled(false);
        sv.setBackgroundColor(0x00000000);

        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(160));

        TextView title = UiConfig.text(ctx, "提权", 30f, MiuixTheme.onSurface());
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        page.addView(title);

        TextView sub = UiConfig.text(ctx, "检测本机环境 · 判断内核是否可提权", 11.5f,
                MiuixTheme.onSurfaceVariant());
        sub.setPadding(0, dp(6), 0, 0);
        page.addView(sub);

        // ---------- 环境卡 ----------
        page.addView(gap(14));
        MiuixCard env = MiuixCard.create(ctx);
        LinearLayout envCol = new LinearLayout(ctx);
        envCol.setOrientation(LinearLayout.VERTICAL);
        env.addView(envCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mModel = infoRow(envCol, "设备", "…");
        mKernel = wrapRow(envCol, "内核版本", "…");
        mSelinux = infoRow(envCol, "SELinux", "…");
        mRootState = infoRow(envCol, "Root 状态", "…");
        mSuPath = infoRow(envCol, "su 路径", "…");
        page.addView(env);

        // ---------- 兼容性卡 ----------
        page.addView(gap(14));
        MiuixGlass compatCard = new MiuixGlass(ctx);
        compatCard.radiusDp(MiuixTheme.RADIUS_MEDIUM);
        compatCard.tint(0xFFEAF1FF, 0xFFDCE8FF);
        compatCard.rim(0x40FFFFFF, 0x143482FF);
        compatCard.specular(false);
        compatCard.paddingDp(16, 14, 16, 14);
        LinearLayout compatBox = new LinearLayout(ctx);
        compatBox.setOrientation(LinearLayout.VERTICAL);
        compatCard.addView(compatBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView compatTitle = UiConfig.text(ctx, "提权兼容性", 15f, MiuixTheme.onSurface());
        compatTitle.setTypeface(compatTitle.getTypeface(), android.graphics.Typeface.BOLD);
        compatBox.addView(compatTitle);

        mCompat = UiConfig.text(ctx, "正在检测…", 12f, 0xFF2A4A7F);
        mCompat.setPadding(0, dp(6), 0, 0);
        mCompat.setLineSpacing(dp(2), 1f);
        compatBox.addView(mCompat);
        page.addView(compatCard);

        // ---------- 提权工具 ----------
        page.addView(gap(14));
        MiuixPreference.Group tool = MiuixPreference.group(ctx, "提权工具");
        tool.iconClickRow(resId("ic_nav_root"), ThemeColors.ACCENT,
                "打开提权工具", "已安装则直接启动", "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        launchTool();
                    }
                });
        tool.iconClickRow(resId("ic_file_text"), ThemeColors.ACCENT,
                "查看提权日志", "读取最近一次交接日志", "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        showLog();
                    }
                });
        tool.iconClickRow(resId("ic_nav_root"), MiuixTheme.success(),
                "重新检测环境", "刷新机型 / 内核 / root 状态", "", new MiuixPreference.OnClick() {
                    @Override
                    public void onClick(View v) {
                        detect();
                    }
                });
        View toolCard = tool.build();
        mToolState = UiConfig.text(ctx, "", 11f, MiuixTheme.onSurfaceVariant());
        page.addView(toolCard);

        // ---------- 说明 ----------
        page.addView(gap(14));
        MiuixPreference.Group notice = MiuixPreference.group(ctx, "说明");
        TextView note = UiConfig.text(ctx,
                "本 App 不内置内核提权载荷。\n\n"
                        + "· 提权载荷与具体内核构建号死绑，偏移错一个字节就会内核 panic 或无法开机\n"
                        + "· 载荷属第三方私有代码，随公开仓库分发有合规风险\n\n"
                        + "本页只做环境检测与兼容性判断；实际提权请使用对应机型的专用工具，"
                        + "并在操作前备份数据、确认内核版本完全一致。",
                11.5f, MiuixTheme.onSurfaceVariant());
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout noteWrap = new LinearLayout(ctx);
        noteWrap.setOrientation(LinearLayout.VERTICAL);
        noteWrap.addView(note);
        notice.customRow(noteWrap);
        page.addView(notice.build());

        sv.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        detect();
    }

    private View gap(int v) {
        View g = new View(ctx);
        g.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(v)));
        return g;
    }

    private TextView infoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (parent.getChildCount() > 0) {
            parent.addView(line());
        }
        TextView t = UiConfig.text(ctx, label, 14f, MiuixTheme.onSurface());
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = UiConfig.text(ctx, value, 12.5f, MiuixTheme.onSurfaceVariant());
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(dp(170),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
        return v;
    }

    private TextView wrapRow(LinearLayout parent, String label, String value) {
        if (parent.getChildCount() > 0) {
            parent.addView(line());
        }
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.addView(UiConfig.text(ctx, label, 14f, MiuixTheme.onSurface()));
        TextView v = UiConfig.text(ctx, value, 11.5f, MiuixTheme.onSurfaceVariant());
        v.setPadding(0, dp(4), 0, 0);
        v.setTextIsSelectable(true);
        row.addView(v);
        parent.addView(row);
        return v;
    }

    private View line() {
        View v = new View(ctx);
        v.setBackgroundColor(MiuixTheme.outline());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    private int resId(String name) {
        return ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
    }

    // ---------------------------------------------------------------- 检测

    /** 检测环境（后台线程）。 */
    public void detect() {
        if (checked) {
            mCompat.setText("正在重新检测…");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String kernel = Shell.execSync("uname -r 2>/dev/null", 8000).trim();
                String se = Shell.execSync(
                        "getenforce 2>/dev/null || /system/bin/getenforce 2>/dev/null", 8000).trim();
                final boolean root = Shell.detectRoot();
                final String su = Shell.suPath();
                final String model = Build.MANUFACTURER + " " + Build.MODEL;
                final String seText;
                if ("Enforcing".equalsIgnoreCase(se)) {
                    seText = "强制执行（Enforcing）";
                } else if ("Permissive".equalsIgnoreCase(se)) {
                    seText = "宽容（Permissive）";
                } else if (se.isEmpty()) {
                    seText = "未知";
                } else {
                    seText = se;
                }

                final String profile = PrivProfiles.match(kernel);
                final boolean toolInstalled = isToolInstalled();

                postUi(new Runnable() {
                    @Override
                    public void run() {
                        checked = true;
                        if (mModel != null) {
                            mModel.setText(model);
                        }
                        if (mKernel != null) {
                            mKernel.setText(kernel.isEmpty() ? "未知" : kernel);
                        }
                        if (mSelinux != null) {
                            mSelinux.setText(seText);
                        }
                        if (mRootState != null) {
                            mRootState.setText(root ? "已 root" : "未 root");
                            mRootState.setTextColor(root ? MiuixTheme.success() : MiuixTheme.error());
                        }
                        if (mSuPath != null) {
                            mSuPath.setText(su == null || su.isEmpty() ? "—" : su);
                        }
                        renderCompat(kernel, profile, root, toolInstalled);
                    }
                });
            }
        }, "priv-detect").start();
    }

    private void renderCompat(String kernel, String profile, boolean root, boolean toolInstalled) {
        if (mCompat == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (profile != null) {
            sb.append("✓ 内核属于已知可提权构建\n");
            sb.append("· 匹配机型档案：").append(profile).append('\n');
        } else if (kernel.isEmpty()) {
            sb.append("· 读不到内核版本，无法判断\n");
        } else {
            sb.append("· 当前内核不在已知档案中\n");
            sb.append("· 已知档案共 ").append(PrivProfiles.size()).append(" 个机型构建\n");
        }
        sb.append('\n');
        if (root) {
            sb.append("· 本机已有 root，无需提权；可直接使用终端 / 分区功能\n");
        } else if (profile != null) {
            sb.append("· 本机未 root，且内核有对应档案 → 可用对应工具尝试提权\n");
        } else {
            sb.append("· 本机未 root，内核也无档案 → 不建议尝试，失败可能无法开机\n");
        }
        sb.append('\n');
        sb.append(toolInstalled
                ? "· 已检测到提权工具，可点「打开提权工具」启动"
                : "· 未检测到提权工具（包名 hr.h）");
        mCompat.setText(sb.toString());
    }

    // ---------------------------------------------------------------- 动作

    private boolean isToolInstalled() {
        try {
            return ctx.getPackageManager()
                    .getLaunchIntentForPackage("hr.h") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void launchTool() {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage("hr.h");
            if (i == null) {
                MiuixWindow.builder(ctx)
                        .title("未安装提权工具")
                        .message("本 App 不内置内核提权载荷。\n\n"
                                + "请自行获取对应机型的专用提权工具并安装，"
                                + "确认内核版本与本机完全一致后再操作。\n\n"
                                + "当前内核：\n" + mKernel.getText())
                        .positive("知道了", null)
                        .show();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            toast("无法启动：" + t.getMessage());
        }
    }

    private void showLog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String dir = "/sdcard/Download/临时ROOT测试日志";
                String latest = Shell.execSync(
                        "ls -t " + Shell.quote(dir) + "/*.log 2>/dev/null | head -1", 8000).trim();
                String text;
                if (latest == null || latest.isEmpty()) {
                    text = "没有找到日志。\n\n目录：\n" + dir
                            + "\n\n（该目录由提权工具在执行后写入）";
                } else {
                    String body = Shell.execSync(
                            "tail -n 200 " + Shell.quote(latest) + " 2>/dev/null", 12000);
                    text = "文件：" + latest + "\n\n" + (body == null ? "(空)" : body);
                }
                final String f = text;
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        MiuixWindow.builder(ctx)
                                .title("提权日志")
                                .message(f)
                                .positive("关闭", null)
                                .show();
                    }
                });
            }
        }, "priv-log").start();
    }

    // ---------------------------------------------------------------- 工具

    private int dp(float v) {
        return UiConfig.dp(ctx, v);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void postUi(Runnable r) {
        if (ctx instanceof android.app.Activity) {
            ((android.app.Activity) ctx).runOnUiThread(r);
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        }
    }
}
