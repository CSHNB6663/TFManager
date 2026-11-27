# RootTerm 构建工具链（TOOLCHAIN）

> **⚠️ 实测修订（2026-11-21，Lead 已跑通并产出 APK）**
> 1. **aapt1 不能配 API 36 的 android.jar**：aapt1（AOSP 14）解析 API 35/36 框架资源表会报
>    `Entry offset at index N points outside the Type's boundaries`，随后 `@android:style/*` 全部
>    找不到 —— 原因是新版本框架资源用了稀疏编码。
> 2. **实际可用链路**：`aapt2`（build-tools 36 自带，x86_64）通过 **qemu-x86_64-static +
>    /opt/x86root（amd64 glibc sysroot）** 运行；`javac` / `d8` 使用 API 36 的 `android.jar`。
>    → `compileSdkVersion=36`、`targetSdkVersion=37`、`minSdkVersion=26`，`apksigner verify` v2+v3 通过。
> 3. 回退链路：`aapt1 + tools/sdk/android34.jar`（API 34 框架 jar，aapt1 能解析），
>    `bash build.sh --no-aapt2` 触发；此模式 badging 会记 `compileSdkVersion='34'`（功能不受影响）。
> 4. 权威文件是 **`build.sh` + `tools/setup.sh` + `README.md`**；本文是其背后的完整说明
>    （已按实际链路改写），排错顺序也是「先看 README 的验证情况，再看本文 §8」。
> 5. `tools/bootstrap.py` 是早期纯 SDK 下载器，**保留但已不参与正式构建**（`tools/setup.sh` 自包含）；
>    `tools/selftest/` 已改成与 `build.sh` 完全相同的链路，可随时做冒烟测试。

---

## 1. 环境事实

- Ubuntu 24.04 **arm64**，proot 容器，**无 root**（但容器内是 uid 0），**无 Android SDK / Gradle / 预装 JDK**；
- 网络可访问 `dl.google.com`（SDK 仓库）与国内镜像（腾讯 SDK 镜像、清华/阿里/archive.ubuntu.com 的 deb）；
- 本机没有可用沙箱后端，所有命令需要 full-access 权限执行；
- 官方 build-tools 里只有 **纯 Java** 的工具（`d8.jar`、`apksigner.jar`）能直接在 arm64 跑；
  `aapt/aapt2/zipalign` 是 **x86_64 ELF**，要么用 apt 的 arm64 版（aapt1/zipalign），要么用 qemu 跑 x86_64（aapt2）。

## 2. 实际工具链组成

| 环节 | 工具 | 架构 / 运行方式 | 来源 |
|---|---|---|---|
| Java 源码编译 | `javac`（JDK 17） | 纯 Java | apt `openjdk-17-jdk-headless` |
| 资源编译 + 链接 + R.java | **`aapt2`（build-tools 36）** | x86_64 ELF，经 **qemu-x86_64-static -L /opt/x86root** | build-tools zip 的 `aapt2` |
| 资源回退链路 | `aapt`（aapt1，AOSP 14）+ `android34.jar` | arm64 原生 | apt `aapt` + platform-34 `android.jar` |
| class → dex | `d8`（`lib/d8.jar`） | 纯 Java（JDK 11+） | build-tools 36 的 `lib/d8.jar` |
| APK 4 字节对齐 | `zipalign` | arm64 原生 | apt `zipalign` |
| 签名（v1/v2/v3） | `apksigner` | 纯 Java | apt `apksigner`，缺失时用 build-tools 的 `lib/apksigner.jar`（主类 `com.android.apksigner.ApkSignerTool`） |
| x86_64 兼容层 | `qemu-user-static` | 用户态模拟 | apt `qemu-user-static` |
| amd64 glibc 运行库 | `/opt/x86root`（libc6 + libgcc-s1 + libstdc++6 的 amd64 deb 解包） | 一次性准备 | 清华/阿里/archive.ubuntu.com |
| 编译期 API 类库 | `android.jar`（**API 36**） | 纯数据 | `platforms;android-36` 的 platform zip |
| aapt1 专用 API 类库 | `android34.jar`（**API 34**） | 纯数据 | `platforms;android-34` 的 platform zip |

关键结论：
- **`compileSdk = 36` 只体现在「javac/d8/aapt2 用 API 36 类库」**，`targetSdkVersion=37` 由
  `aapt2 link --target-sdk-version 37`（或 aapt1 的同名参数）写进 manifest，aapt 不校验上限。
- build-tools zip 里的 `aapt`/`aapt2`/`zipalign` **不能直接执行**（x86_64），aapt2 必须套 qemu；
  aapt1/zipalign 用 apt 的 arm64 版。

## 3. 目录与关键文件

```
rootterm/
  build.sh                      正式构建入口（aapt2+qemu 优先，aapt1 回退）
  tools/
    setup.sh                    环境一键准备（apt + SDK 下载 + x86root sysroot + keystore）
    TOOLCHAIN.md                本文件
    typecheck.sh                只做 javac 类型检查（不出 APK，面板开发自查用）
    bootstrap.py                早期纯 SDK 下载器（保留，不再参与正式构建）
    selftest/
      AndroidManifest.xml       自检 manifest（package com.rootterm.selftest，min 26 / target 37）
      res/values/strings.xml    自检字符串资源
      src/com/rootterm/selftest/MainActivity.java   最小 Activity（纯代码 UI，不引用 R）
      run.sh                    与 build.sh 同链路的冒烟测试 → dist/RootTerm-selftest.apk
    sdk/
      android.jar               API 36（javac / d8 / aapt2 用）
      android34.jar             API 34（仅 aapt1 回退用）
      d8.jar                    build-tools 36 的 lib/d8.jar
      apksigner.jar             build-tools 36 的 lib/apksigner.jar（兜底）
      aapt2 / aapt2_34          build-tools 的 aapt2（x86_64，靠 qemu 运行）
      SOURCES.txt               实际来源 / 大小 / sha1 记录
    cache/                      下载缓存（SDK zip / repository2-1.xml）
    out/                        构建中间产物（每次构建清空）
    debug.keystore              调试签名密钥（setup.sh / build.sh 自动生成）
  dist/
    RootTerm-<版本>.apk          产物（正式签名；缺密钥时回退 debug 并告警）
    RootTerm-1.0.apk.idsig      apksigner 生成的 v4 签名附属文件（对安装无影响，可忽略）
```

## 4. 从零准备：`bash tools/setup.sh`

幂等，可重复执行。四步：

1. **apt 安装**：`openjdk-17-jdk-headless aapt zipalign apksigner qemu-user-static zip unzip`。
   注意：proot 里 `man` 目录缺失会让 openjdk 的 postinst 失败，setup.sh 先建
   `/usr/share/man/man1`、`/usr/share/man/man7` 再装。
2. **下载 SDK 资源**（脚本内嵌 Python，无第三方依赖，带腾讯镜像回退）：
   - `repository2-1.xml` → `platforms;android-36`、`platforms;android-34`、`build-tools;36.0.0`（linux）；
   - 解出 `android.jar`、`android34.jar`、`d8.jar`、`apksigner.jar`、`aapt2`；
   - 下载 zip 缓存到 `tools/cache/`（可重复执行时复用）。
3. **准备 x86_64 sysroot `/opt/x86root`**（让 qemu 能跑 aapt2）：
   - 从 Ubuntu 镜像下 3 个 amd64 deb：`libc6`、`libgcc-s1`、`libstdc++6`；
   - `dpkg-deb -x` 解到 `/opt/x86root`；
   - 建 `lib64/ld-linux-x86-64.so.2` 与 `lib -> usr/lib` 软链；
   - 用 `qemu-x86_64-static -L /opt/x86root tools/sdk/aapt2 version` 自检，失败则提示回退 aapt1。
   流量约 200MB（apt + SDK + sysroot），手机网络下几分钟。
4. **debug keystore**：不存在才生成（见 §6.3）。

## 5. 构建：`bash build.sh`

```bash
cd /root/workspace/rootterm
bash build.sh                 # -> dist/RootTerm-1.0.apk，并自动验收
bash build.sh --out /tmp/x.apk
bash build.sh --no-aapt2      # 强制走 aapt1 回退链路（compileSdk 记为 34）
bash build.sh -h
```

流程（脚本里是 `[1/7]`~`[7/7]`）：

| 步 | 动作 |
|---|---|
| 0 | 检查 `tools/sdk`、`app/src/main`、keystore（缺失则生成） |
| 1 | **aapt2 模式**：`aapt2 compile --dir app/src/main/res -o out/res.zip` → `aapt2 link -o out/base.apk -I android.jar --manifest … --java out/gen --min-sdk-version 26 --target-sdk-version 37 res.zip`；**回退模式**：`aapt package -f -m -J out/gen -M manifest -S res -I android34.jar -F out/base.apk --min-sdk-version 26 --target-sdk-version 37` |
| 2 | `javac -source 8 -target 8 -bootclasspath android.jar -encoding UTF-8 -proc:none -nowarn -Xlint:none -d out/classes $(find app/src/main/java out/gen -name '*.java')` |
| 3 | `java -cp d8.jar com.android.tools.r8.D8 --min-api 26 --lib android.jar --output out/dex <所有 .class>` |
| 4 | python3 把 `out/dex/classes.dex` 追加进 `out/base.apk` → `out/unsigned.apk` |
| 5 | `zipalign -f -p 4 out/unsigned.apk out/aligned.apk`（必须在签名前） |
| 6 | `apksigner sign --ks .signing/release.keystore --ks-pass pass:<pw> --key-pass pass:<pw> --ks-key-alias rootterm --min-sdk-version 26 --v1/v2/v3-signing-enabled true --out dist/RootTerm-1.0.apk out/aligned.apk` |
| 7 | 校验：`apksigner verify -v` + `aapt dump badging`，并**硬断言** `sdkVersion:'26'`、`targetSdkVersion:'37'`，任一不满足 exit 1 |

实测：`bash build.sh` 一次通过，约 **10 秒**；产物 `dist/RootTerm-1.0.apk` 约 61 KB。

## 6. 关键坑与设计决策

### 6.1 aapt1 与 API 36 框架资源表（最重要）
现象：`aapt package -I tools/sdk/android.jar`（API 36）报
`Entry offset at index N points outside the Type's boundaries`，之后所有 `@android:style/*` 解析失败。
原因：API 35/36 的 `resources.arsc` 用了 aapt1(AOSP 14) 不认识的稀疏编码。
两种解法（build.sh 都实现了）：
1. **主链路**：build-tools 36 的 `aapt2` 是 x86_64，但它支持新格式；用 qemu-x86_64-static +
   `/opt/x86root` 跑它，`compileSdkVersion` 正确记 36。
2. **回退链路**：`aapt1` + **API 34 的 `android34.jar`**（旧格式它读得动），
   `bash build.sh --no-aapt2` 触发；APK 功能与 36 版一致，只是 badging 里 `compileSdkVersion` 会显示 34。
   本项目 res 只用到 API ≤33 的属性（`windowLayoutInDisplayCutoutMode`、`enableOnBackInvokedCallback` 等），
   所以 API 34 框架 jar 足够编译当前 res。

### 6.2 targetSdkVersion=37 怎么写进去
aapt/aapt2 不校验 targetSdk 上限，`--target-sdk-version 37` 直接写进 manifest 的 `uses-sdk`；
`aapt dump badging` 显示 `targetSdkVersion:'37'`。Android 17 尚未发布正式 SDK，所以
**compileSdk 36 + targetSdk 37** 的组合是刻意为之（SPEC 硬约束）。

### 6.3 签名
- **正式签名（默认）**：`.signing/release.keystore`（PKCS12，alias `rootterm`，RSA 4096，有效期到 2056），
  密码放在 `.signing/pass.txt`。两者都在 `.gitignore` 里，**绝不可提交到仓库**。
  `build.sh` 自动优先使用它；可用环境变量覆盖：
  ```bash
  RELEASE_KS=/path/to/ks RELEASE_KS_PASS=xxxx RELEASE_KS_ALIAS=rootterm bash build.sh
  ```
  首次生成（换机后照此重建，但**换 key 会导致无法覆盖安装**）：
  ```bash
  mkdir -p .signing && chmod 700 .signing
  keytool -genkeypair -keystore .signing/release.keystore -storetype PKCS12 \
      -storepass "$PASS" -alias rootterm -keypass "$PASS" \
      -keyalg RSA -keysize 4096 -validity 10950 \
      -dname "CN=TF Manager, O=CSHNB6663, C=CN"
  ```
- **回退**：找不到正式密钥时用 `tools/debug.keystore`（PKCS12，密码 `android`，alias `androiddebugkey`），
  构建会打印醒目告警。debug 签名不可分发：密码公开，任何人都能签出同签名包。
- 必须先 `zipalign` 后 `apksigner`（v2 签名块会覆盖对齐信息）。
- `dist/` 下的 `*.idsig` 是 v4 签名附属文件，**不影响安装**，可忽略/删除。
- **keystore 一旦生成不要删**，否则签名 key 变化，覆盖安装会失败（必须先卸载旧 APK，应用数据会丢）。
  请离线备份 `.signing/release.keystore` 与 `pass.txt`。

### 6.3.1 版本号唯一来源
- `app/src/main/java/com/rootterm/fm/AppVersion.java` 的 `CODE` / `NAME` 是唯一来源。
- `build.sh` 在构建时把两者 `sed` 注入 `tools/out/AndroidManifest.xml` 再交给 aapt，
  所以 `AndroidManifest.xml` 里的 `versionCode`/`versionName` 只是占位，改了会被覆盖。
- 编码规则：`CODE = major*10000 + minor*100 + patch`（6.4.3 → 60403），与更新源 tag 解析一致。

### 6.3.2 可复现构建
- `tools/encstrings.py` 的 BuildKey 默认由 `BUILD_SEED` 决定，`build.sh` 默认取 `tfmanager-<版本号>`，
  因此**同源码同版本重复构建 dex 字节一致**。
- 需要每次随机（例如防批量对比）：`BUILD_SEED=random bash build.sh`。

### 6.4 javac / d8
- `javac -source 8 -target 8 -bootclasspath android.jar`：JDK 17 下合法，`-bootclasspath` 只在
  `-source 8`（或更低）时可用；若改成 `--release 8` 会失去 android.jar 的 bootclasspath，编译会失败。
- 源码含中文，必须 `-encoding UTF-8`。
- 没有任何注解处理器，用 `-proc:none -nowarn -Xlint:none` 保持输出干净。
- `d8` 必须 `--lib android.jar`，否则报 `java.lang.Object` 等库类缺失；`--min-api 26` 与 minSdk 一致。

### 6.5 qemu sysroot 的脆弱点
- `/opt/x86root` 在 **workspace 之外**，不随项目打包；换机器/重装 proot 后需重跑 `tools/setup.sh`。
- 只解了 `libc6/libgcc-s1/libstdc++6` 三个 amd64 deb：aapt2 只需要这些；若换更新版本 aapt2 报缺库，
  按同样方式补 deb 再 `dpkg-deb -x` 到 `/opt/x86root`。
- `qemu-x86_64-static` 必须存在且可执行，否则 build.sh 自动回退 aapt1，不会失败。
- 性能：qemu 跑 aapt2 只有几十毫秒级开销，实测整次构建约 10 秒。

### 6.6 没有 Gradle / Android SDK manager
- 不用 Gradle，无 manifest merger、lint、annotation processor、`BuildConfig`、`R` 引用（SPEC 约定）；
- 无 aapt2 的增量资源缓存，每次全量编译资源（本项目 res 很小，无所谓）；
- 无 `adb` 安装验证（本机 `adb shell pm install` 被系统 USB 安装限制拦截），安装需手动点 APK。

### 6.7 proot 环境注意事项
- 无沙箱后端，命令需 full-access；apt 的 openjdk postinst 需要先建 man 目录（setup.sh 已处理）；
- apt/下载走国内镜像；SDK 下载有腾讯镜像回退，deb 有清华/阿里/官方回退；
- `/tmp` 空间有限，大文件都在 `tools/` 或 `/opt` 处理。

## 7. 自检与开发期检查

```bash
bash tools/selftest/run.sh    # 冒烟测试：与 build.sh 同链路打 hello-world APK
                              # -> dist/RootTerm-selftest.apk，末尾打印 SELFTEST OK
bash tools/typecheck.sh       # 只 javac（不生成 R.java，要求源码不引用 R.*），改面板后可先自查
```

自检脚本会按 build.sh 同样的规则选择 aapt2+qemu 或 aapt1+API34，并对产物做
`minSdk 26 / targetSdk 37 / 签名` 硬断言。

## 8. 排错清单

| 现象 | 原因 / 处理 |
|---|---|
| `aapt: ... Entry offset at index N points outside the Type's boundaries` | 用 API 36 jar 喂了 aapt1；确认走 aapt2+qemu，或 `--no-aapt2` 回退（用 android34.jar） |
| `qemu-x86_64-static: command not found` | 重跑 `bash tools/setup.sh`；或缺 qemu 时会自动走 aapt1 回退 |
| `aapt2: error while loading shared libraries` | `/opt/x86root` 缺库：重跑 setup.sh 第 3 步，或补对应 amd64 deb |
| `缺少 tools/sdk/android.jar` / `android34.jar` / `d8.jar` | `bash tools/setup.sh`（SDK 下载失败看它的镜像回退日志） |
| `javac: 错误: 找不到符号` | 面板源码没写完/接口不一致；把 javac 原文发给对应 teammate（工具链不改 app 源码） |
| `d8: ... Type java.lang.Object not found` | 缺 `--lib android.jar`（脚本已带） |
| `apksigner: ... does not verify` | 是否先 zipalign 后签名；删 `tools/out/` 重跑 |
| `keytool: ... keystore password was incorrect` | 删 `tools/debug.keystore` 重新生成（签名 key 会变） |
| `zipalign: ...` / `apksigner` 找不到 | 重跑 setup.sh（apt 安装） |
| 构建通过但 badging 里 compileSdkVersion=34 | 当前走了 aapt1 回退（`--no-aapt2` 或 qemu/aapt2 不可用）；要 36 就修好 qemu 环境 |

## 9. 验证记录

**SDK 依赖**（`tools/sdk/SOURCES.txt` 记录）：

| 文件 | 来源 | 大小 | sha1 |
|---|---|---|---|
| `android.jar`（API 36） | `platforms;android-36`（dl.google.com） | 27,783,499 bytes | `70deee125c400ef1cda968e19d6846442772b168` |
| `d8.jar` | `build-tools;36.0.0` 的 `lib/d8.jar` | 17,998,582 bytes | `9e7ed8fb63a9aac0051fa1e51497f0d480886397` |

**构建结果**（2026-11-21，Lead 实测，见 `README.md`）：

| 项 | 结果 |
|---|---|
| `bash build.sh` | ✅ 一次通过，约 **10 秒** |
| 产物 | `dist/RootTerm-1.0.apk`，约 61 KB（debug 签名） |
| `aapt dump badging` | `sdkVersion:'26'`、`targetSdkVersion:'37'`、`compileSdkVersion='36'` |
| `apksigner verify` | v2 ✅ v3 ✅ |
| `zipalign -c -p 4` | ✅ |
| `classes.dex` 类清单 | `MainActivity`、`TerminalPanel`、`FileBrowserPanel`、`FileEditor`、`Shell`、`UiConfig` 均在 |
| 真机安装 | 未做（本机 `adb shell pm install` 被系统 USB 安装限制拦截），APK 已放 `/sdcard/Download/DSH-Go/` |
| `tools/selftest/run.sh` | 脚本已对齐正式链路，待下次跑一次确认（不影响正式产物） |
