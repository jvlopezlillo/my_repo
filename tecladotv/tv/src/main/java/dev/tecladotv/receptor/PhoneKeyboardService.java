package dev.tecladotv.receptor;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Teclado (método de entrada) de la TV. No dibuja teclas: recibe por Wi-Fi lo que
 * se escribe en el móvil y lo inserta directamente en el campo de texto que tiene
 * el foco. Al ser el propio teclado del sistema, no depende de que la app de la TV
 * colabore con ningún "servicio de mando a distancia".
 */
public class PhoneKeyboardService extends InputMethodService implements BridgeServer.Callback {

    private final Handler main = new Handler(Looper.getMainLooper());
    private BridgeServer server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;
    private TextView bar;
    private boolean fieldActive;

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            sendTextSync();
        }
    };

    private final Runnable focusRunnable = new Runnable() {
        @Override
        public void run() {
            sendFocus();
        }
    };

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    public void onCreate() {
        super.onCreate();
        server = new BridgeServer(NetUtil.PORT, this, main);
        server.start();
        registerNsd();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        unregisterNsd();
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        bar = (TextView) getLayoutInflater().inflate(R.layout.keyboard_bar, null);
        updateBar();
        return bar;
    }

    /** Nunca pantalla completa: la barra es pequeña y deja ver la app. */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    /** Mostrar siempre la barra, aunque Android crea que hay un teclado físico (el mando). */
    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        fieldActive = attribute != null && attribute.inputType != InputType.TYPE_NULL;
        main.removeCallbacks(focusRunnable);
        if (!fieldActive) return;
        if (restarting) {
            // Mismo campo reiniciado por la app: solo resincronizar el texto.
            scheduleSync();
        } else {
            main.postDelayed(focusRunnable, 80);
        }
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        if (fieldActive) {
            fieldActive = false;
            main.removeCallbacks(focusRunnable);
            if (server != null) server.broadcast(Protocol.event("blur"));
        }
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        scheduleSync();
    }

    /** Botón Menú (≡) del mando mientras se ve la barra: volver al teclado anterior. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && isInputViewShown()) {
            switchToPreviousKeyboard();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- red

    @Override
    public void onClientConnected(BridgeServer.Client client) {
        JSONObject hello = Protocol.event("hello");
        Protocol.put(hello, "name", Build.MODEL);
        client.send(hello);
        if (fieldActive) client.send(buildFocusEvent());
    }

    @Override
    public void onClientsChanged(int count) {
        updateBar();
    }

    @Override
    public void onCommand(BridgeServer.Client client, JSONObject cmd) {
        int q = cmd.optInt("q", -1);
        if (q >= 0) client.lastSeq = q;

        String c = cmd.optString("c");
        if ("hello".equals(c)) return;
        if ("switchIme".equals(c)) {
            switchToPreviousKeyboard();
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            client.send(Protocol.event("nofield"));
            return;
        }

        switch (c) {
            case "text":
                typeText(ic, cmd.optString("s"));
                break;
            case "del":
                int n = Math.max(1, Math.min(cmd.optInt("n", 1), 5000));
                for (int i = 0; i < n; i++) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                break;
            case "enter":
                pressEnter(ic);
                break;
            case "clear":
                clearField(ic);
                break;
            case "key":
                int k = cmd.optInt("k", 0);
                if (k > 0) sendDownUpKeyEvents(k);
                break;
            default:
                return;
        }
        if (!fieldActive && !"key".equals(c)) client.send(Protocol.event("nofield"));
    }

    // ---------------------------------------------------------------- escribir

    private void typeText(InputConnection ic, String s) {
        if (s.isEmpty()) return;
        if (fieldActive) {
            // Campo de texto normal: insertar el texto tal cual (acentos, ñ, emojis...).
            ic.commitText(s, 1);
            return;
        }
        // Vista que no es un campo de texto (inputType NULL): simular pulsaciones.
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        KeyEvent[] events = kcm.getEvents(s.toCharArray());
        if (events == null) {
            ic.commitText(s, 1);
            return;
        }
        for (KeyEvent e : events) ic.sendKeyEvent(e);
    }

    private void pressEnter(InputConnection ic) {
        EditorInfo ei = getCurrentInputEditorInfo();
        if (fieldActive && ei != null) {
            int action = ei.imeOptions & EditorInfo.IME_MASK_ACTION;
            boolean multiLine = (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
            boolean noAction = (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
            if (!multiLine && !noAction
                    && action != EditorInfo.IME_ACTION_NONE
                    && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                // Buscar / Ir / Enviar... tal como lo haría el teclado de Google.
                ic.performEditorAction(action);
                return;
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
    }

    private void clearField(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(10000, 0);
        CharSequence after = ic.getTextAfterCursor(10000, 0);
        int b = before == null ? 0 : before.length();
        int a = after == null ? 0 : after.length();
        if (b + a > 0) {
            ic.beginBatchEdit();
            ic.finishComposingText();
            ic.deleteSurroundingText(b, a);
            ic.endBatchEdit();
        }
        String left = readFieldText(ic);
        if (left == null || !left.isEmpty()) {
            // La app no ha obedecido: seleccionar todo y borrar como con una tecla.
            ic.performContextMenuAction(android.R.id.selectAll);
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        }
    }

    // ---------------------------------------------------------------- sincronizar con el móvil

    /** Texto completo del campo con el foco, o null si la app no lo deja leer. */
    private String readFieldText(InputConnection ic) {
        ExtractedTextRequest req = new ExtractedTextRequest();
        req.hintMaxChars = 10000;
        req.hintMaxLines = 100;
        ExtractedText et = ic.getExtractedText(req, 0);
        if (et != null && et.text != null) return et.text.toString();
        CharSequence b = ic.getTextBeforeCursor(10000, 0);
        CharSequence a = ic.getTextAfterCursor(10000, 0);
        if (b == null && a == null) return null;
        return (b == null ? "" : b.toString()) + (a == null ? "" : a.toString());
    }

    private JSONObject buildFocusEvent() {
        JSONObject ev = Protocol.event("focus");
        EditorInfo ei = getCurrentInputEditorInfo();
        String label = null;
        if (ei != null) {
            if (ei.hintText != null) label = ei.hintText.toString();
            else if (ei.label != null) label = ei.label.toString();
            Protocol.put(ev, "app", ei.packageName);
            Protocol.put(ev, "password", isPassword(ei.inputType));
        }
        Protocol.put(ev, "label", label);
        InputConnection ic = getCurrentInputConnection();
        String text = ic == null ? null : readFieldText(ic);
        if (ic != null && text != null) {
            // El móvil escribe siempre "al final": colocar el cursor ahí.
            ic.setSelection(text.length(), text.length());
        }
        Protocol.put(ev, "text", text);
        return ev;
    }

    private void sendFocus() {
        if (server == null || server.clientCount() == 0 || !fieldActive) return;
        server.broadcast(buildFocusEvent());
    }

    private void scheduleSync() {
        if (!fieldActive || server == null || server.clientCount() == 0) return;
        main.removeCallbacks(syncRunnable);
        main.postDelayed(syncRunnable, 350);
    }

    /** Envía el texto real del campo para que el móvil lo refleje (p. ej. si la app lo borra). */
    private void sendTextSync() {
        if (!fieldActive || server == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String text = readFieldText(ic);
        if (text == null) return;
        // Cada móvil recibe el número de su último comando aplicado, para que solo
        // acepte la sincronización si no tiene nada pendiente de llegar.
        for (BridgeServer.Client c : server.clients()) {
            JSONObject ev = Protocol.event("text");
            Protocol.put(ev, "text", text);
            Protocol.put(ev, "q", c.lastSeq);
            c.send(ev);
        }
    }

    private static boolean isPassword(int inputType) {
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        return (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    // ---------------------------------------------------------------- utilidades

    @SuppressWarnings("deprecation")
    private void switchToPreviousKeyboard() {
        boolean switched = false;
        if (Build.VERSION.SDK_INT >= 28) {
            switched = switchToPreviousInputMethod();
        } else {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            IBinder token = getWindow().getWindow() == null ? null
                    : getWindow().getWindow().getAttributes().token;
            if (imm != null && token != null) switched = imm.switchToLastInputMethod(token);
        }
        if (!switched) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        }
    }

    private void updateBar() {
        if (bar == null) return;
        int n = server == null ? 0 : server.clientCount();
        if (n > 0) {
            bar.setText("📱 Escribe desde el móvil — conectado   ·   ≡ Menú: teclado normal");
        } else {
            bar.setText("📱 Esperando al móvil… (IP " + NetUtil.localIpText() + ")   ·   ≡ Menú: teclado normal");
        }
    }

    private void registerNsd() {
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("TecladoTV " + Build.MODEL);
            info.setServiceType(NetUtil.SERVICE_TYPE);
            info.setPort(NetUtil.PORT);
            nsdListener = new NsdManager.RegistrationListener() {
                @Override
                public void onRegistrationFailed(NsdServiceInfo s, int errorCode) {
                    nsdListener = null;
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo s, int errorCode) {
                }

                @Override
                public void onServiceRegistered(NsdServiceInfo s) {
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo s) {
                }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception e) {
            nsdListener = null;
        }
    }

    private void unregisterNsd() {
        try {
            if (nsd != null && nsdListener != null) nsd.unregisterService(nsdListener);
        } catch (Exception ignored) {
        }
        nsdListener = null;
    }
}
