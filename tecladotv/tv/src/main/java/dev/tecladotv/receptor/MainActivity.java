package dev.tecladotv.receptor;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

/** Pantalla de la TV: muestra la IP y ayuda a activar el teclado. */
public class MainActivity extends Activity {

    private TextView ipText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ipText = findViewById(R.id.ip);
        statusText = findViewById(R.id.status);

        findViewById(R.id.btn_enable).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openKeyboardSettings();
            }
        });
        findViewById(R.id.btn_select).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Al cerrar el selector de teclado volvemos a tener el foco: actualizar estado.
        if (hasFocus) refresh();
    }

    private void refresh() {
        ipText.setText("IP de esta TV: " + NetUtil.localIpText() + "   (puerto " + NetUtil.PORT + ")");
        boolean enabled = isEnabled();
        boolean selected = isSelected();
        String s;
        if (selected) {
            s = "✅ Todo listo: «Teclado del móvil» es el teclado actual.";
        } else if (enabled) {
            s = "⚠ Activado, pero falta elegirlo: pulsa «2. Elegir como teclado».";
        } else {
            s = "❌ Aún no está activado: pulsa «1. Activar teclado».";
        }
        statusText.setText(s);
    }

    private boolean isEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (getPackageName().equals(info.getPackageName())) return true;
        }
        return false;
    }

    private boolean isSelected() {
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.startsWith(getPackageName() + "/");
    }

    private void openKeyboardSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }
}
