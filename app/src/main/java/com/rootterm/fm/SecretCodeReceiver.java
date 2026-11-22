package com.rootterm.fm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 拨号盘暗码入口：在电话里输入 *#*#7891#*#* 直接打开管理器页面。
 *
 * <p>原理：系统会把 {@code *#*#<host>#*#*} 解析成
 * {@code android_secret_code://<host>} 广播，注册了对应 host 的应用即可收到。</p>
 *
 * <p>注意：部分定制 ROM（MIUI/HyperOS、ColorOS 等）会拦截第三方暗码，
 * 若无效属系统限制，可通过桌面图标 / 通知 / 模块入口打开。</p>
 */
public class SecretCodeReceiver extends BroadcastReceiver {

    /** 暗码：*#*#7891#*#* */
    public static final String HOST = "7891";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        try {
            Intent open = new Intent(context, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra("from_secret_code", true);
            context.startActivity(open);
        } catch (Throwable ignored) {
        }
    }
}
