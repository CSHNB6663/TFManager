#!/system/bin/sh
# ---------------------------------------------------------------------------
# 【v1.1050】独立的 pstore 转储脚本 —— 给"设备本来就有 root"的情况用。
#
# 为什么单独一个脚本，而不复用 ksu_handoff.sh：
#   ksu_handoff.sh 是**移交 KernelSU** 用的，会 load_policy / late-load 模块；
#   我们只想读一次崩溃现场，不该顺手动 SELinux 策略或加载模块。
#
# 谁调用它：App 在「已 root 检测」判定 ROOTED 之后**自动**跑一次
#   （走设备自带 root 通道：su -c，或 Shizuku 本身就是 uid 0 时直连）。
#   这条路**不需要载荷命中** —— 小米 17 Pro 那种已经装好 ReSukiSU 的机器，
#   开机就能把上一次重启的崩溃现场取出来。
#
# 为什么 App 自己读不到：/sys/fs/pstore/* 是 0400 root:root，
#   Shizuku shell（uid 2000）一律 Permission denied（真机实测）。
#
# 输出 /data/local/tmp/.ghostlock_pstore.log：ext4、跨重启保留、1777 目录，
#   App 的 Shizuku shell 读得到 —— KlogDump 的 0b) 节会自动收走它。
# ---------------------------------------------------------------------------
set -u
OUT=/data/local/tmp/.ghostlock_pstore.log

{
  echo "=== ghostlock pstore dump（App 自动触发 / 设备自带 root）==="
  echo "时间        $(date 2>/dev/null)"
  echo "uid         $(id -u 2>/dev/null)   se=$(cat /proc/self/attr/current 2>/dev/null)"
  echo "enforce     $(cat /sys/fs/selinux/enforce 2>/dev/null)"
  echo "uptime      $(cat /proc/uptime 2>/dev/null)"
  echo "uname       $(uname -r 2>/dev/null)"
  echo "bootreason  $(getprop ro.boot.bootreason 2>/dev/null)"
  echo "fingerprint $(getprop ro.build.fingerprint 2>/dev/null)"
  echo "--- ls -la /sys/fs/pstore/ ---"
  ls -la /sys/fs/pstore/ 2>&1
  echo "--- 逐个已知 ramoops 文件 ---"
  for f in /sys/fs/pstore/console-ramoops-0 /sys/fs/pstore/console-ramoops \
           /sys/fs/pstore/dmesg-ramoops-0   /sys/fs/pstore/dmesg-ramoops \
           /sys/fs/pstore/panic-ramoops     /sys/fs/pstore/pmsg-ramoops-0 \
           /sys/fs/pstore/ftrace-ramoops-0  /sys/fs/pstore/console-ramoops-1; do
    if [ -r "$f" ]; then
      echo "===== $f  ($(wc -c <"$f" 2>/dev/null) 字节) ====="
      cat "$f" 2>&1
      echo
    else
      echo "----- $f : 不可读或不存在 -----"
    fi
  done
  echo "--- 目录扫描（不同内核 ramoops 命名不一样）---"
  for f in /sys/fs/pstore/*; do
    [ -e "$f" ] || continue
    case "$f" in
      *console-ramoops*|*dmesg-ramoops*|*panic-ramoops*|*pmsg-ramoops*|*ftrace-ramoops*) continue ;;
    esac
    [ -r "$f" ] || continue
    echo "===== $f (目录扫描命中) ====="
    cat "$f" 2>&1
  done
  echo "--- ramoops 到底注册上没有（root 才看得到）---"
  echo "\$ dmesg | grep -iE 'ramoops|pstore'"
  dmesg 2>/dev/null | grep -iE 'ramoops|pstore' | head -20
  echo "\$ ls /proc/device-tree/reserved-memory/"
  ls /proc/device-tree/reserved-memory/ 2>&1
  echo "\$ cat /proc/device-tree/reserved-memory/ramoops*/reg 2>/dev/null | od -An -tx1 | head -4"
  cat /proc/device-tree/reserved-memory/ramoops*/reg 2>/dev/null | od -An -tx1 | head -4
  echo "\$ ls -d /sys/module/*ramoops* /sys/module/*pstore* 2>/dev/null"
  ls -d /sys/module/*ramoops* /sys/module/*pstore* 2>/dev/null
  echo "\$ grep -oE '[^ ]*ramoops[^ ]*' /proc/cmdline"
  grep -oE '[^ ]*ramoops[^ ]*' /proc/cmdline 2>/dev/null
  echo "\$ cat /sys/module/ramoops/parameters/* 2>/dev/null | head -8"
  for _p in /sys/module/ramoops/parameters/*; do [ -e "$_p" ] && echo "  $(basename "$_p") = $(cat "$_p" 2>/dev/null)"; done
  echo "--- 结束 ---"
} >"$OUT" 2>&1

chmod 644 "$OUT" 2>/dev/null
sync
# 关键判断直接回显，App 拿得到：
#   有内容 = 真的读到了 ramoops；只有清单 = 内核没配 pstore 或本来就是第一次开机
if grep -q "Kernel panic\|Unable to handle\|Internal error\|Call trace\|BUG:\|Oops" "$OUT" 2>/dev/null; then
  echo "PSTORE_HAS_CRASH bytes=$(wc -c <"$OUT" 2>/dev/null)"
else
  echo "PSTORE_NO_CRASH bytes=$(wc -c <"$OUT" 2>/dev/null) entries=$(ls -1 /sys/fs/pstore/ 2>/dev/null | wc -l)"
fi
