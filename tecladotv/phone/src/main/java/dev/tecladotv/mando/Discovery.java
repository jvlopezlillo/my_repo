package dev.tecladotv.mando;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Busca televisores con el receptor activo de dos maneras a la vez:
 * 1) anuncio de red (NSD/mDNS) y 2) probando el puerto en todas las IP de la red local.
 * La segunda funciona aunque el router bloquee los anuncios.
 */
final class Discovery {

    interface Listener {
        void onTvFound(String name, String host);

        void onScanFinished();
    }

    private static final String SERVICE_TYPE = "_tecladotv._tcp";

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager nsd;
    private NsdManager.DiscoveryListener nsdListener;
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private ExecutorService pool;
    private int runId;

    Discovery(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        stop();
        final int id = ++runId;
        startNsd();
        startScan(id);
    }

    void stop() {
        runId++;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        stopNsd();
    }

    // ------------------------------------------------------------------ NSD

    private void startNsd() {
        try {
            nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            nsdListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String t, int e) {
                }

                @Override
                public void onStopDiscoveryFailed(String t, int e) {
                }

                @Override
                public void onDiscoveryStarted(String t) {
                }

                @Override
                public void onDiscoveryStopped(String t) {
                }

                @Override
                public void onServiceFound(final NsdServiceInfo info) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            toResolve.add(info);
                            resolveNext();
                        }
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                }
            };
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception e) {
            nsdListener = null;
        }
    }

    private void stopNsd() {
        try {
            if (nsd != null && nsdListener != null) nsd.stopServiceDiscovery(nsdListener);
        } catch (Exception ignored) {
        }
        nsdListener = null;
        toResolve.clear();
        resolving = false;
    }

    /** Android antiguo solo permite resolver un servicio a la vez. */
    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || nsd == null || nsdListener == null) return;
        final NsdServiceInfo next = toResolve.poll();
        if (next == null) return;
        resolving = true;
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    done();
                }

                @Override
                public void onServiceResolved(final NsdServiceInfo info) {
                    final InetAddress addr = info.getHost();
                    final String name = info.getServiceName();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (addr instanceof Inet4Address && nsdListener != null) {
                                listener.onTvFound(cleanName(name), addr.getHostAddress());
                            }
                        }
                    });
                    done();
                }

                private void done() {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            resolving = false;
                            resolveNext();
                        }
                    });
                }
            });
        } catch (Exception e) {
            resolving = false;
        }
    }

    private static String cleanName(String n) {
        if (n == null) return "TV";
        return n.startsWith("TecladoTV ") ? n.substring(10) : n;
    }

    // ------------------------------------------------------------------ barrido de la red

    private void startScan(final int id) {
        final Set<String> prefixes = new LinkedHashSet<>();
        final Set<String> own = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        own.add(ip);
                        prefixes.add(ip.substring(0, ip.lastIndexOf('.') + 1));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        final ExecutorService p = Executors.newFixedThreadPool(48);
        pool = p;
        for (String prefix : prefixes) {
            for (int i = 1; i < 255; i++) {
                final String host = prefix + i;
                if (own.contains(host)) continue;
                p.execute(new Runnable() {
                    @Override
                    public void run() {
                        probe(host, id);
                    }
                });
            }
        }
        p.shutdown();
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    p.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id == runId) listener.onScanFinished();
                    }
                });
            }
        });
        waiter.setDaemon(true);
        waiter.start();
    }

    private void probe(final String host, final int id) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, TvConnection.PORT), 400);
            s.setSoTimeout(1500);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), Charset.forName("UTF-8")));
            String line = in.readLine();
            String name = "TV";
            if (line != null) {
                JSONObject hello = new JSONObject(line);
                if (!"hello".equals(hello.optString("e"))) return;
                name = hello.optString("name", "TV");
            }
            final String found = name;
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (id == runId) listener.onTvFound(found, host);
                }
            });
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
