package com.rootterm.fm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.coui.widget.CouiCardView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件管理面板（MT 风格浅色目录界面）。
 *
 * <p>白底、大图标列表、顶部居中路径 + 统计；自身不再提供底栏（由 {@link DualFilePanel}
 * 的全局底栏承担），但通过 {@link Host} 与 {@link #setActive(boolean)} 参与双栏联动。</p>
 *
 * <p>自包含的 {@link LinearLayout} 子类：不引用 {@code R.*}、不用 AndroidX / 布局 XML；
 * 所有文件系统读取与写操作走 {@link Shell}，阻塞操作全部在后台线程执行，
 * 结果回到主线程更新 UI。复制/剪切剪贴板为 static，两栏共享。</p>
 */
public class FileBrowserPanel extends LinearLayout {

    // ------------------------------------------------------------ MIUIX（HyperOS）配色

    private static final int F_BG = 0xFFFFFFFF;        // 列表/卡片底
    private static final int F_PAGE = 0xFFF7F8FA;      // 页面底色（MIUIX 灰）
    private static final int F_TEXT = 0xFF191919;      // 主文字
    private static final int F_DIM = 0xFF8A8F98;       // 次要文字
    private static final int F_LINE = 0xFFF0F1F3;      // 分隔线
    private static final int F_ICON = 0xFF191919;      // 顶栏图标
    private static int F_ROOT = 0xFF3482FF;          // 主色（Monet 动态）
    private static int F_ACCENT = 0xFF3482FF;        // 强调色（Monet 动态）
    private static final int F_ICON_BG = 0xFF3482FF;   // 文件夹图标底（蓝）
    private static final int F_ICON_FILE = 0xFFAAB0B8; // 文件图标底（灰）
    private static final int F_ICON_LINK = 0xFFF0A23C; // 链接图标底（橙）
    private static final int F_ICON_UNKNOWN = 0xFFC6CBD1;
    private static final int F_WHITE = 0xFFFFFFFF;
    private static final int F_RED = 0xFFE8481F;
    private static final int F_FIELD = 0xFFF2F3F5;     // 输入/胶囊底
    private static final int F_RADIUS_CARD = 16;
    private static final int F_RADIUS_ICON = 12;

    /** 浅色 Material 对话框主题。 */
    private static final int LIGHT_DIALOG = android.R.style.Theme_Material_Light_Dialog_Alert;

    // ------------------------------------------------------------ 常量

    private static final int SORT_NAME = 0;
    private static final int SORT_SIZE = 1;
    private static final int SORT_TIME = 2;

    private static final int MAX_ENTRIES = 2000;
    private static final int HISTORY_MAX = 50;
    private static final long TEXT_SIZE_LIMIT = 512L * 1024L;

    // 文件类型图标（MIUIX 风格圆角方块 + 白色字形；字形来自 MDI 图标集）
    private static final int ICON_DIR = 0;
    private static final int ICON_FILE = 1;
    private static final int ICON_LINK = 2;
    private static final int ICON_UNKNOWN = 3;
    private static final int ICON_TEXT = 4;
    private static final int ICON_CODE = 5;
    private static final int ICON_IMAGE = 6;
    private static final int ICON_VIDEO = 7;
    private static final int ICON_AUDIO = 8;
    private static final int ICON_APK = 9;
    private static final int ICON_ARCHIVE = 10;
    private static final int ICON_PDF = 11;
    private static final int ICON_CONFIG = 12;
    private static final int ICON_DB = 13;
    private static final int ICON_FONT = 14;
    private static final int ICON_EBOOK = 15;
    private static final int ICON_SHEET = 16;
    private static final int ICON_SLIDE = 17;

    /** 每种类型对应的 MIUIX 底色。 */
    private static int colorForType(int type) {
        switch (type) {
            case ICON_DIR:
                return F_ACCENT;
            case ICON_APK:
                return 0xFF00C853;
            case ICON_IMAGE:
                return 0xFF34C759;
            case ICON_VIDEO:
                return 0xFFF2555A;
            case ICON_AUDIO:
                return 0xFFAF52DE;
            case ICON_ARCHIVE:
                return 0xFFFF9F0A;
            case ICON_PDF:
                return 0xFFE8481F;
            case ICON_CODE:
                return 0xFF00A3A3;
            case ICON_CONFIG:
                return 0xFF8E8E93;
            case ICON_DB:
                return 0xFF6B7B8C;
            case ICON_FONT:
                return 0xFFC86BFF;
            case ICON_EBOOK:
                return 0xFFFFB300;
            case ICON_SHEET:
                return 0xFF21A366;
            case ICON_SLIDE:
                return 0xFFFF7A00;
            case ICON_LINK:
                return 0xFFF0A23C;
            case ICON_UNKNOWN:
                return 0xFFC6CBD1;
            default:
                return 0xFF5C6BC0;
        }
    }

    private static final String[] TEXT_EXT = {
            ".txt", ".conf", ".cfg", ".ini", ".sh", ".rc", ".xml", ".json", ".log", ".md",
            ".prop", ".yml", ".yaml", ".csv", ".java", ".js", ".py", ".html", ".htm", ".css",
            ".kt", ".c", ".h", ".cpp", ".rb", ".lua", ".properties"
    };

    private static final String[] SCRIPT_EXT = {
            ".sh", ".bash", ".zsh", ".ksh", ".csh", ".py", ".pl", ".rb", ".lua"
    };

    private static final String[] VIDEO_EXT = {
            ".mp4", ".mkv", ".avi", ".mov", ".webm", ".3gp", ".flv", ".ts", ".m4v", ".wmv"
    };

    private static final String[] AUDIO_EXT = {
            ".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac", ".ape", ".amr", ".opus", ".mid", ".wma"
    };

    private static final String[] ARCHIVE_EXT = {
            ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz", ".zst", ".jar", ".apks", ".xapk"
    };

    private static final String[] SHEET_EXT = {
            ".xls", ".xlsx", ".ods", ".csv"
    };

    private static final String[] SLIDE_EXT = {
            ".ppt", ".pptx", ".odp"
    };

    private static final String[] DOC_EXT = {
            ".doc", ".docx", ".odt", ".rtf", ".pages", ".wps"
    };

    private static final String[] CODE_EXT = {
            ".java", ".js", ".ts", ".py", ".sh", ".bash", ".zsh", ".c", ".h", ".cpp", ".cc",
            ".hpp", ".kt", ".kts", ".rb", ".lua", ".php", ".go", ".rs", ".swift", ".pl", ".sql",
            ".html", ".htm", ".css", ".scss", ".vue", ".dart"
    };

    private static final String[] CONFIG_EXT = {
            ".conf", ".cfg", ".ini", ".prop", ".properties", ".rc", ".toml", ".yml", ".yaml", ".xml"
    };

    private static final String[] FONT_EXT = {
            ".ttf", ".otf", ".woff", ".woff2", ".ttc"
    };

    private static final String[] EBOOK_EXT = {
            ".epub", ".mobi", ".azw", ".azw3", ".fb2", ".djvu"
    };

    private static final String[] DB_EXT = {
            ".db", ".sqlite", ".sqlite3", ".realm", ".mdb"
    };

    private static final String[] IMAGE_EXT = {
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"
    };

    private static final Pattern LS_MODERN = Pattern.compile(
            "^(\\S{10,11})\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+([\\d,]+(?:,\\s*\\d+)?)"
                    + "\\s+(\\d{4}-\\d{2}-\\d{2}[_ ]\\d{2}:\\d{2}(?::\\d{2})?)\\s+(.*)$");

    private static final Pattern LS_CLASSIC = Pattern.compile(
            "^(\\S{10,11})\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+([\\d,]+(?:,\\s*\\d+)?)"
                    + "\\s+([A-Za-z]{3}\\s+\\d{1,2}\\s+(?:\\d{2}:\\d{2}|\\d{4}))\\s+(.*)$");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // ------------------------------------------------------------ 字段

    /** 运行文件时交给宿主（MainActivity）在终端里执行。 */
    public interface Runner {
        void runInTerminal(String cmd);
    }

    private Runner terminalRunner;

    /** 设置「跳转到终端执行」的回调（由 MainActivity 注入）。 */
    public void setTerminalRunner(Runner r) {
        terminalRunner = r;
    }

    private final Context ctx;
    private final Activity act;

    private final ArrayList<Entry> entries = new ArrayList<>();
    private final ArrayList<Entry> visible = new ArrayList<>();

    private String cwd = "/";
    private boolean showHidden = false;
    private int sortMode = SORT_NAME;
    private String pendingSelect = null;

    /** 复制/剪切剪贴板：源绝对路径；sCut=true 表示剪切。双栏共享（static），实现栏间互拷。 */
    private static String sClip = null;
    private static boolean sCut = false;

    private int loadSeq = 0;
    /** 下次 apply 时是否播放入场动画（只在切目录时播）。 */
    private boolean pendingAnimateIn;

    private TextView pathText;
    private TextView statsText;
    private TextView identityTag;
    private ListView list;
    private EntryAdapter adapter;
    private TextView emptyView;
    private View activeBar;

    private AlertDialog activeDialog;

    /** 路径历史栈（最多 HISTORY_MAX 条）。 */
    private final ArrayList<String> history = new ArrayList<>();
    private int historyIndex = -1;

    /** 当前目录的存储统计（df 解析结果，失败为空）。 */
    private String spaceAvail = "";
    private String spaceTotal = "";

    private Host host;
    private boolean active = false;
    private int headerLeftInsetDp = 14;

    // ------------------------------------------------------------ 对外接口

    /** 双栏容器（DualFilePanel）回调。 */
    public interface Host {
        void onToggleDual();

        void onSwap();

        boolean isDual();

        void onPaneActivated(FileBrowserPanel pane);
    }

    private interface InputCallback {
        void onInput(String text);
    }

    // ------------------------------------------------------------ 构造 / 布局

    public FileBrowserPanel(Context ctx) {
        super(ctx);
        this.ctx = ctx;
        this.act = (ctx instanceof Activity) ? (Activity) ctx : null;
        setOrientation(VERTICAL);
        setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        F_ACCENT = ThemeColors.ACCENT;
        F_ROOT = ThemeColors.ACCENT;
        setBackgroundColor(F_PAGE);

        history.add("/");
        historyIndex = 0;

        buildHeader();
        buildList();

        activeBar = new View(ctx);
        activeBar.setBackgroundColor(F_ROOT);
        activeBar.setVisibility(active ? VISIBLE : GONE);
        addView(activeBar, new LinearLayout.LayoutParams(MATCH, dpI(2)));

        updateStats();
        reload();
        // MainActivity 也会探测，这里再兜一次：探测完成后刷新身份标签；拿到 root 则重载一遍
        Shell.detectRootAsync(new Runnable() {
            @Override
            public void run() {
                updateIdentityTag();
                if (Shell.isRoot()) {
                    reload();
                }
            }
        });
    }

    /** MIUIX（HyperOS）风头部：圆角路径胶囊 + ⋮ / 统计行。双栏时不会重复出现大标题。 */
    private void buildHeader() {
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(F_PAGE);
        header.setPadding(dpI(headerLeftInsetDp), dpI(10), dpI(14), dpI(6));

        // 第一行：圆角路径胶囊（可点输入）+ ⋮
        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(F_FIELD);
        pillBg.setCornerRadius(dpI(14));
        pill.setBackground(pillBg);
        pill.setPadding(dpI(12), dpI(7), dpI(12), dpI(7));

        pathText = UiConfig.text(ctx, cwd, 12.5f, F_TEXT);
        pathText.setSingleLine(true);
        pathText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pathText.setClickable(true);
        pathText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptPath();
            }
        });
        pill.addView(pathText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row1.addView(pill, new LinearLayout.LayoutParams(0, WRAP, 1f));
        header.addView(row1, new LinearLayout.LayoutParams(MATCH, WRAP));

        // 第二行：统计（文件夹/文件/储存） + ROOT/USER 小字
        LinearLayout row3 = new LinearLayout(ctx);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        row3.setPadding(dpI(4), dpI(7), dpI(4), 0);

        statsText = UiConfig.text(ctx, "", 11f, F_DIM);
        statsText.setSingleLine(true);
        statsText.setEllipsize(TextUtils.TruncateAt.END);
        row3.addView(statsText, new LinearLayout.LayoutParams(0, WRAP, 1f));

        identityTag = UiConfig.text(ctx, Shell.isRoot() ? "ROOT" : "USER", 9.5f,
                Shell.isRoot() ? F_ACCENT : F_DIM);
        identityTag.setSingleLine(true);
        row3.addView(identityTag, new LinearLayout.LayoutParams(WRAP, WRAP));
        header.addView(row3, new LinearLayout.LayoutParams(MATCH, WRAP));

        addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void buildList() {
        FrameLayout wrap = new FrameLayout(ctx);

        list = new ListView(ctx);
        list.setBackgroundColor(F_BG);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(0);
        list.setVerticalScrollBarEnabled(true);
        // 无点击动画：完全透明的 selector（不用 Ripple / 状态 selector）
        list.setSelector(new ColorDrawable(0x00000000));
        adapter = new EntryAdapter(this);
        list.setAdapter(adapter);

        emptyView = new TextView(ctx);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText("加载中…");
        emptyView.setTextColor(F_DIM);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        emptyView.setBackgroundColor(F_BG);
        emptyView.setPadding(dpI(24), dpI(24), dpI(24), dpI(24));

        wrap.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
        wrap.addView(emptyView, new FrameLayout.LayoutParams(MATCH, MATCH));
        list.setEmptyView(emptyView);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= visible.size()) {
                    return;
                }
                activate();
                final Entry entry = visible.get(position);
                if (view != null) {
                    // 只做视觉反馈，不阻塞打开动作
                    view.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.9f)
                            .setDuration(60).start();
                    view.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                    .setDuration(60).start();
                        }
                    }, 60);
                }
                onClickEntry(entry);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= visible.size()) {
                    return false;
                }
                activate();
                showItemMenu(visible.get(position));
                return true;
            }
        });

        addView(wrap, new LinearLayout.LayoutParams(MATCH, 0, 1f));
    }

    /** 点击本栏任意位置时通知 Host，让全局底栏作用到本栏。 */
    private void activate() {
        if (host != null) {
            try {
                host.onPaneActivated(this);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 数据加载

    /** 后台加载目录，主线程 apply。 */
    private void reload() {
        final String dir = cwd;
        final int seq = ++loadSeq;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                LoadResult loaded;
                try {
                    loaded = load(dir);
                } catch (Throwable t) {
                    loaded = new LoadResult();
                    loaded.error = "加载异常：" + t.getClass().getSimpleName();
                }
                final LoadResult r = loaded;
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != loadSeq || !dir.equals(cwd)) {
                            return; // 已经切到别的目录，丢弃旧结果
                        }
                        apply(r);
                    }
                });
            }
        }, "filebrowser-load");
        t.setDaemon(true);
        t.start();
    }

    private LoadResult load(String dir) {
        final LoadResult res = new LoadResult();
        final String q = Shell.quote(dir);

        String out = safeExec("ls -la --time-style=+%Y-%m-%d_%H:%M " + q + " 2>&1", 20000);
        if (containsIgnoreCase(out, "permission denied")) {
            res.denied = true;
        }
        parseLs(out, dir, res);

        if (res.entries.isEmpty()) {
            // toybox 不支持 --time-style：降级为普通长列表
            String out2 = safeExec("ls -la " + q + " 2>&1", 20000);
            if (containsIgnoreCase(out2, "permission denied")) {
                res.denied = true;
            }
            parseLs(out2, dir, res);
            out = out + "\n" + out2;
        }
        if (res.entries.isEmpty()) {
            // 再兜底：cd 进去用 stat 逐项格式化
            String st = safeExec("cd " + q + " 2>/dev/null && stat -c '%n|%A|%U|%G|%s|%y' * .* 2>/dev/null",
                    20000);
            parseStat(st, dir, res);
            if (res.entries.isEmpty()) {
                out = out + "\n" + st;
            }
        }
        if (res.entries.isEmpty() && !res.denied) {
            // 最后兜底：只拿名字
            String fo = safeExec("find " + q + " -maxdepth 1 2>/dev/null | head -2001", 20000);
            parseFind(fo, dir, res);
        }
        res.error = firstErrorLine(out);
        dfStat(dir, res);
        return res;
    }

    /** 列表淡出 + 左移（进入目录前）。 */
    private void animateListOut(final Runnable end) {
        if (list == null) {
            if (end != null) {
                end.run();
            }
            return;
        }
        list.animate().cancel();
        list.setAlpha(1f);
        list.animate().translationX(-dpI(14)).setDuration(90)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        list.setAlpha(1f);
                        if (end != null) {
                            end.run();
                        }
                    }
                }).start();
    }

    /** 列表淡入 + 从右侧滑入（新目录内容）。 */
    private void animateListIn() {
        if (list == null) {
            return;
        }
        list.animate().cancel();
        list.setAlpha(0.55f);
        list.setTranslationX(dpI(14));
        list.animate().alpha(1f).translationX(0f).setDuration(150)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (list != null) {
                            list.setAlpha(1f);
                            list.setTranslationX(0f);
                        }
                    }
                }).start();
    }

    private void apply(LoadResult r) {
        if (r == null) {
            return;
        }
        entries.clear();
        entries.addAll(r.entries);
        rebuildVisible();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (pathText != null) {
            pathText.setText(cwd);
        }
        spaceAvail = r.spaceAvail == null ? "" : r.spaceAvail;
        spaceTotal = r.spaceTotal == null ? "" : r.spaceTotal;
        updateStats();
        if (visible.isEmpty()) {
            if (r.denied) {
                emptyView.setText("无权限：需要 root\n\n" + cwd);
                emptyView.setTextColor(F_RED);
            } else if (r.error != null && !r.error.isEmpty()) {
                emptyView.setText("加载失败\n" + r.error);
                emptyView.setTextColor(F_RED);
            } else {
                emptyView.setText("空目录");
                emptyView.setTextColor(F_DIM);
            }
        }
        if (r.truncated) {
            toastLong("条目过多，仅显示前 " + MAX_ENTRIES + " 项");
        }
        if (pendingSelect != null) {
            final String target = pendingSelect;
            pendingSelect = null;
            int idx = indexOfVisible(target);
            if (idx < 0 && !showHidden) {
                showHidden = true;
                rebuildVisible();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                updateStats();
                idx = indexOfVisible(target);
            }
            if (idx >= 0) {
                list.setSelection(idx);
                final int fIdx = idx;
                list.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            list.smoothScrollToPosition(fIdx);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                toast("已定位：" + basename(target));
            } else {
                toast("未找到：" + target);
            }
        }
    }

    private int indexOfVisible(String path) {
        if (path == null) {
            return -1;
        }
        for (int i = 0; i < visible.size(); i++) {
            Entry e = visible.get(i);
            if (e != null && path.equals(e.path)) {
                return i;
            }
        }
        return -1;
    }

    private void rebuildVisible() {
        visible.clear();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e == null || e.name == null) {
                continue;
            }
            if (!showHidden && e.name.startsWith(".")) {
                continue;
            }
            visible.add(e);
        }
        Collections.sort(visible, comparatorFor(sortMode));
    }

    /** 刷新统计行（文件夹数 / 文件数 / 储存 可用/总量 + ROOT/USER 小字）。 */
    private void updateStats() {
        if (statsText == null) {
            return;
        }
        int dirs = 0;
        int files = 0;
        for (int i = 0; i < visible.size(); i++) {
            Entry e = visible.get(i);
            if (e == null) {
                continue;
            }
            if (e.isDir) {
                dirs++;
            } else {
                files++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("文件夹: ").append(dirs).append("  文件: ").append(files);
        if (!spaceAvail.isEmpty() && !spaceTotal.isEmpty()) {
            sb.append("  储存: ").append(spaceAvail).append('/').append(spaceTotal);
        }
        statsText.setText(sb.toString());
        updateIdentityTag();
    }

    private void updateIdentityTag() {
        if (identityTag == null) {
            return;
        }
        boolean root = Shell.isRoot();
        identityTag.setText(root ? "ROOT" : "USER");
        identityTag.setTextColor(root ? F_ROOT : F_DIM);
    }

    // ------------------------------------------------------------ ls / stat / find 解析

    private static void parseLs(String out, String dir, LoadResult res) {
        if (out == null) {
            return;
        }
        String[] lines = out.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Entry e = parseLsLine(line, dir);
            if (e != null) {
                addEntry(res, e);
                continue;
            }
            String t = line == null ? "" : line.trim();
            if (t.isEmpty() || looksLikeError(t) || t.startsWith("total")) {
                continue;
            }
            // 解析不出的行：原样显示，不崩溃
            Entry raw = new Entry();
            raw.name = t;
            raw.raw = t;
            raw.unknown = true;
            raw.path = joinPath(dir, t);
            addEntry(res, raw);
        }
    }

    private static Entry parseLsLine(String line, String dir) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty() || t.startsWith("total ")) {
            return null;
        }
        Matcher m = LS_MODERN.matcher(t);
        if (!m.matches()) {
            m = LS_CLASSIC.matcher(t);
            if (!m.matches()) {
                return null;
            }
        }
        String perm = m.group(1);
        String owner = m.group(3);
        String group = m.group(4);
        String sizeS = m.group(5);
        String timeS = m.group(6);
        String name = m.group(7);
        if (perm == null || perm.isEmpty() || name == null) {
            return null;
        }
        char type = perm.charAt(0);
        if (type != 'd' && type != '-' && type != 'l' && type != 'c' && type != 'b'
                && type != 'p' && type != 's') {
            return null;
        }
        Entry e = new Entry();
        e.perm = perm;
        e.owner = owner == null ? "" : owner;
        e.group = group == null ? "" : group;
        e.size = parseSize(sizeS);
        e.isDir = type == 'd';
        e.isLink = type == 'l';
        e.time = normalizeTime(timeS);
        String nm = name;
        if (e.isLink) {
            int i = nm.indexOf(" -> ");
            if (i > 0) {
                e.linkTarget = nm.substring(i + 4).trim();
                nm = nm.substring(0, i);
            }
        }
        nm = nm.trim();
        if (nm.isEmpty() || ".".equals(nm) || "..".equals(nm)) {
            return null;
        }
        e.name = nm;
        e.path = joinPath(dir, nm);
        return e;
    }

    private static void parseStat(String out, String dir, LoadResult res) {
        if (out == null) {
            return;
        }
        String[] lines = out.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i] == null ? "" : lines[i].trim();
            if (t.isEmpty() || t.indexOf('|') <= 0) {
                continue;
            }
            String[] p = t.split("\\|", 6);
            if (p.length < 6) {
                continue;
            }
            String name = p[0];
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)
                    || name.endsWith("/.") || name.endsWith("/..")) {
                continue;
            }
            if (!p[4].trim().matches("[\\d,]+")) {
                continue;
            }
            Entry e = new Entry();
            e.name = basename(name);
            e.path = name.startsWith("/") ? name : joinPath(dir, e.name);
            e.perm = p[1];
            e.owner = p[2];
            e.group = p[3];
            e.size = parseSize(p[4]);
            e.time = normalizeTime(p[5]);
            char type = e.perm.isEmpty() ? '-' : e.perm.charAt(0);
            e.isDir = type == 'd';
            e.isLink = type == 'l';
            if (e.name.isEmpty()) {
                continue;
            }
            addEntry(res, e);
        }
    }

    private static void parseFind(String out, String dir, LoadResult res) {
        if (out == null) {
            return;
        }
        String base = dir == null || dir.isEmpty() ? "/" : dir;
        String[] lines = out.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i] == null ? "" : lines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.equals(dir) || t.equals(base)) {
                continue;
            }
            String name;
            if (t.startsWith(base)) {
                name = t.substring(base.length());
                if (name.startsWith("/")) {
                    name = name.substring(1);
                }
            } else {
                name = t;
            }
            if (name.isEmpty() || name.indexOf('/') >= 0 || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            Entry e = new Entry();
            e.name = name;
            e.path = joinPath(dir, name);
            e.unknown = true;
            addEntry(res, e);
        }
    }

    private static void addEntry(LoadResult res, Entry e) {
        if (res == null || e == null) {
            return;
        }
        if (res.entries.size() >= MAX_ENTRIES) {
            res.truncated = true;
            return;
        }
        res.entries.add(e);
    }

    // ------------------------------------------------------------ df（可用/总量）

    private static void dfStat(String dir, LoadResult res) {
        String q = Shell.quote(dir);
        String out = safeExecStatic("df -h " + q + " 2>&1", 10000);
        if (!parseDf(out, res)) {
            out = safeExecStatic("df " + q + " 2>&1", 10000);
            parseDf(out, res);
        }
    }

    private static boolean parseDf(String out, LoadResult res) {
        if (out == null || res == null) {
            return false;
        }
        String[] lines = out.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i] == null ? "" : lines[i].trim();
            if (t.isEmpty() || t.startsWith("Filesystem")) {
                continue;
            }
            String[] tok = t.split("\\s+");
            if (tok.length < 4) {
                continue;
            }
            int pct = -1;
            for (int j = 0; j < tok.length; j++) {
                if (tok[j].endsWith("%")) {
                    pct = j;
                    break;
                }
            }
            String size;
            String avail;
            if (pct >= 3) {
                size = tok[pct - 3];
                avail = tok[pct - 1];
            } else if (tok.length >= 7) {
                size = tok[1];
                avail = tok[3];
            } else {
                continue;
            }
            String availS = tokenToSize(avail);
            String totalS = tokenToSize(size);
            if (availS.isEmpty() || totalS.isEmpty()) {
                continue;
            }
            res.spaceAvail = availS;
            res.spaceTotal = totalS;
            return true;
        }
        return false;
    }

    private static String tokenToSize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.matches("[\\d,]+")) {
            try {
                return humanSize(Long.parseLong(s.replace(",", "")) * 1024L);
            } catch (Throwable ignored) {
            }
        }
        return s;
    }

    // ------------------------------------------------------------ 列表 Adapter / 行

    private RowHolder buildRow() {
        RowHolder h = new RowHolder();

        // coui 卡片行：外层用 padding 模拟条目间距（ListView item 不支持 margin）
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(new AbsListView.LayoutParams(MATCH, WRAP));
        wrap.setPadding(dpI(10), dpI(3), dpI(10), dpI(3));

        CouiCardView card = new CouiCardView(ctx);
        card.setCardCornerRadiusDp(14);
        card.setCardBackgroundColor(F_BG);
        card.setCardBorderWidth(0);
        wrap.addView(card, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpI(14), 0, dpI(14), 0);
        row.setMinimumHeight(dpI(56));
        card.addView(row, new FrameLayout.LayoutParams(MATCH, WRAP));

        h.icon = new IconView(ctx);
        row.addView(h.icon, new LinearLayout.LayoutParams(dpI(40), dpI(40)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tlp.leftMargin = dpI(14);
        row.addView(texts, tlp);

        h.name = UiConfig.text(ctx, "", 15f, F_TEXT);
        h.name.setSingleLine(true);
        h.name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(h.name, new LinearLayout.LayoutParams(MATCH, WRAP));

        h.meta = UiConfig.text(ctx, "", 11f, F_DIM);
        h.meta.setSingleLine(true);
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        mlp.topMargin = dpI(2);
        texts.addView(h.meta, mlp);

        wrap.setTag(h);
        h.row = wrap;
        return h;
    }

    private int iconTypeFor(Entry e) {
        if (e == null) {
            return ICON_FILE;
        }
        if (e.unknown) {
            return ICON_UNKNOWN;
        }
        if (e.isLink) {
            return ICON_LINK;
        }
        if (e.isDir) {
            return ICON_DIR;
        }
        String n = e.name == null ? "" : e.name.toLowerCase(Locale.US);
        if (n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk")) {
            return ICON_APK;
        }
        if (hasExt(n, IMAGE_EXT)) {
            return ICON_IMAGE;
        }
        if (hasExt(n, VIDEO_EXT)) {
            return ICON_VIDEO;
        }
        if (hasExt(n, AUDIO_EXT)) {
            return ICON_AUDIO;
        }
        if (hasExt(n, ARCHIVE_EXT)) {
            return ICON_ARCHIVE;
        }
        if (n.endsWith(".pdf")) {
            return ICON_PDF;
        }
        if (hasExt(n, SHEET_EXT)) {
            return ICON_SHEET;
        }
        if (hasExt(n, SLIDE_EXT)) {
            return ICON_SLIDE;
        }
        if (hasExt(n, DOC_EXT)) {
            return ICON_TEXT;
        }
        if (hasExt(n, CODE_EXT)) {
            return ICON_CODE;
        }
        if (hasExt(n, CONFIG_EXT)) {
            return ICON_CONFIG;
        }
        if (hasExt(n, FONT_EXT)) {
            return ICON_FONT;
        }
        if (hasExt(n, EBOOK_EXT)) {
            return ICON_EBOOK;
        }
        if (hasExt(n, DB_EXT)) {
            return ICON_DB;
        }
        if (hasExt(n, TEXT_EXT)) {
            return ICON_TEXT;
        }
        return ICON_FILE;
    }

    /** 行视图缓存。 */
    private static final class RowHolder {
        View row;
        IconView icon;
        TextView name;
        TextView meta;
    }

    /** 自绘 BaseAdapter（嵌套 static，持有面板引用）。 */
    private static final class EntryAdapter extends BaseAdapter {

        private final FileBrowserPanel p;

        EntryAdapter(FileBrowserPanel p) {
            this.p = p;
        }

        @Override
        public int getCount() {
            return p.visible.size();
        }

        @Override
        public Object getItem(int position) {
            return p.visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder h;
            if (convertView == null) {
                h = p.buildRow();
                convertView = h.row;
            } else {
                h = (RowHolder) convertView.getTag();
            }
            Entry e = p.visible.get(position);
            h.icon.setType(p.iconTypeFor(e));
            h.name.setText(e.displayName());
            h.meta.setText(p.metaText(e));
            return convertView;
        }
    }

    /** 行第二行：日期 + 符号链接目标。 */
    private String metaText(Entry e) {
        StringBuilder sb = new StringBuilder();
        String t = shortTime(e == null ? null : e.time);
        if (!t.isEmpty()) {
            sb.append(t);
        }
        if (e != null && e.isLink && e.linkTarget != null && !e.linkTarget.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append("-> ").append(e.linkTarget);
        }
        return sb.toString();
    }

    /** {@code yyyy-MM-dd HH:mm} → {@code MM-dd HH:mm}；无法识别时原样返回。 */
    private static String shortTime(String time) {
        if (time == null) {
            return "";
        }
        String t = time.trim();
        if (t.isEmpty()) {
            return "";
        }
        if (t.length() >= 16 && t.charAt(4) == '-' && t.charAt(7) == '-') {
            return t.substring(5, 16);
        }
        return t;
    }

    /**
     * 44dp 目录/文件/链接图标：黑色圆角方块 + 白色图形，全部 Canvas 自绘，无位图资源。
     */
    private static final class IconView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int type = ICON_FILE;
        private Drawable glyph;

        IconView(Context c) {
            super(c);
            glyph = loadGlyph(type);
        }

        void setType(int t) {
            if (type != t) {
                type = t;
                glyph = loadGlyph(t);
                invalidate();
            }
        }

        private Drawable loadGlyph(int t) {
            String name = glyphResName(t);
            if (name == null) {
                return null;
            }
            try {
                int id = getContext().getResources().getIdentifier(name, "drawable",
                        getContext().getPackageName());
                if (id == 0) {
                    return null;
                }
                return getContext().getResources().getDrawable(id, null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static String glyphResName(int t) {
            switch (t) {
                case ICON_DIR:
                    return "ic_file_dir";
                case ICON_TEXT:
                    return "ic_file_text";
                case ICON_CODE:
                    return "ic_file_code";
                case ICON_IMAGE:
                    return "ic_file_image";
                case ICON_VIDEO:
                    return "ic_file_video";
                case ICON_AUDIO:
                    return "ic_file_audio";
                case ICON_APK:
                    return "ic_file_apk";
                case ICON_ARCHIVE:
                    return "ic_file_archive";
                case ICON_PDF:
                    return "ic_file_pdf";
                case ICON_CONFIG:
                    return "ic_file_config";
                case ICON_DB:
                    return "ic_file_db";
                case ICON_FONT:
                    return "ic_file_font";
                case ICON_EBOOK:
                    return "ic_file_ebook";
                case ICON_SHEET:
                    return "ic_file_sheet";
                case ICON_SLIDE:
                    return "ic_file_slide";
                case ICON_LINK:
                    return "ic_file_link";
                case ICON_UNKNOWN:
                    return "ic_file_unknown";
                default:
                    return "ic_file_text";
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int hgt = getHeight();
            if (w <= 0 || hgt <= 0) {
                return;
            }
            float size = Math.min(w, hgt);
            float inset = size * 0.06f;
            float left = (w - size) / 2f + inset;
            float top = (hgt - size) / 2f + inset;
            float right = left + size - inset * 2f;
            float bottom = top + size - inset * 2f;
            float radius = size * 0.30f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colorForType(type));
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, radius, radius, paint);

            if (glyph != null) {
                float gs = (right - left) * 0.58f;
                int gx = Math.round((left + right) / 2f - gs / 2f);
                int gy = Math.round((top + bottom) / 2f - gs / 2f);
                glyph.setBounds(gx, gy, gx + Math.round(gs), gy + Math.round(gs));
                glyph.setAlpha(255);
                glyph.draw(canvas);
            }
        }
    }

    private void onClickEntry(Entry e) {
        if (e == null) {
            return;
        }
        if (e.unknown) {
            toast("未识别条目，直接打开菜单");
            genericMenu(e);
            return;
        }
        if (e.isDir) {
            navigateTo(e.path);
            return;
        }
        String lower = e.name.toLowerCase(Locale.US);
        if (lower.endsWith(".apk")) {
            apkMenu(e);
            return;
        }
        if (hasExt(e.name, IMAGE_EXT)) {
            imageMenu(e);
            return;
        }
        if (isExecutable(e)) {
            execMenu(e);
            return;
        }
        if (isTextLike(e)) {
            openEditor(e);
            return;
        }
        genericMenu(e);
    }

    private void openEditor(final Entry e) {
        if (act == null) {
            return;
        }
        FileEditor.open(act, e.path, new Runnable() {
            @Override
            public void run() {
                reload();
            }
        });
    }

    private void apkMenu(final Entry e) {
        String[] items = {"查看属性", "复制路径", "以 root 安装"};
        menu(e.name, items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    showProps(e);
                } else if (which == 1) {
                    copyPath(e.path);
                } else if (which == 2) {
                    installApk(e);
                }
            }
        });
    }

    private void installApk(final Entry e) {
        if (!Shell.isRoot()) {
            AlertDialog.Builder b = lightBuilder();
            b.setTitle("安装 APK 需要 root");
            b.setMessage("当前身份为 " + Shell.identity() + "，无法调用系统安装器。\n\n"
                    + "可以复制路径后从系统文件管理器手动安装。");
            b.setPositiveButton("复制路径", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    copyPath(e.path);
                }
            });
            b.setNegativeButton("关闭", null);
            showAlert(b);
            return;
        }
        toastLong("开始安装 " + e.name + " …（root 授权可能弹窗）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder out = new StringBuilder();
                int code = Shell.execSyncCode("pm install -r " + Shell.quote(e.path), 180000, out);
                final int fc = code;
                final String fo = out.toString();
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = fc == 0 && containsIgnoreCase(fo, "success");
                        toastLong(ok ? "安装成功：" + e.name
                                : "安装失败(" + fc + ")：" + safeLine(fo, fc));
                        showTextDialog("安装结果 · " + e.name, fo.isEmpty() ? "(无输出)" : fo);
                    }
                });
            }
        }, "filebrowser-apk").start();
    }

    private void execMenu(final Entry e) {
        ArrayList<String> items = new ArrayList<>();
        items.add("以 root 运行");
        items.add("以当前身份运行");
        if (isTextLike(e)) {
            items.add("编辑");
        }
        items.add("属性");
        items.add("复制路径");
        final String[] arr = items.toArray(new String[0]);
        menu(e.name, arr, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                String label = arr[which];
                if ("以 root 运行".equals(label)) {
                    runScript(e, true);
                } else if ("以当前身份运行".equals(label)) {
                    runScript(e, false);
                } else if ("编辑".equals(label)) {
                    openEditor(e);
                } else if ("属性".equals(label)) {
                    showProps(e);
                } else if ("复制路径".equals(label)) {
                    copyPath(e.path);
                }
            }
        });
    }

    /** 运行脚本：先自动挑解释器（bash → shebang → sh），再弹出运行窗口实时输出。 */
    private void runScript(final Entry e, final boolean asRoot) {
        if (asRoot && !Shell.isRoot()) {
            toastLong("以 root 运行需要 root 权限（当前身份 " + Shell.identity() + "）");
            return;
        }
        if (!asRoot && Shell.isRoot()) {
            toast("当前身份已是 root，命令仍以 root 执行");
        }
        if (act == null) {
            return;
        }
        final String path = e.path;
        final String who = asRoot ? "root" : Shell.identity();

        // 从文件管理器运行时：自动跳到终端页面并在终端里执行（不再弹小窗口）
        if (terminalRunner != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String cmd = buildRunCommand(path);
                    postUi(new Runnable() {
                        @Override
                        public void run() {
                            terminalRunner.runInTerminal(cmd);
                        }
                    });
                }
            }, "run-to-terminal").start();
            return;
        }

        final TextView outView = UiConfig.mono(act, "", 11f, F_TEXT);
        outView.setTextIsSelectable(true);
        final int pad = dpI(10);
        outView.setPadding(pad, dpI(6), pad, dpI(6));
        final ScrollView sv = new ScrollView(act);
        sv.setBackgroundColor(F_BG);
        sv.addView(outView, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(F_BG);
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, dpI(300)));

        AlertDialog.Builder b = lightBuilder();
        b.setTitle("运行 · " + e.name);
        b.setView(box);
        b.setPositiveButton("关闭", null);
        b.setNegativeButton("复制输出", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                copyText("运行输出", outView.getText().toString());
            }
        });
        AlertDialog dlg = showAlert(b);
        if (dlg == null) {
            toastLong("无法打开运行窗口：" + e.name);
            return;
        }
        outView.append("[准备] 检测解释器…\n");

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 先判断是「文本脚本」还是「ELF 二进制/其它可执行文件」
                final String cmd = buildRunCommand(path);
                final boolean elf = cmd.contains("chmod 711 ");
                final String interp = elf ? null : pickInterpreter(path);
                final String kindTip = elf
                        ? "[检测] 这是一个 ELF 可执行文件（不是文本脚本），直接以 root 运行\n"
                        : "[检测] 文本脚本，解释器: " + interp + "\n";
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        outView.append(kindTip);
                        outView.append("[身份 " + who + "] " + cmd + "\n\n");
                        Shell.exec(cmd, new Shell.Callback() {
                            @Override
                            public void onStart() {
                            }

                            @Override
                            public void onLine(String line) {
                                outView.append(Ansi.parse(line + "\n", F_TEXT));
                                sv.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        sv.fullScroll(View.FOCUS_DOWN);
                                    }
                                });
                            }

                            @Override
                            public void onDone(int exitCode) {
                                outView.append("\n[完成 exit " + exitCode + "]\n");
                                if (exitCode == 126 || exitCode == 127) {
                                    outView.append("\n[提示] exit " + exitCode
                                            + "：执行权限/依赖库问题。可试着复制到"
                                            + " /data/local/tmp 再运行（该目录保证可执行）。\n");
                                }
                                if (exitCode != 0 && outView.getText().toString()
                                        .contains("syntax error")) {
                                    outView.append("\n[提示] 该脚本需要 bash 语法，但内置 bash 释放失败。\n"
                                            + "  · 需要 root（用于在 /data/local/tmp 安装内置 bash）\n"
                                            + "  · 或安装 Termux 后 pkg install bash，App 也会自动用它\n");
                                }
                                sv.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        sv.fullScroll(View.FOCUS_DOWN);
                                    }
                                });
                            }
                        });
                    }
                });
            }
        }, "run-detect").start();
    }

    /** 组装运行命令：ELF 直接执行；文本脚本用自动挑的解释器。后台线程调用。 */
    private String buildRunCommand(String path) {
        // 关键：先切到文件所在目录。很多加载器/下载器会把临时文件写到 cwd，
        // 而终端默认 cwd 是 /（只读），写入失败就会出现「下载失败」。
        String dir = "/";
        int slash = path == null ? -1 : path.lastIndexOf('/');
        if (slash > 0) {
            dir = path.substring(0, slash);
        }
        // 给动态链接库留路径（驱动加载器常依赖 vendor/lib64 等）
        // 有些加载器会用 TMPDIR/TEMP 下载临时文件，指向文件所在目录（可写）
        String cd = "cd " + Shell.quote(dir) + " && export LD_LIBRARY_PATH=/vendor/lib64:/system/lib64:/system/lib:/vendor/lib:" + Shell.quote(dir) + " TMPDIR=" + Shell.quote(dir) + " TEMP=" + Shell.quote(dir) + " && ";

        String magic = Shell.execSync(
                "head -c 4 " + Shell.quote(path) + " 2>/dev/null | od -An -tx1",
                6000).trim().replace(" ", "").toLowerCase(Locale.US);
        if (magic.startsWith("7f454c46")) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return cd + "chmod 711 " + Shell.quote(name) + " && ./" + Shell.quote(name) + " 2>&1";
        }
        return cd + BashKit.command(pickInterpreter(path), path);
    }

    /**
     * 选解释器：优先 bash（脚本语法兼容性最好），其次读脚本 shebang，最后 /system/bin/sh。
     * 必须在后台线程调用。
     */
    private String pickInterpreter(String path) {
        String probe = Shell.execSync(
                "for p in /system/bin/bash /system/xbin/bash /data/adb/ksu/bin/bash"
                        + " /data/adb/ap/bin/bash /data/adb/magisk/bash"
                        + " /data/data/com.termux/files/usr/bin/bash; do "
                        + "if [ -x $p ]; then echo $p; exit 0; fi; done; "
                        + "command -v bash 2>/dev/null", 8000).trim();
        for (String line : probe.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && t.startsWith("/")) {
                return t;
            }
        }
        // 设备上没有 bash 时，用 App 内置的 bash（首次自动释放到 /data/local/tmp/tfbash）
        String bundled = BashKit.ensure(ctx);
        if (bundled != null) {
            return bundled;
        }
        String first = Shell.execSync("head -n 1 " + Shell.quote(path) + " 2>/dev/null", 5000).trim();
        if (first.startsWith("#!")) {
            String shebang = first.substring(2).trim();
            for (String tok : shebang.split("\\s+")) {
                if (tok.endsWith("/bash")) {
                    return "bash";
                }
                if (tok.endsWith("/sh") || tok.endsWith("/mksh") || tok.endsWith("/ash")
                        || tok.endsWith("/dash")) {
                    return tok;
                }
                if (tok.endsWith("/env")) {
                    continue;
                }
            }
        }
        return "/system/bin/sh";
    }

    private void imageMenu(final Entry e) {
        String[] items = {"复制路径", "用系统查看器打开", "属性"};
        menu(e.name, items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    copyPath(e.path);
                } else if (which == 1) {
                    openWithSystem(e);
                } else if (which == 2) {
                    showProps(e);
                }
            }
        });
    }

    private void openWithSystem(final Entry e) {
        if (act == null) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("file://" + e.path), mimeFor(e.name));
            act.startActivity(Intent.createChooser(i, "打开 " + e.name));
        } catch (Throwable t) {
            // Android 7+ 没有 FileProvider 时 file:// 会被拒（FileUriExposedException）
            copyPath(e.path);
            toastLong("无法直接打开（未配置 FileProvider），已复制路径，请到系统相册/文件管理器打开");
        }
    }

    private void genericMenu(final Entry e) {
        ArrayList<String> items = new ArrayList<>();
        items.add("复制路径");
        items.add("属性");
        if (isTextLike(e)) {
            items.add("编辑");
        }
        if (isExecutable(e)) {
            items.add("以 root 运行");
        }
        items.add("分享");
        items.add("删除");
        final String[] arr = items.toArray(new String[0]);
        menu(e.name, arr, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                String label = arr[which];
                if ("复制路径".equals(label)) {
                    copyPath(e.path);
                } else if ("属性".equals(label)) {
                    showProps(e);
                } else if ("编辑".equals(label)) {
                    openEditor(e);
                } else if ("以 root 运行".equals(label)) {
                    runScript(e, true);
                } else if ("分享".equals(label)) {
                    sharePath(e);
                } else if ("删除".equals(label)) {
                    confirmDelete(e);
                }
            }
        });
    }

    // ------------------------------------------------------------ 长按菜单

    private void showItemMenu(final Entry e) {
        ArrayList<String> items = new ArrayList<>();
        items.add("复制");
        items.add("剪切");
        items.add("粘贴");
        items.add("重命名");
        items.add("删除");
        items.add("chmod");
        items.add("属性");
        items.add("分享");
        items.add("复制路径");
        if (isExecutable(e)) {
            items.add("以 root 运行");
        }
        if (isTextLike(e)) {
            items.add("编辑");
        }
        final String[] arr = items.toArray(new String[0]);
        menu(e.name, arr, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                String label = arr[which];
                if ("复制".equals(label)) {
                    sClip = e.path;
                    sCut = false;
                    toast("已复制：" + e.name);
                } else if ("剪切".equals(label)) {
                    sClip = e.path;
                    sCut = true;
                    toast("已剪切：" + e.name);
                } else if ("粘贴".equals(label)) {
                    paste();
                } else if ("重命名".equals(label)) {
                    promptRename(e);
                } else if ("删除".equals(label)) {
                    confirmDelete(e);
                } else if ("chmod".equals(label)) {
                    promptChmod(e);
                } else if ("属性".equals(label)) {
                    showProps(e);
                } else if ("分享".equals(label)) {
                    sharePath(e);
                } else if ("复制路径".equals(label)) {
                    copyPath(e.path);
                } else if ("以 root 运行".equals(label)) {
                    runScript(e, true);
                } else if ("编辑".equals(label)) {
                    openEditor(e);
                }
            }
        });
    }

    // ------------------------------------------------------------ 复制 / 粘贴 / 删除 / chmod

    private void paste() {
        if (sClip == null || sClip.isEmpty()) {
            toastLong("剪贴板为空：先长按文件「复制」或「剪切」");
            return;
        }
        final String src = sClip;
        final boolean cut = sCut;
        String name = basename(src);
        if (name.isEmpty()) {
            toast("源路径无效");
            return;
        }
        final String dst = joinPath(cwd, name);
        if (dst.equals(src)) {
            toast("源路径与目标路径相同");
            return;
        }
        if (dst.startsWith(src.endsWith("/") ? src : src + "/")) {
            toastLong("不能粘贴到源目录自身的子目录中");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String chk = Shell.execSync("test -e " + Shell.quote(dst) + " && echo __YES__ || echo __NO__",
                        10000);
                final boolean exists = chk != null && chk.contains("__YES__");
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (exists) {
                            AlertDialog.Builder b = lightBuilder();
                            b.setTitle("目标已存在");
                            b.setMessage("是否覆盖？\n\n" + dst);
                            b.setPositiveButton("覆盖", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    doPaste(src, dst, cut, true);
                                }
                            });
                            b.setNegativeButton("取消", null);
                            showAlert(b);
                        } else {
                            doPaste(src, dst, cut, false);
                        }
                    }
                });
            }
        }, "filebrowser-paste-check").start();
    }

    private void doPaste(final String src, final String dst, final boolean cut, final boolean overwrite) {
        String prefix = overwrite ? "rm -rf -- " + Shell.quote(dst) + " && " : "";
        final String cmd = prefix + (cut ? "mv -- " : "cp -a -- ")
                + Shell.quote(src) + " " + Shell.quote(dst);
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder out = new StringBuilder();
                int code = Shell.execSyncCode(cmd, 120000, out);
                final int fc = code;
                final String fo = out.toString();
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (fc == 0) {
                            toastLong(cut ? "已移动" : "已复制");
                            if (cut) {
                                sClip = null;
                                sCut = false;
                            }
                            reload();
                        } else {
                            toastLong("操作失败：" + safeLine(fo, fc));
                        }
                    }
                });
            }
        }, "filebrowser-paste").start();
    }

    private void confirmDelete(final Entry e) {
        AlertDialog.Builder b = lightBuilder();
        b.setTitle("确认删除");
        b.setMessage("将永久删除" + (e.isDir ? "目录及其全部内容" : "文件") + "：\n\n"
                + e.path + "\n\n此操作不可撤销。");
        b.setPositiveButton("删除", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                execAction("rm -rf -- " + Shell.quote(e.path), "已删除：" + e.name);
            }
        });
        b.setNegativeButton("取消", null);
        showAlert(b);
    }

    private void promptChmod(final Entry e) {
        promptInput("chmod " + e.name, "644", "3~4 位八进制，如 644 / 755",
                new InputCallback() {
                    @Override
                    public void onInput(String s) {
                        if (s == null || !s.matches("[0-7]{3,4}")) {
                            toastLong("权限格式无效：应为 3~4 位八进制（如 644）");
                            return;
                        }
                        execAction("chmod " + s + " " + Shell.quote(e.path), "已修改权限：" + s);
                    }
                });
    }

    // ------------------------------------------------------------ 重命名 / 新建 / 搜索

    private void promptRename(final Entry e) {
        promptInput("重命名", e.name, "新名称（不能包含 /）", new InputCallback() {
            @Override
            public void onInput(String s) {
                if (!isValidName(s)) {
                    toastLong("名称无效：不能为空、不能包含 / 或 \\");
                    return;
                }
                String parent = parentOf(e.path);
                if (parent == null) {
                    parent = "/";
                }
                final String dst = joinPath(parent, s);
                if (dst.equals(e.path)) {
                    return;
                }
                execAction("mv -- " + Shell.quote(e.path) + " " + Shell.quote(dst), "已重命名为：" + s);
            }
        });
    }

    private void promptNewFolder() {
        promptInput("新建文件夹 · " + cwd, "", "文件夹名称", new InputCallback() {
            @Override
            public void onInput(String s) {
                if (!isValidName(s)) {
                    toastLong("名称无效：不能为空、不能包含 / 或 \\");
                    return;
                }
                execAction("mkdir -p -- " + Shell.quote(joinPath(cwd, s)), "已创建文件夹：" + s);
            }
        });
    }

    private void promptNewFile() {
        promptInput("新建文件 · " + cwd, "", "文件名", new InputCallback() {
            @Override
            public void onInput(String s) {
                if (!isValidName(s)) {
                    toastLong("名称无效：不能为空、不能包含 / 或 \\");
                    return;
                }
                execAction("touch -- " + Shell.quote(joinPath(cwd, s)), "已创建文件：" + s);
            }
        });
    }

    private void promptSearch() {
        promptInput("搜索 · " + cwd, "", "文件名关键词（不区分大小写）", new InputCallback() {
            @Override
            public void onInput(String s) {
                if (s == null || s.isEmpty()) {
                    toast("请输入关键词");
                    return;
                }
                doSearch(s);
            }
        });
    }

    private void doSearch(final String kw) {
        final String dir = cwd;
        toast("搜索中：" + kw);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String q = Shell.quote(dir);
                String pat = Shell.quote("*" + kw + "*");
                String out = Shell.execSync("find " + q + " -maxdepth 6 -iname " + pat
                        + " 2>/dev/null | head -200", 40000);
                if (out == null || out.trim().isEmpty()) {
                    // 老 toybox 没有 -iname 时降级为 -name
                    out = Shell.execSync("find " + q + " -maxdepth 6 -name " + pat
                            + " 2>/dev/null | head -200", 40000);
                }
                final String fo = out == null ? "" : out;
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        showSearchResults(fo);
                    }
                });
            }
        }, "filebrowser-search").start();
    }

    private void showSearchResults(String out) {
        ArrayList<String> abs = new ArrayList<>();
        ArrayList<String> rel = new ArrayList<>();
        String[] lines = out.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i] == null ? "" : lines[i].trim();
            if (t.isEmpty() || !t.startsWith("/")) {
                continue;
            }
            String r = t;
            if (t.startsWith(cwd)) {
                r = t.substring(cwd.length());
                if (r.startsWith("/")) {
                    r = r.substring(1);
                }
            }
            if (r.isEmpty()) {
                continue;
            }
            abs.add(t);
            rel.add(r);
        }
        if (abs.isEmpty()) {
            AlertDialog.Builder b = lightBuilder();
            b.setTitle("搜索");
            b.setMessage("没有找到匹配项。");
            b.setPositiveButton("关闭", null);
            showAlert(b);
            return;
        }
        final String[] absArr = abs.toArray(new String[0]);
        String[] relArr = rel.toArray(new String[0]);
        AlertDialog.Builder b = lightBuilder();
        b.setTitle("找到 " + absArr.length + " 项");
        b.setItems(relArr, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                jumpTo(absArr[which]);
            }
        });
        b.setNegativeButton("关闭", null);
        showAlert(b);
    }

    private void jumpTo(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        String parent = parentOf(path);
        if (parent == null) {
            parent = "/";
        }
        pendingSelect = path;
        if (parent.equals(cwd)) {
            reload();
        } else {
            pushHistory(parent);
            loadPath(parent);
        }
    }

    // ------------------------------------------------------------ 属性 / 分享 / 复制路径

    private void showProps(final Entry e) {
        final String path = e.path;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = Shell.execSync("stat -c '%n|%A|%U|%G|%s|%y' "
                        + Shell.quote(path) + " 2>&1", 10000);
                final String fo = out == null ? "" : out.trim();
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        showTextDialog("属性", buildProps(e, fo));
                    }
                });
            }
        }, "filebrowser-stat").start();
    }

    private static String buildProps(Entry e, String statOut) {
        StringBuilder sb = new StringBuilder();
        sb.append("路径: ").append(e.path).append('\n');
        sb.append("类型: ").append(e.isDir ? "目录" : e.isLink ? "符号链接" : "文件").append('\n');
        sb.append("权限: ").append(e.perm == null || e.perm.isEmpty() ? "?" : e.perm).append('\n');
        sb.append("属主: ").append(e.owner).append(':').append(e.group).append('\n');
        sb.append("大小: ").append(e.size >= 0 ? humanSize(e.size) : "未知");
        if (e.size >= 0) {
            sb.append(" (").append(e.size).append(" B)");
        }
        sb.append('\n');
        sb.append("修改: ").append(e.time == null || e.time.isEmpty() ? "未知" : e.time).append('\n');
        if (!e.linkTarget.isEmpty()) {
            sb.append("指向: ").append(e.linkTarget).append('\n');
        }
        sb.append('\n').append("stat 输出:\n").append(statOut);
        return sb.toString();
    }

    private void sharePath(final Entry e) {
        if (act == null) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://" + e.path));
            i.putExtra(Intent.EXTRA_TEXT, e.path);
            act.startActivity(Intent.createChooser(i, "分享 " + e.name));
        } catch (Throwable t) {
            copyPath(e.path);
            toastLong("分享失败（未配置 FileProvider），已复制路径");
        }
    }

    private void copyText(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
            }
            toast("已复制");
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    private void copyPath(String path) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("path", path));
            }
            toast("已复制路径");
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    // ------------------------------------------------------------ ⋮ 菜单 / 排序

    /** ⋮ 菜单：新建、排序、隐藏文件、搜索、单栏/双栏、交换。 */
    public void showHeaderMenu() {
        activate();
        ArrayList<String> items = new ArrayList<>();
        items.add("新建文件夹");
        items.add("新建文件");
        items.add("排序：按名称");
        items.add("排序：按大小");
        items.add("排序：按时间");
        items.add(showHidden ? "隐藏隐藏文件" : "显示隐藏文件");
        items.add("搜索");
        if (host != null) {
            items.add(host.isDual() ? "切换为单栏" : "切换为双栏");
            items.add("交换左右栏");
        }
        final String[] arr = items.toArray(new String[0]);
        menu(null, arr, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                String label = arr[which];
                if ("新建文件夹".equals(label)) {
                    promptNewFolder();
                } else if ("新建文件".equals(label)) {
                    promptNewFile();
                } else if ("排序：按名称".equals(label)) {
                    setSort(SORT_NAME);
                } else if ("排序：按大小".equals(label)) {
                    setSort(SORT_SIZE);
                } else if ("排序：按时间".equals(label)) {
                    setSort(SORT_TIME);
                } else if ("隐藏隐藏文件".equals(label) || "显示隐藏文件".equals(label)) {
                    toggleHidden();
                } else if ("搜索".equals(label)) {
                    promptSearch();
                } else if ("切换为单栏".equals(label) || "切换为双栏".equals(label)) {
                    if (host != null) {
                        host.onToggleDual();
                    }
                } else if ("交换左右栏".equals(label)) {
                    if (host != null) {
                        host.onSwap();
                    }
                }
            }
        });
    }

    /** 底栏「＋」新建菜单。 */
    public void showCreateMenu() {
        activate();
        String[] items = {"新建文件夹", "新建文件"};
        menu(null, items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    promptNewFolder();
                } else if (which == 1) {
                    promptNewFile();
                }
            }
        });
    }

    private void setSort(int mode) {
        sortMode = mode;
        rebuildVisible();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateStats();
        toast("排序：" + (mode == SORT_SIZE ? "按大小" : mode == SORT_TIME ? "按时间" : "按名称"));
    }

    private void toggleHidden() {
        showHidden = !showHidden;
        rebuildVisible();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateStats();
        if (visible.isEmpty() && emptyView != null) {
            emptyView.setText(showHidden ? "空目录" : "没有可见条目（隐藏文件已隐藏）");
            emptyView.setTextColor(F_DIM);
        }
        toast(showHidden ? "显示隐藏文件" : "隐藏隐藏文件");
    }

    // ------------------------------------------------------------ 导航 / 历史 / 对外 API

    /** 设置双栏容器回调（构造后调用即可，不会立即回调）。 */
    public void setHost(Host h) {
        this.host = h;
    }

    /** 选中态：active 时底部显示 2dp 绿色指示条。 */
    public void setActive(boolean active) {
        this.active = active;
        if (activeBar != null) {
            activeBar.setVisibility(active ? VISIBLE : GONE);
        }
    }

    /** 头部左侧额外缩进（dp）：给 MainActivity 左上角 ☰ 按钮让位。 */
    public void setHeaderLeftInset(int dp) {
        headerLeftInsetDp = Math.max(0, dp);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof LinearLayout && v.getParent() == this
                    && v.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                try {
                    ((LinearLayout) v).setPadding(dpI(headerLeftInsetDp), dpI(10), dpI(8), dpI(6));
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 当前目录（供双栏面板读取）。 */
    public String getPath() {
        return cwd;
    }

    /** 切换目录并刷新；主线程调用。 */
    public void setPath(String path) {
        navigateTo(path);
    }

    /** 重新加载当前目录；主线程调用。 */
    public void refreshList() {
        reload();
    }

    /** 历史后退；成功返回 true。 */
    public boolean goBack() {
        if (historyIndex <= 0) {
            return false;
        }
        historyIndex--;
        loadPath(history.get(historyIndex));
        activate();
        return true;
    }

    /** 历史前进；成功返回 true。 */
    public boolean goForward() {
        if (historyIndex < 0 || historyIndex >= history.size() - 1) {
            return false;
        }
        historyIndex++;
        loadPath(history.get(historyIndex));
        activate();
        return true;
    }

    /** 返回上级目录；已在 / 返回 false。 */
    public boolean goParent() {
        if (cwd == null || "/".equals(cwd)) {
            return false;
        }
        String parent = parentOf(cwd);
        if (parent == null || parent.equals(cwd)) {
            return false;
        }
        navigateTo(parent);
        return true;
    }

    /** 兼容旧调用方（MainActivity）：等价于 {@link #goParent()}。 */
    public boolean navigateUp() {
        return goParent();
    }

    /** 路径输入框（点顶部路径触发）。 */
    private void promptPath() {
        activate();
        promptInput("输入路径", cwd, "绝对路径或相对当前目录", new InputCallback() {
            @Override
            public void onInput(String s) {
                if (s == null || s.isEmpty()) {
                    return;
                }
                navigateTo(s);
            }
        });
    }

    private void navigateTo(String path) {
        final String norm = normalize(path);
        if (norm == null) {
            toast("路径无效");
            return;
        }
        if (norm.equals(cwd)) {
            reload();
            return;
        }
        pushHistory(norm);
        animateListOut(null);   // 视觉反馈，不阻塞加载
        loadPath(norm);         // 立刻开始读目录
    }

    /** 切换目录但不写历史（历史后退/前进、外部已确认的跳转使用）。 */
    private void loadPath(String path) {
        pendingAnimateIn = true;
        cwd = path;
        pendingSelect = null;
        if (pathText != null) {
            pathText.setText(cwd);
        }
        reload();
    }

    private void pushHistory(String path) {
        if (path == null) {
            return;
        }
        if (historyIndex >= 0 && historyIndex < history.size()
                && path.equals(history.get(historyIndex))) {
            return;
        }
        while (history.size() > historyIndex + 1) {
            history.remove(history.size() - 1);
        }
        history.add(path);
        historyIndex = history.size() - 1;
        while (history.size() > HISTORY_MAX) {
            history.remove(0);
            historyIndex--;
        }
        if (historyIndex < 0) {
            historyIndex = 0;
        }
    }

    // ------------------------------------------------------------ 通用执行

    private void execAction(final String cmd, final String okMsg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder out = new StringBuilder();
                int code = Shell.execSyncCode(cmd, 120000, out);
                final int fc = code;
                final String fo = out.toString();
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        if (fc == 0) {
                            toastLong(okMsg);
                            reload();
                        } else {
                            toastLong("失败(" + fc + ")：" + safeLine(fo, fc));
                        }
                    }
                });
            }
        }, "filebrowser-action").start();
    }

    // ------------------------------------------------------------ 对话框（浅色）

    private AlertDialog.Builder lightBuilder() {
        return new AlertDialog.Builder(act, LIGHT_DIALOG);
    }

    private void menu(String title, String[] items, DialogInterface.OnClickListener cb) {
        if (act == null || items == null) {
            return;
        }
        AlertDialog.Builder b = lightBuilder();
        if (title != null && !title.isEmpty()) {
            b.setTitle(title);
        }
        b.setItems(items, cb);
        b.setNegativeButton("取消", null);
        showAlert(b);
    }

    private AlertDialog showAlert(AlertDialog.Builder b) {
        if (b == null || act == null) {
            return null;
        }
        AlertDialog d;
        try {
            d = b.create();
        } catch (Throwable t) {
            try {
                d = new AlertDialog.Builder(act).create();
                d.setTitle("对话框");
                d.setMessage("该弹窗主题在当前系统上不可用");
            } catch (Throwable t2) {
                toastLong("弹窗创建失败：" + t2);
                return null;
            }
        }
        showDialog(d);
        return d;
    }

    private void showDialog(AlertDialog d) {
        if (d == null) {
            return;
        }
        if (activeDialog != null && activeDialog != d) {
            try {
                if (activeDialog.isShowing()) {
                    activeDialog.dismiss();
                }
            } catch (Throwable ignored) {
            }
        }
        activeDialog = d;
        try {
            d.show();
        } catch (Throwable ignored) {
        }
    }

    private void showTextDialog(String title, String body) {
        if (act == null) {
            return;
        }
        TextView tv = UiConfig.mono(act, body == null ? "" : body, 11.5f, F_TEXT);
        tv.setTextIsSelectable(true);
        int p = UiConfig.dp(act, 12);
        tv.setPadding(p, UiConfig.dp(act, 6), p, UiConfig.dp(act, 6));
        ScrollView sv = new ScrollView(act);
        sv.setBackgroundColor(F_BG);
        sv.addView(tv, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(F_BG);
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, dpI(320)));
        AlertDialog.Builder b = lightBuilder();
        b.setTitle(title);
        b.setView(box);
        b.setPositiveButton("关闭", null);
        showAlert(b);
    }

    private void promptInput(String title, String def, String hint, final InputCallback cb) {
        if (act == null) {
            return;
        }
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setText(def == null ? "" : def);
        et.setHint(hint);
        et.setTextColor(F_TEXT);
        et.setHintTextColor(F_DIM);
        et.setBackgroundColor(F_FIELD);
        et.setPadding(dpI(10), dpI(8), dpI(10), dpI(8));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpI(16), dpI(10), dpI(16), dpI(4));
        box.addView(et, new LinearLayout.LayoutParams(MATCH, WRAP));

        AlertDialog.Builder b = lightBuilder();
        b.setTitle(title);
        b.setView(box);
        b.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                String s = et.getText() == null ? "" : et.getText().toString().trim();
                if (cb != null) {
                    cb.onInput(s);
                }
            }
        });
        b.setNegativeButton("取消", null);
        AlertDialog dlg = showAlert(b);
        if (dlg != null && def != null && !def.isEmpty()) {
            try {
                et.setSelection(def.length());
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onDetachedFromWindow() {
        try {
            loadSeq++;
            if (activeDialog != null) {
                if (activeDialog.isShowing()) {
                    activeDialog.dismiss();
                }
                activeDialog = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------ 工具

    private void postUi(final Runnable r) {
        if (r == null) {
            return;
        }
        Runnable w = new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            }
        };
        if (act != null) {
            try {
                act.runOnUiThread(w);
                return;
            } catch (Throwable ignored) {
            }
        }
        MAIN.post(w);
    }

    private int dpI(float v) {
        return UiConfig.dp(ctx, v);
    }

    private void toast(String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void toastLong(String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private static String safeExec(String cmd, int timeoutMs) {
        String out = Shell.execSync(cmd, timeoutMs);
        return out == null ? "" : out;
    }

    private static String safeExecStatic(String cmd, int timeoutMs) {
        String out = Shell.execSync(cmd, timeoutMs);
        return out == null ? "" : out;
    }

    private static boolean isScript(String lowerName) {
        if (lowerName == null) {
            return false;
        }
        for (String e : SCRIPT_EXT) {
            if (lowerName.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExt(String name, String[] exts) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.US);
        for (int i = 0; i < exts.length; i++) {
            if (n.endsWith(exts[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTextLike(Entry e) {
        if (e == null || e.isDir) {
            return false;
        }
        if (hasExt(e.name, TEXT_EXT)) {
            return true;
        }
        int dot = e.name.lastIndexOf('.');
        boolean noExt = dot <= 0;
        return noExt && e.size >= 0 && e.size < TEXT_SIZE_LIMIT;
    }

    private static boolean isExecutable(Entry e) {
        if (e == null || e.isDir) {
            return false;
        }
        if (e.name != null && isScript(e.name.toLowerCase(Locale.US))) {
            return true;
        }
        String p = e.perm;
        if (p == null || p.length() < 10) {
            return false;
        }
        return p.charAt(3) == 'x' || p.charAt(6) == 'x' || p.charAt(9) == 'x';
    }

    private static String mimeFor(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/*";
    }

    private static boolean isValidName(String s) {
        if (s == null || s.isEmpty() || s.length() > 255) {
            return false;
        }
        if (".".equals(s) || "..".equals(s)) {
            return false;
        }
        return s.indexOf('/') < 0 && s.indexOf('\\') < 0;
    }

    private static Comparator<Entry> comparatorFor(final int mode) {
        return new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.isDir != b.isDir) {
                    return a.isDir ? -1 : 1;
                }
                if (mode == SORT_SIZE) {
                    int c = Long.compare(b.size, a.size);
                    if (c != 0) {
                        return c;
                    }
                } else if (mode == SORT_TIME) {
                    String ta = a.time == null ? "" : a.time;
                    String tb = b.time == null ? "" : b.time;
                    int c = tb.compareTo(ta);
                    if (c != 0) {
                        return c;
                    }
                }
                String na = a.name == null ? "" : a.name;
                String nb = b.name == null ? "" : b.name;
                return na.compareToIgnoreCase(nb);
            }
        };
    }

    // ------------------------------------------------------------ 字符串 / 路径工具

    private static String joinPath(String dir, String name) {
        String d = dir;
        if (d == null || d.isEmpty()) {
            d = "/";
        }
        String n = name == null ? "" : name;
        if ("/".equals(d)) {
            return "/" + n;
        }
        return d + "/" + n;
    }

    private static String parentOf(String path) {
        if (path == null) {
            return null;
        }
        String p = path;
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        int i = p.lastIndexOf('/');
        if (i < 0) {
            return null;
        }
        if (i == 0) {
            return "/";
        }
        return p.substring(0, i);
    }

    private static String basename(String path) {
        if (path == null) {
            return "";
        }
        String p = path;
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    /** 路径规范化：支持相对路径、. / ..，返回绝对路径；非法返回 null。 */
    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.trim();
        if (p.isEmpty()) {
            return null;
        }
        if (!p.startsWith("/")) {
            String base = cwd == null || cwd.isEmpty() ? "/" : cwd;
            p = "/".equals(base) ? "/" + p : base + "/" + p;
        }
        ArrayList<String> parts = new ArrayList<>();
        String[] segs = p.split("/");
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i];
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(seg);
        }
        if (parts.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            sb.append('/').append(parts.get(i));
        }
        return sb.toString();
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        double v = bytes;
        String[] units = {"K", "M", "G", "T", "P"};
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, "%.1f%s", v, units[i]);
    }

    private static long parseSize(String s) {
        if (s == null) {
            return -1;
        }
        String t = s.trim();
        if (t.isEmpty() || t.indexOf(' ') >= 0) {
            return -1;
        }
        t = t.replace(",", "");
        try {
            return Long.parseLong(t);
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String firstToken(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        int i = 0;
        while (i < t.length() && !Character.isWhitespace(t.charAt(i))) {
            i++;
        }
        return t.substring(0, i);
    }

    private static int monthNum(String m) {
        if (m == null || m.length() < 3) {
            return -1;
        }
        String[] names = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        String k = m.substring(0, 3);
        for (int i = 0; i < names.length; i++) {
            if (names[i].equalsIgnoreCase(k)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** 把 ls / stat 的各种时间格式统一成 {@code yyyy-MM-dd HH:mm}。 */
    private static String normalizeTime(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return "";
        }
        if (t.length() >= 16) {
            String head = t.substring(0, 16).replace('_', ' ');
            if (head.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
                return head;
            }
        }
        String[] p = t.split("\\s+");
        if (p.length >= 3) {
            int mon = monthNum(p[0]);
            if (mon > 0) {
                String day = p[1];
                if (day.length() == 1) {
                    day = "0" + day;
                }
                String year;
                String hm;
                if (p[2].indexOf(':') >= 0) {
                    year = String.valueOf(Calendar.getInstance().get(Calendar.YEAR));
                    hm = p[2];
                } else {
                    year = p[2];
                    hm = "00:00";
                }
                return year + "-" + (mon < 10 ? "0" : "") + mon + "-" + day + " " + hm;
            }
        }
        return t;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return "";
        }
        int i = t.indexOf('\n');
        if (i >= 0) {
            t = t.substring(0, i).trim();
        }
        if (t.length() > 240) {
            t = t.substring(0, 239) + "...";
        }
        return t;
    }

    private static String safeLine(String out, int code) {
        String l = firstLine(out);
        return l.isEmpty() ? ("exit " + code) : l;
    }

    /** 从 ls 输出里挑一条错误信息用于展示（忽略正常行）。 */
    private static String firstErrorLine(String out) {
        if (out == null) {
            return "";
        }
        String[] lines = out.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i] == null ? "" : lines[i].trim();
            if (t.isEmpty() || t.startsWith("total ")) {
                continue;
            }
            if (looksLikeError(t)) {
                return firstLine(t);
            }
            if (parseLsLine(t, "/") == null && t.indexOf("permission denied") >= 0) {
                return firstLine(t);
            }
        }
        return "";
    }

    private static boolean looksLikeError(String t) {
        if (t == null) {
            return true;
        }
        String l = t.toLowerCase(Locale.US);
        return l.startsWith("ls:") || l.startsWith("find:") || l.startsWith("stat:")
                || l.startsWith("sh:") || l.startsWith("su:") || l.startsWith("usage:")
                || l.contains("permission denied") || l.contains("no such file")
                || l.contains("not found") || l.contains("invalid option")
                || l.contains("unknown option") || l.contains("unrecognized option")
                || l.contains("not a directory");
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        if (hay == null || needle == null) {
            return false;
        }
        return hay.toLowerCase(Locale.US).contains(needle.toLowerCase(Locale.US));
    }

    // ------------------------------------------------------------ 数据结构

    /** 目录条目。 */
    private static final class Entry {
        String name = "";
        String path = "";
        String perm = "";
        String owner = "";
        String group = "";
        String time = "";
        String linkTarget = "";
        long size = -1;
        boolean isDir;
        boolean isLink;
        boolean unknown;
        String raw = "";

        String displayName() {
            if (unknown) {
                return raw == null || raw.isEmpty() ? name : raw;
            }
            return name;
        }
    }

    /** 一次目录加载结果。 */
    private static final class LoadResult {
        final ArrayList<Entry> entries = new ArrayList<>();
        boolean denied;
        boolean truncated;
        String error = "";
        String spaceAvail = "";
        String spaceTotal = "";
    }
}
