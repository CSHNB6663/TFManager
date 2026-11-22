#!/system/bin/sh
HOME_DIR='/data/local/tmp'
export PATH="/system/bin:/system/xbin:/vendor/bin:/product/bin:/system_ext/bin:/data/adb/ksu/bin${PATH:+:$PATH}"
LOG='/data/local/tmp/.ghostlock_ksu.log'
KSUD="$HOME_DIR/ksud"
# 【11.10.07】删掉一段死代码：旧版"退出时把 SELinux 拉回 enforcing"的实现
# 及其失败提示。11.00.27 起就是保持宽容，这里只留一个空 trap。
trap ':' EXIT   # 保持宽容：never restore enforcing

# 【11.11.02 用户指定】越狱（handoff）一跑完就把本轮落在 /data/local/tmp 的文件删干净：
#   .ghostlock_* / ksu-lkm*.ko / ksu.raw.ko / kkptr_restrict / ksudpatch / ksud.src.* /
#   mgr.apk / .ow_root_mgr / ez98260-offsets.json / libk80exp.so / exploit-98260(.bin) /
#   ezsu / ksud / ksu_handoff.sh 自己
# 用 EXIT trap 挂在最后：中途任何一条 exit 路径都会走到，不用逐个改。
# 删之前先把交接日志拷到 /sdcard —— 那是排查"管理器为什么未安装"的唯一现场。
handoff_tmp_cleanup() {
  [ "$HAN_CLEANED" = "1" ] && return 0
  HAN_CLEANED=1
  _dst='/sdcard/Download/临时ROOT测试日志'
  if [ -f "$LOG" ]; then
    mkdir -p "$_dst" 2>/dev/null
    cp -f "$LOG" "$_dst/handoff-ksu-$(date +%H%M%S 2>/dev/null).log" 2>/dev/null
  fi
  cd / 2>/dev/null
  rm -f "$HOME_DIR/.ghostlock_pstore.log" "$HOME_DIR/.ghostlock_pstore.sh" \
        "$HOME_DIR/.ghostlock_dmesg.txt" "$HOME_DIR/.ghostlock_iomem" \
        "$HOME_DIR/.ghostlock_ksu.log" "$HOME_DIR/ksu-lkm.ko" \
        "$HOME_DIR/ksu-lkm.patched.ko" "$HOME_DIR/ksu.raw.ko" \
        "$HOME_DIR/kkptr_restrict" "$HOME_DIR/ksudpatch" \
        "$HOME_DIR/mgr.apk" "$HOME_DIR/.ow_root_mgr" \
        "$HOME_DIR/ez98260-offsets.json" "$HOME_DIR/libk80exp.so" \
        "$HOME_DIR/exploit-98260" "$HOME_DIR/exploit-98260.bin" \
        "$HOME_DIR/ezsu" "$HOME_DIR/ksud" "$HOME_DIR/ksu_handoff.sh" \
        "$HOME_DIR"/ksud.src.* 2>/dev/null
}
trap 'handoff_tmp_cleanup' EXIT
PSTORE_OUT="$HOME_DIR/.ghostlock_pstore.log"
dump_pstore() {
  _ts=$(date 2>/dev/null)
  {
    echo "=== ghostlock pstore dump ==="
    echo "时间      $_ts"
    echo "uid       $(id -u)   se=$(cat /proc/self/attr/current 2>/dev/null)"
    ls -la /sys/fs/pstore/ 2>&1
    echo "--- 逐个已知 ramoops 文件 ---"
  } >"$PSTORE_OUT" 2>&1
  _n=0
  for _f in /sys/fs/pstore/console-ramoops-0 /sys/fs/pstore/console-ramoops \
            /sys/fs/pstore/dmesg-ramoops-0 /sys/fs/pstore/dmesg-ramoops \
            /sys/fs/pstore/panic-ramoops /sys/fs/pstore/pmsg-ramoops-0 \
            /sys/fs/pstore/ftrace-ramoops-0 /sys/fs/pstore/console-ramoops-1; do
    if [ -r "$_f" ]; then
      echo "===== $_f  ($(wc -c <"$_f" 2>/dev/null) 字节) =====" >>"$PSTORE_OUT"
      cat "$_f" >>"$PSTORE_OUT" 2>&1
      echo >>"$PSTORE_OUT"
      _n=$((_n + 1))
    else
      echo "----- $_f : 不可读或不存在 -----" >>"$PSTORE_OUT"
    fi
  done
  for _f in /sys/fs/pstore/*; do
    [ -e "$_f" ] || continue
    case "$_f" in
      *console-ramoops*|*dmesg-ramoops*|*panic-ramoops*|*pmsg-ramoops*|*ftrace-ramoops*) continue ;;
    esac
    if [ -r "$_f" ]; then
      echo "===== $_f (目录扫描命中) =====" >>"$PSTORE_OUT"
      cat "$_f" >>"$PSTORE_OUT" 2>&1
      _n=$((_n + 1))
    fi
  done
  echo "--- ramoops 到底注册上没有（root 才看得到）---"
  echo "$ dmesg | grep -iE 'ramoops|pstore'"
  dmesg 2>/dev/null | grep -iE 'ramoops|pstore' | head -20
  echo "$ ls /proc/device-tree/reserved-memory/"
  ls /proc/device-tree/reserved-memory/ 2>&1
  echo "$ cat /proc/device-tree/reserved-memory/ramoops*/reg 2>/dev/null | od -An -tx1 | head -4"
  cat /proc/device-tree/reserved-memory/ramoops*/reg 2>/dev/null | od -An -tx1 | head -4
  echo "$ ls -d /sys/module/*ramoops* /sys/module/*pstore* 2>/dev/null"
  ls -d /sys/module/*ramoops* /sys/module/*pstore* 2>/dev/null
  echo "$ grep -oE '[^ ]*ramoops[^ ]*' /proc/cmdline"
  grep -oE '[^ ]*ramoops[^ ]*' /proc/cmdline 2>/dev/null
  echo "$ cat /sys/module/ramoops/parameters/* 2>/dev/null | head -8"
  for _p in /sys/module/ramoops/parameters/*; do [ -e "$_p" ] && echo "  $(basename "$_p") = $(cat "$_p" 2>/dev/null)"; done
  echo "--- 读到 $_n 个可读的 pstore 条目 ---" >>"$PSTORE_OUT"
  chmod 644 "$PSTORE_OUT" 2>/dev/null
  sync
  echo "[*] pstore dump: $_n 个可读条目 -> $PSTORE_OUT ($(wc -c <"$PSTORE_OUT" 2>/dev/null) 字节)" >>"$LOG"
}
# 【11.10.01】ksud 必须是 arm64 的：11.00.38 的真机日志里 App 从管理器 APK 里
# 抽到了 lib/x86_64/libksud.so，handoff 一执行就是
#   /data/local/tmp/ksud: not executable: 64-bit ELF file
# 于是 late-load 必挂（rc=1 modules=0）。这里按 ELF e_machine（+18，2 字节小端，
# aarch64 = b7 00）过滤，非 arm64 的候选直接跳过，让后面的 arm64 候选顶上。
# 【11.10.08】模块没加载时给一句人话结论：从日志里认真实的 ko 报错，
# 别只说 "KernelSU module not loaded"（用户看不出是哪一步、该换什么）。
diagnose_ko() {
  _why='未知原因（详见日志）'
  _hint=''
  # 【11.10.25】顺序很重要：ksuinit 遇到 vendor vermagic 不同会先打
  # "Kernel requires vermagic ... replacing and retrying"，然后重试；如果重试
  # 因缺 CAP_SYS_MODULE 失败，日志里同时有 "vermagic" 和 "Operation not permitted"。
  # 以前先匹配 vermagic，把"caps 没中"误报成"模块版本不匹配"，测试者就会去换管理器。
  # 现在 EPERM / kallsyms 权限优先，vermagic 只在没有权限问题时才作为结论。
  if grep -qi 'Operation not permitted' "$LOG" 2>/dev/null; then
    _why='加载被内核拒绝：缺 CAP_SYS_MODULE（大概率本轮 caps 没中）或 SELinux 未关'
    _hint='看上面的 caps / enforce 两行；caps=0 是概率问题，重跑一轮即可，别换管理器'
  elif grep -qiE 'Cannot parse kallsyms|Permission denied' "$LOG" 2>/dev/null; then
    _why='kallsyms 读不到（kptr_restrict 不为 0，或 SELinux 不是 permissive）'
    _hint='kptr 应为 0、enforce 应为 0；也是概率/写 sysctl 的结果'
  elif grep -qiE 'no symbol version|overflow in relocation|Exec format error|Unknown symbol' "$LOG" 2>/dev/null; then
    _why='该管理器的 kernelsu.ko 与本机内核不兼容（模块符号版本/重定位不匹配）'
    _hint='换回已验证的管理器（SukiSU），或安装与该内核匹配的 kernelsu.ko'
  elif grep -qi 'vermagic' "$LOG" 2>/dev/null; then
    _why='kernelsu.ko 的 vermagic 与本机内核不一致，且自动改写后仍失败'
    _hint='换用为这颗内核编译的模块'
  fi
  echo "[handoff] 结论：KernelSU 模块没加载 —— $_why" >>"$LOG" 2>/dev/null
  echo "[!] KernelSU 模块没加载：$_why"
  [ -n "$_hint" ] && echo "[!] 建议：$_hint"
}

ksud_machine() {
  [ -f "$1" ] || { echo "none"; return; }
  _m=$(dd if="$1" bs=1 skip=18 count=2 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')
  [ -n "$_m" ] || _m="none"
  echo "$_m"
}
ksud_try() {
  [ -n "$1" ] || return 1
  [ -f "$1" ] || return 1
  [ -r "$1" ] || return 1
  _m=$(ksud_machine "$1")
  if [ "$_m" != "b700" ] && [ "$_m" != "none" ]; then
    echo "[*] 跳过非 arm64 的 ksud：$1（e_machine=$_m）" >>"$LOG"
    return 1
  fi
  chmod 755 "$1" 2>/dev/null
  echo "[*] ksud candidate: $1  abi=${_m}  ($(ls -l "$1" 2>/dev/null))" >>"$LOG"
  KSUD="$1"
  return 0
}
MGR_PKG=""
MGR_APK=""
for _cfg in "${OW_ROOT_MGR:-}" /data/local/tmp/.ow_root_mgr /sdcard/Download/.ow_root_mgr; do
  [ -n "$_cfg" ] || continue
  [ -f "$_cfg" ] || continue
  while IFS= read -r _l || [ -n "$_l" ]; do
    _l=$(printf '%s' "$_l" | tr -d '\r')
    case "$_l" in
      pkg=*) MGR_PKG=${_l#pkg=} ;;
      apk=*) MGR_APK=${_l#apk=} ;;
      ''|'#'*) : ;;
      *) [ -z "$MGR_PKG" ] && MGR_PKG="$_l" ;;
    esac
  done <"$_cfg"
  if [ -n "$MGR_PKG" ] || [ -n "$MGR_APK" ]; then
    echo "[*] 用户指定管理器: pkg=${MGR_PKG:-?} apk=${MGR_APK:-?} (来自 $_cfg)" >>"$LOG"
    break
  fi
done

if [ -z "$KSUD" ] || [ ! -f "$KSUD" ]; then
  if [ -n "$MGR_PKG" ] || [ -n "$MGR_APK" ]; then
    _ap="$MGR_APK"
    [ -n "$_ap" ] || _ap=$(pm path "$MGR_PKG" 2>/dev/null | head -1 | cut -d: -f2)
    [ -n "$_ap" ] || _ap=$(pm path "$MGR_PKG" 2>/dev/null | sed -n 's/^package://p' | head -1)
    if [ -n "$_ap" ] && [ -f "$_ap" ]; then
      _so=$(find "$(dirname "$_ap")" -path '*/lib/*/libksud*.so' 2>/dev/null | head -1)
      if [ -z "$_so" ]; then
        for _abi in arm64-v8a armeabi-v7a x86_64; do
          _o="/data/local/tmp/ksud-$_abi"
          unzip -p "$_ap" "lib/$_abi/libksud.so" >"$_o" 2>/dev/null
          if [ -s "$_o" ]; then _so="$_o"; break; fi
          rm -f "$_o"; _so=""
        done
      fi
      echo "[*] 指定管理器 APK=$_ap 抽出的 ksud=${_so:-<空>}" >>"$LOG"
      [ -n "$_so" ] && ksud_try "$_so"
    else
      echo "[!] 指定管理器 pkg=${MGR_PKG:-?} 找不到 APK（pm path 为空）" >>"$LOG"
    fi
  fi
fi
if [ -z "$KSUD" ] || [ ! -f "$KSUD" ]; then
  KSUD=""
  for _c in "$HOME_DIR/ksud" "$HOME_DIR/ksu/bin/ksud" /data/adb/ksud \
            /data/adb/ksu/bin/ksud /data/adb/modules/*/system/bin/ksud \
            /system/bin/ksud /system/xbin/ksud; do
    ksud_try "$_c" && break
  done
fi
if [ -z "$KSUD" ]; then
  _found=$(find /data/app -type f -name 'libksud*.so' 2>/dev/null | head -5)
  echo "[*] find /data/app -name 'libksud*.so' -> ${_found:-<空>}" >>"$LOG"
  for _c in $_found; do
    ksud_try "$_c" && break
  done
fi
if [ -z "$KSUD" ]; then
  for _pat in '*/me.weishu.kernelsu.pr*/lib/*/libksud.so' \
              '*/me.weishu.kernelsu*/lib/*/libksud.so' \
              '*/com.sukisu.ultra*/lib/*/libksud.so' \
              '*/com.sukisu*/lib/*/libksud.so' \
              '*/com.resukisu*/lib/*/libksud.so' \
              '*/com.kowx712.supermanager*/lib/*/libksud.so' \
              '*/com.rifsxd.ksunext*/lib/*/libksud.so'; do
    _found=$(find /data/app -path "$_pat" 2>/dev/null | head -1)
    [ -n "$_found" ] && ksud_try "$_found" && break
  done
fi
echo "[*] ksud=$KSUD" >>"$LOG"
# 【11.10.50】.ow_root_mgr 可能没写（21:26 日志 pkg=built-in）。补一个确定性的来源：
# ksud 自己知道编译进去的默认管理器包名（FinaSU = me.yuki.fina，SukiSU = com.sukisu.ultra）。
if [ -z "$MGR_PKG" ] && [ -n "$KSUD" ] && [ -x "$KSUD" ]; then
  _dp=$("$KSUD" debug package 2>/dev/null | tail -1 | tr -d '\r')
  case "$_dp" in
    *.*) MGR_PKG="$_dp"; echo "[*] 从 ksud debug package 补出管理器：$MGR_PKG" >>"$LOG" 2>/dev/null ;;
  esac
fi
if [ -z "$KSUD" ] || [ ! -f "$KSUD" ]; then
  {
    echo '[!] ksud 一个候选都没找到 —— 这就是 KSU_RC=3（找不到 ksud）的直接原因'
    echo "时间 $(date 2>/dev/null)  uid=$(id -u)  uname=$(uname -r 2>/dev/null)"
    ls -l "$HOME_DIR/ksud" "$HOME_DIR/ksu/bin/ksud" /data/adb/ksud \
          /data/adb/ksu/bin/ksud /system/bin/ksud 2>&1
    echo '--- 装了哪些 root 管理器（pm list packages）---'
    pm list packages 2>/dev/null | grep -iE 'kernelsu|sukisu|resukisu|supermanager|ksunext|mmrl|sevenk|apatch|magisk' \
      || echo '（没有匹配到任何管理器包名）'
    echo '--- 这些包的 APK 路径 / 数据目录 ---'
    for _p in me.weishu.kernelsu me.weishu.kernelsu.pr com.sukisu.ultra com.sukisu \
              com.resukisu.resukisu com.kowx712.supermanager com.rifsxd.ksunext \
              com.sevenk.core; do
      _ap=$(pm path "$_p" 2>/dev/null | head -1 | cut -d: -f2)
      if [ -n "$_ap" ]; then
        echo "  $_p -> $_ap ($(ls -l "$_ap" 2>/dev/null | awk '{print $1, $5}'))"
        _d=$(dirname "$_ap")
        echo "     ${_d}/lib : $(ls -d "${_d}/lib" 2>/dev/null || echo '（没有 lib 目录 ⇒ so 还在 APK 里）')"
        ls -l "${_d}"/lib/*/libksud*.so 2>/dev/null | head -3
      fi
    done
    echo '--- /data/app 下任何 libksud（不认包名）---'
    find /data/app -type f -name 'libksud*.so' 2>/dev/null | head -10
    echo '--- 结论 ---'
    if pm list packages 2>/dev/null | grep -qiE 'kernelsu|sukisu|resukisu|supermanager|ksunext|sevenk'; then
      echo '装了管理器，但它的 libksud.so 没有以文件形式落盘（多半是 extractNativeLibs=false，'
    else
      echo '这台机器上**没有装任何 KernelSU/SukiSU/ReSukiSU 管理器 APK**。'
    fi
  } >>"$LOG" 2>&1
fi
echo "[*] ksud_file=$(ls -l "$KSUD" 2>/dev/null)" >>"$LOG"
echo "[*] uname=$(uname -r)" >>"$LOG"
LOADPOLICY=""
for _p in /system/bin/load_policy /system/xbin/load_policy /vendor/bin/load_policy; do
  if [ -x "$_p" ]; then LOADPOLICY="$_p"; break; fi
done
if [ -z "$LOADPOLICY" ]; then
  LOADPOLICY=$(command -v load_policy 2>/dev/null)
fi
echo "[*] PATH=$PATH" >>"$LOG"
echo "[*] load_policy=${LOADPOLICY:-<NOT FOUND>}" >>"$LOG"
for _c in load_policy od dd mktemp seq find chmod; do
  if ! command -v "$_c" >/dev/null 2>&1; then
    echo "[!] missing command: $_c" >>"$LOG"
  fi
done
cleanup_note() {
  echo "$1"
  echo "$1" >>"$LOG" 2>/dev/null
}
cleanup_stale_temp() {
  _n=0
  for _f in "$HOME_DIR"/.ghostlock_policy.* "$HOME_DIR"/.ghostlock_iomem.new; do
    [ -e "$_f" ] || continue
    if rm -f -- "$_f" 2>/dev/null; then
      _n=$((_n + 1))
    else
      cleanup_note "[i] cleanup: could not remove ${_f} ($HOME_DIR is sticky, so only its owner or root may unlink it); HARMLESS leftover from an earlier run, it does not affect the result"
    fi
  done
  if [ "$_n" -gt 0 ]; then
    cleanup_note "[*] cleanup: removed ${_n} stale temp file(s) left by an earlier run"
  fi
  return 0
}
cleanup_stale_temp
if [ "$(id -u)" -ne 0 ]; then
  {
    echo "[!] handoff started WITHOUT root: uid=$(id -u) euid=$(id -u -r 2>/dev/null)"
    echo "[!] selinux=$(cat /proc/self/attr/current 2>/dev/null) enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null)"
    echo "[!] uname=$(uname -r 2>/dev/null)"
    ls -l /data/local/tmp/su /system/bin/su /data/adb/ksu/bin/su /data/adb/ksud 2>&1 | sed 's/^/    /'
  } >>"$LOG" 2>/dev/null
  for _su in /data/local/tmp/su /system/bin/su /data/adb/ksu/bin/su; do
    [ -x "$_su" ] || continue
    if "$_su" -c 'id -u' 2>/dev/null | tr -d '\r' | grep -qx 0; then
      echo "[*] re-exec via $_su (was uid=$(id -u))" >>"$LOG" 2>/dev/null
      exec "$_su" -c "/system/bin/sh '$0'"
    fi
  done
  echo '[!] temp su unavailable; aborting' >>"$LOG"
  exit 1
fi

# 【11.00.31】到这里已经确定是 root 了，再挑日志位置：
#   Android 的 /data/local/tmp 是 sticky + 组可写；内核 fs.protected_regular（Android 默认 2）
#   规定：在 sticky 且 group/world 可写的目录里，对"不属于自己"的已存在文件做 O_CREAT
#   打开 ⇒ EACCES。假 root 没有 CAP_FOWNER（caps 来自回收页地址，bit3 永远为 0），
#   所以 App 用 uid2000 预建的 0666 日志它反而**写不了** —— 实测报错正是
#   `can't create /data/local/tmp/.ghostlock_ksu.log: Permission denied`。
#   现在 App 不再预建；这里自己挑一个能写的位置，并把路径打到控制台。
for _ltry in /data/local/tmp/.ghostlock_ksu.log /data/adb/.ghostlock_ksu.log \
             /dev/.ghostlock_ksu.log; do
  if ( : >>"$_ltry" ) 2>/dev/null; then LOG="$_ltry"; break; fi
done
echo "[handoff] uid=$(id -u) se=$(cat /proc/self/attr/current 2>/dev/null) enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null) log=$LOG"
# 【11.11.39】6.6/6.12「越狱成功之后过一会儿卡死/重启」目前**没有一点内核现场**：
#   * pstore 条目数一直是 0（多次 dump 全是空 / 全空格），
#   * dmesg 和 /dev/kmsg 对 shell 域 avc denied（本文件里也有实锤），
#   * 唯一的线索是 timerqueue.c:55 的 WARN（release_task→posix_cpu_timers_exit），
#     那条 WARN 本身不致命（打印完内核继续跑，120521 的现场就是这样）。
# 现在已经是 uid0 + permissive（+ CAP_SYS_ADMIN），所以成功之后挂一个**逐条 fsync** 的
# 内核日志记录器：每读到一条 /dev/kmsg 记录就 conv=fsync 落盘到 ext4
# （/data/local/tmp/kmsg-live.log）。硬复位后文件还在，下一次开机 App 的「崩溃现场」
# 会把它一起抓走（见 KlogDump 第 0d 节）⇒ 下次卡死能看到最后的内核消息。
# 只跑 10 分钟：否则 dd 会跟着手机一直跑，日志无限长（实测卡死都发生在成功后几分钟内）。
start_kmsg_recorder() {
  _KLOG="$HOME_DIR/kmsg-live.log"
  # 同一轮里重跑 handoff 时，先停掉上一轮的记录器（否则两份 dd 抢同一个文件）
  if [ -s "$HOME_DIR/kmsgcap.pid" ]; then
    kill "$(cat "$HOME_DIR/kmsgcap.pid" 2>/dev/null)" 2>/dev/null
  fi
  if [ ! -e /dev/kmsg ]; then
    echo "[i] kmsg 记录器：没有 /dev/kmsg，跳过" >>"$LOG" 2>/dev/null
    return 0
  fi
  if ! : >"$_KLOG" 2>/dev/null; then
    echo "[i] kmsg 记录器：$_KLOG 写不了，跳过" >>"$LOG" 2>/dev/null
    return 0
  fi
  chmod 0666 "$_KLOG" 2>/dev/null
  # 【11.11.54】必须脱离会话：handoff 是引擎用 setsid 拉起来的，引擎一退，
  # 同一会话里的后台子进程会被 SIGHUP 收掉，dd 还没来得及写一条就没了。
  setsid sh -c '
    _end=$(( $(date +%s 2>/dev/null || echo 0) + 600 ))
    while [ "$(date +%s 2>/dev/null || echo 0)" -lt "$_end" ]; do
      dd if=/dev/kmsg bs=4096 count=1 conv=fsync >>"'"$_KLOG"'" 2>/dev/null || sleep 1
    done
  ' >/dev/null 2>&1 &
  echo $! >"$HOME_DIR/kmsgcap.pid" 2>/dev/null
  chmod 0666 "$HOME_DIR/kmsgcap.pid" 2>/dev/null
  echo "[handoff] kmsg 记录器已启动 pid=$! → $_KLOG（10 分钟，逐条 fsync，硬复位后仍在）"
  # 立刻验一次：文件在不在、长没长。只打日志，不改行为。
  sleep 1
  echo "[handoff] kmsg 自检: $(ls -l "$_KLOG" 2>&1 | tr -s ' ') 行数=$(wc -l <"$_KLOG" 2>/dev/null)"
}
start_kmsg_recorder
echo "[*] root script start uid=$(id -u) euid=$(id -u)" >"$LOG"
chmod 644 "$LOG" 2>/dev/null
echo "[*] seccomp=$(grep Seccomp /proc/self/status 2>/dev/null | tr '\n' ' ')" >>"$LOG"
# 【11.00.31】pstore 只在真有崩溃记录时才抓（以前每轮都抓，白等好几秒）
if ls /sys/fs/pstore/* >/dev/null 2>&1; then
  dump_pstore
else
  echo "[*] pstore empty, skip dump" >>"$LOG"
fi
echo "# $(uname -r)" >"$HOME_DIR/.ghostlock_iomem.new"
if cat /proc/iomem >>"$HOME_DIR/.ghostlock_iomem.new" 2>/dev/null && grep -q 'System RAM' "$HOME_DIR/.ghostlock_iomem.new"; then
  mv "$HOME_DIR/.ghostlock_iomem.new" "$HOME_DIR/.ghostlock_iomem"
  chmod 644 "$HOME_DIR/.ghostlock_iomem" 2>/dev/null
  echo "[*] iomem cache: cached $(wc -c <"$HOME_DIR/.ghostlock_iomem") bytes" >>"$LOG"
else
  rm -f "$HOME_DIR/.ghostlock_iomem.new"
  echo '[!] iomem cache: /proc/iomem read failed' >>"$LOG"
fi
KVER=$(uname -r | cut -d. -f1-2)
AVER=$(uname -r | grep -o 'android[0-9]*' | head -1)
if [ -z "$AVER" ] || [ -z "$KVER" ]; then
  echo '[!] cannot parse KMI from uname -r' >>"$LOG"
  exit 1
fi
KMI="${AVER}-${KVER}"
LATE_LOAD_ARGS=""
LATE_LOAD_PROBED=0

# 【11.10.51】先用管理器自己的 ksud 把内嵌的 KO 解出来，由**我们**加载。
# 这样不依赖 late-load 自己的 loader/时序；加载成功后 late-load 看到
# has_kernelsu() 会跳过加载，只做 utils::install + 重启管理器。
# 解出来的 KO 就是管理器同版本、同签名的那个，不会出现“选错 ko”问题。
# 【11.10.57r】主引擎走 JNI 库时，原生二进制留成了 exploit-98260.bin；
# handoff 里的 patchko/insmod 用原生那份，少起两次 JVM。
EZ98260_BIN="/data/local/tmp/exploit-98260"
[ -x /data/local/tmp/exploit-98260.bin ] && EZ98260_BIN="/data/local/tmp/exploit-98260.bin"

# 【11.11.01】把 ksud 以 fsuid/fsgid=1000(system) 跑。
# 内核侧 ksu_cred = init_module 里 prepare_creds() 拷的**加载者** cred（含 fsuid）：
#   · fsuid=0 时 filp_open("/data/adb/ksud")、("/data/system/packages.list") 都 -EACCES
#     （两个父目录 system:system 0770，uid 0 不是属主，CAP_DAC_OVERRIDE 那个 bit 1
#      用 slab 地址凑不出来）⇒ sucompat 失效、管理器永远「未安装」；
#   · fsuid=1000 命中属主 ⇒ owner 位直接过。
# 引擎自己的 insmod(finit_once) 一直这么切；preload 失败改走 ksud late-load 时，
# 这条兜底路以前是 fsuid=0，于是 6.12/6.6 上「root 成功但越狱不显示」。
run_as_system() {
  if [ -x "$EZ98260_BIN" ]; then
    "$EZ98260_BIN" setfsid 1000 1000 "$@"
  else
    "$@"
  fi
}

# 【11.11.04】以 system(1000) 身份跑一段 sh —— 用来体检 /data/adb 的归属/可写性，
# 因为 ksud 的 install() 就是用这套身份（uid0 + fsuid1000）去写 /data/adb 的。
as_system_sh() {
  run_as_system /system/bin/sh -c "$1"
}

# 【11.10.58】管理器加冕（crowning）必须在**permissive 且 KO 已加载**的时候做：
# pm install 会重写 /data/system/packages.list，内核的 fsnotify 才会跑 track_throne；
# 等 ksud late-load 把 SELinux 拉回 enforcing 之后再做，扫描/加冕经常失败，
# 管理器就永远显示“未安装”（很多测试者反馈的现象）。
MGR_CROWNED=0
trigger_manager_crowning() {
  _rounds=${1:-1}
  [ -f /data/local/tmp/mgr.apk ] && [ -n "$MGR_PKG" ] || return 0

  # 【11.11.07】加冕（crowning）改成**多轮**：
  # KernelSU 认领管理器靠内核线程 track_throne —— 它盯 /data/system/packages.list，
  # 管理器被（重）安装/重启的那一刻才可能被扫到。一轮没扫到，管理器就一直显示
  # 「未安装」，而且它自己不会重试。测试者的现象（同一台机器，"原版 ksu 能、SukiSU 要
  # 50% 电量以下才成"）就是命中窗口的竞态表现 —— 系统越慢反而越容易落进窗口。
  # 所以这里不再赌一轮：循环 _rounds 次「覆盖安装（重写 packages.list）+ 重启管理器」，
  # 每轮都把现场写进日志，好判断到底是"没加载"还是"没被认领"。
  echo "[handoff] replace manager: $MGR_PKG (rounds<=$_rounds)" >>"$LOG" 2>/dev/null
  _r=1
  while [ "$_r" -le "$_rounds" ]; do
    pm install -r -d /data/local/tmp/mgr.apk >>"$LOG" 2>&1
    _mrc=$?
    if [ "$_mrc" -ne 0 ]; then
      echo "[handoff] crown $_r: install rc=$_mrc, fallback uninstall+install" >>"$LOG" 2>/dev/null
      pm uninstall "$MGR_PKG" >>"$LOG" 2>&1
      pm install -d /data/local/tmp/mgr.apk >>"$LOG" 2>&1
      _mrc=$?
    fi
    # 杀掉再拉起：管理器只有在**新进程**启动时才向内核要 ksu fd。
    am force-stop "$MGR_PKG" >/dev/null 2>&1
    sleep 1
    # monkey 会往 stdout 刷 "bash arg: …" 四五行，别写进日志占地方
    monkey -p "$MGR_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    _mpid=$(pidof "$MGR_PKG" 2>/dev/null | tr ' ' ',')
    # 【11.11.11】这几个探针以前是错的：/data/adb/ksu 不存在 **不代表**越狱失败
    # （SukiSU 的 LKM late-load 未必建这个目录，19:04 zorn 那次就是 ksu_dir=no 但
    #  末尾 `KernelSU 已加载` + handoff rc=0 + root shell 全通）。改成看真实凭据：
    #   ksud 二进制、modules 目录、管理器包是否还装着、以及最后用 su 实测。
    _ksud_bin=no
    [ -f /data/adb/ksud ] || [ -f /data/adb/ksu/bin/ksud ] && _ksud_bin=yes
    _moddir=0
    [ -d /data/adb/modules ] && _moddir=$(ls /data/adb/modules 2>/dev/null | wc -l)
    _minstalled=no
    [ -n "$(pm path "$MGR_PKG" 2>/dev/null)" ] && _minstalled=yes
    _kmods=$(grep -c kernelsu /proc/modules 2>/dev/null)
    _menf=$(cat /sys/fs/selinux/enforce 2>/dev/null)
    echo "[handoff] crown round $_r/$_rounds: install_rc=$_mrc pid=${_mpid:-none} installed=$_minstalled ksud=$_ksud_bin moddirs=$_moddir kernelsu=$_kmods enforce=$_menf" >>"$LOG" 2>/dev/null
    echo "[handoff] crown round $_r/$_rounds: manager_pid=${_mpid:-none} installed=$_minstalled ksud=$_ksud_bin moddirs=$_moddir kernelsu=$_kmods enforce=$_menf"
    # 【11.11.14 用户反馈】6.1 上一轮就成，再跑两轮纯属白重装（管理器还被反复杀掉）。
    # 只要这一轮管理器进程起来了、包也还在，就收手；起不来才继续下一轮。
    # 判据用"包装着 + 内核模块在"：管理器进程没起来（monkey 在 root 身份下经常
    # 拉不起 App）不代表失败 —— 用户手动点开就行，别为了 pid 再重装两遍。
    if [ "$_minstalled" = "yes" ] && [ "$_kmods" != "0" ]; then
      echo "[handoff] crown: 第 $_r 轮就绪（installed=yes, kernelsu=$_kmods），停止重试"
      break
    fi
    _r=$((_r + 1))
    [ "$_r" -le "$_rounds" ] && sleep 2
  done
  MGR_CROWNED=1
  # 【11.11.11】最后用 su 实测一次，这才是"越狱成功"的硬判据（管理器 UI 看不出来）。
  if [ ! -x /system/bin/su ]; then
    echo "[handoff] su check: skip（没有 /system/bin/su；KernelSU 是内核侧拦截 execve，不靠这个文件）"
    MGR_CROWNED=1
    return 0
  fi
  _su_out=$(timeout 6 /system/bin/su -c id 2>&1 | head -2 | tr '\n' ' ')
  if echo "$_su_out" | grep -q "uid=0"; then
    echo "[handoff] su check: OK（$_su_out）" >>"$LOG" 2>/dev/null
    echo "[handoff] su check: OK"
  else
    echo "[handoff] su check: FAIL（${_su_out:-无输出}）" >>"$LOG" 2>/dev/null
    echo "[handoff] su check: FAIL（${_su_out:-无输出}）"
  fi
}

preload_ko_from_ksud() {
  [ -x "$EZ98260_BIN" ] || return 1
  # 【11.11.05 用户指定】**不再内置任何 kernelsu KO**：KO 只有一个来源 ——
  # 选中的管理器自己的 ksud（`ksud debug extract-binary ${KMI}_kernelsu.ko`）。
  # 内置那 12 份在不同的内核构建上会死在
  #   "module kernelsu: .gnu.linkonce.this_module section size must match
  #    the kernel's built struct module size at run time"
  # （byron 2026-11-01 13:15 的 dmesg 实锤），留着只会误导 + 白占 7.5 MB。
  #
  # 为什么还要"我们自己 insmod"而不是直接交给 ksud late-load：
  # KernelSU 的 ksu_cred 是 init_module 里 prepare_creds() 拷下来的**加载者** cred
  # （含 fsuid/fsgid）。引擎的 finit_once() 会把 fsuid/fsgid 切成 1000(system)，
  # 这样 ksu_cred 才读得动 /data/system/packages.list（system:system 0770）、
  # 写得进 /data/adb。ksud late-load 那条路也切了（见 run_as_system），
  # 能自己 insmod 只是少起一个进程、少一次版本猜测。
  if [ "$(grep -c kernelsu /proc/modules 2>/dev/null)" != "0" ]; then
    # 已经在内核：ksud late-load 会 delete+reload（它自己看 has_kernelsu），
    # 这里不再多此一举 —— 而且现在也没有内置 KO 能拿来重载。
    echo "[*] preload: kernelsu 已在内核（交给 late-load 刷新 ksu_cred）" >>"$LOG" 2>/dev/null
    return 0
  fi
  _ko=/data/local/tmp/ksu-lkm.ko
  _raw=/data/local/tmp/ksu.raw.ko
  rm -f "$_raw" "$_ko" 2>/dev/null
  [ -n "$KSUD" ] && [ -x "$KSUD" ] || {
    echo "[!] preload: 没有 ksud，解不出 KO（直接走 late-load）" >>"$LOG" 2>/dev/null
    return 1
  }
  echo "[*] preload: $KSUD debug extract-binary ${KMI}_kernelsu.ko" >>"$LOG" 2>/dev/null
  run_as_system "$KSUD" debug extract-binary "${KMI}_kernelsu.ko" "$_raw" >>"$LOG" 2>&1
  [ -s "$_raw" ] || {
    echo "[!] preload: ksud 没解出 KO（${KMI}_kernelsu.ko），直接走 late-load" >>"$LOG" 2>/dev/null
    return 1
  }
  echo "[*] preload: raw=$(ls -l "$_raw" 2>/dev/null | tr -s ' ')" >>"$LOG" 2>/dev/null
  if "$EZ98260_BIN" patchko "$_raw" "$_ko" >>"$LOG" 2>&1 && [ -s "$_ko" ]; then
    echo "[*] preload: patchko OK -> $_ko" >>"$LOG" 2>/dev/null
  else
    echo "[!] preload: patchko 失败，直接用 raw（insmod 会试 ignore-vermagic）" >>"$LOG" 2>/dev/null
    cp -f "$_raw" "$_ko" 2>/dev/null
  fi
  [ -s "$_ko" ] || return 1
  "$EZ98260_BIN" insmod "$_ko" allow_shell=1 >>"$LOG" 2>&1
  _i=0
  while [ "$_i" -lt 50 ]; do
    grep -q kernelsu /proc/modules 2>/dev/null && {
      echo "[+] preload: kernelsu 已由我们加载（$_ko）" >>"$LOG" 2>/dev/null
      return 0
    }
    sleep 0.1
    _i=$((_i+1))
  done
  echo "[!] preload: insmod 后 5s 内 /proc/modules 仍无 kernelsu（交给 late-load）" >>"$LOG" 2>/dev/null
  return 1
}

run_late_load() {
  # 【11.10.56i】关键一步：先用**引擎自己的 insmod** 加载内置 KO。
  # sukisu KO 的 init_module 第一条就是 ksu_cred = prepare_creds()，拷的是
  # 调用 finit_module 的进程的 creds；track_throne 又用 ksu_cred 去
  # filp_open("/data/system/packages.list")（system 属主、0600）。
  # fsuid=0 时 open 永远 -13，管理器永远不被 crown（dmesg 实锤，SELinux
  # permissive 也一样）。引擎在 finit_once() 里把 fsuid/fsgid 切成 1000，
  # 命中文件属主，owner 读权限就能过；DAC_OVERRIDE 那个 bit 1 用 slab
  # 地址根本凑不出来。加载失败就退回 ksud late-load，不会更差。
  preload_ko_from_ksud
  if grep -q kernelsu /proc/modules 2>/dev/null; then
    echo "[handoff] KO 已加载，趁 permissive 先触发管理器加冕" >>"$LOG" 2>/dev/null
    trigger_manager_crowning
  fi
  if [ "$LATE_LOAD_PROBED" -eq 0 ]; then
    LATE_LOAD_PROBED=1
    chmod 755 "$KSUD" 2>/dev/null
    if "$KSUD" late-load --help 2>&1 | grep -q -- '--allow-shell'; then
      LATE_LOAD_ARGS="--allow-shell"
      echo "[*] ksud late-load 支持 --allow-shell" >>"$LOG" 2>/dev/null
    else
      echo "[*] ksud late-load 不支持 --allow-shell；按裸 --kmi 调用" >>"$LOG" 2>/dev/null
    fi
    # 【11.10.49】把用户选中的管理器包名传给 late-load。
    # 【11.10.46/47/48 的错误】以前传假包名 com.none.ow，本意是"别重启管理器、
    # 保住引擎清好的 seccomp"；结果 late-load 最后一步 am force-stop/start 只对
    # 假包名做，真正的管理器从未重启：它是在驱动加载前启动的，手里没有新内核给的
    # ksu fd，首页一直显示"未安装"，点安装又走不通（20:25 甚至整机重启）。
    # 正确做法就是让 late-load 把选中的管理器重启一次：新进程 exec 时内核按
    # 默认管理器签名发 ksu fd，首页立刻变成已安装，也才能管理授权/模块。
    # 管理器自己的 seccomp 不需要动（11.10.43/47 清 seccomp 是错的方向，已移除）。
    PKG_ARG=""
    if "$KSUD" late-load --help 2>&1 | grep -q -- '--package-name'; then
      if [ -n "$MGR_PKG" ]; then
        PKG_ARG="--package-name $MGR_PKG"
        echo "[*] late-load 将重启选中的管理器：$MGR_PKG（让它拿到新的 ksu fd）" >>"$LOG" 2>/dev/null
      else
        echo "[*] 未读到管理器包名，late-load 用 ksud 内置默认包名重启" >>"$LOG" 2>/dev/null
      fi
    fi
  fi
  # 回退：管理器自带 late-load（只在 insmod 失败时走）
  _o=$(run_as_system "$KSUD" late-load --kmi "$KMI" $LATE_LOAD_ARGS $PKG_ARG 2>&1); _lr=$?
  printf '%s\n' "$_o" >>"$LOG" 2>/dev/null
  printf '%s\n' "$_o"
  if [ "$_lr" -ne 0 ] && [ -n "$LATE_LOAD_ARGS" ]; then
    echo "[*] retry without --allow-shell (exit was $_lr)" >>"$LOG" 2>/dev/null
    echo "[*] retry without --allow-shell (exit was $_lr)"
    _o=$(run_as_system "$KSUD" late-load --kmi "$KMI" $PKG_ARG 2>&1); _lr=$?
    printf '%s\n' "$_o" >>"$LOG" 2>/dev/null
    printf '%s\n' "$_o"
  fi
  # 【11.11.01】再退一步：system 身份写 /data/adb 里的 root 属主文件可能被拒
  # （老装机残留）。仍没加载上就按原来的 fsuid=0 再跑一次，宁可回到"未安装"也别彻底不加载。
  if [ "$(grep -c kernelsu /proc/modules 2>/dev/null)" = "0" ] && [ -x "$EZ98260_BIN" ]; then
    echo "[*] late-load 退一步：fsuid 保持 0 重试（system 身份被拒时用）" >>"$LOG" 2>/dev/null
    _o=$("$KSUD" late-load --kmi "$KMI" $LATE_LOAD_ARGS $PKG_ARG 2>&1); _lr=$?
    printf '%s\n' "$_o" >>"$LOG" 2>/dev/null
    printf '%s\n' "$_o"
  fi
  # 【11.00.36】ksud 会 daemonize，真正的错误只进 logcat；这里抓一段尾巴写进日志。
  if [ "$(grep -c kernelsu /proc/modules 2>/dev/null)" = "0" ]; then
    echo "--- logcat (KernelSU/ksud) ---" >>"$LOG" 2>/dev/null
    logcat -d -s KernelSU ksud 2>/dev/null | tail -25 >>"$LOG" 2>/dev/null
    logcat -d -s KernelSU ksud 2>/dev/null | tail -12
  fi
  echo "[handoff] ksud late-load rc=$_lr  modules=$(grep -c kernelsu /proc/modules 2>/dev/null)"
  return $_lr
}

if [ "$GHOSTLOCK_DISABLE_MODULES" = "1" ]; then
  echo "[*] safe mode: disabling all modules under /data/adb/modules" >>"$LOG"
  n=0
  for m in /data/adb/modules/*/; do
    [ -d "$m" ] || continue
    if touch "${m}disable" 2>/dev/null; then
      n=$((n+1))
      echo "  disabled ${m}" >>"$LOG"
    fi
  done
  echo "[*] safe mode: $n module(s) disabled" >>"$LOG"
fi
# 【11.10.35】FinaSU/SukiSU 换管理器后常见：/data/adb/ksu/bin 不是普通目录，
# ksud install 报 "is not a regular directory" → 管理器显示未安装。
# 【11.10.49】不再在 shell 域里修 /data/adb；交给 late-load 的 ksu 域安装。
# 【11.10.49】删掉 fix_ksu_paths / “修复 /data/adb”：
# handoff 在 u:r:shell:s0 里，SELinux 下 ls/stat /data/adb 会 EACCES，旧逻辑会把
# “读不到”误判成“不是目录”，在 fake 满 caps 轮次里甚至可能真的把正常的
# /data/adb 搬走，直接毁掉 KSU。late-load 的 utils::install 在 u:r:ksu:s0 里
# 自己会建 /data/adb/ksud；管理器重启后也会自己处理，这里不再碰 /data/adb。
if pidof ksud >/dev/null 2>&1; then
  echo "[*] 停掉旧 ksud 守护进程 pid=$(pidof ksud 2>/dev/null)" >>"$LOG"
  kill $(pidof ksud 2>/dev/null) 2>/dev/null
  sleep 0.5
fi

POLICY=$(mktemp "$HOME_DIR/.ghostlock_policy.XXXXXX" 2>/dev/null || true)
[ -n "$POLICY" ] || POLICY=$(mktemp "/data/local/tmp/.ghostlock_policy.XXXXXX" 2>/dev/null || true)
if [ -z "$POLICY" ]; then
  POLICY="/data/local/tmp/.ghostlock_policy.$$"
  : >"$POLICY" 2>/dev/null || POLICY=""
fi
if [ -z "$POLICY" ]; then
  echo "[!] cannot create policy dump (HOME_DIR=$HOME_DIR, uid=$(id -u))" >>"$LOG"
  exit 1
fi
echo "[*] policy dump: $POLICY" >>"$LOG"
cleanup_policy_dump() {
  [ -n "$POLICY" ] || return 0
  if ! rm -f -- "$POLICY" 2>/dev/null; then
    cleanup_note "[i] cleanup: could not remove ${POLICY} ($HOME_DIR is sticky: only its owner or root may unlink it); HARMLESS leftover, it does NOT affect the handoff result and the next run removes it"
  fi
  return 0
}
trap 'cleanup_policy_dump' EXIT   # 【11.00.27】保持宽容
prepare_policy() {
  cat /sys/fs/selinux/policy >"$POLICY" || return 1
  HEADER=$(od -An -tx1 -N24 "$POLICY" | tr -d ' \n')
  case "$HEADER" in
    8cff7cf9080000005345204c696e7578????????????????) ;;
    *) echo '[!] invalid policy header'; return 1 ;;
  esac
  CONFIG=$(od -An -tu1 -j23 -N1 "$POLICY") || return 1
  [ -n "$CONFIG" ] || return 1
  CONFIG=$(printf '\\0%03o' "$((CONFIG | 192))") || return 1
  printf '%b' "$CONFIG" | dd of="$POLICY" bs=1 seek=23 count=1 conv=notrunc
}
# 【11.00.28】kptr_restrict=0 还不够：内核的 kallsyms_show_value() 在
# kptr_restrict==0 时还要求 perf_event_paranoid<=1（kallsyms_for_perf()），
# 否则要求 CAP_SYSLOG —— 假 root 没有 CAP_SYSLOG，ksud 就会
# "Cannot parse kallsyms: Operation not permitted"，模块根本加载不了。
for _s in /proc/sys/kernel/perf_event_paranoid \
          /proc/sys/kernel/panic_on_oops \
          /proc/sys/kernel/panic_on_rcu_stall \
          /proc/sys/kernel/panic_on_warn \
          /proc/sys/kernel/softlockup_panic \
          /proc/sys/kernel/hung_task_panic \
          /sys/module/kfence/parameters/sample_interval \
          /sys/module/kfence/parameters/burst \
          /proc/sys/kernel/panic; do
  [ -e "$_s" ] || continue
  if echo 0 >"$_s" 2>/dev/null; then
    echo "[*] relaxed $_s = $(cat "$_s" 2>/dev/null)" >>"$LOG"
  else
    echo "[i] could not write $_s (只读或不存在)" >>"$LOG"
  fi
done

# 【11.00.30】ksud 的 kptr_restrict 必须改道，理由（内核侧，已逐条验证）：
#   1) SukiSU 的 ksuinit::Kptr::new() 每次 late-load 都会
#        read  /proc/sys/kernel/kptr_restrict
#        write /proc/sys/kernel/kptr_restrict "1"
#   2) 本机内核对 kptr_restrict 的写处理是 proc_dointvec_minmax_sysadmin()：
#        if (write && !capable(CAP_SYS_ADMIN)) return -EPERM;
#      假 root（uid0、CapEff 只靠回收页地址的位碰运气）几乎必然没有 bit21，
#      于是 ksud 直接死在 "Cannot parse kallsyms: Operation not permitted"。
#   3) CAP_SYSLOG(34) 也拿不到：caps 掩码来自内核指针，高 32 位恒为 0xffffffc0，
#      第 34 位永远是 0 —— 所以"让 kptr_restrict=1 后再读"这条路走不通。
# 办法：App 侧（assets/ksudpatch）把二进制里那条 30 字节路径换成
#       /data/local/tmp/kkptr_restrict（等长），真内核的 kptr_restrict 保持 0；
#       配合上面 relaxed 的 perf_event_paranoid=0，kallsyms_show_value() 走
#       kallsyms_for_perf() 分支，/proc/kallsyms 直接给真实地址。
# 【11.10.49】删掉 11.10.44 的“先救 /data/adb”：
# handoff 在 u:r:shell:s0 里，SELinux 会把 /data/adb 的 stat 拒掉（EACCES），
# 旧逻辑会把它误判成“被写坏”，随后 mv/mkdir；fake 满 caps 轮次里可能真的
# 把正常目录搬走。late-load 在 u:r:ksu:s0 里自己会 ensure_dir_exists 并安装。

KPTR_FAKE="$HOME_DIR/kkptr_restrict"
printf '0\n' >"$KPTR_FAKE" 2>/dev/null
chmod 0666 "$KPTR_FAKE" 2>/dev/null
echo "[*] fake kptr: $(ls -l "$KPTR_FAKE" 2>/dev/null) 内容=$(cat "$KPTR_FAKE" 2>/dev/null)" >>"$LOG"
case "$KSUD" in
  "$HOME_DIR/ksud") echo "[*] ksud 用 App 预置的补丁版：$KSUD" >>"$LOG" ;;
  *)
    if [ -n "$KSUD" ] && [ -f "$KSUD" ] && [ -x "$HOME_DIR/ksudpatch" ]; then
      if cp -f "$KSUD" "$HOME_DIR/ksud.src" 2>/dev/null &&
         "$HOME_DIR/ksudpatch" "$HOME_DIR/ksud.src" "$HOME_DIR/ksud.new" >>"$LOG" 2>&1; then
        if mv -f "$HOME_DIR/ksud.new" "$HOME_DIR/ksud" 2>/dev/null; then
          chmod 755 "$HOME_DIR/ksud" 2>/dev/null
          KSUD="$HOME_DIR/ksud"
          echo "[*] handoff 现场改道 ksud -> $KSUD" >>"$LOG"
        fi
      else
        echo "[i] handoff 现场改道失败（ksudpatch 没命中或写不了），继续用原 ksud" >>"$LOG"
      fi
    fi
    ;;
esac

# 【11.10.07】控制台只留 4 行结论（uid/SE、caps、kallsyms、ksud 结果），
# 其它现场（目录/ls/perf/patch/modules）全部写日志文件，用户不用看。
{ echo "[handoff] dir=$(ls -ld /data/local/tmp 2>/dev/null | tr -s ' ')"
  echo "[handoff] log=$LOG $(ls -l "$LOG" 2>/dev/null | tr -s ' ')"
  echo "[handoff] perf=$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null) kptr=$(cat /proc/sys/kernel/kptr_restrict 2>/dev/null) dmesg=$(cat /proc/sys/kernel/dmesg_restrict 2>/dev/null)"
  echo "[handoff] ksud: $(ls -l "$KSUD" 2>/dev/null | tr -s ' ')"
  echo "[handoff] patch: $(ls -l /data/local/tmp/ksudpatch /data/local/tmp/kkptr_restrict 2>/dev/null | tr -s ' ' | tr '\n' ' ')"
  echo "[handoff] modules(before)=$(grep kernelsu /proc/modules 2>/dev/null || echo none)"
} >>"$LOG" 2>/dev/null
echo "[handoff] uid=$(id -u) se=$(cat /proc/self/attr/current 2>/dev/null) enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null)"
echo "[handoff] caps: $(grep -E '^Cap(Eff|Bnd)' /proc/self/status 2>/dev/null | tr '\n' ' ')"
echo "[handoff] kallsyms[1]=$(head -1 /proc/kallsyms 2>&1)"

# 【11.10.57f】sucompat 修复要趁 SELinux 还 permissive 的时候做：
# 内核 execve("/system/bin/su") 会 override_creds(ksu_cred) 后 filp_open("/data/adb/ksud")，
# 而 /data/adb 是 0700 root；fsuid=1000 进不去。chmod 需要 shell 域对 adb_data_file 有权限，
# 只有 permissive 下才做得到（09:55 那轮 fixup 后 enforce 被拉回 1，chmod 全被拒）。
fix_sucompat_early() {
  # 【11.11.15 用户提问"uid0 不比 1000 权限大吗"】正解：uid 0 不等于万能。
  # DAC 检查是「属主 → 属组 → 其他」三段：/data/adb 是 system:system 0770，
  # 我们 uid=0 既不是属主也不在 system 组，走的是"其他"位（0）⇒ 读写都被拒。
  # root 想越过这一关靠的是 CAP_DAC_OVERRIDE(bit1)/CAP_DAC_READ_SEARCH(bit2)，
  # 而这两个 bit 在我们能写的指针值里**永远是 0**（8 字节对齐）。
  # 但 chmod 只要 CAP_FOWNER(bit3) —— 这个 bit 指针值里有！
  # 所以别去"写"它，直接把自己能改的权限位放开：/data/adb 0777、/data/system 0771。
  # 放完 ksud（不管 fsuid 是 0 还是 1000）都能读 /data/adb/ksud 和 packages.list。
  chmod 0777 /data/adb 2>/dev/null
  chmod 0771 /data/system 2>/dev/null
  chmod -R a+rX /data/adb 2>/dev/null
  chmod 0777 /data/adb 2>/dev/null
  echo "[handoff] chmod: adb=$(ls -ld /data/adb 2>/dev/null | tr -s ' ') system=$(ls -ld /data/system 2>/dev/null | tr -s ' ')" >>"$LOG" 2>/dev/null
  echo "[handoff] chmod: adb=$(ls -ld /data/adb 2>/dev/null | awk '{print $1,$3,$4}') system=$(ls -ld /data/system 2>/dev/null | awk '{print $1,$3,$4}')"
  # 【11.11.27】/sys/kernel/btf/vmlinux 是 0400 root:root —— shell 读不了，但**现在已经是 root**
  # 且 SELinux 宽容。把它抓一份到 /sdcard，App 下一轮就能用 Btf.java 解出本机精确的
  # task_struct.cred / cred.* 偏移（不再依赖我写死的 JSON 值；fake cred 上一轮
  # 在 6.1 上写不中，最可能就是 off_task_cred 或 ps_probe 给的 task 指针不对）。
  _btf="/sdcard/Download/临时ROOT测试日志/vmlinux-btf-$(uname -r 2>/dev/null).btf"
  # 【11.11.55】默认不再抓 BTF：root 没有 CAP_DAC_OVERRIDE，往 /sdcard 写基本必然
  # "btf dump failed"（每轮白等 + 白报错）；而且现在新机型的偏移表直接从 boot.img 解，
  # BTF 这条只在"未知机型想临时抓一份"时才有用 ⇒ 用 OW_BTF_DUMP=1 手动开。
  if [ "${OW_BTF_DUMP:-0}" = "1" ] && [ -r /sys/kernel/btf/vmlinux ]; then
    if cat /sys/kernel/btf/vmlinux >"$_btf" 2>/dev/null && [ -s "$_btf" ]; then
      echo "[handoff] btf saved: $(wc -c <"$_btf" 2>/dev/null) B -> $_btf" >>"$LOG" 2>/dev/null
      echo "[handoff] btf saved: $(wc -c <"$_btf" 2>/dev/null) B -> $_btf"
    else
      echo "[handoff] btf dump failed" >>"$LOG" 2>/dev/null
      echo "[handoff] btf dump failed"
    fi
  else
    echo "[handoff] btf not readable" >>"$LOG" 2>/dev/null
    echo "[handoff] btf not readable"
  fi
  # 再用 system 身份补一次（fsuid 能切成功的机器上多一层保险）
  as_system_sh '[ -f /data/adb/ksud ] || cp -f /data/local/tmp/ksud /data/adb/ksud 2>/dev/null; chmod 0755 /data/adb/ksud 2>/dev/null' >/dev/null 2>&1
  [ -f /data/adb/ksud ] || cp -f /data/local/tmp/ksud /data/adb/ksud 2>/dev/null
  chmod 0755 /data/adb/ksud 2>/dev/null
  chmod 0711 /data/adb 2>/dev/null
  # 【11.10.57n】模块/管理器读 /data/adb 时走的是 ksu_cred（fsuid=1000）：
  # 目录 0711 只能"穿过"，模块子目录还是 0700 root，模块列表/挂载会失败。
  # 趁 permissive 递归补 a+rX（SELinux 标签仍然挡着普通 App）。
  chmod -R a+rX /data/adb 2>/dev/null
  chmod 0755 /data/adb/ksud 2>/dev/null
  echo "[handoff] early sucompat: $(ls -ld /data/adb /data/adb/ksud 2>&1 | tr '\n' ' ')" >>"$LOG" 2>/dev/null
  echo "[handoff] /data/adb perms: $(find /data/adb -maxdepth 1 -mindepth 1 2>/dev/null | head -8 | tr '\n' ' ')" >>"$LOG" 2>/dev/null
}
[ "$(cat /sys/fs/selinux/enforce 2>/dev/null)" = "0" ] && fix_sucompat_early
# 【11.10.57u】permissive 时先把内核日志落一份到 /sdcard：
# 6.12 上 late-load 之后 enforce 会被拉回 1，结尾再抓 dmesg 就是 Permission denied。
dump_dmesg_early() {
  _d=/sdcard/Download/临时ROOT测试日志
  mkdir -p "$_d" 2>/dev/null
  if dmesg 2>/dev/null >"$_d/dmesg-early-$(date +%H%M%S).txt"; then
    echo "[handoff] early dmesg saved: $(wc -l <"$_d/dmesg-early-$(date +%H%M%S).txt" 2>/dev/null) lines" >>"$LOG" 2>/dev/null
  fi
}
[ "$(cat /sys/fs/selinux/enforce 2>/dev/null)" = "0" ] && dump_dmesg_early

# 【11.00.41】kptr_restrict 兜底：载荷那次零写是"plant 抽奖"，没中就会留 2，
# 于是 /proc/kallsyms 全 0 → ksud 把模块里的未定义符号补成 0 → init_module EPERM。
# 这里以 root（caps 里通常带 CAP_SYS_ADMIN）再写一次 0，并留证。
# 【11.11.36】kallsyms_show_value() 两条路：kptr_restrict==0 **且** perf_event_paranoid<=1，
# 或者进程自己有 CAP_SYSLOG。后者拿不到（线性映射地址 bit34 恒 0），所以两条 sysctl 都要清：
# 这两个 sysctl 的写处理都是 proc_dointvec_minmax_sysadmin ⇒ 需要 CAP_SYS_ADMIN，
# 引擎 11.11.36 起把 caps 挑成"带 SYS_ADMIN"的那一发。
PEP_NOW=$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null)
if [ -n "$PEP_NOW" ] && [ "$PEP_NOW" -gt 1 ] 2>/dev/null; then
  echo 1 >/proc/sys/kernel/perf_event_paranoid 2>/dev/null
  echo "[handoff] perf_event_paranoid：${PEP_NOW} -> $(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null)"
fi
KPTR_NOW=$(cat /proc/sys/kernel/kptr_restrict 2>/dev/null)
if [ "$KPTR_NOW" != "0" ] && [ -w /proc/sys/kernel/kptr_restrict ]; then
  echo 0 >/proc/sys/kernel/kptr_restrict 2>/dev/null
  KPTR_NOW2=$(cat /proc/sys/kernel/kptr_restrict 2>/dev/null)
  echo "[handoff] kptr_restrict 兜底：${KPTR_NOW:-?} -> ${KPTR_NOW2:-?}" >>"$LOG" 2>/dev/null
  echo "[handoff] kptr_restrict 兜底：${KPTR_NOW:-?} -> ${KPTR_NOW2:-?}"
else
  echo "[handoff] kptr_restrict=${KPTR_NOW:-?}（无需兜底）" >>"$LOG" 2>/dev/null
fi

# 【11.10.57g】把上次重启的 pstore 现场在读得到的时候拷一份到 /sdcard，
# 免得下一次开机又被 SELinux 挡住、永远看不到 panic 原因。
if [ "$(cat /sys/fs/selinux/enforce 2>/dev/null)" = "0" ]; then
  _ps=/sdcard/Download/临时ROOT测试日志
  mkdir -p "$_ps" 2>/dev/null
  for _f in /sys/fs/pstore/*; do
    [ -f "$_f" ] || continue
    _b=$(basename "$_f")
    cat "$_f" >"$_ps/pstore-$_b.txt" 2>/dev/null       && echo "[handoff] pstore 已拷: $_ps/pstore-$_b.txt ($(wc -c <"$_ps/pstore-$_b.txt" 2>/dev/null) B)" >>"$LOG" 2>/dev/null
  done
fi

# 【11.11.47】tqclean 彻底下线（有实测证据，不再猜）：
#   · 6.6 dali：insmod 直接 CFI Oops —— `Internal error: Oops - CFI ... target: dfwalk_init+0x0/0xffc [tqclean]`；
#   · 6.12 pudding：能加载，但自述 `dfwalk: done plants=0 cleared=0`，什么都没清。
#   所以它既危险又没用，删掉。

KSU_ALREADY=0
if grep -q kernelsu /proc/modules 2>/dev/null; then
  KSU_ALREADY=1
fi
FIXUP_RC=1
if [ "$(cat /sys/fs/selinux/enforce 2>/dev/null)" = "0" ]; then
  # 【11.10.57g】引擎已经把 SELinux 写成 permissive 了；这时再 load_policy 会把
  # enforce 拉回 1（10:09 日志：fixup rc=0 之后 enforce=1、dmesg/sucompat 全被拒）。
  # 57f 只改了 FIXUP_RC 没跳过循环，这次真的用 else 把循环包起来。
  echo '[*] already permissive; really skip policy fixup loop' >>"$LOG"
  FIXUP_RC=0
elif [ -z "$LOADPOLICY" ]; then
  echo '[!] load_policy not found anywhere; skipping the policy fixup loop' >>"$LOG"
  FIXUP_RC=127
fi
if [ "$FIXUP_RC" != "0" ]; then
for i in $(seq 1 10); do
  [ -n "$LOADPOLICY" ] || break
  echo "[*] fixup: attempt $i" >>"$LOG"
  if ! prepare_policy >>"$LOG" 2>&1; then
    sleep 2
    continue
  fi
  "$LOADPOLICY" "$POLICY" >>"$LOG" 2>&1 &
  LPID=$!
  (sleep 8; kill -9 $LPID 2>/dev/null) &
  SPID=$!
  wait $LPID 2>/dev/null
  FIXUP_RC=$?
  kill $SPID 2>/dev/null
  if [ "$FIXUP_RC" -eq 0 ]; then
    break
  fi
  sleep 2
done
echo "[*] policy fixup rc=$FIXUP_RC" >>"$LOG"
fi
cleanup_policy_dump
if [ "$FIXUP_RC" -eq 0 ]; then
if [ "$KSU_ALREADY" -eq 1 ]; then
  echo '[+] kernelsu already loaded; no late-load' >>"$LOG"
  # 【11.10.56i】同一 boot 里重跑时，旧模块的 ksu_cred 还是上一轮（fsuid=0）
  # 加载出来的，track_throne 读 packages.list 依旧 -13。用引擎 insmod 重载一次
  # 刷新 ksu_cred（fsuid=1000）；后面的管理器安装会触发 scan/crowning。
  preload_ko_from_ksud
else
  if [ ! -f "$KSUD" ]; then
    echo '[!] ksud missing; cannot late-load（详见上面那段"为什么找不到"）' >>"$LOG"
    exit 3
  fi
  # 【11.00.23 用户指定】先走作者那套"一键越狱"：用临时 uid0 调管理器自带 ksud 的
  # Magica 阶段；它能拿到 adb root 的完整权限 shell，再在里面做 post-magica late-load。
  if [ "$(getprop ro.debuggable 2>/dev/null)" != "1" ] && "$KSUD" late-load --help 2>&1 | grep -q -- '--magica'; then
    echo "[*] ro.debuggable=$(getprop ro.debuggable 2>/dev/null) ⇒ magica 依赖的 setprop 必被拒，跳过（省 13 秒）" >>"$LOG"
  elif "$KSUD" late-load --help 2>&1 | grep -q -- '--magica'; then
    echo "[*] 作者方法：$KSUD late-load --magica=5555 --allow-shell" >>"$LOG"
    "$KSUD" late-load --magica=5555 --allow-shell >>"$LOG" 2>&1
    echo "[*] magica exit=$?" >>"$LOG"
    for i in $(seq 1 100); do
      if grep -q kernelsu /proc/modules 2>/dev/null; then KSU_READY=1; break; fi
      sleep 0.1
    done
    if [ "${KSU_READY:-0}" -eq 1 ]; then
      echo '[+] 作者方法 magica 已把 kernelsu 载入' >>"$LOG"
    else
      echo '[*] magica 没成，退回普通 late-load' >>"$LOG"
    fi
  fi
  if [ "${KSU_READY:-0}" -ne 1 ]; then
  echo "[*] late-load kmi=$KMI as uid=$(id -u)" >>"$LOG"
  run_late_load
  echo "[*] late-load exit=$?" >>"$LOG"
  KSU_READY=0
  for i in $(seq 1 50); do
    if grep -q kernelsu /proc/modules 2>/dev/null; then KSU_READY=1; break; fi
    sleep 0.1
  done
  if [ "$KSU_READY" -ne 1 ]; then
    echo '[!] KernelSU module not loaded' >>"$LOG"
    diagnose_ko
    exit 4
  fi
  echo '[+] KernelSU module loaded' >>"$LOG"
  fi
fi
# 【11.00.27】用户要求：保持 SELinux 宽容，不再拉回 enforcing
:
else
  echo '[!] fixup failed; SELinux left permissive' >>"$LOG"
  echo "[!] load_policy=$LOADPOLICY rc=$FIXUP_RC  PATH=$PATH" >>"$LOG"
  echo '[!] this is a SELinux policy-restore failure, NOT a KernelSU problem' >>"$LOG"
  if [ "$KSU_ALREADY" -eq 1 ]; then
    echo '[+] kernelsu already loaded; nothing to do' >>"$LOG"
    exit 0
  fi
  if [ ! -f "$KSUD" ]; then
    echo '[!] ksud missing; cannot late-load（详见上面那段"为什么找不到"）' >>"$LOG"
    exit 3
  fi
  echo "[*] last-resort late-load (fixup failed) kmi=$KMI" >>"$LOG"
  run_late_load
  echo "[*] last-resort late-load exit=$?" >>"$LOG"
  KSU_READY=0
  for i in $(seq 1 50); do
    if grep -q kernelsu /proc/modules 2>/dev/null; then KSU_READY=1; break; fi
    sleep 0.1
  done
  if [ "$KSU_READY" -eq 1 ]; then
    echo '[+] KernelSU module loaded (despite the policy fixup failure)' >>"$LOG"
    if [ "$(cat /sys/fs/selinux/enforce 2>/dev/null)" != "1" ]; then
      : # 【11.00.27】保持宽容
    fi
    exit 0
  fi
  echo '[!] KernelSU module not loaded' >>"$LOG"
  diagnose_ko
  exit 2
fi
echo "[*] late-load(LKM) 已生效：kernelsu 已在 /proc/modules" >>"$LOG"
# 【11.10.44】按朋友的接管方案：不只看 late-load 返回码，等模块真的可见。
_i=0
while [ "$_i" -lt 30 ]; do
  grep -q kernelsu /proc/modules 2>/dev/null && break
  sleep 1
  _i=$((_i+1))
done
echo "[handoff] modules after wait: $(grep -c kernelsu /proc/modules 2>/dev/null) (waited ${_i}s)" >>"$LOG"
# 【11.11.04】越狱到底卡在哪：把 /data/adb 的归属、可读、可写（**以 system 身份**）打出来。
# 依据：KernelSU 的 late-load 加载完 KO 之后，ksud 还要 install() 一套 userspace
# （/data/adb/ksu/bin/ksud、/data/adb/modules…）。HyperOS 上 /data/adb 是 system:system 0770，
# 我们的假 root 只有 uid0 + CAP_SYS_MODULE，**没有 CAP_DAC_OVERRIDE**（slab 地址凑不出 bit1），
# 所以 fsuid=0 时连目录都写不进去 ⇒ userspace 装不上 ⇒ 管理器永远「未安装」。
# 这段体检就是用来证实/证伪它的：
# 【11.11.10】zorn 19:04 的日志：handoff 开始时 enforce=0，跑完 `enforce_final=1`
# —— 是 late-load 那条链路把策略装回去了。enforcing 下管理器拿不到 ksu fd、
# sucompat 也会被 SELinux 拦，所以这里再打一次 setenforce 0，并把结果写进日志。
if [ "$(cat /sys/fs/selinux/enforce 2>/dev/null)" = "1" ]; then
  echo "[handoff] late-load 之后 enforce=1（策略被装回去了），尝试 setenforce 0" >>"$LOG" 2>/dev/null
  setenforce 0 >>"$LOG" 2>&1
  echo "[handoff] enforce after setenforce: $(cat /sys/fs/selinux/enforce 2>/dev/null)" >>"$LOG" 2>/dev/null
fi
echo "[handoff] enforce now: $(cat /sys/fs/selinux/enforce 2>/dev/null)"

ADB_DIAG=$(as_system_sh 'id 2>&1; echo "---"; ls -ld /data/adb /data/adb/ksud /data/adb/ksu /data/adb/ksu/bin 2>&1; echo "---"; ls -l /data/adb 2>&1 | head -15; echo "---"; if touch /data/adb/.ezw_test 2>/dev/null; then echo WRITE_OK; rm -f /data/adb/.ezw_test; else echo WRITE_DENIED; fi' 2>&1)
{
  echo "--- /data/adb 体检（system 身份）---"
  printf '%s\n' "$ADB_DIAG"
} >>"$LOG" 2>/dev/null
echo "[handoff] /data/adb 体检: $(printf '%s' "$ADB_DIAG" | tr '\n' ' ' | cut -c1-300)"
echo "[handoff] modules=$(grep -c kernelsu /proc/modules 2>/dev/null)"

# 4) 不再自己跑 ksud install / debug su。
#    FinaSU 的 late-load 在加载完 ko 后会自己 utils::install（u:r:ksu:s0），
#    并在所有 late-load 脚本跑完后 am force-stop/start 选中的管理器（--package-name）。
#    管理器重启时 exec 会从内核拿到新的 ksu fd，首页才会显示已安装。
#    11.10.44-47 在 shell 域里补 install/debug su 全部 EPERM；11.10.43/47 去清
#    管理器 seccomp 更是错的方向（点安装会整机重启）。这里只等 late-load 收尾。
# 【11.10.55d 用户明确要求】不再用《接管方案.md》那套：
#   不修 /data/adb、不跑 ksud install、不等 /system/bin/su、不手动 setenforce、
#   不额外重启管理器。只跑管理器自己的 ksud late-load；它内部会做 install 并重启管理器。
# 【11.10.56f】完全走管理器自己的 late-load：它加载 KO 后进程进入 u:r:ksu:s0，
# 由它在 ksu 域做 utils::install，建 /data/adb/ksu 和 su。shell 域直接
# ksud install 会因打不开 /data/adb 失败（00:37 日志）。
# late-load 完成后才安装内置管理器，触发 package 事件；然后手动打开。
# 【11.10.56g】必须 uninstall + install，不能只 pm install -r：
# 只替换已安装包只发 PACKAGE_REPLACED，内核 pkg_observer 不一定重新
# search_manager/crowning；管理器就永远拿不到 ksu fd。先卸载再装，
# 强制发 PACKAGE_ADDED（别人的流程是“本来没装 → 系统安装器新装”）。

# 【11.10.56m】sucompat 修复：我们为了 crowning 把 ksu_cred 的 fsuid 切成了 1000，
# 内核 execve("/system/bin/su") 时会 override_creds(ksu_cred) 再
# filp_open("/data/adb/ksud")（KO 里 .rodata 写死这个路径）。
# /data/adb 是 0700 root，fsuid=1000 连目录都进不去 → open 失败 →
# 内核回退成"按原 uid 执行 su"，所以管理器里已授权的 App 也显示没有 root。
# /data/adb 属主就是 root，我们本身 uid0，chmod 不需要 CAP_FOWNER：
#   /data/adb 0711 = 能穿过、不能列目录；/data/adb/ksud 0755 = fsuid=1000 能 open。
fix_sucompat_paths() {
  mkdir -p /data/adb 2>/dev/null
  [ -f /data/adb/ksud ] || cp -f /data/local/tmp/ksud /data/adb/ksud 2>/dev/null
  chmod 0755 /data/adb/ksud 2>/dev/null
  chmod 0711 /data/adb 2>/dev/null
  for d in /data/adb/ksu /data/adb/ksu/bin /data/adb/modules /data/adb/modules_update; do
    [ -d "$d" ] && chmod 0711 "$d" 2>/dev/null
  done
  [ -d /data/adb/ksu/bin ] && chmod 0755 /data/adb/ksu/bin/* 2>/dev/null
  chmod -R a+rX /data/adb 2>/dev/null
  echo "[handoff] sucompat: $(ls -ld /data/adb /data/adb/ksud 2>&1 | tr '\n' ' ')" >>"$LOG" 2>/dev/null
}

fix_sucompat_paths

trigger_manager_crowning 3


# 【11.10.56i】把内核侧 crowning 的结果直接写进日志：下一次上传就能看到
# track_throne 到底有没有读到 packages.list、有没有 Crowning/install fd。
sleep 2
{
  # 【11.11.14 用户要求】读不到的东西别再写进日志占位：
  #   ls /data/system|/data/adb、dmesg、logcat -b kernel 在 shell 域一律
  #   Permission denied / 空，打出来只是噪声。只留真正能读到的现场。
  echo "--- handoff diag ---"
  id 2>&1
  grep -E '^Cap(Eff|Bnd)' /proc/self/status 2>&1
  grep kernelsu /proc/modules 2>&1
  echo "adb_readable=$(ls /data/adb >/dev/null 2>&1 && echo yes || echo no) ksu_dir=$([ -d /data/adb/ksu ] && echo yes || echo no) ksud=$([ -f /data/adb/ksud ] && echo yes || echo no) moddirs=$(ls /data/adb/modules 2>/dev/null | wc -l)"
} >>"$LOG" 2>/dev/null
echo "[+] KernelSU 已加载；管理器已安装。请手动打开管理器（不要点安装）"
exit 0

