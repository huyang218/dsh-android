package io.github.huyang218.dshandroid;

import android.content.Context;

import java.io.File;

/**
 * Where the on-device runtime lives.
 *
 * <p>One place decides the layout, because two things must agree on it: the
 * service that execs Node, and whatever provisions the files. Today
 * provisioning is done from the build machine with adb (see PLAN.md 线 A);
 * when the runtime ships inside the apk the unpacker will target these same
 * paths and nothing else needs to change.
 *
 * <p>Everything sits under {@code getFilesDir()} — the app's private data
 * directory. That is not incidental: it is the one place this app both owns
 * and may execute from at targetSdk 28.
 */
final class Runtime {

    private Runtime() {}

    /** The app's private data root. */
    static File files(Context c) {
        return c.getFilesDir();
    }

    /** Node's own tree: {@code node/bin/node} plus {@code node/lib/*.so}. */
    static File nodeDir(Context c) {
        return new File(files(c), "node");
    }

    static File nodeBinary(Context c) {
        return new File(nodeDir(c), "bin/node");
    }

    /** Shared objects Node links against, found through LD_LIBRARY_PATH. */
    static File libDir(Context c) {
        return new File(nodeDir(c), "lib");
    }

    /** The dsh runtime snapshot (what dsh-desktop calls a seed). */
    static File runtimeDir(Context c) {
        return new File(files(c), "runtime");
    }

    static File dshBin(Context c) {
        return new File(runtimeDir(c), "node_modules/@deepseek-ai/dsh/lib/bin.js");
    }

    /** DSH_HOME: profiles, sessions, storages — the user's data. */
    static File dshHome(Context c) {
        return new File(files(c), "dsh-home");
    }

    /** Host stdout/stderr, the first place to look when the WebView stays blank. */
    static File logFile(Context c) {
        return new File(files(c), "dsh-host.log");
    }

    /** True when provisioning has put a runnable tree in place. */
    static boolean isProvisioned(Context c) {
        return nodeBinary(c).exists() && dshBin(c).exists();
    }
}
