package com.rootterm.fm;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 免 root 目录浏览（Storage Access Framework）。
 *
 * <p>用系统文件选择器授权一个目录后，通过 {@link DocumentsContract} 读写该目录，
 * <b>不需要 root，也不需要「所有文件访问」</b>，适合 Android 10+ 的普通手机。</p>
 *
 * <p>这是文件管理器的补充入口：主管理器仍走 root / MANAGE_EXTERNAL_STORAGE；
 * 本页只依赖 SAF，纯 framework API（没有 AndroidX）。</p>
 */
public class SafPanel extends LinearLayout {

    private static final int REQ_PICK_DIR = 0x5101;

    private static final int C_BG = 0xFFF7F8FA;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF191919;
    private static final int C_DIM = 0xFF8A8F98;
    private static final int C_LINE = 0xFFF0F1F3;

    private final Activity act;
    private final LinearLayout listBox;
    private final TextView pathView;
    private final TextView tipView;
    private final ArrayList<SafDir> stack = new ArrayList<SafDir>();
    private Runnable onBackListener;
    private Uri treeUri;

    public SafPanel(Activity activity) {
        super(activity);
        this.act = activity;
        setOrientation(VERTICAL);
        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setBackgroundColor(0x00000000);

        addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        addView(divider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f))));

        LinearLayout tool = new LinearLayout(activity);
        tool.setOrientation(HORIZONTAL);
        tool.setGravity(Gravity.CENTER_VERTICAL);
        tool.setPadding(dp(14), dp(8), dp(14), dp(6));
        tool.setBackgroundColor(0x00000000);

        TextView pick = chip("选择目录…");
        pick.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDirectory();
            }
        });
        tool.addView(pick);

        TextView refresh = chip("刷新");
        refresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                reload();
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(8);
        tool.addView(refresh, rlp);

        TextView mk = chip("新建文件夹");
        mk.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                createFolder();
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = dp(8);
        tool.addView(mk, mlp);
        addView(tool);

        pathView = UiConfig.text(activity, "", 11.5f, C_DIM);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pathView.setPadding(dp(16), 0, dp(16), dp(6));
        addView(pathView);

        tipView = UiConfig.text(activity,
                "未授权目录：点「选择目录…」用系统文件选择器授权一个目录（如 Download），"
                        + "之后读写都不需要 root。", 12f, C_DIM);
        tipView.setPadding(dp(16), dp(6), dp(16), dp(6));
        addView(tipView);

        ScrollView sv = new ScrollView(activity);
        listBox = new LinearLayout(activity);
        listBox.setOrientation(VERTICAL);
        listBox.setPadding(dp(12), dp(4), dp(12), dp(24));
        sv.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        restoreTree();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(C_CARD);
        header.setPadding(dp(6), 0, dp(12), 0);

        ImageView back = new ImageView(act);
        int res = act.getResources().getIdentifier("ic_nav_back", "drawable", act.getPackageName());
        if (res != 0) {
            back.setImageResource(res);
            back.setColorFilter(C_TEXT);
        }
        back.setClickable(true);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onBackListener != null) {
                    onBackListener.run();
                }
            }
        });

        TextView title = UiConfig.text(act, "免 root 目录（SAF）", 16f, C_TEXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(6);
        header.addView(title, tlp);
        return header;
    }

    public void setOnBack(Runnable r) {
        onBackListener = r;
    }

    // ------------------------------------------------------------ SAF

    private void pickDirectory() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            act.startActivityForResult(i, REQ_PICK_DIR);
        } catch (Throwable t) {
            toast("无法打开系统文件选择器：" + t);
        }
    }

    /** 由 MainActivity 转发 onActivityResult。 */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_DIR || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            act.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        prefs().edit().putString("saf_tree", uri.toString()).apply();
        openTree(uri);
        toast("已授权目录");
    }

    private android.content.SharedPreferences prefs() {
        return act.getSharedPreferences("saf", Context.MODE_PRIVATE);
    }

    private void restoreTree() {
        String s = prefs().getString("saf_tree", null);
        if (s == null) {
            return;
        }
        try {
            openTree(Uri.parse(s));
        } catch (Throwable ignored) {
        }
    }

    private void openTree(Uri uri) {
        treeUri = uri;
        stack.clear();
        String rootId = DocumentsContract.getTreeDocumentId(uri);
        stack.add(new SafDir(rootId, treeName(uri)));
        reload();
    }

    private String treeName(Uri uri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(uri);
            int idx = id.indexOf(':');
            return idx >= 0 ? id.substring(idx + 1) : id;
        } catch (Throwable t) {
            return "目录";
        }
    }

    private SafDir current() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    // ------------------------------------------------------------ 列表

    private void reload() {
        final SafDir dir = current();
        if (dir == null) {
            tipView.setVisibility(VISIBLE);
            pathView.setText("");
            listBox.removeAllViews();
            return;
        }
        tipView.setVisibility(GONE);
        pathView.setText(buildPath(dir));

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<SafEntry> items = listChildren(dir);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        render(items);
                    }
                });
            }
        }, "saf-list").start();
    }

    private List<SafEntry> listChildren(SafDir dir) {
        ArrayList<SafEntry> out = new ArrayList<SafEntry>();
        if (treeUri == null) {
            return out;
        }
        ContentResolver cr = act.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.docId);
        Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
            }, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long size = c.isNull(3) ? 0 : c.getLong(3);
                    boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    out.add(new SafEntry(id, name == null ? "" : name, mime, size, isDir));
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        Collections.sort(out, new Comparator<SafEntry>() {
            @Override
            public int compare(SafEntry a, SafEntry b) {
                if (a.isDir != b.isDir) {
                    return a.isDir ? -1 : 1;
                }
                return a.name.toLowerCase(Locale.US).compareTo(b.name.toLowerCase(Locale.US));
            }
        });
        return out;
    }

    private void render(List<SafEntry> items) {
        listBox.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = UiConfig.text(act, "空目录 / 无权限", 13f, C_DIM);
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            listBox.addView(empty);
            return;
        }
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(VERTICAL);
        card.setBackground(round(C_CARD, 16, C_LINE));
        listBox.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < items.size(); i++) {
            card.addView(row(items.get(i)));
            if (i != items.size() - 1) {
                card.addView(divider());
            }
        }
    }

    private View row(final SafEntry e) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);

        ImageView icon = new ImageView(act);
        String iconName = e.isDir ? "ic_file_dir" : iconForName(e.name);
        int res = act.getResources().getIdentifier(iconName, "drawable", act.getPackageName());
        if (res != 0) {
            icon.setImageResource(res);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        TextView n = UiConfig.text(act, e.name, 15f, C_TEXT);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        TextView meta = UiConfig.text(act, e.isDir ? "文件夹" : humanSize(e.size), 11f, C_DIM);
        meta.setPadding(0, dp(2), 0, 0);
        texts.addView(n);
        texts.addView(meta);
        row.addView(texts, tlp);

        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (e.isDir) {
                    stack.add(new SafDir(e.docId, e.name));
                    reload();
                } else {
                    openFile(e);
                }
            }
        });
        row.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showItemMenu(e);
                return true;
            }
        });
        return row;
    }

    private void openFile(SafEntry e) {
        try {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, e.docId);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, e.mime == null ? "*/*" : e.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(i);
        } catch (Throwable t) {
            toast("没有能打开它的应用");
        }
    }

    private void showItemMenu(final SafEntry e) {
        final ArrayList<String> items = new ArrayList<String>();
        items.add("重命名");
        items.add("删除");
        if (e.isDir) {
            items.add("新建子文件夹");
        }
        items.add("名称 / 大小");
        final String[] arr = items.toArray(new String[0]);
        new android.app.AlertDialog.Builder(act,
                android.R.style.Theme_Material_Light_Dialog_Alert)
                .setItems(arr, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String label = arr[which];
                        if ("重命名".equals(label)) {
                            rename(e);
                        } else if ("删除".equals(label)) {
                            confirmDelete(e);
                        } else if ("新建子文件夹".equals(label)) {
                            createFolderIn(e.docId);
                        } else {
                            toast(e.name + " · " + humanSize(e.size));
                        }
                    }
                })
                .show();
    }

    private void createFolder() {
        SafDir dir = current();
        if (dir == null) {
            toast("请先选择一个目录");
            return;
        }
        createFolderIn(dir.docId);
    }

    private void createFolderIn(final String parentDocId) {
        final EditText et = new EditText(act);
        et.setHint("文件夹名");
        et.setSingleLine(true);
        new android.app.AlertDialog.Builder(act,
                android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("新建文件夹")
                .setView(et)
                .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) {
                            return;
                        }
                        try {
                            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId);
                            DocumentsContract.createDocument(act.getContentResolver(), parent,
                                    DocumentsContract.Document.MIME_TYPE_DIR, name);
                            reload();
                        } catch (Throwable t) {
                            toast("创建失败：" + t);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void rename(final SafEntry e) {
        final EditText et = new EditText(act);
        et.setText(e.name);
        et.setSingleLine(true);
        new android.app.AlertDialog.Builder(act,
                android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) {
                            return;
                        }
                        try {
                            Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, e.docId);
                            DocumentsContract.renameDocument(act.getContentResolver(), uri, name);
                            reload();
                        } catch (Throwable t) {
                            toast("重命名失败：" + t);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final SafEntry e) {
        new android.app.AlertDialog.Builder(act,
                android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("删除")
                .setMessage("确定删除？\n" + e.name)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, e.docId);
                            DocumentsContract.deleteDocument(act.getContentResolver(), uri);
                            reload();
                        } catch (Throwable t) {
                            toast("删除失败：" + t);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** ← 上一级；已在根返回 false。 */
    public boolean goUp() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            reload();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 模型 / 工具

    private static final class SafDir {
        final String docId;
        final String name;

        SafDir(String docId, String name) {
            this.docId = docId;
            this.name = name;
        }
    }

    private static final class SafEntry {
        final String docId;
        final String name;
        final String mime;
        final long size;
        final boolean isDir;

        SafEntry(String docId, String name, String mime, long size, boolean isDir) {
            this.docId = docId;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.isDir = isDir;
        }
    }

    private String buildPath(SafDir dir) {
        StringBuilder sb = new StringBuilder(dir.name);
        for (int i = stack.size() - 2; i >= 0; i--) {
            sb.insert(0, stack.get(i).name + " / ");
        }
        return sb.toString();
    }

    private String iconForName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif")) {
            return "ic_file_image";
        }
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi")) {
            return "ic_file_video";
        }
        if (n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".wav")) {
            return "ic_file_audio";
        }
        if (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")) {
            return "ic_file_archive";
        }
        if (n.endsWith(".apk")) {
            return "ic_file_apk";
        }
        if (n.endsWith(".pdf")) {
            return "ic_file_pdf";
        }
        return "ic_file_text";
    }

    private static String humanSize(long b) {
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", b / 1024.0);
        }
        if (b < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0);
        }
        return String.format(Locale.US, "%.2f GB", b / 1024.0 / 1024.0 / 1024.0);
    }

    private View divider() {
        View v = new View(act);
        v.setBackgroundColor(C_LINE);
        return v;
    }

    private TextView chip(String text) {
        TextView tv = UiConfig.text(act, text, 12.5f, C_TEXT);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        tv.setBackground(round(0xFFF2F3F5, 12, C_LINE));
        tv.setClickable(true);
        return tv;
    }

    private GradientDrawable round(int color, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(UiConfig.dp(act, r));
        if (stroke != 0) {
            g.setStroke(Math.max(1, dp(0.8f)), stroke);
        }
        return g;
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return UiConfig.dp(act, v);
    }
}
