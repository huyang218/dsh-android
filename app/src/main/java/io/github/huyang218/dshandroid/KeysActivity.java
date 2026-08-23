package io.github.huyang218.dshandroid;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one screen this app has besides the WebView: the keys the host process
 * needs.
 *
 * <p>It exists because the client cannot offer it. The Plugins page renders a
 * list of sections hardcoded in `dsh-client-ui-settings-plugins`, so a
 * third-party provider — the Tencent search row this repo adds — has nowhere to
 * put a form. What it does have is the launch environment, and this app is what
 * launches the host.
 *
 * <p>Saving restarts the host. Environment is read once at spawn, so a key
 * saved without a restart would appear to do nothing until the next launch —
 * the kind of gap that gets diagnosed as "the key does not work".
 *
 * <p><b>Layout: header and footer are fixed, only the fields scroll.</b> The
 * save button used to sit below the last field inside the scroller, which
 * meant that with a keyboard up it was off-screen and reachable only by
 * knowing to scroll for it. A control the whole screen exists to reach should
 * not have to be discovered.
 */
public class KeysActivity extends Activity {

    private final Map<String, EditText> fields = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.keys_title));

        Map<String, String> stored = HostEnv.load(this);
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.shell_bg));

        // ── header, always visible ──────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(pad, pad, pad, dp(12));

        TextView title = new TextView(this);
        title.setText(getString(R.string.keys_title));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(color(R.color.shell_text));
        header.addView(title);

        TextView intro = new TextView(this);
        intro.setText(getString(R.string.keys_intro));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        intro.setTextColor(color(R.color.shell_text_dim));
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introParams.topMargin = dp(6);
        header.addView(intro, introParams);
        root.addView(header);

        // ── the fields, the only part that scrolls ─────────────────────────
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, 0, pad, pad);

        int lastGroup = 0;
        for (HostEnv.Key key : HostEnv.KNOWN) {
            if (key.group != lastGroup) {
                lastGroup = key.group;
                TextView group = new TextView(this);
                group.setText(getString(key.group));
                group.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                group.setTextColor(color(R.color.shell_accent));
                LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                groupParams.topMargin = dp(22);
                form.addView(group, groupParams);
            }

            TextView label = new TextView(this);
            label.setText(getString(key.label));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setTextColor(color(R.color.shell_text));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(16);
            form.addView(label, labelParams);

            // Two lines, in this order on purpose. What the key turns on is
            // what decides whether someone fills the field; where to get it
            // only matters once they have decided. A screen that leads with
            // the source makes every row look equally mandatory.
            TextView purpose = new TextView(this);
            purpose.setText(getString(key.purpose));
            purpose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            purpose.setTextColor(color(R.color.shell_text));
            form.addView(purpose);

            TextView source = new TextView(this);
            source.setText(getString(key.source));
            source.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            source.setTextColor(color(R.color.shell_text_dim));
            LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sourceParams.topMargin = dp(2);
            form.addView(source, sourceParams);

            EditText field = new EditText(this);
            field.setSingleLine(true);
            // A password field for a value the person is pasting, not typing:
            // visible enough to check with the reveal toggle, hidden from
            // anyone reading over a shoulder.
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setText(stored.getOrDefault(key.name, ""));
            field.setTextColor(color(R.color.shell_text));
            field.setMinHeight(dp(44));
            // The variable name is what an error message will quote, so it is
            // the placeholder rather than a third line of prose.
            field.setHint(key.name);
            field.setHintTextColor(color(R.color.shell_text_dim));
            form.addView(field);
            fields.put(key.name, field);
        }

        ScrollView scroller = new ScrollView(this);
        // Without this the form does not fill a short screen and the footer
        // floats up into the middle of the layout.
        scroller.setFillViewport(true);
        scroller.addView(form);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── footer, always reachable ───────────────────────────────────────
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(pad, dp(12), pad, dp(12));

        Button save = new Button(this);
        save.setText(getString(R.string.keys_save));
        save.setMinHeight(dp(48));
        save.setOnClickListener(v -> saveAndRestart());
        footer.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText(getString(R.string.keys_storage_note));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setTextColor(color(R.color.shell_text_dim));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(8);
        footer.addView(note, noteParams);
        root.addView(footer);

        setContentView(root);
    }

    private void saveAndRestart() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, EditText> entry : fields.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getText().toString());
        }
        try {
            HostEnv.save(this, values);
        } catch (IOException e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        // Restart rather than start: a host already running holds the old
        // environment, and nothing else would ever replace it.
        Intent service = new Intent(this, NodeService.class);
        stopService(service);
        startService(service);
        Toast.makeText(this, getString(R.string.keys_saved), Toast.LENGTH_LONG).show();
        finish();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    @SuppressWarnings("deprecation")
    private int color(int id) {
        return getResources().getColor(id);
    }
}
