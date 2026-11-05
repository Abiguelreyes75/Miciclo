package com.cerquita.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mensajería directa entre teléfonos de la misma red, sin servidor. No depende de Android,
 * para poder probarla con Java normal. Cada mensaje viaja cifrado con AES-GCM usando una
 * clave acordada con ECDH. La identidad de cada teléfono es el resumen de su clave pública.
 */
public final class LanCore {
    public interface Listener {
        /** Llegó algo nuevo; notify indica si amerita un aviso visible. */
        void onInbox(boolean notify, String fromName);
    }

    public static final int DISCOVERY_PORT = 47820;
    private static final int MAGIC = 0x43455251;
    private static final int MAX_CIPHERTEXT = 16 * 1024 * 1024;
    private static final long ONLINE_MS = 13_000;
    private static final int HELLO_MS = 4_000;

    static final class Peer {
        volatile String id, pub, name = "", ip = "";
        volatile int port;
        volatile long lastSeen;
    }

    private final File dir, kvDir, outDir, inboxFile, peersFile;
    private final int discoveryPort;
    private final Listener listener;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Set<String> manualTargets = ConcurrentHashMap.newKeySet();
    private final Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
    private final Semaphore wake = new Semaphore(0);
    private final SecureRandom random = new SecureRandom();
    private final Object inboxLock = new Object();
    private KeyPair keys;
    private String pubB64, id;
    private volatile String name = "";
    private volatile boolean running;
    private DatagramSocket udp;
    private ServerSocket server;
    private ExecutorService pool;

    public LanCore(File dir, int discoveryPort, Listener listener) throws Exception {
        this.dir = dir;
        this.discoveryPort = discoveryPort;
        this.listener = listener;
        kvDir = new File(dir, "kv");
        outDir = new File(dir, "outbox");
        kvDir.mkdirs();
        outDir.mkdirs();
        inboxFile = new File(dir, "inbox.jsonl");
        peersFile = new File(dir, "peers.txt");
        loadKeys();
        loadPeers();
        String saved = kvGet("__name");
        name = saved == null ? "" : saved;
    }

    public String id() { return id; }
    public String name() { return name; }

    public void setName(String n) {
        name = n == null ? "" : n.length() > 40 ? n.substring(0, 40) : n;
        kvSet("__name", name);
        sendHello();
    }

    /* ---- identidad ---- */

    private void loadKeys() throws Exception {
        File f = new File(dir, "identity.key");
        if (f.exists()) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(f.toPath())))) {
                byte[] pub = new byte[in.readInt()];
                in.readFully(pub);
                byte[] priv = new byte[in.readInt()];
                in.readFully(priv);
                KeyFactory kf = KeyFactory.getInstance("EC");
                keys = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(pub)),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(priv)));
            }
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            keys = g.generateKeyPair();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (DataOutputStream o = new DataOutputStream(b)) {
                byte[] pub = keys.getPublic().getEncoded(), priv = keys.getPrivate().getEncoded();
                o.writeInt(pub.length);
                o.write(pub);
                o.writeInt(priv.length);
                o.write(priv);
            }
            writeAtomic(f, b.toByteArray());
        }
        pubB64 = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        id = idOf(pubB64);
    }

    private static String idOf(String pub) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(pub.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    /** Clave compartida: igual en ambos teléfonos, imposible de calcular para quien solo escucha la red. */
    private byte[] sharedKey(String otherPub) throws Exception {
        byte[] cached = keyCache.get(otherPub);
        if (cached != null) return cached;
        PublicKey other = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(otherPub)));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(keys.getPrivate());
        ka.doPhase(other, true);
        byte[] key = MessageDigest.getInstance("SHA-256").digest(ka.generateSecret());
        keyCache.put(otherPub, key);
        return key;
    }

    /** Código para comparar en persona: si coincide en ambos teléfonos, nadie se hizo pasar por el otro. */
    public String safetyCode(String peerId) {
        Peer p = peers.get(peerId);
        if (p == null) return "";
        try {
            String[] both = {pubB64, p.pub};
            Arrays.sort(both);
            byte[] d = MessageDigest.getInstance("SHA-256").digest((both[0] + both[1]).getBytes(StandardCharsets.UTF_8));
            int n = ((d[0] & 255) << 24 | (d[1] & 255) << 16 | (d[2] & 255) << 8 | (d[3] & 255)) & 0x7fffffff;
            return String.format("%03d %03d", (n / 1000) % 1000, n % 1000);
        } catch (Exception e) {
            return "";
        }
    }

    /* ---- arranque ---- */

    public synchronized void start() throws IOException {
        if (running) return;
        running = true;
        pool = Executors.newFixedThreadPool(10);
        server = new ServerSocket(0);
        udp = new DatagramSocket(null);
        udp.setReuseAddress(true);
        udp.setBroadcast(true);
        udp.bind(new InetSocketAddress(discoveryPort));
        thread(this::acceptLoop);
        thread(this::udpLoop);
        thread(this::helloLoop);
        thread(this::flushLoop);
    }

    public synchronized void stop() {
        running = false;
        wake.release();
        try { if (udp != null) udp.close(); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    private void thread(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    /* ---- descubrimiento ---- */

    private byte[] helloBytes() {
        String n = Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        return ("CERQ1\n" + pubB64 + "\n" + n + "\n" + server.getLocalPort()).getBytes(StandardCharsets.UTF_8);
    }

    private void helloLoop() {
        while (running) {
            sendHello();
            sleep(HELLO_MS);
        }
    }

    public void sendHello() {
        if (!running || udp == null) return;
        byte[] data = helloBytes();
        for (InetSocketAddress t : targets()) {
            try {
                udp.send(new DatagramPacket(data, data.length, t));
            } catch (Exception ignored) { }
        }
    }

    private List<InetSocketAddress> targets() {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add("255.255.255.255");
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface n = all.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress a : n.getInterfaceAddresses()) {
                    if (a.getBroadcast() != null) hosts.add(a.getBroadcast().getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        List<InetSocketAddress> out = new ArrayList<>();
        for (String h : hosts) out.add(new InetSocketAddress(h, discoveryPort));
        for (String m : manualTargets) {
            String[] p = m.split(":");
            out.add(new InetSocketAddress(p[0], p.length > 1 ? Integer.parseInt(p[1]) : DISCOVERY_PORT));
        }
        for (Peer p : peers.values()) {
            if (!p.ip.isEmpty()) out.add(new InetSocketAddress(p.ip, discoveryPort));
        }
        return out;
    }

    /** Para redes que bloquean la búsqueda automática. Acepta "ip" o "ip:puerto". */
    public boolean addManual(String target) {
        String t = target == null ? "" : target.trim();
        if (!t.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?")) return false;
        manualTargets.add(t);
        sendHello();
        return true;
    }

    private void udpLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                String[] parts = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length != 4 || !parts[0].equals("CERQ1")) continue;
                String peerId = idOf(parts[1]);
                if (peerId.equals(id)) continue;
                int port = Integer.parseInt(parts[3]);
                if (port < 1 || port > 65535) continue;
                sharedKey(parts[1]); // descarta claves inválidas
                String n = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                boolean wasOnline = isOnline(peers.get(peerId));
                remember(peerId, parts[1], n, p.getAddress().getHostAddress(), port);
                if (!wasOnline) {
                    // Responder al recién llegado y reintentar lo que tenía pendiente.
                    byte[] data = helloBytes();
                    udp.send(new DatagramPacket(data, data.length, p.getSocketAddress()));
                    wake.release();
                }
            } catch (Exception ignored) { }
        }
    }

    private void remember(String peerId, String pub, String n, String ip, int port) {
        Peer p = peers.get(peerId);
        boolean changed = p == null;
        if (p == null) {
            p = new Peer();
            p.id = peerId;
            p.pub = pub;
            peers.put(peerId, p);
        }
        if (n != null && !n.equals(p.name)) {
            p.name = n.length() > 40 ? n.substring(0, 40) : n;
            changed = true;
        }
        if (ip != null && !ip.equals(p.ip)) {
            p.ip = ip;
            changed = true;
        }
        if (port > 0 && port != p.port) {
            p.port = port;
            changed = true;
        }
        if (port > 0) p.lastSeen = System.currentTimeMillis();
        if (changed) savePeers();
    }

    private static boolean isOnline(Peer p) {
        return p != null && p.port > 0 && System.currentTimeMillis() - p.lastSeen < ONLINE_MS;
    }

    /* ---- enviar ---- */

    /** Guarda el mensaje y lo entrega en cuanto el otro teléfono esté disponible. */
    public void send(String peerId, String msgId, int kind, String payload) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (DataOutputStream o = new DataOutputStream(b)) {
                o.writeUTF(peerId);
                o.writeUTF(msgId);
                o.writeByte(kind);
                byte[] data = payload.getBytes(StandardCharsets.UTF_8);
                o.writeInt(data.length);
                o.write(data);
            }
            writeAtomic(new File(outDir, String.format("%020d_%08x", System.currentTimeMillis(), random.nextInt())), b.toByteArray());
            wake.release();
        } catch (Exception e) {
            appendInbox("{\"failed\":" + json(msgId) + "}");
        }
    }

    private void flushLoop() {
        while (running) {
            try {
                wake.tryAcquire(5, TimeUnit.SECONDS);
                wake.drainPermits();
                File[] files = outDir.listFiles();
                if (files == null) continue;
                Arrays.sort(files);
                Set<String> failed = new HashSet<>();
                for (File f : files) {
                    if (!running) return;
                    if (!f.getName().contains("_") || f.getName().endsWith(".tmp")) continue;
                    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(f.toPath())))) {
                        String peerId = in.readUTF(), msgId = in.readUTF();
                        int kind = in.readByte();
                        byte[] data = new byte[in.readInt()];
                        in.readFully(data);
                        Peer p = peers.get(peerId);
                        if (failed.contains(peerId) || !isOnline(p)) continue;
                        if (deliver(p, msgId, kind, data)) {
                            f.delete();
                            if (kind != 0) appendInbox("{\"ack\":" + json(msgId) + ",\"peer\":" + json(peerId) + "}");
                            signal(false, "");
                        } else {
                            failed.add(peerId);
                        }
                    } catch (IOException e) {
                        f.delete(); // archivo dañado
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) { }
        }
    }

    private boolean deliver(Peer p, String msgId, int kind, byte[] data) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(p.ip, p.port), 3000);
            s.setSoTimeout(20000);
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            try (DataOutputStream o = new DataOutputStream(plain)) {
                o.writeByte(kind);
                o.writeUTF(msgId);
                o.write(data);
            }
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sharedKey(p.pub), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(p.id.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.toByteArray());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeInt(MAGIC);
            out.writeUTF(pubB64);
            out.write(iv);
            out.writeInt(ct.length);
            out.write(ct);
            out.flush();
            return s.getInputStream().read() == 6;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---- recibir ---- */

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (Exception ignored) { }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(20000);
            DataInputStream in = new DataInputStream(sock.getInputStream());
            if (in.readInt() != MAGIC) return;
            String pub = in.readUTF();
            byte[] iv = new byte[12];
            in.readFully(iv);
            int len = in.readInt();
            if (len < 16 || len > MAX_CIPHERTEXT) return;
            byte[] ct = new byte[len];
            in.readFully(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sharedKey(pub), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            byte[] plain = c.doFinal(ct); // falla si el contenido fue alterado o no era para este teléfono
            DataInputStream p = new DataInputStream(new ByteArrayInputStream(plain));
            int kind = p.readByte();
            p.readUTF(); // identificador de envío; la app ignora los duplicados por su propio id
            byte[] data = new byte[p.available()];
            p.readFully(data);
            String from = idOf(pub);
            Peer known = peers.get(from);
            remember(from, pub, known == null ? null : known.name, sock.getInetAddress().getHostAddress(), 0);
            appendInbox("{\"from\":" + json(from) + ",\"kind\":" + kind + ",\"payload\":"
                    + json(new String(data, StandardCharsets.UTF_8)) + "}");
            sock.getOutputStream().write(6);
            sock.getOutputStream().flush();
            Peer sender = peers.get(from);
            signal(kind != 0, sender == null || sender.name.isEmpty() ? "alguien" : sender.name);
        } catch (Exception ignored) { }
    }

    private void signal(boolean notify, String fromName) {
        if (listener != null) listener.onInbox(notify, fromName);
    }

    /** Entrega a la app lo recibido y vacía la bandeja. */
    public String drain() {
        synchronized (inboxLock) {
            try {
                if (!inboxFile.exists()) return "[]";
                List<String> lines = Files.readAllLines(inboxFile.toPath(), StandardCharsets.UTF_8);
                inboxFile.delete();
                return "[" + String.join(",", lines) + "]";
            } catch (IOException e) {
                return "[]";
            }
        }
    }

    private void appendInbox(String line) {
        synchronized (inboxLock) {
            try (FileOutputStream o = new FileOutputStream(inboxFile, true)) {
                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
    }

    /* ---- contactos ---- */

    public String peersJson() {
        List<String> out = new ArrayList<>();
        for (Peer p : peers.values()) {
            out.add("{\"id\":" + json(p.id) + ",\"name\":" + json(p.name) + ",\"online\":" + isOnline(p) + "}");
        }
        return "[" + String.join(",", out) + "]";
    }

    /** Clave pública de un contacto, para compartirla al invitarlo a un grupo. */
    public String peerPub(String peerId) {
        Peer p = peers.get(peerId);
        return p == null ? "" : p.pub;
    }

    public String pub() { return pubB64; }

    /** Conoce a un integrante de un grupo aunque todavía no lo haya visto en la red. */
    public boolean introduce(String peerId, String pub, String n) {
        try {
            if (peerId.equals(id) || !idOf(pub).equals(peerId)) return false;
            sharedKey(pub);
            if (!peers.containsKey(peerId)) remember(peerId, pub, n, null, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void savePeers() {
        StringBuilder s = new StringBuilder();
        for (Peer p : peers.values()) {
            s.append(p.pub).append('\t')
                    .append(Base64.getEncoder().encodeToString(p.name.getBytes(StandardCharsets.UTF_8)))
                    .append('\t').append(p.ip).append('\n');
        }
        try {
            writeAtomic(peersFile, s.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    private void loadPeers() {
        if (!peersFile.exists()) return;
        try {
            for (String line : Files.readAllLines(peersFile.toPath(), StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length != 3) continue;
                Peer p = new Peer();
                p.pub = f[0];
                p.id = idOf(f[0]);
                p.name = new String(Base64.getDecoder().decode(f[1]), StandardCharsets.UTF_8);
                p.ip = f[2];
                peers.put(p.id, p);
            }
        } catch (Exception ignored) { }
    }

    /* ---- almacenamiento de la app ---- */

    private File kvFile(String key) {
        try {
            return new File(kvDir, hex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String kvGet(String key) {
        File f = kvFile(key);
        try {
            return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public boolean kvSet(String key, String value) {
        try {
            File f = kvFile(key);
            if (value == null) return f.delete() || !f.exists();
            writeAtomic(f, value.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void writeAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getParent(), f.getName() + "." + Thread.currentThread().getId() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("No se pudo guardar");
        }
    }

    /* ---- utilidades ---- */

    /** Direcciones de este teléfono, para que otra persona pueda agregarlo a mano. */
    public List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress a : n.getInterfaceAddresses()) {
                    InetAddress ip = a.getAddress();
                    if (a.getBroadcast() != null) out.add(ip.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) { }
    }
}
