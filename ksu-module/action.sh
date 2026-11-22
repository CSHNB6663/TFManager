#!/system/bin/sh
# KernelSU 模块「操作 / 执行」按钮：一键打开 TF管理终端
MODDIR=${0%/*}
export PATH="/system/bin:/system/xbin:/vendor/bin:$PATH"

PKG="com.rootterm.fm"

if pm list packages 2>/dev/null | grep -q "package:$PKG"; then
  # 优先用 monkey（等价于点桌面图标），失败再走 am start
  monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  if [ $? -ne 0 ]; then
    am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  fi
  if [ $? -eq 0 ] || monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; then
    echo "已打开 TF管理终端"
  else
    echo "启动失败：请手动在桌面打开"
  fi
else
  echo "未检测到已安装的 $PKG"
  echo ""
  echo "可能原因："
  echo "1) 刚装完模块还没重启 → 重启后系统应用才注册"
  echo "2) 手机上装过同包名但签名不同的版本 → 先卸载旧版再重启"
fi

echo ""
echo "模块目录：$MODDIR"
echo "版本：$(grep '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2)"
