package io.github.huyang218.dshandroid;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Fetching a published release and handing it to the system installer.
 *
 * <p><b>This app never installs anything.</b> It downloads a file and opens
 * the platform's package installer, which shows its own confirmation. That is
 * not a formality worked around — with GitHub-direct distribution the install
 * prompt is the one moment the person is told what is about to happen, and it
 * is also where Android checks the two things that actually protect the
 * device:
 *
 * <ul>
 *   <li>the apk must be signed with the SAME key as the installed app, so a
 *       tampered build cannot replace this one; and
 *   <li>its versionCode must not be lower than the installed one, so a
 *       downgrade cannot be smuggled in as an update.
 * </ul>
 *
 * <p><b>What the SHA-256 check is and is not for.</b> When a release publishes
 * `SHA256SUMS`, the download is verified against it. That catches a truncated
 * or corrupted transfer — not a malicious one, because both the apk and the
 * sums come from the same place. The signature check above is what covers
 * that, and it belongs to the OS, not to this file. A release without a sums
 * file is still offered; refusing it would only mean older releases could
 * never be updated from, while adding no protection the signature does not
 * already give.
 *
 * <p><b>Downloaded into app-private storage.</b> `getCacheDir()` rather than a
 * shared directory: nothing else needs the file, and the installer reaches it
 * through the one-shot read grant on {@link UpdateProvider} rather than
 * through a world-readable path.
 */
final class Updater {

    /** Request code for the install intent; the result is informational only. */
    static final int INSTALL_REQUEST = 2;

    /** Give up on a stalled connection rather than holding a thread forever. */
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    /** GitHub caps unauthenticated API calls; a User-Agent is required or it answers 403. */
    private static final String USER_AGENT = "dsh-android-updater";

    /** A sums file is a few hundred bytes; anything larger is not one. */
    private static final int MAX_SUMS_BYTES = 64 * 1024;

    private Updater() {}

    /** Something went wrong far enough along that the person should be told. */
    static final class UpdateError extends IOException {
        UpdateError(String message) {
            super(message);
        }
    }

    /** Reports download progress to the UI; called off the main thread. */
    interface Progress {
        /**
         * @param read - bytes fetched so far.
         * @param total - the expected total, or 0 when the server did not say.
         */
        void onProgress(long read, long total);
    }

    /**
     * Ask GitHub what the latest release is.
     *
     * @param releasesUrl - the API endpoint (overridable so a stub can be pointed at).
     * @param installedVersion - this build's versionName.
     * @return the release to offer, or null when there is nothing to offer.
     * @throws IOException when the request fails; the caller stays quiet about it.
     */
    static UpdateCheck.Available check(String releasesUrl, String installedVersion) throws IOException {
        String body = new String(fetch(releasesUrl, MAX_SUMS_BYTES * 8, null), "UTF-8");
        try {
            return UpdateCheck.parseLatest(body, installedVersion);
        } catch (org.json.JSONException e) {
            throw new UpdateError("release listing was not in the expected shape: " + e.getMessage());
        }
    }

    /**
     * Download the apk, verify it when the release published a digest, and
     * leave it in the cache directory.
     *
     * @param cacheDir - where to write.
     * @param available - the release being installed.
     * @param progress - progress sink, or null.
     * @return the downloaded file.
     * @throws IOException when the download or the verification fails.
     */
    static File download(File cacheDir, UpdateCheck.Available available, Progress progress) throws IOException {
        File target = new File(cacheDir, UpdateProvider.FILE_NAME);
        // A partial file from a previous attempt must never be installed, so
        // the download starts from nothing every time rather than resuming.
        if (target.exists() && !target.delete()) throw new UpdateError("cannot clear " + target);

        String expected = null;
        if (available.sumsUrl != null) {
            String sums = new String(fetch(available.sumsUrl, MAX_SUMS_BYTES, null), "UTF-8");
            expected = UpdateCheck.digestFor(sums, available.apkName);
        }

        MessageDigest digest = expected == null ? null : sha256();
        HttpURLConnection connection = open(available.apkUrl);
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            long total = available.bytes > 0 ? available.bytes : connection.getContentLength();
            byte[] buffer = new byte[64 * 1024];
            long read = 0;
            for (int count; (count = in.read(buffer)) != -1; ) {
                if (count == 0) continue;
                out.write(buffer, 0, count);
                if (digest != null) digest.update(buffer, 0, count);
                read += count;
                if (progress != null) progress.onProgress(read, total);
            }
        } finally {
            connection.disconnect();
        }

        if (digest != null) {
            String actual = hex(digest.digest());
            if (!actual.equals(expected)) {
                // Leaving a file that failed its check on disk is how it ends
                // up installed by something else later.
                target.delete();
                throw new UpdateError("downloaded apk does not match the published SHA-256");
            }
        }
        return target;
    }

    /**
     * Hand the file to the platform installer.
     *
     * <p>Through {@link UpdateProvider}: a `file:` URI has been refused since
     * targetSdk 24, and this app targets 28. The read grant is what lets the
     * installer — a different process — open a file in app-private storage.
     *
     * @param activity - the activity to start the installer from.
     * @param apk - the verified download; its location is fixed, so the URI
     *     names it rather than being derived from the path.
     */
    static void install(Activity activity, File apk) {
        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(activity.getPackageName() + ".updates")
                .appendPath(UpdateProvider.FILE_NAME)
                .build();
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    /**
     * Whether this device will let the app start an install at all.
     *
     * <p>From API 26 the "install unknown apps" switch is per-app and off by
     * default. Starting the installer without it fails with nothing on screen
     * that explains why, so the caller checks first and says so instead.
     *
     * @param activity - any activity.
     * @return true when an install may be started.
     */
    static boolean canRequestInstall(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    /** The settings screen carrying that per-app switch. */
    static Intent installPermissionSettings(Activity activity) {
        return new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
    }

    /**
     * GET a URL into memory, refusing anything larger than the cap.
     * @param url - what to fetch.
     * @param cap - maximum bytes to accept.
     * @param progress - unused for small bodies; kept for symmetry.
     * @return the body.
     * @throws IOException when the request fails or the body is too large.
     */
    private static byte[] fetch(String url, int cap, Progress progress) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, count);
                if (out.size() > cap) throw new UpdateError("response from " + url + " is larger than expected");
                if (progress != null) progress.onProgress(out.size(), 0);
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Open a connection, following GitHub's redirect to its asset host.
     *
     * <p>`HttpURLConnection` follows redirects only within the same protocol,
     * and an asset download is https to https, so the default is enough. A
     * redirect that tried to leave https would stop here rather than silently
     * downgrading.
     */
    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new UpdateError("HTTP " + status + " from " + url);
        }
        return connection;
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new UpdateError("this device has no SHA-256");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >> 4) & 0xf, 16));
            text.append(Character.forDigit(value & 0xf, 16));
        }
        return text.toString();
    }
}
