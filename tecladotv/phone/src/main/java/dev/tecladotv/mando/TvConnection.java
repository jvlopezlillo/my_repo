package dev.tecladotv.mando;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Conexión TCP con la TV. Se reconecta sola si se corta.
 * Todos los métodos públicos y los avisos al Listener van en el hilo principal.
 */
final class TvConnection {

    static final int PORT = 48900;
    static final int DISCONNECTED = 0;
    static final int CONNECTING = 1;
    static final int CONNECTED = 2;

    interface Listener {
        void onConnectionState(int state, String host);

        void onTvMessage(JSONObject msg);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String POISON = "\u0000";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile int generation;
    private volatile Session session;
    private int state = DISCONNECTED;
    private int seq;
    private String host;

    private final Runnable pinger = new Runnable() {
        @Override
        public void run() {
            if (state == CONNECTED) {
                Session s = session;
                if (s != null) s.send("{\"c\":\"ping\"}\n");
            }
            main.postDelayed(this, 4000);
        }
    };

    TvConnection(Listener listener) {
        this.listener = listener;
        main.postDelayed(pinger, 4000);
    }

    int state() {
        return state;
    }

    String host() {
        return host;
    }

    /** Número del último comando enviado (la TV lo devuelve al sincronizar). */
    int lastSeq() {
        return seq;
    }

    void connect(final String targetHost) {
        disconnect();
        host = targetHost;
        final int gen = ++generation;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop(targetHost, gen);
            }
        }, "tecladotv-conn");
        t.setDaemon(true);
        t.start();
    }

    void disconnect() {
        generation++;
        Session s = session;
        session = null;
        if (s != null) s.close();
        setState(DISCONNECTED, host);
    }

    void release() {
        disconnect();
        main.removeCallbacks(pinger);
    }

    /** Envía un comando. Devuelve false si no hay conexión. */
    boolean send(JSONObject cmd) {
        Session s = session;
        if (s == null || state != CONNECTED) return false;
        try {
            cmd.put("q", ++seq);
        } catch (JSONException ignored) {
        }
        s.send(cmd.toString() + "\n");
        return true;
    }

    static JSONObject cmd(String name) {
        JSONObject o = new JSONObject();
        try {
            o.put("c", name);
        } catch (JSONException ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------------ hilo de red

    private boolean alive(int gen) {
        return gen == generation;
    }

    private void post(final int gen, final int newState, final String h) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (alive(gen)) setState(newState, h);
            }
        });
    }

    private void setState(int newState, String h) {
        if (state == newState) return;
        state = newState;
        listener.onConnectionState(newState, h);
    }

    private void runLoop(String target, final int gen) {
        long backoff = 1000;
        while (alive(gen)) {
            post(gen, CONNECTING, target);
            Socket socket = new Socket();
            Session ses = null;
            try {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(target, PORT), 4000);
                // La TV responde a los ping cada 4 s; 15 s sin nada = conexión muerta.
                socket.setSoTimeout(15000);
                ses = new Session(socket);
                if (!alive(gen)) break;
                session = ses;
                ses.start();
                ses.send(cmd("hello").toString() + "\n");
                post(gen, CONNECTED, target);
                backoff = 1000;

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF8));
                String line;
                while (alive(gen) && (line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    final JSONObject msg;
                    try {
                        msg = new JSONObject(line);
                    } catch (JSONException e) {
                        continue;
                    }
                    if ("pong".equals(msg.optString("e"))) continue;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (alive(gen)) listener.onTvMessage(msg);
                        }
                    });
                }
            } catch (IOException ignored) {
            } finally {
                if (ses != null) {
                    if (session == ses) session = null;
                    ses.close();
                } else {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            if (!alive(gen)) break;
            post(gen, DISCONNECTED, target);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 4000);
        }
    }

    /** Una conexión concreta, con su propio hilo de escritura (mantiene el orden). */
    private static final class Session {
        private final Socket socket;
        private final Writer out;
        private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private volatile boolean closed;

        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), UTF8));
        }

        void start() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (!closed) {
                            String line = queue.take();
                            if (line == POISON) break;
                            out.write(line);
                            // Agrupar lo que ya esté en cola y enviar de una vez.
                            String more;
                            while ((more = queue.poll()) != null) {
                                if (more == POISON) {
                                    closed = true;
                                    break;
                                }
                                out.write(more);
                            }
                            out.flush();
                        }
                    } catch (IOException | InterruptedException ignored) {
                    } finally {
                        close();
                    }
                }
            }, "tecladotv-writer");
            t.setDaemon(true);
            t.start();
        }

        void send(String line) {
            if (!closed) queue.offer(line);
        }

        void close() {
            if (closed) return;
            closed = true;
            queue.offer(POISON);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
