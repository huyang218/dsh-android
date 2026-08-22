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
 */
public class KeysActivity extends Activity {

    private final Map<String, EditText> fields = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.keys_title));

        Map<String, String> stored = HostEnv.load(this);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackgroundColor(color(R.color.shell_bg));
        int pad = dp(24);
        form.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setText(getString(R.string.keys_intro));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        intro.setTextColor(color(R.color.shell_text_dim));
        form.addView(intro);

        for (String[] known : HostEnv.KNOWN) {
            String name = known[0];

            TextView label = new TextView(this);
            label.setText(known[1] + "  ·  " + name);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTextColor(color(R.color.shell_text));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(20);
            form.addView(label, labelParams);

            EditText field = new EditText(this);
            field.setSingleLine(true);
            // A password field for a value the person is pasting, not typing:
            // visible enough to check with the reveal toggle, hidden from
            // anyone reading over a shoulder.
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setText(stored.getOrDefault(name, ""));
            field.setTextColor(color(R.color.shell_text));
            field.setMinHeight(dp(44));
            form.addView(field);
            fields.put(name, field);
        }

        Button save = new Button(this);
        save.setText(getString(R.string.keys_save));
        save.setOnClickListener(v -> saveAndRestart());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp(28);
        form.addView(save, saveParams);

        TextView note = new TextView(this);
        note.setText(getString(R.string.keys_storage_note));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(color(R.color.shell_text_dim));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(24);
        form.addView(note, noteParams);

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(color(R.color.shell_bg));
        scroller.addView(form);
        setContentView(scroller);
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
