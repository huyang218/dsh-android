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

    /** One row of the keys screen: what it is called, and where it comes from. */
    static final class Key {
        /** Environment variable name. */
        final String name;
        /** Section this row belongs under; rows sharing one are drawn together. */
        final int group;
        /** Human label. */
        final int label;
        /**
         * What filling this in turns on, and what happens if it is left empty.
         *
         * <p>This is the line that decides whether someone fills the field at
         * all. "Where to get it" cannot answer "do I need this?", and a screen
         * that only answers the second question leaves every row looking
         * equally mandatory.
         */
        final int purpose;
        /** Where the value comes from. */
        final int source;

        Key(String name, int group, int label, int purpose, int source) {
            this.name = name;
            this.group = group;
            this.label = label;
            this.purpose = purpose;
            this.source = source;
        }
    }

    /**
     * The variables this app offers to fill in, in display order.
     *
     * <p>A fixed list rather than a free-form editor: every one of these is a
     * name some plugin already looks for, and a typo in a free-form key would
     * produce a credential that silently never resolves.
     *
     * <p><b>Grouped, and each row says where its value comes from.</b> The two
     * Tencent entries are the reason. They look like one thing and are two
     * different products with two different credential systems — 联网搜索 is a
     * cloud API signed per request with a SecretId/SecretKey PAIR, TokenHub is
     * an OpenAI-compatible endpoint taking a single bearer key. Pasting one
     * into the other's field fails as an opaque 401, which is the kind of
     * mistake a screen should prevent rather than diagnose.
     */
    static final Key[] KNOWN = {
        new Key("TENCENTCLOUD_SECRET_ID", R.string.keys_group_search,
                R.string.keys_tc_id, R.string.keys_tc_purpose, R.string.keys_tc_id_source),
        new Key("TENCENTCLOUD_SECRET_KEY", R.string.keys_group_search,
                R.string.keys_tc_key, R.string.keys_tc_key_purpose, R.string.keys_tc_key_source),
        new Key("TOKENHUB_API_KEY", R.string.keys_group_models,
                R.string.keys_tokenhub, R.string.keys_tokenhub_purpose, R.string.keys_tokenhub_source),
        new Key("DEEPSEEK_API_KEY", R.string.keys_group_models,
                R.string.keys_deepseek, R.string.keys_deepseek_purpose, R.string.keys_deepseek_source),
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
