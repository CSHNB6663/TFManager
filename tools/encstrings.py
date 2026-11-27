#!/usr/bin/env python3
"""构建期加固脚本（无第三方依赖）。

做三件事：
1. 生成 BuildKey.java —— 本次出包的随机 XOR 密钥（每次构建都不同）；
2. 生成 BuildAssets.java —— assets/tfbash/* 的 SHA-256，供运行时完整性校验；
3. 把源码中指定的敏感字面量替换成 Crypt.d("<密文>")，静态扫描看不到明文。

用法：
    python3 tools/encstrings.py <srcCopyDir> <genDir> <assetsDir>
"""
import base64
import hashlib
import os
import random
import sys

# 需要加密的敏感字面量（明文）。构建时会被替换为 Crypt.d("...")
SENSITIVE = [
    "https://api.github.com/repos/CSHNB6663/TFManager/releases/latest",
    "https://pan.quark.cn/s/3a946401d243",
    "api.github.com",
    "pan.quark.cn",
    "pan.baidu.com",
    "aliyundrive",
    "lanzou",
    "https://api.github.com/repos/你/仓库/releases/latest 或 JSON 清单地址",
]

# 参与完整性校验的 assets 子目录
ASSET_SUBDIR = "tfbash"


def make_key(n=32):
    return bytes(random.randrange(1, 256) for _ in range(n))


def seed_from_env():
    """BUILD_SEED 环境变量存在时固定随机种子，使构建可复现。"""
    s = os.environ.get("BUILD_SEED", "").strip()
    if not s:
        return None
    try:
        seed = int(s)
    except ValueError:
        seed = int(hashlib.sha256(s.encode("utf-8")).hexdigest()[:16], 16)
    random.seed(seed)
    return seed


def xor_b64(plain, key):
    data = plain.encode("utf-8")
    out = bytes(data[i] ^ key[i % len(key)] for i in range(len(data)))
    return base64.b64encode(out).decode("ascii")


def gen_buildkey(gen_dir, key):
    arr = ", ".join("(byte)0x%02x" % b for b in key)
    path = os.path.join(gen_dir, "com", "rootterm", "fm", "BuildKey.java")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(
            "package com.rootterm.fm;\n\n"
            "/** 由构建脚本生成：本次出包的字符串解密密钥。请勿手改。 */\n"
            "public final class BuildKey {\n"
            "    private static final byte[] K = {%s};\n\n"
            "    public static byte[] get() {\n"
            "        return K;\n"
            "    }\n\n"
            "    private BuildKey() {\n"
            "    }\n"
            "}\n" % arr
        )
    return path


def gen_buildassets(gen_dir, assets_dir):
    sub = os.path.join(assets_dir, ASSET_SUBDIR)
    entries = []
    if os.path.isdir(sub):
        for name in sorted(os.listdir(sub)):
            p = os.path.join(sub, name)
            if not os.path.isfile(p):
                continue
            h = hashlib.sha256()
            with open(p, "rb") as f:
                for chunk in iter(lambda: f.read(65536), b""):
                    h.update(chunk)
            entries.append((name, h.hexdigest()))

    rows = ",\n".join('        {"%s", "%s"}' % (n, h) for n, h in entries)
    body = (
        "package com.rootterm.fm;\n\n"
        "/** 由构建脚本生成：assets/%s 下各文件 SHA-256。请勿手改。 */\n"
        "public final class BuildAssets {\n"
        "    public static final String[][] MAP = {\n%s\n    };\n\n"
        "    private BuildAssets() {\n"
        "    }\n"
        "}\n" % (ASSET_SUBDIR, rows if rows else "")
    )
    path = os.path.join(gen_dir, "com", "rootterm", "fm", "BuildAssets.java")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(body)
    return path, len(entries)


def rewrite(src_dir, key):
    """把敏感字面量替换成 Crypt.d("<密文>")。"""
    mapping = {}
    for plain in SENSITIVE:
        mapping['"%s"' % plain] = 'Crypt.d("%s")' % xor_b64(plain, key)

    changed_files = 0
    changed_hits = 0
    for root, _dirs, files in os.walk(src_dir):
        for name in files:
            if not name.endswith(".java"):
                continue
            p = os.path.join(root, name)
            with open(p, encoding="utf-8") as f:
                text = f.read()
            orig = text
            hits = 0
            for plain_lit, repl in mapping.items():
                if plain_lit in text:
                    hits += text.count(plain_lit)
                    text = text.replace(plain_lit, repl)
            if text != orig:
                with open(p, "w", encoding="utf-8") as f:
                    f.write(text)
                changed_files += 1
                changed_hits += hits
    return changed_files, changed_hits


def main():
    if len(sys.argv) < 4:
        print("用法: encstrings.py <srcCopyDir> <genDir> <assetsDir>")
        return 2
    src_dir, gen_dir, assets_dir = sys.argv[1], sys.argv[2], sys.argv[3]

    seed = seed_from_env()
    key = make_key()
    gen_buildkey(gen_dir, key)
    _path, n_assets = gen_buildassets(gen_dir, assets_dir)
    files, hits = rewrite(src_dir, key)

    print("  BuildKey 已生成（32 字节%s密钥）" % ("固定种子" if seed is not None else "随机"))
    print("  BuildAssets 已生成：%d 个文件哈希" % n_assets)
    print("  字符串加密：%d 个文件、%d 处字面量已替换" % (files, hits))
    return 0


if __name__ == "__main__":
    sys.exit(main())
