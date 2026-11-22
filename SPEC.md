# RootTerm 文件管理终端 —— 项目规格（团队共用契约）

## 目标
一个**纯本地**的 Android 工具 App：文件管理终端。
- 能用 root（Magisk / KernelSU / 系统 su）执行**任意命令与任意文件**；
- 能从 **`/` 根目录**开始浏览整个文件系统（/system、/data、/proc 等）；
- 无 root 时自动降级为普通 app 身份，并明确提示（不崩溃）。

## 硬约束
- **编译 SDK：android-36 的 `android.jar`**（本机可下载），**targetSdk = 37（Android 17），minSdk = 26**。
  即：面向 Android 17 行为适配，但用 36 的 API 编译（API 37 尚未公开分发）。
- **不使用 AndroidX、Kotlin、Gradle、布局 XML**。只用 framework API + Java 8 语法。
- 所有 UI 用代码构建（`new TextView(...)` 等），深色主题，自行设色。
- 所有文件系统访问、命令执行**必须走 root shell**（`java.io.File` 在无 root 时读不到 /data，且 Android 11+ 访问 /sdcard 也受限）。
- 所有阻塞操作放到后台线程；主线程只更新 UI。禁止主线程 IO。
- 不要引用 `R.*`（编译期只保证 aapt 生成的 R.java 存在，但为避免签名不一致，**统一不引用 R**，字符串用 Java 常量）。
- 不要写 `android:debuggable`、不要联网权限（纯本地）、不要第三方 SDK/统计。
- 不要写危险行为：不自动启用 root 命令、不做静默删除、不写 /system（只读浏览即可，写操作需用户逐次确认）。

## 目录结构（严格按此，禁止越界写别人的文件）
```
rootterm/
  SPEC.md                                    (Lead)
  build.sh                                   (task-1 toolchain)
  tools/**                                   (task-1 toolchain)
  dist/**                                    (task-1 toolchain 输出 APK)
  app/src/main/AndroidManifest.xml           (Lead)
  app/src/main/res/values/styles.xml         (Lead)
  app/src/main/res/values/strings.xml        (Lead)
  app/src/main/res/mipmap-*/ic_launcher.png  (Lead)
  app/src/main/java/com/rootterm/fm/
      MainActivity.java      (Lead)          顶层：标题栏 + 两个 Tab（终端 / 文件）+ insets/返回处理
      Shell.java             (Lead)          唯一的命令执行引擎
      UiConfig.java          (Lead)          颜色/dp/字体等 UI 常量与工具
      TerminalPanel.java     (task-2)        终端面板，自包含
      FileBrowserPanel.java  (task-3)        文件管理面板，自包含
      FileEditor.java        (task-3)        文本查看/编辑对话框
```

## 面板契约（MainActivity 与面板之间）
两个面板都是**自包含**的 `android.widget.LinearLayout` 子类，构造函数签名必须是：
```java
public TerminalPanel(android.content.Context ctx)      // 只接受 Activity context
public FileBrowserPanel(android.content.Context ctx)
```
- 构造时必须自己 `setOrientation(VERTICAL)`、`setLayoutParams(MATCH_PARENT, MATCH_PARENT)`，**不要**假设外部有布局容器做 padding；
- 不得引用 `MainActivity` 的字段；需要 Activity 时用 `(Activity) getContext()`；
- 面板各自负责顶部自己的工具栏/状态栏；
- `FileBrowserPanel` 必须公开 `public boolean navigateUp()`：能上退一级目录就返回 `true`，已在 `/` 返回 `false`；
- `TerminalPanel` 公开 `public void onPanelShown()`（切到时聚焦输入框）和 `public boolean navigateUp()`（恒返回 `false`，让返回键退出 App）；
- 面板之间不得直接互相引用。

## Shell API（Lead 实现，两个面板都只能用这个）
文件：`com.rootterm.fm.Shell`
```java
public interface Callback {
    void onStart();              // 主线程，命令开始时
    void onLine(String line);    // 主线程，逐行/逐块输出（stdout+stderr 已合并）
    void onDone(int exitCode);   // 主线程；-1 = 超时/无法执行
}

public static boolean isRoot();                 // 读缓存，不阻塞，可在主线程调用
public static boolean detectRoot();             // 阻塞探测（<=3s），刷新缓存并返回
public static void detectRootAsync(Runnable doneOnMain); // 后台探测，完成后主线程回调
public static String execSync(String cmd);      // 阻塞；合并输出；默认超时 20s
public static String execSync(String cmd, int timeoutMs);
public static void exec(String cmd, Callback cb);         // 异步流式执行
public static String quote(String arg);         // 安全地单引号包裹一个参数
public static String identity();                // "root" 或 "shell"
```
- `exec`/`execSync` 内部已处理：有 root 用 `su -c`，无 root 用 `/system/bin/sh -c`；
- 面板**不要自己写 ProcessBuilder / su 拼装**，统一走 Shell（便于统一超时与降级）；
- `Shell.isRoot()` 在 `detectRoot()` 未完成前返回 false。

## UiConfig API（Lead 实现）
```java
public final class UiConfig {
    public static final int BG = 0xFF0D1117;       // 页面底色
    public static final int SURFACE = 0xFF161B22;  // 卡片/工具栏底色
    public static final int TEXT = 0xFFE6EDF3;     // 主文字
    public static final int TEXT_DIM = 0xFF8B949E; // 次要文字
    public static final int ACCENT = 0xFF3FB950;   // 绿（root/成功）
    public static final int WARN = 0xFFD29922;     // 黄
    public static final int ERROR = 0xFFF85149;    // 红（失败/无权限）
    public static final int BORDER = 0xFF30363D;
    public static int dp(Context c, float v);              // dp -> px
    public static TextView text(Context c, String s, int sizeSp, int color);
    public static Button button(Context c, String s, boolean primary);
    public static View divider(Context c);
}
```
颜色请统一用 `UiConfig` 常量，不要散落魔法数字。

## 功能清单（验收标准）
### TerminalPanel（task-2）
1. 顶部：`root@device` 状态徽标（绿=已 root / 黄=无 root），显示 `Shell.identity()`，右侧「刷新」按钮重新探测；
2. 等宽字体输出区（`Typeface.MONOSPACE`），可滚动，自动滚到底，超过 5000 行裁剪头部；
3. 底部输入行：`$ ` 提示符 + EditText + 「执行」按钮，软键盘保持（`windowSoftInputMode` 由 Lead 处理，面板内不要 setSoftInputMode）；
4. 回车执行；执行中按钮禁用；执行完恢复；`exit code != 0` 用红色显示 `[exit N]`；
5. 命令历史（最多 200 条，上下按钮或长按菜单调用，或 EditText 的 KeyEvent DPAD_UP/DOWN）；
6. `cd` 命令自己处理（更新提示符里的路径，路径不存在时报错），其它命令前自动加 `cd <cwd> && `；
7. `clear` / `help` / `exit`（exit 提示可用返回键退出）内置命令；
8. 长按输出区 → 全选复制到剪贴板；
9. 启动时 `detectRootAsync` 并打印横幅：`RootTerm v1.0 · 身份: root/shell`；
10. 无 root 时横幅用黄色提示「未获取 root：仅能访问 app 私有目录与 /sdcard 可读部分」。

### FileBrowserPanel（task-3）
1. 顶部工具条：路径输入框（可手输跳转）+「上级」「刷新」「⋮」（新建文件夹/新建文件/排序/隐藏文件/搜索）；
2. 列表：每项一行，显示 `图标(目录/文件/链接)` 名称 / 权限位 / owner:group / 大小 / 修改时间；目录优先、按名称排序；隐藏文件默认不显示；
3. 点击目录 → 进入；点击文本类（.txt/.conf/.sh/.xml/.json/.log/无扩展且 size<512KB）→ FileEditor 打开；点击 .apk → 弹菜单（属性/复制路径/以 root 安装 `pm install -r`）；点击可执行/脚本 → 菜单（以 root 运行、以 shell 运行、编辑）；点击图片 → 复制路径 + 提示（不内嵌图片解码，避免 OOM）；
4. 长按项 → 上下文菜单：复制、剪切、粘贴、重命名、删除（必须二次确认，删除用 `rm -rf --`，确认框里显示绝对路径）、属性、分享、复制路径；
5. 剪贴板式复制/剪切用面板内字段记录源路径，粘贴前检查目标是否在同一路径；
6. 新建文件夹/文件、chmod（弹输入权限，如 755）；
7. 顶部右侧显示 `df -h <path>` 的可用空间（例如 `可用 12.3G`），路径不可读时显示「无权限」；
8. `navigateUp()` 返回上级；到 `/` 返回 false；返回键行为由 MainActivity 调 `navigateUp()`；
9. 所有操作后台线程执行，结果用 Toast 提示（成功/失败原因）；
10. `ls` 解析：用 `ls -la --time-style=+%Y-%m-%d_%H:%M` 或 `stat`（优先 `toybox ls`，Android 无 `--time-style` 时降级 `ls -la`）；解析失败时用 `find`/`stat` 兜底，任何情况不能崩。

## Miuix UI 规格（按上游模块划分复刻）

上游：<https://github.com/compose-miuix-ui/miuix>（Compose Multiplatform）。
本工程是 Android framework-only Java，按「同名、同职责」复刻，不引入其 Kotlin 代码。
所有 UI 必须用代码构建，玻璃相关组件统一走 `com.miuix.*`：

| 模块 | 包 | 契约 |
| --- | --- | --- |
| `miuix-ui` | `com.miuix.ui` | `MiuixTheme` 语义色/字号/圆角/间距；`MiuixCard` 基础表面 |
| `miuix-preference` | `com.miuix.pref` | `MiuixPreference.group()` 构建设置分组；支持 `switchRow` / `clickRow` / `iconClickRow` / `infoRow` / `wrapInfoRow` / `customRow` |
| `miuix-icons` | `com.miuix.icons` | `MiuixIcons.icon()` 纯图标 |
| `miuix-glass-icons` | `com.miuix.glassicons` | `MiuixGlassIcons.tile()` / `badge()` 玻璃底图标 |
| `miuix-blur` | `com.miuix.blur` | `applyWindowBlur()` 系统模糊；`backdrop()` 磨砂底 |
| `miuix-squircle` | `com.miuix.squircle` | `Squircle.path()` 超椭圆；`clipChildren()` 连续圆角裁剪 |
| `miuix-material` | `com.miuix.material` | `MiuixMaterial.Preset`（CLEAR / LIQUID / ACCENT）集中定义玻璃材质参数 |
| `miuix-glass` | `com.miuix.glass` | `MiuixGlass`：磨砂底 + 折射色带 + 染色 + 镜面光带 + 高光棱 + 超椭圆裁剪；`create/clear/accent/pill/material/tint/rim/specular/autoBackdrop` |
| `miuix-nav` | `com.miuix.nav` | `MiuixNavBar`：玻璃胶囊底栏、玻璃指示块、点击/拖动切页、`autoBackdrop()` |
| `miuix-window` | `com.miuix.window` | `MiuixWindow.captionBar()` 桌面标题栏；`builder()` 玻璃对话框（支持 `captionBar(true)`）；`freeform()` 自由窗口辅助 |

强制约定：
1. **爱用 `miuix-glass`**：卡片、设置分组、底栏、关于页、弹窗、弹窗按钮、
   首页操作卡与状态块都用 `MiuixGlass` / `MiuixCard`（后者继承前者）；不再用
   `GradientDrawable` 纯色圆角充当 Miuix 卡片。
2. 玻璃材质参数只在 `miuix-material` 定义，组件不得各自硬编码一整套颜色。
3. 底栏与「关于」是验收重点：底栏必须整块液态玻璃 + 连续圆角 + 自动磨砂；
   「关于」必须用 `miuix-window` 玻璃窗口 + 桌面标题栏 + `miuix-glass-icons`
   图标 + `miuix-preference` 信息卡，并列出全部 Miuix 模块。
4. 图标分两套：纯图标走 `miuix-icons`，玻璃底图标走 `miuix-glass-icons`。
5. 模糊只通过 `miuix-blur`；连续圆角只通过 `miuix-squircle`。

## 构建与验证（Lead 负责集成）
- `bash /root/workspace/rootterm/build.sh` → `dist/RootTerm-1.0.apk`（已签名，v1+v2+v3）；
- 验证：`aapt dump badging` 显示 `minSdk 26 / targetSdk 37`，`apksigner verify` 通过；
- 产物另存一份到 `/sdcard/Download/DSH-Go/RootTerm-1.0.apk`，并附 README（安装方式、root 说明、功能列表、已知限制）。

## 协作规则
- 只写自己任务里列出的文件；别的文件只读。
- 写代码前先看本文件与已存在的 `Shell.java`、`UiConfig.java`。
- 完成后：`team_task_update` 把任务置为 completed，并 `send_message` 给 Lead 汇报（改动的文件、关键实现点、未通过的自检项）。
- 不要运行 `build.sh`（Lead 统一集成，避免并发写 dist/）。可以用 `javac` 单独做语法/类型检查（JDK 装好后 android.jar 在 `rootterm/tools/sdk/android.jar`）。
