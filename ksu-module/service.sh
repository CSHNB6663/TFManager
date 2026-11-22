#!/system/bin/sh
# 开机后（以 root 身份）拉起 App 的常驻通知服务，
# 绕过部分 ROM 的「自启动管理」限制，让 App 常驻在系统里。
MODDIR=${0%/*}
LOG=/data/local/tmp/.tfmanager_boot.log

(
  # 等待系统启动完成
  i=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do
    sleep 2
    i=$((i+1))
  done
  sleep 5

  echo "[$(date)] boot_completed=$(getprop sys.boot_completed)" >>"$LOG"

  # 等系统应用注册完成
  for n in 1 2 3 4 5; do
    # 安装/更新「暗码助手」（无图标小应用，仅用于 *#*#7891#*#* 唤起）
  HELPER="$MODDIR/helper/TFSecretCode.apk"
  if [ -f "$HELPER" ]; then
    if ! pm list packages 2>/dev/null | grep -q "package:com.rootterm.secretcode"; then
      pm install -r "$HELPER" >>"$LOG" 2>&1
      echo "[$(date)] installed secretcode helper rc=$?" >>"$LOG"
    fi
  fi

  if pm list packages 2>/dev/null | grep -q "package:com.rootterm.fm"; then
      break
    fi
    sleep 4
  done

  # 安装/更新「暗码助手」（无图标小应用，仅用于 *#*#7891#*#* 唤起）
  HELPER="$MODDIR/helper/TFSecretCode.apk"
  if [ -f "$HELPER" ]; then
    if ! pm list packages 2>/dev/null | grep -q "package:com.rootterm.secretcode"; then
      pm install -r "$HELPER" >>"$LOG" 2>&1
      echo "[$(date)] installed secretcode helper rc=$?" >>"$LOG"
    fi
  fi

  if pm list packages 2>/dev/null | grep -q "package:com.rootterm.fm"; then
    am start-foreground-service -n com.rootterm.fm/.NotifyService >>"$LOG" 2>&1 \
      || am startservice -n com.rootterm.fm/.NotifyService >>"$LOG" 2>&1
    echo "[$(date)] started NotifyService rc=$?" >>"$LOG"
  else
    echo "[$(date)] package com.rootterm.fm not found" >>"$LOG"
  fi
) &

exit 0
