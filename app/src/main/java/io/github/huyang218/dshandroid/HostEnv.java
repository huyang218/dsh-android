package io.github.huyang218.dshandroid;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Environment variables the host process is started with, and the screen that
 * fills them in.
 *
 * <p><b>Why environment variables at all.</b> dsh resolves a plugin's key
 * through the credentials service first and the launch environment second. The
 * credentials service has exactly one writer in this client — the Models page,
 * which knows about model providers and nothing else — and the Plugins page
 * renders a FIXED list of sections compiled into
 * `dsh-client-ui-settings-plugins`, so a third-party plugin cannot present a
 * form there at all. The launch environment is the one door left open, and this
 * app owns it: it is the process that spawns the host.
 *
 * <p><b>Stored in the clear, deliberately.</b> This file sits in app-private
 * storage with 0600, which is the same protection dsh's own credential store
 * and every session log on this device already have. Encrypting only this file
 * with an Android Keystore key would raise the security of the whole app by
 * nothing while suggesting otherwise. Keystore-backed storage is a milestone-4
 * item precisely because it has to cover all of it, not one file.
 */
final class HostEnv {

    /** Where the values live; `KEY=value`, one per line, app-private. */
    private static final String FILE_NAME = "host-env.properties";

    /**
     * The variables this app offers to fill in, in display order.
     *
     * <p>A fixed list rather than a free-form editor: every one of these is a
     * name some plugin already looks for, and a typo in a free-form key would
     * produce a credential that silently never resolves.
     */
    static final String[][] KNOWN = {
        { "TENCENTCLOUD_SECRET_ID", "腾讯云 SecretId" },
        { "TENCENTCLOUD_SECRET_KEY", "腾讯云 SecretKey" },
        { "DEEPSEEK_API_KEY", "DeepSeek API Key" },
    };

    private HostEnv() {}

    static File file(Context c) {
        return new File(Runtime.files(c), FILE_NAME);
    }

    /**
     * Everything stored, or an empty map when nothing has been saved yet.
     * @param c - any context.
     * @return name to value.
     */
    static Map<String, String> load(Context c) {
        Map<String, String> values = new LinkedHashMap<>();
        File source = file(c);
        if (!source.isFile()) return values;
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(source)) {
            properties.load(in);
        } catch (IOException e) {
            return values;
        }
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            if (value != null && !value.isEmpty()) values.put(key, value);
        }
        return values;
    }

    /**
     * Replace the stored set. An empty value drops the variable rather than
     * exporting an empty string — dsh treats an empty credential as present,
     * and the resulting failure is a confusing one.
     * @param c - any context.
     * @param values - the complete set to keep.
     * @throws IOException when the file cannot be written.
     */
    static void save(Context c, Map<String, String> values) throws IOException {
        Properties properties = new Properties();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!value.isEmpty()) properties.setProperty(entry.getKey(), value);
        }
        File target = file(c);
        try (OutputStream out = new FileOutputStream(target)) {
            properties.store(out, "dsh host environment — written by the app, read by NodeService");
        }
        // Owner-only, matching the rest of the data directory. setReadable's
        // `ownerOnly` first clears the world bits, so the order matters.
        if (!target.setReadable(false, false) || !target.setReadable(true, true)) {
            throw new IOException("cannot restrict " + target);
        }
        if (!target.setWritable(false, false) || !target.setWritable(true, true)) {
            throw new IOException("cannot restrict " + target);
        }
    }
}
