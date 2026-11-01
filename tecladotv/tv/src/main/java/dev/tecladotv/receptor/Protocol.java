package dev.tecladotv.receptor;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Mensajes (una línea JSON cada uno).
 * Móvil → TV: {"c":"text","s":"hola","q":12}, {"c":"del","n":2}, {"c":"enter"}, {"c":"clear"},
 *             {"c":"key","k":19}, {"c":"switchIme"}, {"c":"hello"}, {"c":"ping"}
 * TV → móvil: {"e":"hello","name":".."}, {"e":"focus","text":"..","label":".."}, {"e":"blur"},
 *             {"e":"text","text":"..","q":12}, {"e":"nofield"}, {"e":"pong"}
 */
final class Protocol {
    private Protocol() {}

    static JSONObject event(String name) {
        JSONObject o = new JSONObject();
        put(o, "e", name);
        return o;
    }

    static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v == null ? JSONObject.NULL : v);
        } catch (JSONException ignored) {
        }
        return o;
    }
}
