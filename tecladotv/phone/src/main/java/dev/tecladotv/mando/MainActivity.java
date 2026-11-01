package dev.tecladotv.mando;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity implements TvConnection.Listener, Discovery.Listener {

    private static final String PREFS = "tecladotv";
    private static final String KEY_HOST = "host";
    private static final String KEY_NAME = "name";
    private static final String KEY_LETTER = "letter";

    private TvConnection conn;
    private Discovery discovery;
    private SharedPreferences prefs;

    private TextView status;
    private TextView fieldInfo;
    private TextView scanStatus;
    private EditText input;
    private EditText manualIp;
    private View connectPanel;
    private LinearLayout deviceList;
    private CheckBox letterMode;

    /** Lo que creemos que hay escrito en el campo de la TV (siempre igual a {@link #input}). */
    private String mirror = "";
    private boolean suppressWatcher;
    private long lastLocalEdit;
    private String tvName;
    private final Set<String> foundHosts = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        status = findViewById(R.id.status);
        fieldInfo = findViewById(R.id.field_info);
        scanStatus = findViewById(R.id.scan_status);
        input = findViewById(R.id.input);
        manualIp = findViewById(R.id.manual_ip);
        connectPanel = findViewById(R.id.connect_panel);
        deviceList = findViewById(R.id.device_list);
        letterMode = findViewById(R.id.letter_mode);

        conn = new TvConnection(this);
        discovery = new Discovery(this, this);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppressWatcher) onLocalEdit(s.toString());
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                // Con teclado físico llega pulsar y soltar: enviar solo una vez.
                if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) sendEnter();
                return true; // mantener el teclado del móvil abierto
            }
        });

        click(R.id.btn_del, new Runnable() {
            public void run() {
                deleteOne();
            }
        });
        click(R.id.btn_clear, new Runnable() {
            public void run() {
                clearAll();
            }
        });
        click(R.id.btn_enter, new Runnable() {
            public void run() {
                sendEnter();
            }
        });
        click(R.id.btn_switch, new Runnable() {
            public void run() {
                if (!conn.send(TvConnection.cmd("switchIme"))) notConnected();
            }
        });
        click(R.id.btn_change, new Runnable() {
            public void run() {
                if (connectPanel.getVisibility() == View.VISIBLE) {
                    connectPanel.setVisibility(View.GONE);
                    discovery.stop();
                } else {
                    showConnectPanel();
                }
            }
        });
        click(R.id.btn_scan, new Runnable() {
            public void run() {
                startDiscovery();
            }
        });
        click(R.id.btn_connect, new Runnable() {
            public void run() {
                connectManual();
            }
        });
        manualIp.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                connectManual();
                return true;
            }
        });
        keyButton(R.id.btn_left, KeyEvent.KEYCODE_DPAD_LEFT);
        keyButton(R.id.btn_right, KeyEvent.KEYCODE_DPAD_RIGHT);
        keyButton(R.id.btn_up, KeyEvent.KEYCODE_DPAD_UP);
        keyButton(R.id.btn_down, KeyEvent.KEYCODE_DPAD_DOWN);
        keyButton(R.id.btn_ok, KeyEvent.KEYCODE_DPAD_CENTER);

        letterMode.setChecked(prefs.getBoolean(KEY_LETTER, false));
        applyInputType();
        letterMode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(KEY_LETTER, checked).apply();
                applyInputType();
            }
        });

        String host = prefs.getString(KEY_HOST, null);
        tvName = prefs.getString(KEY_NAME, null);
        if (host != null) {
            conn.connect(host);
        } else {
            showConnectPanel();
        }
    }

    @Override
    protected void onDestroy() {
        discovery.stop();
        conn.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ escribir

    /**
     * Cada vez que cambia el texto del móvil se calcula la diferencia con lo que ya hay
     * en la TV y se envía solo eso: N borrados + el texto nuevo. Así funciona igual
     * con autocorrector, sugerencias, dictado o si se mueve el cursor.
     */
    private void onLocalEdit(String now) {
        String old = mirror;
        int p = commonPrefix(old, now);
        String removed = old.substring(p);
        String added = now.substring(p);
        mirror = now;
        lastLocalEdit = SystemClock.uptimeMillis();
        if (removed.isEmpty() && added.isEmpty()) return;

        boolean ok = true;
        if (!removed.isEmpty()) {
            JSONObject c = TvConnection.cmd("del");
            put(c, "n", removed.codePointCount(0, removed.length()));
            ok = conn.send(c);
        }
        if (!added.isEmpty()) {
            JSONObject c = TvConnection.cmd("text");
            put(c, "s", added);
            ok &= conn.send(c);
        }
        if (!ok) notConnected();
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        // No partir un emoji (par sustituto) por la mitad.
        if (i > 0 && Character.isHighSurrogate(a.charAt(i - 1))) i--;
        return i;
    }

    private void deleteOne() {
        Editable e = input.getText();
        if (e.length() > 0) {
            int end = e.length();
            int start = Character.offsetByCodePoints(e, end, -1);
            e.delete(start, end); // el watcher envía el borrado
            input.setSelection(e.length());
        } else {
            // El campo de la TV puede tener texto que no conocemos: borrar igualmente.
            JSONObject c = TvConnection.cmd("del");
            put(c, "n", 1);
            if (!conn.send(c)) notConnected();
        }
    }

    private void clearAll() {
        if (!conn.send(TvConnection.cmd("clear"))) notConnected();
        setMirror("");
    }

    private void sendEnter() {
        if (!conn.send(TvConnection.cmd("enter"))) notConnected();
    }

    private void sendKey(int keyCode) {
        JSONObject c = TvConnection.cmd("key");
        put(c, "k", keyCode);
        if (!conn.send(c)) notConnected();
    }

    /** Cambia el texto del móvil sin enviar nada a la TV. */
    private void setMirror(String text) {
        mirror = text;
        if (!text.equals(input.getText().toString())) {
            suppressWatcher = true;
            input.setText(text);
            input.setSelection(input.getText().length());
            suppressWatcher = false;
        }
    }

    private void applyInputType() {
        int type = InputType.TYPE_CLASS_TEXT;
        if (letterMode.isChecked()) {
            // Los teclados del móvil envían cada letra al momento, sin "componer" palabras.
            type |= InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
        suppressWatcher = true;
        input.setInputType(type);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setSelection(input.getText().length());
        suppressWatcher = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.restartInput(input);
    }

    private void showKeyboard() {
        input.requestFocus();
        if (!hasWindowFocus()) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    // ------------------------------------------------------------------ mensajes de la TV

    @Override
    public void onTvMessage(JSONObject m) {
        String e = m.optString("e");
        switch (e) {
            case "hello":
                tvName = m.optString("name", "TV");
                prefs.edit().putString(KEY_NAME, tvName).apply();
                updateStatus();
                break;
            case "focus": {
                String label = m.isNull("label") ? null : m.optString("label");
                String app = m.isNull("app") ? null : m.optString("app");
                String where = label != null && !label.isEmpty() ? "«" + label + "»" : "un campo de texto";
                if (app != null && !app.isEmpty()) where += " (" + app + ")";
                if (m.optBoolean("password")) where += " — contraseña";
                fieldInfo.setText("✏ La TV está escribiendo en " + where);
                setMirror(m.isNull("text") ? "" : m.optString("text"));
                showKeyboard();
                break;
            }
            case "text":
                // Solo aceptar el texto real de la TV si ya le llegó todo lo que escribimos
                // y no estamos tecleando en este momento (evita "pisar" lo que escribes).
                if (!m.isNull("text") && m.optInt("q", -1) == conn.lastSeq()
                        && SystemClock.uptimeMillis() - lastLocalEdit > 700) {
                    setMirror(m.optString("text"));
                }
                break;
            case "blur":
                fieldInfo.setText("La TV no tiene ningún campo de texto seleccionado.");
                break;
            case "nofield":
                fieldInfo.setText("⚠ Selecciona con el mando un campo de texto en la TV.");
                break;
            default:
                break;
        }
    }

    @Override
    public void onConnectionState(int state, String host) {
        updateStatus();
        if (state == TvConnection.CONNECTED) {
            connectPanel.setVisibility(View.GONE);
            discovery.stop();
            fieldInfo.setText("Selecciona un campo de texto en la TV y escribe aquí.");
        }
    }

    private void updateStatus() {
        String name = tvName != null ? tvName : conn.host();
        switch (conn.state()) {
            case TvConnection.CONNECTED:
                status.setText("✅ Conectado a " + name);
                status.setTextColor(0xFF2E7D32);
                break;
            case TvConnection.CONNECTING:
                status.setText("… Conectando a " + name);
                status.setTextColor(0xFF616161);
                break;
            default:
                status.setText(conn.host() == null ? "Sin TV elegida" : "❌ Sin conexión con " + name + " — reintentando");
                status.setTextColor(0xFFC62828);
                break;
        }
    }

    private void notConnected() {
        if (conn.host() == null) {
            showConnectPanel();
        } else {
            fieldInfo.setText("⚠ No hay conexión con la TV. Comprueba que está encendida, en la misma Wi-Fi "
                    + "y con «Teclado del móvil» elegido como teclado.");
        }
    }

    // ------------------------------------------------------------------ elegir TV

    private void showConnectPanel() {
        connectPanel.setVisibility(View.VISIBLE);
        startDiscovery();
    }

    private void startDiscovery() {
        foundHosts.clear();
        deviceList.removeAllViews();
        scanStatus.setText("Buscando televisores en tu Wi-Fi…");
        discovery.start();
    }

    @Override
    public void onTvFound(final String name, final String host) {
        if (!foundHosts.add(host)) return;
        Button b = new Button(this);
        b.setText("📺 " + name + "  (" + host + ")");
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectTo(host, name);
            }
        });
        deviceList.addView(b, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scanStatus.setText("Toca tu TV:");
    }

    @Override
    public void onScanFinished() {
        if (foundHosts.isEmpty()) {
            scanStatus.setText("No se ha encontrado ninguna TV. En la TV, abre «Teclado TV Receptor» y "
                    + "comprueba que «Teclado del móvil» está elegido como teclado. Ambos deben estar en la misma Wi-Fi.");
        }
    }

    private void connectManual() {
        String ip = manualIp.getText().toString().trim();
        if (ip.isEmpty()) return;
        connectTo(ip, null);
    }

    private void connectTo(String host, String name) {
        tvName = name;
        SharedPreferences.Editor ed = prefs.edit().putString(KEY_HOST, host);
        if (name != null) ed.putString(KEY_NAME, name);
        else ed.remove(KEY_NAME);
        ed.apply();
        discovery.stop();
        connectPanel.setVisibility(View.GONE);
        conn.connect(host);
        updateStatus();
        input.requestFocus();
    }

    // ------------------------------------------------------------------ utilidades

    private void click(int id, final Runnable r) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
    }

    private void keyButton(int id, final int keyCode) {
        click(id, new Runnable() {
            public void run() {
                sendKey(keyCode);
            }
        });
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }
}
