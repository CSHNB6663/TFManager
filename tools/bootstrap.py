#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RootTerm 工具链依赖下载器（纯 Python3，只用标准库）。

下载/解出 4 个文件到 tools/sdk/：
  android.jar      API 36，javac / d8 用的编译类库（platforms;android-36 的 platform zip）
  android34.jar    API 34，**只给 aapt1 做 -I 资源链接**（aapt1(AOSP 14) 读不了 API 35/36
                   的稀疏资源表；API 34 的 android.jar 它能正常解析）
  d8.jar           build-tools 的 lib/d8.jar（纯 Java，arm64 可跑）
  apksigner.jar    build-tools 的 lib/apksigner.jar（纯 Java，apt apksigner 缺失时兜底）

不下载、不使用 build-tools 里的 aapt/aapt2/zipalign 等 x86_64 ELF（arm64 上跑不了）。

用法：
  python3 tools/bootstrap.py            # 缺什么补什么
  python3 tools/bootstrap.py --force    # 强制重新下载全部
"""
import hashlib
import os
import shutil
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SDK_DIR = os.path.join(HERE, "sdk")
CACHE_DIR = os.path.join(HERE, "cache")
SOURCES_TXT = os.path.join(SDK_DIR, "SOURCES.txt")

ANDROID_JAR = os.path.join(SDK_DIR, "android.jar")
ANDROID34_JAR = os.path.join(SDK_DIR, "android34.jar")
D8_JAR = os.path.join(SDK_DIR, "d8.jar")
APKSIGNER_JAR = os.path.join(SDK_DIR, "apksigner.jar")

BASES = [
    "https://dl.google.com/android/repository",
    "https://mirrors.cloud.tencent.com/AndroidSDK",
]
REPO_XML = "repository2-1.xml"

PLATFORM_CANDIDATES = ["platforms;android-36"]
PLATFORM_AAPT_CANDIDATES = ["platforms;android-34", "platforms;android-33"]
BUILD_TOOLS_CANDIDATES = [
    "build-tools;36.0.0",
    "build-tools;35.0.1",
    "build-tools;35.0.0",
    "build-tools;34.0.0",
]

# 元数据解析失败时的兜底文件名（按顺序尝试）。
PLATFORM_FALLBACK = ["platform-36_r02.zip", "platform-36_r01.zip"]
AAPT_PLATFORM_FALLBACK = ["platform-34_r03.zip", "platform-34_r02.zip",
                          "platform-34_r01.zip", "platform-33_r03.zip"]
BUILD_TOOLS_FALLBACK = [
    "build-tools_r36-linux.zip",
    "build-tools_r36.0.0-linux.zip",
    "build-tools_r35-linux.zip",
    "build-tools_r35.0.0-linux.zip",
    "build-tools_r34-linux.zip",
]
USER_AGENT = "rootterm-toolchain/1.0 (android proot arm64)"
CHUNK = 1 << 16
LAST_URL = None  # 最近一次成功下载的真实 URL（记录镜像回退后的来源）


def log(msg):
    print(msg, flush=True)


def die(msg):
    log("错误: " + msg)
    sys.exit(1)


def local(tag):
    return tag.rsplit("}", 1)[-1]


def digest(path, algo):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url, dest, expect_sha1=None):
    global LAST_URL
    tmp = dest + ".part"
    log("    下载: " + url)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=180) as resp, open(tmp, "wb") as f:
        total = int(resp.headers.get("Content-Length") or 0)
        got = 0
        last_pct = -10
        while True:
            chunk = resp.read(CHUNK)
            if not chunk:
                break
            f.write(chunk)
            got += len(chunk)
            if total > 0:
                pct = got * 100 // total
                if pct >= last_pct + 10:
                    last_pct = pct - (pct % 10)
                    log("    %s: %d%%" % (os.path.basename(dest), pct))
            elif got // (10 << 20) > last_pct:
                last_pct = got // (10 << 20)
                log("    %s: %d MB" % (os.path.basename(dest), got >> 20))
    if expect_sha1:
        actual = digest(tmp, "sha1")
        if actual.lower() != expect_sha1.lower():
            os.remove(tmp)
            raise RuntimeError("sha1 校验失败\n    期望 %s\n    实际 %s" % (expect_sha1, actual))
    os.replace(tmp, dest)
    LAST_URL = url
    return dest


def fetch(filename, expect_sha1=None):
    """按 BASES 顺序尝试下载同一个文件名到缓存目录。"""
    dest = os.path.join(CACHE_DIR, filename)
    if os.path.isfile(dest) and os.path.getsize(dest) > 1000000:
        log("    已缓存: " + dest)
        LAST_URL = "cache://" + filename
        return dest
    errors = []
    for base in BASES:
        url = base.rstrip("/") + "/" + filename
        try:
            return download(url, dest, expect_sha1)
        except Exception as exc:  # noqa: BLE001 - 逐个镜像回退，最后统一报错
            errors.append("%s\n      -> %s" % (url, exc))
            log("    失败: %s" % exc)
    raise RuntimeError("所有下载地址都失败:\n    " + "\n    ".join(errors))


def fetch_with_fallback(primary_name, primary_sha1, fallbacks):
    """先试元数据解析出的文件名，再按兜底列表依次试。返回本地路径。"""
    checksums = {primary_name: primary_sha1}
    tried = []
    for name in [primary_name] + [n for n in fallbacks if n != primary_name]:
        try:
            return fetch(name, checksums.get(name))
        except Exception as exc:  # noqa: BLE001
            tried.append("%s -> %s" % (name, exc))
    raise RuntimeError("全部下载地址都失败:\n    " + "\n    ".join(tried))


def fetch_repo_xml():
    dest = os.path.join(CACHE_DIR, REPO_XML)
    if os.path.isfile(dest) and os.path.getsize(dest) > 100000:
        log("    已缓存: " + dest)
        return dest
    for base in BASES:
        url = base.rstrip("/") + "/" + REPO_XML
        try:
            return download(url, dest)
        except Exception as exc:  # noqa: BLE001
            log("    元数据下载失败: %s" % exc)
    return None


def parse_archives(xml_path):
    """返回 {remotePackage path: [(host_os, url, sha1), ...]}。"""
    result = {}
    root = ET.parse(xml_path).getroot()
    for pkg in root.iter():
        if local(pkg.tag) != "remotePackage":
            continue
        path = pkg.get("path") or ""
        archives = []
        for arch in pkg.iter():
            if local(arch.tag) != "archive":
                continue
            host = None
            url = None
            checksum = None
            for child in arch:
                name = local(child.tag)
                if name == "host-os":
                    host = (child.text or "").strip()
                elif name == "complete":
                    for sub in child:
                        sub_name = local(sub.tag)
                        if sub_name == "url":
                            url = (sub.text or "").strip()
                        elif sub_name == "checksum":
                            checksum = (sub.text or "").strip()
            if url:
                archives.append((host, url, checksum))
        if archives:
            result[path] = archives
    return result


def pick_linux_archive(archives):
    """优先 host-os=linux 或无 host-os 的包（platform zip 不分平台）。"""
    for host, url, checksum in archives:
        if host in (None, "", "any", "linux"):
            return url, checksum
    return None


def pick_package(archive_map, candidates):
    for pkg in candidates:
        if pkg in archive_map:
            picked = pick_linux_archive(archive_map[pkg])
            if picked:
                log("    %s -> %s" % (pkg, picked[0]))
                return picked
    return None


def extract_member(zip_path, dest, suffix):
    with zipfile.ZipFile(zip_path) as zf:
        names = [n for n in zf.namelist() if n.endswith(suffix)]
        if not names:
            raise RuntimeError("%s 里找不到 %s" % (zip_path, suffix))
        names.sort(key=len)
        name = names[0]
        tmp = dest + ".part"
        with zf.open(name) as src, open(tmp, "wb") as out:
            shutil.copyfileobj(src, out, 1 << 20)
        os.replace(tmp, dest)
        log("    解出 %s -> %s" % (name, dest))
        return name


def record(entries):
    """entries: [(文件名, 来源说明, 本地路径)]，写成 SOURCES.txt。"""
    os.makedirs(SDK_DIR, exist_ok=True)
    lines = ["# RootTerm 工具链依赖记录（由 tools/bootstrap.py 生成）",
             "# 说明: android34.jar 只用于 aapt1 的资源链接 -I；javac/d8 用 android.jar (API 36)。",
             "#        apksigner.jar 是 apt apksigner 缺失时的兜底（纯 Java）。"]
    for name, source, path in entries:
        if not os.path.isfile(path):
            continue
        lines.append("")
        lines.append(name)
        lines.append("  source: %s" % source)
        lines.append("  size: %d bytes" % os.path.getsize(path))
        lines.append("  sha1: %s" % digest(path, "sha1"))
        lines.append("  sha256: %s" % digest(path, "sha256"))
    with open(SOURCES_TXT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    log("    校验记录: " + SOURCES_TXT)


def require_jar(path, min_size, label):
    if not os.path.isfile(path):
        die("%s 不存在: %s" % (label, path))
    size = os.path.getsize(path)
    if size < min_size:
        die("%s 只有 %d 字节，明显不对: %s" % (label, size, path))
    return size


def main():
    force = "--force" in sys.argv[1:]
    os.makedirs(SDK_DIR, exist_ok=True)
    os.makedirs(CACHE_DIR, exist_ok=True)

    all_files = [ANDROID_JAR, ANDROID34_JAR, D8_JAR, APKSIGNER_JAR]
    if not force and all(os.path.isfile(p) for p in all_files):
        log("4 个 SDK 依赖都已存在，跳过下载（--force 可强制重下）")
        for p in all_files:
            log("  %s (%d bytes)" % (p, os.path.getsize(p)))
        return

    log("== 1/5 下载 SDK 元数据")
    repo_path = fetch_repo_xml()
    platform36 = None
    platform34 = None
    build_tools = None
    if repo_path:
        log("== 2/5 解析 repository2-1.xml")
        try:
            archive_map = parse_archives(repo_path)
            platform36 = pick_package(archive_map, PLATFORM_CANDIDATES)
            platform34 = pick_package(archive_map, PLATFORM_AAPT_CANDIDATES)
            build_tools = pick_package(archive_map, BUILD_TOOLS_CANDIDATES)
        except Exception as exc:  # noqa: BLE001
            log("    解析失败: %s" % exc)

    if not platform36:
        log("    android-36 未解析到，用兜底文件名")
        platform36 = (PLATFORM_FALLBACK[0], None)
    if not platform34:
        log("    android-34 未解析到，用兜底文件名")
        platform34 = (AAPT_PLATFORM_FALLBACK[0], None)
    if not build_tools:
        log("    build-tools 未解析到，用兜底文件名")
        build_tools = (BUILD_TOOLS_FALLBACK[0], None)

    entries = []

    # ---- android.jar (API 36)：javac / d8 ----
    if force or not os.path.isfile(ANDROID_JAR):
        log("== 3/5 下载并解出 android.jar（API 36，javac/d8 用）")
        jar = fetch_with_fallback(platform36[0], platform36[1], PLATFORM_FALLBACK)
        source = LAST_URL if LAST_URL and not LAST_URL.startswith("cache://") else platform36[0]
        extract_member(jar, ANDROID_JAR, "android.jar")
        require_jar(ANDROID_JAR, 5 * 1024 * 1024, "android.jar")
        entries.append(("android.jar（API 36，javac/d8）", source, ANDROID_JAR))
    else:
        log("android.jar 已存在，跳过下载")

    # ---- android34.jar (API 34)：只给 aapt1 的 -I ----
    if force or not os.path.isfile(ANDROID34_JAR):
        log("== 4/5 下载并解出 android34.jar（API 34，仅 aapt1 资源链接用）")
        jar = fetch_with_fallback(platform34[0], platform34[1], AAPT_PLATFORM_FALLBACK)
        source = LAST_URL if LAST_URL and not LAST_URL.startswith("cache://") else platform34[0]
        extract_member(jar, ANDROID34_JAR, "android.jar")
        require_jar(ANDROID34_JAR, 5 * 1024 * 1024, "android34.jar")
        entries.append(("android34.jar（API 34，仅 aapt1 -I）", source, ANDROID34_JAR))
    else:
        log("android34.jar 已存在，跳过下载")

    # ---- d8.jar + apksigner.jar：build-tools 纯 Java 工具 ----
    if force or not (os.path.isfile(D8_JAR) and os.path.isfile(APKSIGNER_JAR)):
        log("== 5/5 下载 build-tools 并解出 d8.jar / apksigner.jar")
        bt_file = fetch_with_fallback(build_tools[0], build_tools[1], BUILD_TOOLS_FALLBACK)
        bt_source = LAST_URL if LAST_URL and not LAST_URL.startswith("cache://") \
            else os.path.basename(bt_file)
        if force or not os.path.isfile(D8_JAR):
            extract_member(bt_file, D8_JAR, "lib/d8.jar")
            require_jar(D8_JAR, 1024 * 1024, "d8.jar")
        entries.append(("d8.jar（build-tools）", bt_source, D8_JAR))
        if force or not os.path.isfile(APKSIGNER_JAR):
            try:
                extract_member(bt_file, APKSIGNER_JAR, "lib/apksigner.jar")
                require_jar(APKSIGNER_JAR, 256 * 1024, "apksigner.jar")
                entries.append(("apksigner.jar（build-tools）", bt_source, APKSIGNER_JAR))
            except Exception as exc:  # noqa: BLE001
                log("    （可选）apksigner.jar 未解出，将只用 apt 的 apksigner: %s" % exc)
    else:
        log("d8.jar / apksigner.jar 已存在，跳过下载")

    record(entries)

    # 清掉大 zip，给手机省空间；下次重跑会重新下载。
    shutil.rmtree(CACHE_DIR, ignore_errors=True)

    log("")
    log("完成，tools/sdk/ 内容:")
    for p in all_files:
        if os.path.isfile(p):
            log("  %s (%d bytes)" % (p, os.path.getsize(p)))
    log("下一步: java -cp tools/sdk/d8.jar com.android.tools.r8.D8 --version")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # noqa: BLE001
        die(str(exc))
