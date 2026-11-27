# TF管理终端（TF Manager）

一个 Android 文件管理 + 终端工具：可以从 `/` 浏览整个文件系统、用 root 执行任意命令与脚本。

- **包名**：`com.rootterm.fm`
- **版本**：见 `app/src/main/java/com/rootterm/fm/AppVersion.java`
- **兼容**：minSdk 26（Android 8.0）/ targetSdk 37（Android 17）/ compileSdk 36
- **界面**：按 [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)
  模块划分重写的 Miuix 液态玻璃风格（固定玻璃蓝配色 + 超椭圆连续圆角 +
  悬浮玻璃胶囊底栏 + 玻璃卡片列表）

## 功能

- **目录管理**：左右双栏、从 `/` 开始浏览；卡片式文件行；`‹ › ＋ ⇄ ↑` 底栏；
  `＋` 内含新建/排序/隐藏文件/搜索/单双栏/交换；文件类型矢量图标；进入目录有过渡动画
- **终端**：全屏终端，左上角返回；支持交互式程序（菜单类加载器输入 1/2 等）、
  ANSI 彩色输出、进度条 `\r` 覆盖；运行文件自动跳到终端
- **内置 bash 5.3.20**（Termux 官方 aarch64 构建）：脚本里的 bash 语法可直接跑；
  首次运行自动释放到 `/data/local/tmp/tfbash`
- **ELF 二进制识别**：`.sh` 其实是 ELF 时直接以 root 执行；脚本自动挑解释器
- **免 root 目录（SAF）**：用系统文件选择器授权目录即可读写，不需要 root
- **首页**：状态卡（root/未 root）、总存储/运行内存、设备名/内核版本/系统版本
- **设置**：界面风格、Monet 取色、所有文件访问权限、重新探测 root、终端、检查更新、关于
- **自动更新检查**：支持 GitHub Releases API 或自定义 JSON 清单

## 构建（无 Gradle，手动链路）

本项目不含 Gradle，构建依赖 `javac + aapt2 + d8 + zipalign + apksigner`。

```bash
bash tools/setup.sh   # 首次：装 JDK、下载 android.jar / build-tools、准备 aapt2 运行环境
bash build.sh         # 产物：dist/RootTerm-<version>.apk
```

本机（Ubuntu 24.04 arm64 proot）用 qemu + x86_64 aapt2；在 x86_64 Linux/CI 上
可用 `.github/workflows/build.yml`（见 `ci/build-ci.sh`）原生构建。

## 目录结构

```
app/src/main/java/com/rootterm/fm/   # 主程序（Java，framework only）
app/src/main/java/com/coui/          # coui 组件库（卡片/按钮/工具）
app/src/main/res/                    # 资源（图标、颜色、样式、coui attrs）
app/src/main/assets/tfbash/          # 内置 bash 5.3.20 + 依赖库
tools/                               # 本地工具链脚本（sdk 与 cache 不入库）
.github/workflows/build.yml          # CI：构建 APK 并上传 artifact / Release
scripts/publish.sh                   # 一键创建仓库 + push + 发布 Release
```

## 发布与更新

- 仓库：<https://github.com/CSHNB6663/TFManager>
- 发布页：<https://github.com/CSHNB6663/TFManager/releases>
- **App 默认更新源**（已内置）：
  `https://api.github.com/repos/CSHNB6663/TFManager/releases/latest`

发版流程：改 `AppVersion.java`（CODE = major*10000+minor*100+patch、NAME）+ `AndroidManifest` 版本号
→ `bash build.sh` → `GH_TOKEN=xxx python3 scripts/publish.py TFManager public`

## 更新检查配置

- **GitHub Releases**：`https://api.github.com/repos/<owner>/<repo>/releases/latest`
  （Release 里上传 `.apk` 附件，`tag_name` 作为版本号，`body` 作为更新说明）
- **自定义 JSON**：
  ```json
  {"versionCode":58,"versionName":"2.7.2","url":"https://.../app.apk","notes":"更新内容"}
  ```
- 设置页也可填网页/网盘分享页（如夸克网盘），这种只打开浏览器、不自动比对版本。

## 许可

- 本仓库代码：仅供学习/自用。
- 内置 bash 来自 [Termux](https://github.com/termux/termux-packages)（GPL-3.0），
  随 APK 一并分发仅用于运行脚本。
- UI 参考 [KernelSU-Style-UI-Kit](https://github.com/chenaizhang/KernelSU-Style-UI-Kit)（GPL-3.0）
  与 [Miuix](https://github.com/compose-miuix-ui/miuix) 的视觉规格，未直接引入其代码。

## Miuix 模块（framework-only 复刻）

按上游仓库的模块边界，把 Miuix 风格拆成独立包，全部位于
`app/src/main/java/com/miuix/`：

| 模块 | 包 | 职责 |
| --- | --- | --- |
| `miuix-ui` | `com.miuix.ui` | 基础组件：`MiuixTheme` 语义色 / 字号 / 圆角，`MiuixCard` 卡片表面 |
| `miuix-preference` | `com.miuix.pref` | 设置项分组、开关行、点击行、信息行（分组卡片即液态玻璃） |
| `miuix-icons` | `com.miuix.icons` | 纯线条图标 |
| `miuix-glass-icons` | `com.miuix.glassicons` | 嵌在玻璃方块里的图标（关于页头部、设置项前置图标） |
| `miuix-blur` | `com.miuix.blur` | 系统窗口模糊 + 降采样/盒式模糊磨砂底 |
| `miuix-squircle` | `com.miuix.squircle` | 超椭圆连续圆角路径与子 View 裁剪 |
| `miuix-material` | `com.miuix.material` | 玻璃材质参数：染色、折射、镜面、棱边、厚度（CLEAR / LIQUID / ACCENT） |
| `miuix-glass` | `com.miuix.glass` | **液态玻璃组件**：磨砂底 + 折射色带 + 染色 + 镜面光带 + 高光棱 + 超椭圆裁剪 |
| `miuix-nav` | `com.miuix.nav` | 底部玻璃导航胶囊、玻璃选中指示块、拖动切页、自动磨砂 |
| `miuix-window` | `com.miuix.window` | 桌面式玻璃标题栏 / 悬浮玻璃对话框窗口 / 自由窗口启动辅助 |

上游是 Compose Multiplatform 库，本项目是 Android framework-only Java 工程，
因此各模块按「同名、同职责」复刻视觉与 API 语义，而非引入其 Kotlin 代码。
所有卡片（首页、设置、文件列表、分区、Fastboot、ADB）、底栏、关于页、
弹窗与按钮统一使用 `miuix-glass`；玻璃材质参数集中在 `miuix-material`。

## 发布流程（每次改版本必做）

发版不是「本地出个 APK」就完了，必须三步都做完：

1. **提交并推送**：`git add -A && git commit && git push origin master`
2. **打 GitHub Release**：tag 用 `v<版本名>`（如 `v6.4.7`），标题写清版本
3. **上传 APK 附件**：把 `dist/RootTerm-<版本>.apk` 作为 release asset 传上去

应用内更新走的是 `api.github.com/repos/CSHNB6663/TFManager/releases/latest`，
只在本地产出的包没有上传，用户端就收不到更新。release notes 里写清本次改了什么。
