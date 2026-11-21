package com.rootterm.selftest;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * 工具链自检用最小 Activity：不引用 R，纯代码 UI。
 * 只要它能被 aapt + javac + d8 + zipalign + apksigner 完整打包，就说明整条链路可用。
 */
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("RootTerm toolchain selftest OK");
        setContentView(tv);
    }
}
