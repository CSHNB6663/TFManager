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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 通用「登录取 Cookie」WebView 面板。
 *
 * <p>用户在页面里自己登录，点「完成」后把目标站点的 cookie 交给宿主。
 * App 不接触账号密码。</p>
 */
public class CookieLoginPanel extends LinearLayout {

    /** 回调。 */
    public interface OnDone {
        void onDone(String cookie);
    }

    private final Context ctx;
    private final String siteUrl;
    private WebView web;
    private ProgressBar bar;
    private OnDone done;

    /** 桌面版 UA：让网站返回 PC 页面（移动版经常没有登录入口）。 */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @SuppressLint("SetJavaScriptEnabled")
    public CookieLoginPanel(Context c, String url, String titleText) {
        this(c, url, titleText, true);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public CookieLoginPanel(Context c, String url, String titleText, boolean desktop) {
        super(c);
        ctx = c;
        siteUrl = url;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFFFFFFFF);

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
                finish(false);
            }
        });
        head.addView(back);

        TextView title = new TextView(c);
        title.setText(titleText == null ? "登录" : titleText);
        title.setTextSize(15f);
        title.setTextColor(0xFF191919);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reload = new TextView(c);
        reload.setText("刷新");
        reload.setTextSize(13f);
        reload.setTextColor(0xFF33507A);
        reload.setPadding(UiConfig.dp(c, 10), UiConfig.dp(c, 4),
                UiConfig.dp(c, 10), UiConfig.dp(c, 4));
        reload.setClickable(true);
        reload.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    web.reload();
                } catch (Throwable ignored) {
                }
            }
        });
        head.addView(reload);

        TextView ok = new TextView(c);
        ok.setText("完成");
        ok.setTextSize(14f);
        ok.setTextColor(0xFFFFFFFF);
        ok.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(UiConfig.dp(c, 14), UiConfig.dp(c, 6),
                UiConfig.dp(c, 14), UiConfig.dp(c, 6));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(ThemeColors.ACCENT);
        g.setCornerRadius(UiConfig.dp(c, 14));
        ok.setBackground(g);
        ok.setClickable(true);
        ok.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                finish(true);
            }
        });
        head.addView(ok);
        addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(c);
        hint.setText("提示：优先用「扫码登录」。若点「获取验证码」没反应，"
                + "通常是滑块验证在 WebView 里拖不动，点右上「刷新」重试，或改用扫码/账号密码登录。");
        hint.setTextSize(10.5f);
        hint.setTextColor(0xFF8A6D3B);
        hint.setBackgroundColor(0xFFFFF8E1);
        hint.setPadding(UiConfig.dp(c, 10), UiConfig.dp(c, 7),
                UiConfig.dp(c, 10), UiConfig.dp(c, 7));
        hint.setLineSpacing(UiConfig.dp(c, 1.5f), 1f);
        addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiConfig.dp(c, 3)));

        web = new WebView(c);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        if (desktop) {
            // 关键：桌面 UA → 站点返回电脑版页面（才有网页登录入口）
            s.setUserAgentString(DESKTOP_UA);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            s.setTextZoom(100);
        } else {
            s.setUserAgentString(s.getUserAgentString());
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

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

        if (desktop) {
            web.setInitialScale(55);
        }
        web.loadUrl(url);
    }

    public void setOnDone(OnDone d) {
        done = d;
    }

    public String currentCookie() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.flush();
            String c = cm.getCookie(siteUrl);
            return c == null ? "" : c;
        } catch (Throwable t) {
            return "";
        }
    }

    private void finish(boolean save) {
        String c = currentCookie();
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
}
