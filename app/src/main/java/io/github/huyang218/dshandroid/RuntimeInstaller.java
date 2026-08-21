package io.github.huyang218.dshandroid;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Unpack the runtime the apk carries into this app's data directory.
 *
 * <p>The apk cannot run anything from inside itself: an asset is a range of
 * bytes in a zip, and the loader needs real files with an executable bit. So
 * first launch copies the trees out, once, into the one directory this app both
 * owns and may execute from ({@link Runtime}).
 *
 * <p><b>Why shell out to toybox instead of parsing the archives here.</b> The
 * Node tree is 18 symlinks out of 35 entries — bionic finds
 * {@code libssl.so.3} through {@code libssl.so}, and the seed's own layout
 * leans on links too. Neither {@code adb push} nor a zip entry carries a
 * symlink, which is the trap this project already hit once (PLAN.md 线 A). A
 * tar does carry them, and every Android device ships a tar that knows how to
 * restore them (/system/bin/tar is toybox). Writing a tar parser in Java would
 * mean re-implementing symlinks, modes and GNU long names — hundreds of lines
 * whose bugs would surface as a runtime that almost works.
 *
 * <p><b>The payloads are plain tars, not tar.gz, and that is not an oversight.</b>
 * AAPT un-gzips any asset whose name ends in {@code .gz} and drops the
 * extension while packaging, so a {@code node.tar.gz} asset arrives as
 * {@code node.tar} — and opening the name we shipped fails with
 * FileNotFoundException. Since the apk deflates its own entries anyway,
 * shipping tars and letting the apk compress them costs nothing (84 MB apk for
 * 372 MB of payload) and removes a decompression layer from this code.
 *
 * <p>Idempotent by stamp: the apk carries {@code runtime/stamp}, and a matching
 * {@code .runtime-stamp} in the data directory means the trees on disk came
 * from this apk. The stamp is written LAST, so an install killed halfway is
 * re-run rather than trusted.
 */
final class RuntimeInstaller {

    private static final String TAG = "dsh.install";

    /** Asset holding the identity of the payloads below. */
    private static final String STAMP_ASSET = "runtime/stamp";

    /** Payload assets, each a gzipped tar rooted at the data directory. */
    private static final String[] PAYLOADS = {
        "runtime/node.tar",
        "runtime/seed.tar",
        "runtime/composition.tar",
    };

    /** Where the stamp of the currently unpacked trees lives. */
    private static File stampFile(Context c) {
        return new File(Runtime.files(c), ".runtime-stamp");
    }

    private RuntimeInstaller() {}

    /** Progress sink; the service turns these into notification text. */
    interface Progress {
        void onStep(String message);
    }

    /**
     * The stamp this apk carries, or {@code null} when it carries no runtime
     * (a build made before the payloads existed, or one deliberately built
     * without them).
     * @param c - any context.
     * @return the stamp string, or null.
     */
    static String packagedStamp(Context c) {
        try (InputStream in = c.getAssets().open(STAMP_ASSET);
             Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name())) {
            return scanner.hasNextLine() ? scanner.nextLine().trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * True when the trees on disk were unpacked from this apk's payloads and
     * are still there.
     * @param c - any context.
     * @return whether {@link #install} has nothing to do.
     */
    static boolean isCurrent(Context c) {
        String packaged = packagedStamp(c);
        if (packaged == null) return Runtime.isProvisioned(c);
        return packaged.equals(readStamp(c)) && Runtime.isProvisioned(c);
    }

    private static String readStamp(Context c) {
        File stamp = stampFile(c);
        if (!stamp.isFile()) return null;
        try (Scanner scanner = new Scanner(stamp, StandardCharsets.UTF_8.name())) {
            return scanner.hasNextLine() ? scanner.nextLine().trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Unpack every payload, replacing whatever was there.
     *
     * <p>The app-owned trees are removed first so a shrinking payload cannot
     * leave a stale file behind. {@code dsh-home} is NOT removed: the user's
     * sessions, storages and settings live there, and only the two directories
     * this apk owns inside it are replaced.
     *
     * @param c - the service context.
     * @param progress - sink for the one-line status the user can see.
     * @throws IOException when a payload is missing or tar fails.
     */
    static void install(Context c, Progress progress) throws IOException {
        String packaged = packagedStamp(c);
        if (packaged == null) throw new IOException("this build carries no runtime payload");

        File files = Runtime.files(c);
        // A half-written stamp would claim a half-written runtime.
        if (!stampFile(c).delete() && stampFile(c).exists()) {
            throw new IOException("cannot clear " + stampFile(c));
        }

        progress.onStep("Clearing the previous runtime…");
        deleteTree(Runtime.nodeDir(c));
        deleteTree(Runtime.runtimeDir(c));
        deleteTree(new File(Runtime.dshHome(c), "profiles/handheld"));
        deleteTree(new File(Runtime.dshHome(c), ".agent-presets/handheld"));

        for (String payload : PAYLOADS) {
            progress.onStep("Unpacking " + payload.substring(payload.indexOf('/') + 1) + "…");
            long started = System.currentTimeMillis();
            extract(c, payload, files);
            Log.i(TAG, payload + " unpacked in " + (System.currentTimeMillis() - started) + "ms");
        }

        File node = Runtime.nodeBinary(c);
        if (!node.isFile()) throw new IOException("payload did not contain " + node);
        // tar restores the mode, but the binary being executable is the one
        // property the whole design rests on — assert it rather than assume.
        if (!node.canExecute() && !node.setExecutable(true, true)) {
            throw new IOException("cannot make " + node + " executable");
        }

        writeStamp(c, packaged);
        progress.onStep("Runtime ready");
    }

    /**
     * Stream one payload through the system tar.
     *
     * <p>tar's stdin is fed from this thread while its output goes to the host
     * log, so a chatty extraction cannot fill a pipe nobody is draining.
     */
    private static void extract(Context c, String asset, File into) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            "/system/bin/tar",
            "-x",
            // Extracting as the app uid: there is no owner to restore, and
            // trying would only produce noise.
            "-o",
            "-C", into.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(Runtime.logFile(c)));

        Process tar = pb.start();
        IOException failure = null;
        try (InputStream in = c.getAssets().open(asset, AssetManager.ACCESS_STREAMING);
             OutputStream out = tar.getOutputStream()) {
            byte[] buffer = new byte[1 << 16];
            // `!= -1`, not `> 0`: a legal read() may return 0 without being at
            // the end, and treating that as EOF stops feeding tar mid-archive
            // — which looks like a hang, not like a failure, because tar goes
            // on waiting for the rest of a stream nobody is still writing.
            for (int read; (read = in.read(buffer)) != -1; ) {
                if (read > 0) out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException e) {
            failure = e;
        }

        if (failure != null) {
            // Nothing more is coming; do not leave tar blocked on a pipe.
            tar.destroy();
        }
        int code;
        try {
            code = tar.waitFor();
        } catch (InterruptedException e) {
            tar.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while unpacking " + asset, e);
        }
        if (failure != null) throw new IOException("failed to feed " + asset + " to tar", failure);
        if (code != 0) throw new IOException("tar exited " + code + " unpacking " + asset);
    }

    private static void writeStamp(Context c, String stamp) throws IOException {
        try (OutputStream out = new java.io.FileOutputStream(stampFile(c))) {
            out.write((stamp + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Remove a tree, if it is there. Symlinks are unlinked, never followed. */
    private static void deleteTree(File root) throws IOException {
        int mode = lstatMode(root);
        if (mode == 0) return;
        if (OsConstants.S_ISDIR(mode)) {
            File[] children = root.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        if (!root.delete()) throw new IOException("cannot delete " + root);
    }

    /**
     * The file's own mode, without following a symlink — 0 when there is
     * nothing there.
     *
     * <p>{@code lstat}, not a canonical-path comparison: this app's data
     * directory is reached through {@code /data/user/0/<pkg>}, which IS a
     * symlink to {@code /data/data/<pkg>}, so comparing absolute against
     * canonical calls every single file below it a link. That mistake turned
     * the recursive delete into a single {@code delete()} on a non-empty
     * directory and failed the whole install.
     *
     * <p>Getting this right in the other direction matters just as much: the
     * runtime's own symlinks must be unlinked, never followed, or removing a
     * tree would delete what its links point at.
     */
    private static int lstatMode(File file) {
        try {
            return Os.lstat(file.getAbsolutePath()).st_mode;
        } catch (ErrnoException e) {
            return 0;
        }
    }
}
