package dev.tecladotv.receptor;

import android.os.Handler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Servidor TCP muy simple: cada línea es un mensaje JSON (UTF-8).
 * Los comandos recibidos se entregan en el hilo principal, en orden.
 */
final class BridgeServer {

    interface Callback {
        void onClientConnected(Client client);

        void onClientsChanged(int count);

        void onCommand(Client client, JSONObject cmd);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final int port;
    private final Callback callback;
    private final Handler main;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    BridgeServer(int port, Callback callback, Handler main) {
        this.port = port;
        this.callback = callback;
        this.main = main;
    }

    void start() {
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "tecladotv-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        closeQuietly(serverSocket);
        for (Client c : clients) c.close();
        clients.clear();
        writer.shutdownNow();
    }

    List<Client> clients() {
        return clients;
    }

    int clientCount() {
        return clients.size();
    }

    void broadcast(JSONObject msg) {
        for (Client c : clients) c.send(msg);
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                serverSocket = ss;
                while (running) {
                    Socket s = ss.accept();
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    // El móvil envía un "ping" cada pocos segundos; si calla, se da por perdido.
                    s.setSoTimeout(30000);
                    Client c = new Client(s);
                    clients.add(c);
                    c.start();
                }
            } catch (IOException e) {
                closeQuietly(ss);
                if (!running) break;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    final class Client {
        private final Socket socket;
        private final Writer out;
        /** Número del último comando del móvil ya aplicado en la TV. */
        int lastSeq;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), UTF8));
        }

        void start() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop();
                }
            }, "tecladotv-client");
            t.setDaemon(true);
            t.start();
            main.post(new Runnable() {
                @Override
                public void run() {
                    callback.onClientConnected(Client.this);
                    callback.onClientsChanged(clients.size());
                }
            });
        }

        private void readLoop() {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF8));
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    final JSONObject cmd;
                    try {
                        cmd = new JSONObject(line);
                    } catch (JSONException e) {
                        continue;
                    }
                    if ("ping".equals(cmd.optString("c"))) {
                        send(Protocol.event("pong"));
                        continue;
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onCommand(Client.this, cmd);
                        }
                    });
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        void send(JSONObject msg) {
            final String line = msg.toString() + "\n";
            try {
                writer.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            out.write(line);
                            out.flush();
                        } catch (IOException e) {
                            close();
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) {
            }
        }

        void close() {
            closeQuietly(socket);
            if (clients.remove(this)) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onClientsChanged(clients.size());
                    }
                });
            }
        }
    }
}
