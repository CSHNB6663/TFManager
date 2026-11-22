package com.rootterm.fm;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * 终端面板：root / shell 命令执行、流式输出、命令历史、cwd 跟踪。
 *
 * <p>自包含的 {@link LinearLayout} 子类，构造函数内完成全部 UI 搭建；
 * 所有命令都经 {@link Shell} 执行（有 root 走 su，无 root 降级 shell），
 * 阻塞操作全部在后台线程，主线程只更新 UI。</p>
 */
public class TerminalPanel extends LinearLayout {

    /** 输出区最多保留的行数。 */
    private static final int MAX_LINES = 5000;
    /** 触发截断后保留的行数（留一点余量，避免每条都重建）。 */
    private static final int TRIM_TO = 4000;
    /** 命令历史上限。 */
    private static final int HISTORY_MAX = 200;
    /** 启动横幅标题。 */
    private static final String APP_VERSION = "TF管理终端 v" + AppVersion.NAME + " · 面向 Android 17";

    /** 徽标文字颜色（徽标底色为绿/黄，用深色字）。 */
    private static final int BADGE_TEXT = UiConfig.BG;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    // 顶部状态条
    private LinearLayout statusBar;
    private View statusDivider;
    private TextView badgeView;
    private TextView infoView;
    private Button histBtn;
    private Button refreshBtn;

    // 输出区
    private ScrollView outputScroll;
    private TextView outputView;

    // 底部输入行
    private View inputDivider;
    private LinearLayout inputRow;
    private TextView promptView;
    private EditText inputView;
    private Button runBtn;

    /** 紧凑模式（右上角小窗）：隐藏状态条、缩小字号与行高。 */
    private boolean compactMode = false;

    /** 输出区纯文本镜像，用于 5000 行截断时整体重建。 */
    private final StringBuilder mirror = new StringBuilder();
    private int lineCount = 0;
    private boolean scrollPending = false;

    /** 命令执行中（含 cd 校验中）。 */
    private boolean running = false;
    /** 防抖：同一次回车可能被 editorAction 与 keyListener 同时触发。 */
    private long lastSubmitAt;
    /** 键盘是否可见（宿主根据 IME inset 回调）。 */
    private boolean imeVisible;
    /** 当前交互式会话（stdin 由终端输入行接管），null 表示普通一次性命令。 */
    private Interactive mInteractive;
    /** root 探测中。 */
    private boolean detecting = false;

    /** 当前工作目录，始终是绝对路径。 */
    private String cwd = "/";

    /** 命令历史（旧 -> 新）。 */
    private final ArrayList<String> history = new ArrayList<String>();
    /** 历史定位：等于 history.size() 表示“正在输入新命令”。 */
    private int historyPos = 0;

    public TerminalPanel(Context ctx) {
        super(ctx);
        this.ctx = ctx;
        setOrientation(VERTICAL);
        setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setBackgroundColor(UiConfig.BG);

        buildStatusBar();
        buildOutputArea();
        buildInputRow();

        updateStatus();
        printWelcome();
        startDetect(true);
    }

    // ================================================================ 生命周期

    /** 面板被切到前台：聚焦输入框并滚到底。 */
    public void onPanelShown() {
        if (inputView != null) {
            inputView.requestFocus();
        inputView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeyboard();
            }
        });
            inputView.setSelection(inputView.getText().length());
        }
        scrollToBottom();
    }

    /** 终端没有“返回上级”，恒返回 false，让返回键退出 App。 */
    public boolean navigateUp() {
        return false;
    }

    // ================================================================ 紧凑模式（右上角小窗）

    /**
     * 切换紧凑模式：隐藏顶部状态条，缩小输出/输入区字号、行距与高度。
     *
     * <p>主线程调用，幂等，可反复切换；只改外观，不影响命令执行、历史、内置命令逻辑。</p>
     */
    public void setCompact(boolean compact) {
        if (compactMode == compact) {
            return;
        }
        compactMode = compact;
        if (compact) {
            applyCompactStyle();
        } else {
            applyFullStyle();
        }
    }

    /** 当前是否为紧凑模式。 */
    public boolean isCompact() {
        return compactMode;
    }

    private void applyCompactStyle() {
        // 顶部状态条（ROOT 徽标 + 身份 + 历史/刷新）及其分隔线一起隐藏
        if (statusBar != null) {
            statusBar.setVisibility(View.GONE);
        }
        if (statusDivider != null) {
            statusDivider.setVisibility(View.GONE);
        }
        // 输出区：13sp -> 10.5sp，行距与内边距收紧
        if (outputView != null) {
            outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            outputView.setLineSpacing(dp(1), 1f);
            outputView.setPadding(dp(4), dp(4), dp(4), dp(4));
        }
        // 输入行：48dp -> 38dp
        if (inputRow != null) {
            LinearLayout.LayoutParams rowLp =
                    (LinearLayout.LayoutParams) inputRow.getLayoutParams();
            if (rowLp != null) {
                rowLp.height = dp(38);
                inputRow.setLayoutParams(rowLp);
            }
            inputRow.setPadding(dp(4), dp(3), dp(4), dp(3));
        }
        if (inputDivider != null) {
            inputDivider.setVisibility(View.VISIBLE);
        }
        // 提示符、输入框、执行按钮同步缩小
        if (promptView != null) {
            promptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            promptView.setMaxWidth(dp(90));
        }
        if (inputView != null) {
            inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            inputView.setPadding(dp(4), dp(2), dp(4), dp(2));
        }
        if (runBtn != null) {
            runBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            runBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
            LinearLayout.LayoutParams btnLp =
                    (LinearLayout.LayoutParams) runBtn.getLayoutParams();
            if (btnLp != null) {
                btnLp.height = dp(30);
                runBtn.setLayoutParams(btnLp);
            }
        }
        // 整体内边距收小
        setPadding(dp(2), dp(2), dp(2), dp(2));
        scrollToBottom();
    }

    private void applyFullStyle() {
        if (statusBar != null) {
            statusBar.setVisibility(View.VISIBLE);
        }
        if (statusDivider != null) {
            statusDivider.setVisibility(View.VISIBLE);
        }
        if (outputView != null) {
            outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            outputView.setLineSpacing(dp(3), 1f);
            outputView.setPadding(dp(8), dp(8), dp(8), dp(8));
        }
        if (inputRow != null) {
            LinearLayout.LayoutParams rowLp =
                    (LinearLayout.LayoutParams) inputRow.getLayoutParams();
            if (rowLp != null) {
                rowLp.height = dp(48);
                inputRow.setLayoutParams(rowLp);
            }
            inputRow.setPadding(dp(8), dp(5), dp(8), dp(5));
        }
        if (promptView != null) {
            promptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            promptView.setMaxWidth(dp(170));
        }
        if (inputView != null) {
            inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            inputView.setPadding(dp(6), dp(4), dp(6), dp(4));
        }
        if (runBtn != null) {
            runBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            runBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
            LinearLayout.LayoutParams btnLp =
                    (LinearLayout.LayoutParams) runBtn.getLayoutParams();
            if (btnLp != null) {
                btnLp.height = dp(34);
                runBtn.setLayoutParams(btnLp);
            }
        }
        setPadding(0, 0, 0, 0);
        scrollToBottom();
    }

    // ================================================================ UI 搭建

    private void buildStatusBar() {
        statusBar = UiConfig.row(ctx);
        statusBar.setBackgroundColor(UiConfig.SURFACE);
        statusBar.setPadding(dp(8), dp(5), dp(8), dp(5));
        statusBar.setMinimumHeight(dp(44));
        statusBar.setGravity(Gravity.CENTER_VERTICAL);

        boolean root = Shell.isRoot();
        badgeView = UiConfig.badge(ctx, root ? "ROOT" : "USER",
                BADGE_TEXT, root ? UiConfig.OK : UiConfig.WARN);
        statusBar.addView(badgeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        infoView = UiConfig.text(ctx, "", 10.5f, UiConfig.TEXT_DIM);
        infoView.setSingleLine(true);
        infoView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(8);
        infoLp.rightMargin = dp(6);
        statusBar.addView(infoView, infoLp);

        histBtn = UiConfig.button(ctx, "历史", false);
        statusBar.addView(histBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        histBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showHistoryDialog();
            }
        });

        refreshBtn = UiConfig.button(ctx, "刷新", false);
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        refreshLp.leftMargin = dp(6);
        statusBar.addView(refreshBtn, refreshLp);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startDetect(false);
            }
        });

        addView(statusBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        statusDivider = UiConfig.divider(ctx);
        addView(statusDivider);
    }

    private void buildOutputArea() {
        outputScroll = new ScrollView(ctx);
        outputScroll.setBackgroundColor(UiConfig.BG);
        outputScroll.setFillViewport(false);
        outputScroll.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        addView(outputScroll, slp);

        outputView = UiConfig.mono(ctx, "", 13f, UiConfig.TEXT);
        outputView.setTextColor(UiConfig.TEXT);
        outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        outputView.setLineSpacing(dp(3), 1f);
        outputView.setPadding(dp(8), dp(8), dp(8), dp(8));
        outputView.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showOutputMenu();
                return true;
            }
        });
        outputScroll.addView(outputView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 输出区空白处（文字之外）长按也弹菜单
        outputScroll.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showOutputMenu();
                return true;
            }
        });
    }

    private void buildInputRow() {
        inputDivider = UiConfig.divider(ctx);
        addView(inputDivider);

        inputRow = UiConfig.row(ctx);
        inputRow.setBackgroundColor(UiConfig.SURFACE);
        inputRow.setPadding(dp(8), dp(5), dp(8), dp(5));
        addView(inputRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        promptView = UiConfig.mono(ctx, promptText(), 12f, UiConfig.ACCENT);
        promptView.setSingleLine(true);
        promptView.setEllipsize(TextUtils.TruncateAt.END);
        promptView.setMaxWidth(dp(170));
        inputRow.addView(promptView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        inputView = new EditText(ctx);
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        inputView.setTextColor(UiConfig.TEXT);
        inputView.setHintTextColor(UiConfig.TEXT_DIM);
        inputView.setHint("输入命令，回车执行");
        inputView.setTypeface(Typeface.MONOSPACE);
        android.graphics.drawable.GradientDrawable inputBg =
                new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(UiConfig.SURFACE_2);
        inputBg.setCornerRadius(UiConfig.dp(ctx, 18));
        inputView.setBackground(inputBg);
        inputView.setPadding(UiConfig.dp(ctx, 12), UiConfig.dp(ctx, 6),
                UiConfig.dp(ctx, 12), UiConfig.dp(ctx, 6));
        inputView.setPadding(dp(6), dp(4), dp(6), dp(4));
        inputView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        inputView.setSingleLine(true);
        inputView.setImeOptions(EditorInfo.IME_ACTION_SEND);
        inputView.setSelectAllOnFocus(false);
        inputView.setCursorVisible(true);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputLp.leftMargin = dp(4);
        inputLp.rightMargin = dp(4);
        inputRow.addView(inputView, inputLp);

        runBtn = UiConfig.button(ctx, "执行", true);
        inputRow.addView(runBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        runBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                submit();
            }
        });

        // 手动唤出键盘（一加/ColorOS 兜底入口）
        TextView kbBtn = UiConfig.button(ctx, "⌨", false);
        LinearLayout.LayoutParams kbLp = new LinearLayout.LayoutParams(dp(40), dp(34));
        kbLp.leftMargin = dp(4);
        inputRow.addView(kbBtn, kbLp);
        kbBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getContext().getSystemService(
                                            android.content.Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        inputView.requestFocus();
                        imm.toggleSoftInput(
                                android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0);
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        inputView.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    submit();
                    return true;
                }
                return false;
            }
        });

        inputView.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        historyPrev();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        historyNext();
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_ENTER) {
                        if (event.getRepeatCount() == 0) {
                            submit();
                        }
                        return true;
                    }
                }
                return false;
            }
        });

        // 长按输入框 -> 历史列表
        inputView.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showHistoryDialog();
                return true;
            }
        });
    }

    // ================================================================ 状态与提示符

    private void updateStatus() {
        boolean root = Shell.isRoot();
        if (badgeView != null) {
            badgeView.setText(root ? "ROOT" : "USER");
            badgeView.setTextColor(BADGE_TEXT);
            GradientDrawable g = new GradientDrawable();
            g.setColor(root ? UiConfig.OK : UiConfig.WARN);
            g.setCornerRadius(dp(5));
            badgeView.setBackground(g);
        }
        if (infoView != null) {
            if (root) {
                infoView.setText("身份: root · " + Shell.suPath());
            } else {
                infoView.setText("身份: shell · 无 root，仅 app 权限");
            }
        }
        updatePrompt();
    }

    private void updatePrompt() {
        if (promptView != null) {
            promptView.setText(promptText());
        }
    }

    private String promptText() {
        String ident = Shell.identity();
        String tail = Shell.isRoot() ? " # " : " $ ";
        return ident + "@android:" + cwd + tail;
    }

    /** 主动弹出软键盘（ColorOS 等 ROM 上仅 requestFocus 不会弹）。 */
    private void showKeyboard() {
        if (inputView == null) {
            return;
        }
        inputView.setFocusable(true);
        inputView.setFocusableInTouchMode(true);
        inputView.setShowSoftInputOnFocus(true);
        inputView.requestFocus();
        attemptShowKeyboard(80);
        attemptShowKeyboard(320);
    }

    private void attemptShowKeyboard(long delay) {
        inputView.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (inputView == null) {
                        return;
                    }
                    if (!inputView.hasFocus()) {
                        inputView.requestFocus();
                    }
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getContext().getSystemService(
                                            android.content.Context.INPUT_METHOD_SERVICE);
                    if (imm == null) {
                        return;
                    }
                    if (imeVisible) {
                        imm.showSoftInput(inputView,
                                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    } else {
                        // 一加/ColorOS 上 showSoftInput 经常被吞，
                        // SHOW_FORCED 的 toggleSoftInput 才是真正管用的那个
                        imm.toggleSoftInput(
                                android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, delay);
    }

    /** 宿主根据 IME inset 告知键盘是否可见。 */
    public void onImeVisible(boolean visible) {
        imeVisible = visible;
    }

    /** 键盘弹出/收起时由宿主通知，滚动到最新输出。 */
    public void onImeChanged(boolean shown) {
        if (shown) {
            scrollToBottom();
        }
    }

    /** 供宿主在显示终端时调用，确保输入行可输入。 */
    public void focusInput() {
        showKeyboard();
    }

    private void setRunning(boolean isRunning) {
        running = isRunning;
        // 注意：绝不能 setEnabled(false) 输入框。
        // 一加/ColorOS 等 ROM 上禁用再启用会丢失 InputConnection，
        // 表现为「第一次回车后键盘再也弹不出来」。
        if (inputView != null) {
            inputView.setAlpha(isRunning ? 0.6f : 1f);
        }
        if (runBtn != null) {
            runBtn.setEnabled(!isRunning);
            runBtn.setAlpha(isRunning ? 0.5f : 1f);
        }
        if (promptView != null) {
            promptView.setTextColor(isRunning ? UiConfig.TEXT_DIM : UiConfig.ACCENT);
        }
        if (!isRunning && inputView != null) {
            showKeyboard();
        }
    }

    /** 交互模式下的输入行状态：保持可输入，提示符换成 →。 */
    private void enterInteractiveUi(boolean on) {
        if (inputView != null) {
            inputView.setEnabled(true);
            showKeyboard();
        }
        if (runBtn != null) {
            runBtn.setEnabled(true);
        }
        if (promptView != null) {
            promptView.setTextColor(UiConfig.ACCENT);
            promptView.setText(on ? "→ " : promptText());
        }
    }

    // ================================================================ root 探测

    private void startDetect(final boolean startup) {
        if (detecting) {
            return;
        }
        if (running) {
            toast("命令执行中，请稍后再刷新");
            return;
        }
        detecting = true;
        if (refreshBtn != null) {
            refreshBtn.setEnabled(false);
        }
        if (!startup) {
            if (infoView != null) {
                infoView.setText("正在探测 root…");
            }
            printLine("[刷新] 正在重新探测 root 权限…", UiConfig.TEXT_DIM);
        }
        Shell.detectRootAsync(new Runnable() {
            public void run() {
                detecting = false;
                if (refreshBtn != null) {
                    refreshBtn.setEnabled(true);
                }
                updateStatus();
                printRootResult(startup);
                scrollToBottom();
            }
        });
    }

    private void printRootResult(boolean startup) {
        if (Shell.isRoot()) {
            printLine("root 权限已获取 · " + Shell.suPath(), UiConfig.ACCENT);
        } else {
            printLine(startup
                    ? "未获取 root：仅能访问 app 私有目录与 /sdcard 可读部分"
                    : "仍未获取 root：仅能访问 app 私有目录与 /sdcard 可读部分",
                    UiConfig.WARN);
            printLine("请确认设备已 root（Magisk/KernelSU）并授予本 App su 权限",
                    UiConfig.WARN);
        }
        printLine("", UiConfig.TEXT);
    }

    private void printWelcome() {
        printLine(APP_VERSION, UiConfig.ACCENT);
        printLine("身份: " + Shell.identity(), UiConfig.TEXT_DIM);
        printLine("cwd: " + cwd, UiConfig.TEXT_DIM);
        if (!Shell.hasProbeResult()) {
            printLine("正在探测 root 权限…", UiConfig.TEXT_DIM);
        }
        printLine("输入 help 查看内置命令；输出区最多保留 " + MAX_LINES + " 行。",
                UiConfig.TEXT_DIM);
    }

    // ================================================================ 输出区

    private void printLine(String text, int color) {
        appendText((text == null ? "" : text) + "\n", color);
    }

    /** 往输出区追加一段文本（用 TextView.append，避免整段 setText 卡顿）。 */
    private void appendText(String text, int color) {
        if (text == null || text.length() == 0 || outputView == null) {
            return;
        }
        outputView.append(Ansi.parse(text, color));

        mirror.append(text);
        lineCount += countLines(text);
        if (lineCount > MAX_LINES) {
            trimHead();
        }
        scrollToBottom();
    }

    private static int countLines(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 超过上限时从头丢弃较早的行，并整体重建一次 TextView。 */
    private void trimHead() {
        int drop = lineCount - TRIM_TO;
        if (drop <= 0 || outputView == null) {
            return;
        }
        String all = mirror.toString();
        int idx = 0;
        int dropped = 0;
        while (dropped < drop) {
            int nl = all.indexOf('\n', idx);
            if (nl < 0) {
                idx = all.length();
                break;
            }
            idx = nl + 1;
            dropped++;
        }
        String tail = all.substring(idx);
        mirror.setLength(0);
        mirror.append(tail);
        lineCount = countLines(tail);

        String notice = "[输出过多，已丢弃较早的 " + dropped + " 行，最多保留 "
                + MAX_LINES + " 行]\n";
        outputView.setText(notice);
        outputView.append(tail);
        lineCount += 1;
    }

    private void scrollToBottom() {
        if (outputScroll == null || scrollPending) {
            return;
        }
        scrollPending = true;
        outputScroll.post(new Runnable() {
            public void run() {
                scrollPending = false;
                if (outputScroll != null) {
                    outputScroll.fullScroll(View.FOCUS_DOWN);
                }
            }
        });
    }

    /** 覆盖最后一行（进度条 \r 用）：重建一次纯文本，颜色会重置。 */
    private void replaceLastLine(String text) {
        if (outputView == null) {
            return;
        }
        String all = mirror.toString();
        int cut = all.length() >= 2 ? all.lastIndexOf('\n', all.length() - 2) : -1;
        String keep = cut >= 0 ? all.substring(0, cut + 1) : "";
        mirror.setLength(0);
        mirror.append(keep).append(text == null ? "" : text).append('\n');
        lineCount = countLines(mirror.toString());
        outputView.setText("");
        outputView.append(mirror.toString());
        scrollToBottom();
    }

    private void clearScreen() {
        mirror.setLength(0);
        lineCount = 0;
        if (outputView != null) {
            outputView.setText("");
        }
    }

    // ================================================================ 命令入口

    /** 从文件管理器跳转过来执行命令（主线程调用）。 */
    public void runExternalCommand(final String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        if (running) {
            toast("终端正在执行命令，请稍后");
            return;
        }
        final String cmd = raw.trim();
        post(new Runnable() {
            public void run() {
                printLine(promptText() + cmd, UiConfig.ACCENT);
                runShell(cmd);
            }
        });
    }

    private void submit() {
        if (inputView == null) {
            return;
        }
        // 防重复：IME 的「发送」键会同时触发 OnEditorAction 与 OnKey(ENTER)。
        // 交互模式下用更长的窗口（800ms）——这类加载器通常「空回车=确认」，
        // 用户连按两次回车会把空行当成菜单选项发进去，导致程序判定无效选择而退出。
        long now = android.os.SystemClock.uptimeMillis();
        long window = (mInteractive != null) ? 800 : 350;
        if (now - lastSubmitAt < window) {
            return;
        }
        lastSubmitAt = now;
        // 交互式会话：把输入写入程序 stdin 而不是当命令解析
        if (mInteractive != null) {
            String line = inputView.getText().toString();
            inputView.setText("");
            if ("eof".equalsIgnoreCase(line.trim())) {
                mInteractive.closeStdin();
                printLine("[stdin EOF]", UiConfig.TEXT_DIM);
            } else if (!mInteractive.write(line)) {
                printLine("[写入 stdin 失败，进程可能已退出]", UiConfig.ERROR);
            } else {
                printLine("→ " + line, UiConfig.TEXT_DIM);
            }
            return;
        }
        if (running) {
            printLine("[正在执行，请稍候…]", UiConfig.TEXT_DIM);
            showKeyboard();
            return;
        }
        String raw = inputView.getText().toString().trim();
        if (raw.length() == 0) {
            return;
        }
        inputView.setText("");
        addHistory(raw);
        handleCommand(raw);
        // 命令可能长时间运行（或进入交互模式），立刻把键盘还回来
        showKeyboard();
    }

    private void handleCommand(final String raw) {
        String first = raw;
        String rest = "";
        int space = raw.indexOf(' ');
        if (space >= 0) {
            first = raw.substring(0, space);
            rest = raw.substring(space + 1).trim();
        }
        String lc = first.toLowerCase();

        // 回显命令行（提示符 + 命令）
        printLine(promptText() + raw, UiConfig.ACCENT);

        if (lc.equals("clear") || lc.equals("cls")) {
            clearScreen();
            return;
        }
        if (lc.equals("help") || lc.equals("?")) {
            printHelp();
            return;
        }
        if (lc.equals("exit") || lc.equals("quit")) {
            printLine("按返回键退出 App（或从最近任务中移除 RootTerm）。", UiConfig.WARN);
            return;
        }
        if (lc.equals("pwd")) {
            printLine(cwd, UiConfig.TEXT);
            return;
        }
        if (lc.equals("history")) {
            showHistoryDialog();
            return;
        }
        if (lc.equals("cd") && isSimpleCommand(raw)) {
            doCd(rest);
            return;
        }
        runShell(raw);
    }

    /** 是否为「不含 shell 操作符」的简单命令（内置命令只拦这种）。 */
    private static boolean isSimpleCommand(String raw) {
        if (raw == null) {
            return false;
        }
        return raw.indexOf("&&") < 0 && raw.indexOf("||") < 0
                && raw.indexOf(';') < 0 && raw.indexOf('|') < 0
                && raw.indexOf('>') < 0 && raw.indexOf('<') < 0
                && raw.indexOf('$') < 0 && raw.indexOf('`') < 0;
    }

    /** 普通命令：加 {@code cd <cwd> && } 前缀后交给 Shell 异步执行。 */
    private void runShell(final String raw) {
        // 判定是否为交互式：ELF、或含 chmod && ./（典型加载器模式）、或从文件浏览器来的运行命令
        boolean interactive = false;
        String path = extractFirstPath(raw);
        if (path != null) {
            final String file = path;
            new Thread(new Runnable() {
                public void run() {
                    String magic = Shell.execSync(
                            "head -c 4 " + Shell.quote(file) + " 2>/dev/null | od -An -tx1",
                            6000).trim().replace(" ", "").toLowerCase();
                    final boolean elf = magic.startsWith("7f454c46");
                    final boolean loaderPattern = raw.contains("chmod") && raw.contains("./");
                    // 用内置 bash 跑 .sh 脚本：可能是交互式菜单，必须走交互模式，
                    // 否则 stdin 是 EOF → 脚本卡住 → running 永远为 true → 键盘再也回不来
                    final boolean bashScript = raw.contains("/tfbash/bash")
                            || raw.contains("tfbash/bash")
                            || raw.contains(".sh");
                    final boolean interactiveFlag = elf || loaderPattern || bashScript;
                    post(new Runnable() {
                        public void run() {
                            if (interactiveFlag) {
                                runInteractive(raw);
                            } else {
                                runOnce(raw);
                            }
                        }
                    });
                }
            }, "interactive-check").start();
        } else {
            runOnce(raw);
        }
    }

    /** 从命令字符串里提取第一个看起来像绝对路径的 token。 */
    private static String extractFirstPath(String cmd) {
        if (cmd == null) {
            return null;
        }
        for (String tok : cmd.split("\\s+")) {
            String t = tok;
            if ((t.startsWith("'") && t.endsWith("'"))
                    || (t.startsWith("\"") && t.endsWith("\""))) {
                t = t.substring(1, t.length() - 1);
            }
            if (t.startsWith("/") && t.length() > 2 && !t.contains(" ") && !t.startsWith("/data/local/tmp/tfbash")) {
                return t;
            }
        }
        return null;
    }

    /** 普通一次性命令。 */
    private void runOnce(String raw) {
        AppLog.log("SH", "run: " + raw);
        final String cmd = "cd " + Shell.quote(cwd) + " && " + raw;
        setRunning(true);
        Shell.exec(cmd, new Shell.Callback() {
            public void onStart() {
            }

            public void onLine(String line) {
                printLine(line, UiConfig.TEXT);
            }

            public void onDone(int exitCode) {
                AppLog.log("SH", "exit " + exitCode);
                if (exitCode == -1) {
                    printLine("[执行失败/超时]", UiConfig.ERROR);
                } else if (exitCode != 0) {
                    printLine("[exit " + exitCode + "]", UiConfig.ERROR);
                }
                setRunning(false);
                scrollToBottom();
            }
        });
    }

    /** 交互式执行：进程持续运行，输入行的每行会写入 stdin。输入 `eof` 发送 EOF 结束。 */
    private void runInteractive(final String raw) {
        printLine("[交互模式] 进程保持运行，直接输入选项（如 1）回车发送；输入 eof 结束输入。",
                UiConfig.ACCENT);
        setRunning(true);
        final String cmd = "cd " + Shell.quote(cwd) + " && " + raw;
        mInteractive = Interactive.start(cmd, new Interactive.Callback() {
            public void onStart() {
            }

            public void onLine(String line) {
                printLine(line, UiConfig.TEXT);
            }

            public void onDone(int exitCode) {
                mInteractive = null;
                if (exitCode == -1) {
                    printLine("[交互结束/超时]", UiConfig.WARN);
                } else if (exitCode != 0) {
                    printLine("[exit " + exitCode + "]", UiConfig.ERROR);
                } else {
                    printLine("[交互结束 exit 0]", UiConfig.ACCENT);
                }
                setRunning(false);
                updatePrompt();
                if (inputView != null) {
                    inputView.setHint("输入命令，回车执行");
                }
                scrollToBottom();
            }
        }, 0);
        if (mInteractive == null) {
            printLine("[交互启动失败，改为一次性执行]", UiConfig.WARN);
            setRunning(false);
            runOnce(raw);
        } else {
            enterInteractiveUi(true);
            if (inputView != null) {
                inputView.setHint("输入选项后回车（如 1）；空回车=确认；eof 结束");
            }
        }
    }

    // ================================================================ cd

    private void doCd(String argRaw) {
        String arg = stripQuotes(argRaw);
        if (arg.length() == 0 || arg.equals("~")) {
            cwd = "/";
            updatePrompt();
            printLine("cwd: " + cwd, UiConfig.ACCENT);
            return;
        }

        final String target = normalizePath(resolvePath(arg, cwd));
        setRunning(true);
        Thread t = new Thread(new Runnable() {
            public void run() {
                String out = null;
                try {
                    out = Shell.execSync("[ -d " + Shell.quote(target) + " ] && echo Y");
                } catch (Throwable e) {
                    out = null;
                }
                final boolean ok = out != null && out.contains("Y");
                main.post(new Runnable() {
                    public void run() {
                        setRunning(false);
                        if (ok) {
                            cwd = target;
                            updatePrompt();
                            printLine("cwd: " + cwd, UiConfig.ACCENT);
                        } else {
                            printLine("cd: 目录不存在或无权限: " + target, UiConfig.ERROR);
                        }
                    }
                });
            }
        }, "term-cd");
        t.setDaemon(true);
        t.start();
    }

    private static String stripQuotes(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() >= 2) {
            char a = t.charAt(0);
            char b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    private static String resolvePath(String arg, String base) {
        if (arg == null || arg.length() == 0) {
            return base;
        }
        if (arg.startsWith("/")) {
            return arg;
        }
        if (base.equals("/")) {
            return "/" + arg;
        }
        return base + "/" + arg;
    }

    /** 折叠 "." / ".." / 重复斜杠，得到规范的绝对路径。 */
    private static String normalizePath(String path) {
        if (path == null || path.length() == 0) {
            return "/";
        }
        String p = path;
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        String[] parts = p.split("/");
        ArrayList<String> stack = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.length() == 0 || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(part);
        }
        if (stack.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            sb.append('/').append(stack.get(i));
        }
        return sb.toString();
    }

    // ================================================================ 内置命令与对话框

    private void printHelp() {
        printLine("—— RootTerm 帮助 ——", UiConfig.ACCENT);
        printLine("内置命令（不发送给 shell）：", UiConfig.TEXT_DIM);
        printLine("  clear / cls     清屏", UiConfig.TEXT);
        printLine("  help            显示本帮助", UiConfig.TEXT);
        printLine("  cd <path>       切换工作目录（cd 不带参数回 /）", UiConfig.TEXT);
        printLine("  pwd             显示当前工作目录", UiConfig.TEXT);
        printLine("  history         打开命令历史（也可长按输入框）", UiConfig.TEXT);
        printLine("  exit            提示如何退出", UiConfig.TEXT);
        printLine("其它命令原样执行（自动先 cd 到当前目录）：", UiConfig.TEXT_DIM);
        printLine("  ls -la /data", UiConfig.TEXT);
        printLine("  pm install -r /sdcard/xxx.apk", UiConfig.TEXT);
        printLine("  /system/bin/sh /path/script.sh", UiConfig.TEXT);
        printLine("root 说明：有 root 时命令以 su 执行，可访问 /system、/data 等任意路径；",
                UiConfig.TEXT_DIM);
        printLine("无 root 时降级为 shell（app）身份，只能访问 app 私有目录与 /sdcard 可读部分。",
                UiConfig.TEXT_DIM);
        printLine("右上角「刷新」可重新探测 root；长按输出区可复制全部/清屏。",
                UiConfig.TEXT_DIM);
    }

    private void showOutputMenu() {
        final CharSequence[] items = {"复制全部", "清屏"};
        new AlertDialog.Builder(ctx)
                .setTitle("输出区")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            copyAll();
                        } else {
                            clearScreen();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyAll() {
        if (outputView == null) {
            return;
        }
        String text = outputView.getText().toString();
        if (text.length() == 0) {
            toast("输出区为空");
            return;
        }
        ClipboardManager cm = null;
        try {
            cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        } catch (Throwable ignored) {
            cm = null;
        }
        if (cm == null) {
            toast("剪贴板不可用");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("RootTerm", text));
        toast("已复制 " + text.length() + " 个字符");
    }

    private void showHistoryDialog() {
        if (history.isEmpty()) {
            toast("暂无命令历史");
            return;
        }
        final String[] items = new String[history.size()];
        for (int i = 0; i < history.size(); i++) {
            items[i] = history.get(history.size() - 1 - i);
        }
        new AlertDialog.Builder(ctx)
                .setTitle("命令历史（最新在前）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (inputView == null) {
                            return;
                        }
                        inputView.setText(items[which]);
                        inputView.setSelection(inputView.getText().length());
                        historyPos = history.size() - 1 - which;
                        inputView.requestFocus();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ 历史

    private void addHistory(String cmd) {
        if (cmd == null || cmd.length() == 0) {
            return;
        }
        if (!history.isEmpty() && history.get(history.size() - 1).equals(cmd)) {
            historyPos = history.size();
            return;
        }
        history.add(cmd);
        while (history.size() > HISTORY_MAX) {
            history.remove(0);
        }
        historyPos = history.size();
    }

    private void historyPrev() {
        if (history.isEmpty() || historyPos <= 0) {
            return;
        }
        historyPos--;
        if (inputView != null) {
            inputView.setText(history.get(historyPos));
            inputView.setSelection(inputView.getText().length());
        }
    }

    private void historyNext() {
        if (history.isEmpty()) {
            return;
        }
        if (historyPos < history.size() - 1) {
            historyPos++;
            if (inputView != null) {
                inputView.setText(history.get(historyPos));
                inputView.setSelection(inputView.getText().length());
            }
        } else {
            historyPos = history.size();
            if (inputView != null) {
                inputView.setText("");
            }
        }
    }

    // ================================================================ 小工具

    private int dp(float v) {
        return UiConfig.dp(ctx, v);
    }

    private void toast(String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // Activity 已销毁时忽略
        }
    }
}
