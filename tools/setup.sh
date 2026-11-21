#!/usr/bin/env bash
# RootTerm 构建环境一键准备（Ubuntu 24.04 arm64 / proot / 无 Android SDK）
#
# 做四件事：
#   1) apt 安装 JDK17 + aapt1 + zipalign + apksigner + qemu-user-static + zip/unzip
#   2) 下载 Android SDK platform 36 与 platform 34 的 android.jar、build-tools 的 d8.jar/apksigner.jar/aapt2
#   3) 准备 x86_64 glibc sysroot（/opt/x86root），让 qemu 能跑 aapt2（aapt1 读不了 API 35+ 的稀疏资源表）
#   4) 生成 debug keystore，并打印自检结果
#
# 用法：bash tools/setup.sh
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$ROOT"
SDK="tools/sdk"; CACHE="tools/cache"
mkdir -p "$SDK" "$CACHE" dist
export DEBIAN_FRONTEND=noninteractive

echo "== [1/4] apt 安装依赖"
apt-get update -qq || true
# proot 里 man 目录缺失会让 openjdk postinst 失败，先补上
mkdir -p /usr/share/man/man1 /usr/share/man/man7
apt-get install -y --no-install-recommends \
  openjdk-17-jdk-headless aapt zipalign apksigner qemu-user-static zip unzip >/dev/null 2>&1 || true
dpkg --configure -a >/dev/null 2>&1 || true
command -v javac >/dev/null || { echo "javac 仍不可用，请手动检查 apt" >&2; exit 1; }
echo "   javac: $(javac -version 2>&1)"

echo "== [2/4] 下载 SDK 资源（platform 36/34、build-tools 36）"
python3 - "$CACHE" "$SDK" <<'PY'
import hashlib, os, re, sys, urllib.request, zipfile

cache, sdk = sys.argv[1], sys.argv[2]
REPO = "https://dl.google.com/android/repository/repository2-1.xml"
MIRROR = "https://mirrors.cloud.tencent.com/AndroidSDK/"

def fetch(url):
    dst = os.path.join(cache, url.rsplit("/", 1)[-1])
    if os.path.exists(dst) and os.path.getsize(dst) > 4096:
        print("   cached", os.path.basename(dst)); return dst
    for base in (url, MIRROR + url.rsplit("/", 1)[-1]):
        try:
            print("   GET", base)
            req = urllib.request.Request(base, headers={"User-Agent": "curl/8"})
            with urllib.request.urlopen(req, timeout=300) as r, open(dst + ".part", "wb") as f:
                while True:
                    b = r.read(1 << 20)
                    if not b: break
                    f.write(b)
            os.replace(dst + ".part", dst)
            return dst
        except Exception as e:
            print("   FAIL", repr(e)[:120])
    raise SystemExit("download failed: " + url)

def repo_xml():
    p = os.path.join(cache, "repository2-1.xml")
    if not os.path.exists(p) or os.path.getsize(p) < 10000:
        fetch(REPO)
    return open(p, encoding="utf-8", errors="replace").read()

def pkg_url(xml, path_re, host=None):
    for m in re.finditer(r'<remotePackage path="([^"]+)".*?</remotePackage>', xml, re.S):
        if not re.match(path_re, m.group(1)): continue
        for a in re.finditer(r"<archive.*?</archive>", m.group(0), re.S):
            s = a.group(0)
            ho = re.findall(r"<host-os>([^<]+)</host-os>", s)
            if host and (not ho or ho[0] != host): continue
            u = re.findall(r"<url>([^<]+)</url>", s)
            if u: return u[0]
    return None

def extract_jar(zip_path, pick, out_path):
    with zipfile.ZipFile(zip_path) as z:
        names = [n for n in z.namelist() if pick(n)]
        names.sort(key=lambda n: n.count("/"))
        with z.open(names[0]) as s, open(out_path, "wb") as f:
            f.write(s.read())
    print("   %s <- %s (%d bytes)" % (out_path, names[0], os.path.getsize(out_path)))

xml = repo_xml()
p36 = pkg_url(xml, r"^platforms;android-36$")
p34 = pkg_url(xml, r"^platforms;android-34$")
bt  = pkg_url(xml, r"^build-tools;36\.0\.0$", host="linux") or pkg_url(xml, r"^build-tools;35\.0\.1$", host="linux")
for name, u in (("platform-36", p36), ("platform-34", p34), ("build-tools", bt)):
    if not u: raise SystemExit("SDK 仓库里找不到 " + name)
z36 = fetch("https://dl.google.com/android/repository/" + p36)
extract_jar(z36, lambda n: n.endswith("/android.jar"), os.path.join(sdk, "android.jar"))
if not os.path.exists(os.path.join(sdk, "android34.jar")):
    z34 = fetch("https://dl.google.com/android/repository/" + p34)
    extract_jar(z34, lambda n: n.endswith("/android.jar"), os.path.join(sdk, "android34.jar"))
zbt = fetch("https://dl.google.com/android/repository/" + bt)
extract_jar(zbt, lambda n: n.endswith("lib/d8.jar"), os.path.join(sdk, "d8.jar"))
extract_jar(zbt, lambda n: n.endswith("lib/apksigner.jar"), os.path.join(sdk, "apksigner.jar"))
# aapt2：x86_64 二进制，靠 qemu 跑
with zipfile.ZipFile(zbt) as z:
    n = [x for x in z.namelist() if x.endswith("/aapt2")][0]
    with z.open(n) as s, open(os.path.join(sdk, "aapt2"), "wb") as f:
        f.write(s.read())
os.chmod(os.path.join(sdk, "aapt2"), 0o755)
print("   aapt2 <-", n)
PY

echo "== [3/4] 准备 x86_64 sysroot（供 qemu 跑 aapt2）"
if [ ! -d /opt/x86root/usr/lib/x86_64-linux-gnu ]; then
  mkdir -p /opt/x86root /tmp/rt-debs
  python3 - <<'PY'
import os, re, urllib.request
base = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/"
files = [
    "pool/main/g/glibc/libc6_2.39-0ubuntu8_amd64.deb",
    "pool/main/g/gcc-14/libgcc-s1_14-20240412-0ubuntu1_amd64.deb",
    "pool/main/g/gcc-14/libstdc++6_14-20240412-0ubuntu1_amd64.deb",
]
os.makedirs("/tmp/rt-debs", exist_ok=True)
for f in files:
    dst = os.path.join("/tmp/rt-debs", os.path.basename(f))
    if not os.path.exists(dst):
        for b in (base, "https://mirrors.aliyun.com/ubuntu/", "http://archive.ubuntu.com/ubuntu/"):
            try:
                print("   GET", b + f)
                with urllib.request.urlopen(b + f, timeout=180) as r, open(dst, "wb") as o:
                    o.write(r.read())
                break
            except Exception as e:
                print("   FAIL", repr(e)[:100])
    print("   have", dst, os.path.getsize(dst))
PY
  for f in /tmp/rt-debs/*.deb; do dpkg-deb -x "$f" /opt/x86root; done
  mkdir -p /opt/x86root/lib64
  ln -sf ../usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 /opt/x86root/lib64/ld-linux-x86-64.so.2
  [ -e /opt/x86root/lib ] || ln -sfn usr/lib /opt/x86root/lib
fi
if [ -x /usr/bin/qemu-x86_64-static ] && [ -f tools/sdk/aapt2 ] \
   && /usr/bin/qemu-x86_64-static -L /opt/x86root tools/sdk/aapt2 version >/dev/null 2>&1; then
  echo "   aapt2 OK: $(/usr/bin/qemu-x86_64-static -L /opt/x86root tools/sdk/aapt2 version 2>&1 | head -1)"
else
  echo "   [警告] aapt2 无法运行，build.sh 将自动回退 aapt1（compileSdk 记为 34）"
fi

echo "== [4/4] debug keystore"
[ -f tools/debug.keystore ] || keytool -genkeypair -keystore tools/debug.keystore \
  -storetype PKCS12 -storepass android -alias androiddebugkey -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
echo "   tools/debug.keystore OK"

echo
echo "环境就绪。构建：bash build.sh   →  dist/RootTerm-1.0.apk"
