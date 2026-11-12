package com.ninjaball.game;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** UDP LAN beacon (host) + listener (guest) so the joiner can find rooms without typing the IP. No internet. */
public class LanDiscovery {
    public static final int UDP_PORT = 8766;
    public interface Found { void onFound(String ip, int port, String name); }

    // Each start*() bumps a generation number; a worker thread only runs while its generation is current.
    // Every thread works on its own local socket, so an old thread can never close a newer thread's socket.
    private volatile int beaconGen, listenGen;
    private DatagramSocket beaconSock, listenSock;

    public synchronized void startBeacon(final String name, final int port) {
        stopBeacon();
        final int gen = beaconGen;
        final String safeName = (name == null ? "Ninja" : name).replace('|', ' ');
        Thread t = new Thread(new Runnable() {
            public void run() {
                DatagramSocket sock = null;
                try {
                    sock = new DatagramSocket();
                    sock.setBroadcast(true);
                    synchronized (LanDiscovery.this) {
                        if (gen != beaconGen) return;
                        beaconSock = sock;
                    }
                    byte[] data = ("NINJABALL1|" + safeName + "|" + port).getBytes(StandardCharsets.UTF_8);
                    while (gen == beaconGen) {
                        for (InetAddress a : broadcastTargets()) {
                            try { sock.send(new DatagramPacket(data, data.length, a, UDP_PORT)); } catch (Exception ignored) { }
                        }
                        Thread.sleep(1000);
                    }
                } catch (Exception ignored) {
                } finally { if (sock != null) sock.close(); }
            }
        }, "beacon");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stopBeacon() {
        beaconGen++;
        if (beaconSock != null) { beaconSock.close(); beaconSock = null; }
    }

    public synchronized void startListen(final Found cb) {
        stopListen();
        final int gen = listenGen;
        Thread t = new Thread(new Runnable() {
            public void run() {
                DatagramSocket sock = null;
                try {
                    sock = new DatagramSocket(null);
                    sock.setReuseAddress(true);
                    sock.bind(new InetSocketAddress(UDP_PORT));
                    synchronized (LanDiscovery.this) {
                        if (gen != listenGen) return;
                        listenSock = sock;
                    }
                    byte[] buf = new byte[512];
                    while (gen == listenGen) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        sock.receive(p);
                        String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        String[] f = s.split("\\|");
                        if (f.length >= 3 && f[0].equals("NINJABALL1")) {
                            try { cb.onFound(p.getAddress().getHostAddress(), Integer.parseInt(f[2].trim()), f[1]); } catch (NumberFormatException ignored) { }
                        }
                    }
                } catch (Exception ignored) {
                } finally { if (sock != null) sock.close(); }
            }
        }, "discover");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stopListen() {
        listenGen++;
        if (listenSock != null) { listenSock.close(); listenSock = null; }
    }

    static List<InetAddress> broadcastTargets() {
        List<InetAddress> r = new ArrayList<InetAddress>();
        try { r.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) { }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) r.add(ia.getBroadcast());
                }
            }
        } catch (Exception ignored) { }
        return r;
    }

    /** Site-local IPv4 addresses of this device (hotspot AP interface first). */
    public static List<String[]> localIps() {
        List<String[]> r = new ArrayList<String[]>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) r.add(new String[]{ni.getName(), a.getHostAddress()});
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(r, new Comparator<String[]>() {
            public int compare(String[] x, String[] y) { return score(y[0]) - score(x[0]); }
            int score(String n) { n = n.toLowerCase(Locale.ROOT); return (n.startsWith("ap") || n.contains("softap") || n.startsWith("swlan")) ? 2 : n.startsWith("wlan") ? 1 : 0; }
        });
        return r;
    }
}
