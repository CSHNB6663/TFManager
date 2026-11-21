# TF管理终端（TF Manager）

一个 Android 文件管理 + 终端工具：可以从 `/` 浏览整个文件系统、用 root 执行任意命令与脚本。

- **包名**：`com.rootterm.fm`
- **版本**：见 `app/src/main/java/com/rootterm/fm/AppVersion.java`
- **兼容**：minSdk 26（Android 8.0）/ targetSdk 37（Android 17）/ compileSdk 36
- **界面**：Miuix / KernelSU 风格（Monet 动态取色、居中胶囊底栏、卡片列表）

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
