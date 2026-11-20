package local.metrostep.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class CaptureProvider extends ContentProvider {
    static final String AUTHORITY = "local.metrostep.android.capture";
    private static final String DIRECTORY = "station-photos";

    static Uri create(Context context) throws IOException {
        File root = new File(context.getCacheDir(), DIRECTORY);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Unavailable photo directory");
        String filename = UUID.randomUUID().toString() + ".jpg";
        File photo = new File(root, filename);
        if (!photo.createNewFile()) throw new IOException("Unable to create photo");
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("photo").appendPath(filename).build();
    }

    static void clearExpired(Context context) {
        File[] photos = new File(context.getCacheDir(), DIRECTORY).listFiles();
        if (photos == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File photo : photos) {
            if (photo.isFile() && photo.lastModified() < cutoff) photo.delete();
        }
    }

    static boolean hasContent(Context context, Uri address) {
        try {
            return resolve(context, address).length() > 0;
        } catch (FileNotFoundException error) {
            return false;
        }
    }

    static void remove(Context context, Uri address) {
        try {
            resolve(context, address).delete();
        } catch (FileNotFoundException ignored) {
            // The temporary image is already gone.
        }
    }

    private static File resolve(Context context, Uri address) throws FileNotFoundException {
        List<String> path = address.getPathSegments();
        if (!AUTHORITY.equals(address.getAuthority()) || path.size() != 2 || !"photo".equals(path.get(0))
                || !path.get(1).matches("[a-f0-9-]{36}\\.jpg")) {
            throw new FileNotFoundException();
        }
        File photo = new File(new File(context.getCacheDir(), DIRECTORY), path.get(1));
        if (!photo.isFile()) throw new FileNotFoundException();
        return photo;
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri address) {
        return "image/jpeg";
    }

    @Override public ParcelFileDescriptor openFile(Uri address, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(getContext(), address), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public Cursor query(Uri address, String[] projection, String selection, String[] args, String order) {
        File photo;
        try {
            photo = resolve(getContext(), address);
        } catch (FileNotFoundException error) {
            return null;
        }
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if (OpenableColumns.DISPLAY_NAME.equals(column)) values[index] = photo.getName();
            if (OpenableColumns.SIZE.equals(column)) values[index] = photo.length();
        }
        result.addRow(values);
        return result;
    }

    @Override public Uri insert(Uri address, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri address, ContentValues values, String select, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri address, String select, String[] args) { throw new UnsupportedOperationException(); }
}
