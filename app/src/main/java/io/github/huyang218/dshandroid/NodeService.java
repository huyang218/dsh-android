package io.github.huyang218.dshandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owns the dsh host: a Node process running on this device, serving the client
 * over loopback.
 *
 * <p>This is the whole point of the project. There is no server on another
 * machine and no remote host to reach — the process this service starts is the
 * host, its port is bound to 127.0.0.1, and nothing it serves leaves the
 * device. In airplane mode the model is unreachable but the app, the sessions
 * and the files are still here.
 *
 * <p>A foreground service rather than a thread or a bare process: Android kills
 * background processes, and a long agent turn outliving the Activity is the
 * normal case, not the exception. The notification is the price of that
 * guarantee and also the honest disclosure that something is running.
 *
 * <p>Process ownership follows dsh-desktop's answer to the same problem
 * (`src/server.js`): whoever starts the tree destroys the tree. Android has no
 * process groups we can signal from Java, so the child is killed through the
 * {@link Process} handle and the service refuses to leave one behind.
 */
public class NodeService extends Service {

    private static final String TAG = "dsh.node";
    private static final String CHANNEL_ID = "dsh-host";
    private static final int NOTIFICATION_ID = 1;

    /** Loopback port the host binds. Matches {@link MainActivity#HOST_URL}. */
    public static final int PORT = 3080;

    private volatile Process node;
    private Thread reaper;
    private Thread starter;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, notification("dsh host starting…"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (node == null && starter == null) {
            // Off the main thread: first launch unpacks ~250 MB out of the apk
            // before there is anything to spawn, and doing that inline would
            // hold the main thread long past an ANR.
            starter = new Thread(this::bringUp, "dsh-host-start");
            starter.start();
        }
        // Not START_STICKY: a restart with a null Intent would have to re-derive
        // the runtime layout, and a host that died is a fact the UI should see
        // rather than something silently papered over.
        return START_NOT_STICKY;
    }

    /**
     * Get from "an apk is installed" to "a host is listening", in order.
     *
     * <p>The unpack step is skipped on every launch but the first after an
     * install or an update — {@link RuntimeInstaller#isCurrent} compares the
     * stamp the apk carries against the one the data directory was left with.
     */
    private void bringUp() {
        try {
            if (!RuntimeInstaller.isCurrent(this)) {
                Log.i(TAG, "unpacking the runtime this apk carries");
                RuntimeInstaller.install(this, this::notifyNow);
            }
            node = spawn();
            watch(node);
        } catch (IOException e) {
            Log.e(TAG, "failed to start the dsh host", e);
            notifyNow("dsh host failed to start: " + e.getMessage());
            stopSelf();
        }
    }

    /**
     * Start Node on the runtime laid out under this app's files directory.
     *
     * <p>Everything here is inside the app's own data directory, which is what
     * makes the shape legal: at targetSdk 28 a binary there is executable, so
     * the host is an ordinary child process rather than a library embedded
     * through an ~embedder API.
     */
    private Process spawn() throws IOException {
        File files = Runtime.files(this);
        File node = Runtime.nodeBinary(this);
        if (!node.canExecute() && !node.setExecutable(true, true)) {
            throw new IOException("node binary is not executable: " + node);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(node.getAbsolutePath());
        // TODO(line A): drop this flag. The dsh CLI mounts an anonymous
        // cordis-plugin-hmr to watch the user patch file, and without
        // node-addon-require-builtin (no bionic prebuild) that plugin demands
        // --expose-internals or throws at boot. Nobody hand-edits
        // cordis.patch.yml on a phone, so the app should boot the profile
        // without the CLI's watcher instead of shipping this flag.
        cmd.add("--expose-internals");
        cmd.add(Runtime.dshBin(this).getAbsolutePath());
        cmd.add("--profile");
        cmd.add("handheld");
        cmd.add("--port");
        cmd.add(String.valueOf(PORT));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Runtime.runtimeDir(this));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(Runtime.logFile(this)));

        Map<String, String> env = pb.environment();
        // The Node build carries a DT_RUNPATH pointing at the prefix it was
        // compiled for. bionic's linker consults LD_LIBRARY_PATH first, which
        // is what lets the same binary run from our own directory layout.
        env.put("LD_LIBRARY_PATH", Runtime.libDir(this).getAbsolutePath());
        env.put("DSH_HOME", Runtime.dshHome(this).getAbsolutePath());
        env.put("HOME", files.getAbsolutePath());
        // Node writes here; the app cache directory is the only temp this
        // process is allowed to assume, and the system may reclaim it.
        env.put("TMPDIR", getCacheDir().getAbsolutePath());

        Log.i(TAG, "starting host: " + cmd);
        return pb.start();
    }

    /** Surface an exit in the notification rather than dying silently. */
    private void watch(final Process p) {
        reaper = new Thread(() -> {
            try {
                int code = p.waitFor();
                Log.w(TAG, "dsh host exited with " + code);
                notifyNow("dsh host stopped (exit " + code + ")");
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "dsh-host-reaper");
        reaper.start();
    }

    @Override
    public void onDestroy() {
        if (starter != null) starter.interrupt();
        if (reaper != null) reaper.interrupt();
        if (node != null) {
            // The tree must not outlive the service that owns it.
            node.destroy();
            node = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void notifyNow(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "dsh host", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("The agent runtime running on this device.");
            nm.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("dsh")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
