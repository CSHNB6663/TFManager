package com.rootterm.fm;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * 极简 FileProvider（不依赖 AndroidX）。
 *
 * <p>只对外暴露应用私有目录下的 {@code update/} 子目录里的 APK，
 * 供系统安装器通过 {@code content://com.rootterm.fm.apk/apk/<文件名>} 读取。</p>
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.rootterm.fm.apk";
    /** 对外目录名。 */
    public static final String DIR = "update";

    /** 构造一个可供安装器使用的 content:// URI。 */
    public static Uri uriFor(File apk) {
        return Uri.parse("content://" + AUTHORITY + "/apk/" + apk.getName());
    }

    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            return null;
        }
        File dir = new File(getContext().getFilesDir(), DIR);
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = resolve(uri);
        if (f == null) {
            throw new java.io.FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = f.length();
            } else {
                row[i] = null;
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
