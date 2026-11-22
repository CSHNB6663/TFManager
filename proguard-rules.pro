# R8 混淆规则（手动构建链）
# 目标：类/方法/字段全部改名，保留清单里声明的组件名。

# 清单中声明的组件必须保名
-keep class com.rootterm.fm.MainActivity { *; }

# 保留 Application/Activity/Service/Receiver/Provider 的构造与生命周期不被裁掉
-keepclassmembers class * extends android.app.Activity { public void *(android.os.Bundle); }
-keepclassmembers class * extends android.content.BroadcastReceiver { public void onReceive(...); }

# 资源 id 通过 getIdentifier 按名字查，不涉及类名，无需 keep

# 不保留任何调试信息与源文件
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable

# 允许压缩（移除未使用代码）
-dontwarn **
-dontoptimize
-allowaccessmodification
-repackageclasses ''
