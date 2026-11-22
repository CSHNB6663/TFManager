package com.miuix.pref;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.miuix.glass.MiuixGlass;
import com.miuix.glassicons.MiuixGlassIcons;
import com.miuix.icons.MiuixIcons;
import com.miuix.ui.MiuixCard;
import com.miuix.ui.MiuixTheme;

/**
 * miuix-preference：设置项。
 *
 * <p>对应上游 {@code miuix-preference} 模块的 {@code Preference} 系列组件。
 * 这版全部建立在 {@link com.miuix.glass.MiuixGlass} 液态玻璃上：</p>
 * <ul>
 *   <li>分组卡片 = 一块清透玻璃（磨砂 + 折射 + 镜面 + 超椭圆棱边）；</li>
 *   <li>前置图标 = {@code miuix-glass-icons} 的玻璃图标块；</li>
 *   <li>分组标题、分隔线、开关、右值都按玻璃层次重新着色。</li>
 * </ul>
 *
 * <pre>
 * MiuixPreference.Group g = MiuixPreference.group(ctx, "更新");
 * g.switchRow("自动检查更新", "启动时查询", true, listener);
 * g.clickRow("检查更新", "当前 v1.0", "›", listener);
 * View card = g.build();
 * </pre>
 */
public final class MiuixPreference {

    /** 点击回调。 */
    public interface OnClick {
        void onClick(View v);
    }

    /** 开关回调。 */
    public interface OnSwitch {
        void onChanged(boolean checked);
    }

    private MiuixPreference() {
    }

    /** 新建一个分组（标题 + 液态玻璃卡片）。 */
    public static Group group(Context c, String title) {
        return new Group(c, title);
    }

    /** 分组构建器。 */
    public static final class Group {

        private final Context ctx;
        private final String title;
        private final LinearLayout rows;
        private final MiuixCard card;
        private final LinearLayout root;
        private int rowCount;

        Group(Context c, String t) {
            ctx = c;
            title = t;
            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);

            if (t != null && !t.isEmpty()) {
                TextView tv = new TextView(c);
                tv.setText(t);
                tv.setTextSize(12.5f);
                tv.setTextColor(MiuixTheme.onSurfaceVariant());
                tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                tv.setPadding(MiuixTheme.dp(c, 8), MiuixTheme.dp(c, 2),
                        MiuixTheme.dp(c, 8), MiuixTheme.dp(c, 7));
                root.addView(tv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            // 分组本体：miuix-glass 清透液态玻璃卡片
            card = new MiuixCard(c);
            card.fill(MiuixTheme.surface());
            card.stroke(MiuixTheme.outline(), 0.8f);
            card.radiusDp(MiuixTheme.RADIUS_CARD);
            card.specular(true);

            rows = new LinearLayout(c);
            rows.setOrientation(LinearLayout.VERTICAL);
            card.addView(rows, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            root.addView(card, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        /** 开关行。 */
        public Group switchRow(String title, String summary, boolean checked,
                               final OnSwitch cb) {
            LinearLayout row = baseRow(title, summary, 0);
            final Switch sw = new Switch(ctx);
            sw.setChecked(checked);
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                    if (cb != null) {
                        cb.onChanged(v);
                    }
                }
            });
            row.addView(sw);
            final Switch fsw = sw;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    fsw.toggle();
                }
            });
            add(row);
            return this;
        }

        /** 点击行（右侧可带值 + 箭头）。 */
        public Group clickRow(String title, String summary, String value,
                              final OnClick cb) {
            LinearLayout row = baseRow(title, summary, 0);
            if (value != null && !value.isEmpty()) {
                row.addView(valueText(value));
            }
            row.addView(chevron());
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (cb != null) {
                        cb.onClick(v);
                    }
                }
            });
            add(row);
            return this;
        }

        /** 带 {@code miuix-glass-icons} 玻璃图标的点击行。 */
        public Group iconClickRow(int iconRes, int iconTint, String title, String summary,
                                  String value, final OnClick cb) {
            LinearLayout row = baseRow(title, summary, iconRes, iconTint);
            if (value != null && !value.isEmpty()) {
                row.addView(valueText(value));
            }
            row.addView(chevron());
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (cb != null) {
                        cb.onClick(v);
                    }
                }
            });
            add(row);
            return this;
        }

        /** 只读信息行（左标题、右值）。 */
        public Group infoRow(String title, String value) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_H),
                    MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_V),
                    MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_H),
                    MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_V));

            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(MiuixTheme.TEXT_BODY);
            t.setTextColor(MiuixTheme.onSurface());
            row.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView v = new TextView(ctx);
            v.setText(value == null ? "" : value);
            v.setTextSize(12.5f);
            v.setTextColor(MiuixTheme.onSurfaceVariant());
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            v.setGravity(Gravity.END);
            row.addView(v, new LinearLayout.LayoutParams(
                    MiuixTheme.dp(ctx, 160), LinearLayout.LayoutParams.WRAP_CONTENT));

            add(row);
            return this;
        }

        /** 长值信息行（标题在上、值在下可换行）。 */
        public Group wrapInfoRow(String title, String value) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_H),
                    MiuixTheme.dp(ctx, 12),
                    MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_H),
                    MiuixTheme.dp(ctx, 12));

            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(MiuixTheme.TEXT_BODY);
            t.setTextColor(MiuixTheme.onSurface());
            row.addView(t);

            TextView v = new TextView(ctx);
            v.setText(value == null ? "" : value);
            v.setTextSize(11.5f);
            v.setTextColor(MiuixTheme.onSurfaceVariant());
            v.setPadding(0, MiuixTheme.dp(ctx, 4), 0, 0);
            v.setTextIsSelectable(true);
            row.addView(v);

            add(row);
            return this;
        }

        /** 自定义行（完全自己控制内容）。 */
        public Group customRow(View v) {
            add(v);
            return this;
        }

        /** 生成最终 View（标题 + 玻璃卡片）。 */
        public View build() {
            return root;
        }

        /** 卡片本体（不含分组标题）。 */
        public MiuixCard card() {
            return card;
        }

        // ------------------------------------------------------------ 内部

        private LinearLayout baseRow(String title, String summary, int iconRes) {
            return baseRow(title, summary, iconRes, 0);
        }

        private LinearLayout baseRow(String rowTitle, String summary,
                                     int iconRes, int iconTint) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_H),
                    MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_V),
                    MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_H),
                    MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_V));
            row.setClickable(true);

            if (iconRes != 0) {
                // miuix-glass-icons：图标嵌在液态玻璃块里
                int tint = iconTint != 0 ? iconTint : MiuixTheme.primary();
                MiuixGlass tile = MiuixGlassIcons.clearTile(ctx, iconRes,
                        tint, 34, 20);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                        MiuixTheme.dp(ctx, 34), MiuixTheme.dp(ctx, 34));
                ip.rightMargin = MiuixTheme.dp(ctx, 12);
                row.addView(tile, ip);
            }

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(ctx);
            t.setText(rowTitle);
            t.setTextSize(MiuixTheme.TEXT_BODY);
            t.setTextColor(MiuixTheme.onSurface());
            texts.addView(t);
            if (summary != null && !summary.isEmpty()) {
                TextView s = new TextView(ctx);
                s.setText(summary);
                s.setTextSize(MiuixTheme.TEXT_CAPTION);
                s.setTextColor(MiuixTheme.onSurfaceVariant());
                s.setPadding(0, MiuixTheme.dp(ctx, 2), 0, 0);
                texts.addView(s);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }

        private TextView valueText(String value) {
            TextView v = new TextView(ctx);
            v.setText(value);
            v.setTextSize(MiuixTheme.TEXT_LABEL);
            v.setTextColor(MiuixTheme.onSurfaceVariant());
            v.setSingleLine(true);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            vp.leftMargin = MiuixTheme.dp(ctx, 8);
            v.setLayoutParams(vp);
            return v;
        }

        private TextView chevron() {
            TextView chev = new TextView(ctx);
            chev.setText("›");
            chev.setTextSize(18f);
            chev.setTextColor(0xFF9AA6B8);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.leftMargin = MiuixTheme.dp(ctx, 6);
            chev.setLayoutParams(cp);
            return chev;
        }

        private void add(View row) {
            if (rowCount > 0) {
                View line = new View(ctx);
                line.setBackgroundColor(0x1AFFFFFF);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1,
                        MiuixTheme.dp(ctx, 0.7f)));
                lp.leftMargin = MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_H);
                lp.rightMargin = MiuixTheme.dp(ctx, MiuixTheme.SPACE_ROW_H);
                rows.addView(line, lp);
            }
            rows.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            rowCount++;
        }
    }
}
