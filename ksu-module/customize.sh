#!/system/bin/sh
# KernelSU / Magisk 模块安装脚本
SKIPUNZIP=0

ui_print "- TF管理终端 KernelSU 模块"
ui_print "- 版本：v5.4.0 (40230)"

# 仅通过 overlay 提供系统应用，不改动真实分区
ui_print "- 放置系统应用：/system/app/TFManager/TFManager.apk"

# 权限
set_perm_recursive "$MODPATH" 0 0 0755 0644
[ -f "$MODPATH/system/app/TFManager/TFManager.apk" ] && \
  set_perm "$MODPATH/system/app/TFManager/TFManager.apk" 0 0 0644

# 如果已经用普通方式装过同名包，提示可能冲突（不强制卸载，避免动用户数据）
if pm list packages 2>/dev/null | grep -q "package:com.rootterm.fm"; then
  ui_print "! 检测到已安装 com.rootterm.fm"
  ui_print "! 系统应用与已安装版本签名需一致，否则可能不生效"
  ui_print "! 建议先卸载旧版再重启，或忽略此提示"
fi

ui_print "- 安装完成，重启后生效"
