package dev.tecladotv.receptor;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class NetUtil {
    /** Puerto TCP en el que la TV espera al móvil. Debe coincidir con la app del móvil. */
    static final int PORT = 48900;
    static final String SERVICE_TYPE = "_tecladotv._tcp";

    private NetUtil() {}

    /** Direcciones IPv4 de la red local (Wi-Fi o cable) de este aparato. */
    static List<String> localIpv4() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static String localIpText() {
        List<String> ips = localIpv4();
        if (ips.isEmpty()) return "sin red";
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(ip);
        }
        return sb.toString();
    }
}
