package io.github.huyang218.dshandroid;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves exactly one file — the downloaded apk — to the platform installer.
 *
 * <p><b>Why not androidx's FileProvider.</b> The app has no dependencies at
 * all, and pulling `androidx.core` (and the Kotlin runtime behind it) into a
 * 130 MB apk for one class is a poor trade. More to the point, a generic
 * FileProvider is configured with directory mappings, so what it exposes grows
 * with the paths someone adds later. This one has no mapping: it answers for
 * a single fixed file and nothing else, which is the whole surface it will
 * ever have.
 *
 * <p><b>Why a provider is needed at all.</b> A `file:` URI handed to another
 * app has thrown `FileUriExposedException` since targetSdk 24, and this app
 * targets 28. The installer is a different process reading a file in
 * app-private storage, so it needs a content URI plus a read grant.
 *
 * <p>The installer asks for the display name and size before it shows its
 * confirmation, so {@link #query} answers those and nothing else. Everything
 * that would modify state is refused rather than left as a silent no-op.
 */
public class UpdateProvider extends ContentProvider {

    /** The only file this provider will ever serve. Kept in step with {@link Updater}. */
    static final String FILE_NAME = "update.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The apk in the cache directory, whatever path the URI claims. */
    private File file() throws FileNotFoundException {
        android.content.Context context = getContext();
        if (context == null) throw new FileNotFoundException("provider has no context");
        File apk = new File(context.getCacheDir(), FILE_NAME);
        if (!apk.isFile()) throw new FileNotFoundException("no downloaded update");
        return apk;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // Read-only regardless of what was asked for: nothing has any business
        // writing through this provider.
        return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File apk;
        try {
            apk = file();
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection == null
                ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) row[index] = FILE_NAME;
            else if (OpenableColumns.SIZE.equals(columns[index])) row[index] = apk.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("this provider only reads one file");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("this provider only reads one file");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("this provider only reads one file");
    }
}
