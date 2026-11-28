package com.rootterm.fm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：拉起状态栏常驻通知。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String a = intent.getAction();
        // 只接受系统保护广播：BOOT_COMPLETED 与 MY_PACKAGE_REPLACED 只有系统能发，
        // 第三方 App 无法伪造。QUICKBOOT_POWERON 不是保护广播，能被任意应用触发，已移除。
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            NotifyService.startIfEnabled(context);
        }
    }
}
