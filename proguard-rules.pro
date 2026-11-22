# R8 混淆规则（手动构建链）
# 目标：业务类全部改名；清单里声明的四大组件必须保名，否则系统实例化会 ClassNotFoundException。

# ---- 清单声明的组件：保名（关键！否则 BootReceiver/NotifyService 会被改名而崩溃）----
-keep public class * extends android.app.Activity { *; }
-keep public class * extends android.app.Service { *; }
-keep public class * extends android.content.BroadcastReceiver { *; }
-keep public class * extends android.content.ContentProvider { *; }
-keep public class * extends android.app.Application { *; }
-keep public class * extends android.app.backup.BackupAgentHelper { *; }

# 显式列出，双保险
-keep class com.rootterm.fm.MainActivity { *; }
-keep class com.rootterm.fm.BootReceiver { *; }
-keep class com.rootterm.fm.NotifyService { *; }

# 组件生命周期方法
-keepclassmembers class * extends android.app.Activity { public void *(android.os.Bundle); }
-keepclassmembers class * extends android.content.BroadcastReceiver { public void onReceive(...); }
-keepclassmembers class * extends android.app.Service { public int onStartCommand(...); public android.os.IBinder onBind(...); public void onCreate(); public void onDestroy(); }

# 资源 id 通过 getIdentifier 按名字查，不涉及类名，无需 keep

# 不保留调试信息与源文件
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable

-dontwarn **
-dontoptimize
-allowaccessmodification
-repackageclasses ''
