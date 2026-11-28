package com.rootterm.fm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 番茄小说登录页（内嵌 WebView）。
 *
 * <p>用户在页面里自己登录，点「完成」后从 {@link CookieManager} 取 cookie 交给宿主。
 * App 不接触账号密码。</p>
 */
public class FanqieWebLogin extends LinearLayout {

    /** 登录完成回调。 */
    public interface OnDone {
        void onDone(String cookie);
    }

    private final Context ctx;
    private WebView web;
    private ProgressBar bar;
    private OnDone done;

    @SuppressLint("SetJavaScriptEnabled")
    public FanqieWebLogin(Context c) {
        super(c);
        ctx = c;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFFFFFFFF);

        // 顶栏
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(0xFFF7F8FA);
        head.setPadding(UiConfig.dp(c, 8), UiConfig.dp(c, 10),
                UiConfig.dp(c, 8), UiConfig.dp(c, 10));

        TextView back = new TextView(c);
        back.setText("← 返回");
        back.setTextSize(14f);
        back.setTextColor(ThemeColors.ACCENT);
        back.setPadding(UiConfig.dp(c, 8), UiConfig.dp(c, 4),
                UiConfig.dp(c, 8), UiConfig.dp(c, 4));
        back.setClickable(true);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithCookie(false);
            }
        });
        head.addView(back);

        TextView title = new TextView(c);
        title.setText("登录番茄小说");
        title.setTextSize(15f);
        title.setTextColor(0xFF191919);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView doneBtn = new TextView(c);
        doneBtn.setText("完成");
        doneBtn.setTextSize(14f);
        doneBtn.setTextColor(0xFFFFFFFF);
        doneBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        doneBtn.setGravity(Gravity.CENTER);
        doneBtn.setPadding(UiConfig.dp(c, 14), UiConfig.dp(c, 6),
                UiConfig.dp(c, 14), UiConfig.dp(c, 6));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(ThemeColors.ACCENT);
        g.setCornerRadius(UiConfig.dp(c, 14));
        doneBtn.setBackground(g);
        doneBtn.setClickable(true);
        doneBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithCookie(true);
            }
        });
        head.addView(doneBtn);
        addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiConfig.dp(c, 3)));

        // WebView
        web = new WebView(c);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // 加固：禁止 WebView 读取本地文件与 content://，防被恶意页面当成文件读取器
        try {
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
            s.setSafeBrowsingEnabled(true);
        } catch (Throwable ignored) {
        }
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(s.getUserAgentString());

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        // 只允许 http/https 导航，挡住 file:// content:// intent:// javascript: 等
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return blockUnsafeUrl(v, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v,
                                                    android.webkit.WebResourceRequest req) {
                return blockUnsafeUrl(v, req.getUrl() == null ? "" : req.getUrl().toString());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                bar.setProgress(p);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                bar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                bar.setVisibility(View.GONE);
            }
        });
        addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web.loadUrl("https://fanqienovel.com/");
    }

    public void setOnDone(OnDone d) {
        done = d;
    }

    /** 取当前 cookie。 */
    public String currentCookie() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.flush();
            String c = cm.getCookie("https://fanqienovel.com");
            return c == null ? "" : c;
        } catch (Throwable t) {
            return "";
        }
    }

    private void finishWithCookie(boolean save) {
        String c = currentCookie();
        if (save) {
            if (c == null || !c.contains("sessionid")) {
                android.widget.Toast.makeText(ctx,
                        "还没检测到登录状态，请先在页面里登录", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            FanqieSession.save(ctx, c);
        }
        if (done != null) {
            done.onDone(c);
        }
    }

    public void destroy() {
        try {
            if (web != null) {
                web.stopLoading();
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 拦截非 http/https 的导航。
     *
     * 登录页加载的是第三方站点，若不限制协议，恶意页面可以跳 file://
     * 读取应用私有文件，或用 intent:// 触发别的组件。
     */
    private boolean blockUnsafeUrl(WebView v, String url) {
        if (url == null || url.isEmpty()) {
            return true;
        }
        String low = url.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("about:") || low.startsWith("data:")) {
            return false;   // 交给 WebView 自己加载
        }
        return true;        // 其余协议一律拦下
    }

}
