package com.rootterm.fm.adb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ADB 主机（USB Host 模式）——用于「系统模式」管理另一台手机的文件。
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>找 interface class 0xFF / subclass 0x42 / protocol 0x01 的 ADB 接口；</li>
 *   <li>CNXN 握手 → AUTH(TOKEN) → 用本机 RSA 私钥签名回 AUTH(SIGNATURE) →
 *       设备未信任时再发 AUTH(RSAPUBLICKEY)，对方弹「允许 USB 调试」；</li>
 *   <li>OPEN("shell:命令") 执行命令，读 WRTE 直到 CLSE；</li>
 *   <li>文件读写用 {@code toybox base64} 走 shell，避免实现 sync 协议。</li>
 * </ul>
 */
public final class AdbHost {

    /** USB 权限广播 action。 */
    public static final String ACTION_PERM = "com.rootterm.fm.ADB_PERM";

    private static final int ADB_CLASS = 0xFF;
    private static final int ADB_SUBCLASS = 0x42;
    private static final int ADB_PROTOCOL = 0x01;

    private static final int A_CNXN = 0x4E584E43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4E45504F;
    private static final int A_OKAY = 0x59414B4F;
    private static final int A_CLSE = 0x45534C43;
    private static final int A_WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000001;
    private static final int MAXDATA = 4096;

    private static final int TIMEOUT_IO = 5000;
    private static final int TIMEOUT_PKT = 8000;

    private AdbHost() {
    }

    // ---------------------------------------------------------------- 数据模型

    /** 远端文件项。 */
    public static final class Entry {
        public String name = "";
        public String path = "";
        public boolean dir;
        public long size;
        public String perm = "";
        public String date = "";
    }

    // ---------------------------------------------------------------- 设备发现

    public static boolean isAdb(UsbDevice d) {
        if (d == null) {
            return false;
        }
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() == ADB_CLASS
                    && itf.getInterfaceSubclass() == ADB_SUBCLASS
                    && itf.getInterfaceProtocol() == ADB_PROTOCOL) {
                return true;
            }
        }
        return false;
    }

    public static List<UsbDevice> findDevices(UsbManager m) {
        List<UsbDevice> out = new ArrayList<UsbDevice>();
        if (m == null) {
            return out;
        }
        for (UsbDevice d : m.getDeviceList().values()) {
            if (isAdb(d)) {
                out.add(d);
            }
        }
        return out;
    }

    public static String describe(UsbDevice d) {
        if (d == null) {
            return "无设备";
        }
        return String.format(Locale.US, "%04X:%04X", d.getVendorId(), d.getProductId());
    }

    /** 打开 ADB 会话（需已授权 USB 权限）。 */
    public static Session open(UsbManager m, UsbDevice d, File keyDir) throws IOException {
        if (m == null || d == null) {
            throw new IOException("设备为空");
        }
        if (!m.hasPermission(d)) {
            throw new IOException("没有 USB 权限");
        }
        UsbInterface target = null;
        UsbEndpoint epIn = null;
        UsbEndpoint epOut = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() != ADB_CLASS
                    || itf.getInterfaceSubclass() != ADB_SUBCLASS
                    || itf.getInterfaceProtocol() != ADB_PROTOCOL) {
                continue;
            }
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int e = 0; e < itf.getEndpointCount(); e++) {
                UsbEndpoint ep = itf.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    in = ep;
                } else {
                    out = ep;
                }
            }
            if (in != null && out != null) {
                target = itf;
                epIn = in;
                epOut = out;
                break;
            }
        }
        if (target == null) {
            throw new IOException("未找到 ADB 接口");
        }
        UsbDeviceConnection conn = m.openDevice(d);
        if (conn == null) {
            throw new IOException("无法打开 USB 设备");
        }
        if (!conn.claimInterface(target, true)) {
            conn.close();
            throw new IOException("无法占用 ADB 接口");
        }
        Session s = new Session(conn, target, epIn, epOut, keyDir);
        s.handshake();
        return s;
    }

    // ---------------------------------------------------------------- 会话

    /** 一个 ADB 会话。 */
    public static final class Session implements Closeable {

        private final UsbDeviceConnection conn;
        private final UsbInterface itf;
        private final UsbEndpoint epIn;
        private final UsbEndpoint epOut;
        private final File keyDir;

        private int nextLocalId = 1;
        private volatile boolean closed;
        private KeyPair keyPair;

        Session(UsbDeviceConnection c, UsbInterface i, UsbEndpoint in, UsbEndpoint out,
                File keyDir) {
            conn = c;
            itf = i;
            epIn = in;
            epOut = out;
            this.keyDir = keyDir;
        }

        // -------------------------------------------------------- 底层收发

        private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
            int len = data == null ? 0 : data.length;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(24 + len);
            writeLe(bos, cmd);
            writeLe(bos, arg0);
            writeLe(bos, arg1);
            writeLe(bos, len);
            writeLe(bos, checksum(data));
            writeLe(bos, cmd ^ 0xFFFFFFFF);
            if (len > 0) {
                bos.write(data, 0, len);
            }
            byte[] pkt = bos.toByteArray();
            int off = 0;
            while (off < pkt.length) {
                int n = conn.bulkTransfer(epOut, slice(pkt, off, pkt.length - off),
                        pkt.length - off, TIMEOUT_IO);
                if (n <= 0) {
                    throw new IOException("发送失败");
                }
                off += n;
            }
        }

        private static void writeLe(ByteArrayOutputStream b, int v) {
            b.write(v & 0xFF);
            b.write((v >>> 8) & 0xFF);
            b.write((v >>> 16) & 0xFF);
            b.write((v >>> 24) & 0xFF);
        }

        private static int readLe(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                    | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
        }

        private static int checksum(byte[] d) {
            if (d == null) {
                return 0;
            }
            int s = 0;
            for (byte b : d) {
                s += (b & 0xFF);
            }
            return s;
        }

        private static byte[] slice(byte[] src, int off, int len) {
            byte[] out = new byte[len];
            System.arraycopy(src, off, out, 0, len);
            return out;
        }

        private byte[] readExact(int len, int timeoutMs) throws IOException {
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = conn.bulkTransfer(epIn, buf, off, len - off, timeoutMs);
                if (n <= 0) {
                    throw new IOException("读取超时");
                }
                off += n;
            }
            return buf;
        }

        /** 读一个包：{cmd, arg0, arg1, data}。 */
        private Object[] readPacket(int timeoutMs) throws IOException {
            byte[] h = readExact(24, timeoutMs);
            int cmd = readLe(h, 0);
            int arg0 = readLe(h, 4);
            int arg1 = readLe(h, 8);
            int len = readLe(h, 12);
            byte[] data = len > 0 ? readExact(len, TIMEOUT_IO) : new byte[0];
            return new Object[]{cmd, arg0, arg1, data};
        }

        // -------------------------------------------------------- 握手 / 认证

        private void handshake() throws IOException {
            send(A_CNXN, VERSION, MAXDATA, "host::\u0000".getBytes("UTF-8"));
            boolean sentPubKey = false;
            long deadline = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < deadline) {
                Object[] p = readPacket(TIMEOUT_PKT);
                int cmd = (Integer) p[0];
                byte[] data = (byte[]) p[3];

                if (cmd == A_CNXN) {
                    return; // 已连接
                }
                if (cmd == A_AUTH) {
                    int type = (Integer) p[1];
                    if (type == AUTH_TOKEN) {
                        if (!sentPubKey) {
                            byte[] sig = signToken(data);
                            if (sig != null) {
                                send(A_AUTH, AUTH_SIGNATURE, 0, sig);
                                continue;
                            }
                        }
                        // 签名不被接受 → 发送公钥，等对方点「允许」
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, androidPubKey());
                        sentPubKey = true;
                        continue;
                    }
                }
                // 其他包忽略
            }
            throw new IOException("ADB 认证超时：请在对方手机上点「允许 USB 调试」后重试");
        }

        private byte[] signToken(byte[] token) {
            try {
                KeyPair kp = keyPair();
                Signature s = Signature.getInstance("SHA1withRSA");
                s.initSign(kp.getPrivate());
                s.update(token);
                return s.sign();
            } catch (Throwable t) {
                return null;
            }
        }

        /** 取（或生成）本机 ADB 密钥对，持久化到应用私有目录。 */
        private KeyPair keyPair() throws Exception {
            if (keyPair != null) {
                return keyPair;
            }
            File f = new File(keyDir, "adb_key.pk8");
            if (f.isFile()) {
                try {
                    byte[] der = readFile(f);
                    java.security.KeyFactory kf =
                            java.security.KeyFactory.getInstance("RSA");
                    java.security.PrivateKey pk = kf.generatePrivate(
                            new java.security.spec.PKCS8EncodedKeySpec(der));
                    java.security.PublicKey pub = derivePublic(pk);
                    keyPair = new KeyPair(pub, pk);
                    return keyPair;
                } catch (Throwable ignored) {
                }
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            try {
                keyDir.mkdirs();
                FileOutputStream fo = new FileOutputStream(f);
                try {
                    fo.write(kp.getPrivate().getEncoded());
                } finally {
                    fo.close();
                }
            } catch (Throwable ignored) {
            }
            keyPair = kp;
            return kp;
        }

        private static java.security.PublicKey derivePublic(java.security.PrivateKey pk) {
            try {
                if (pk instanceof java.security.interfaces.RSAPrivateCrtKey) {
                    java.security.interfaces.RSAPrivateCrtKey c =
                            (java.security.interfaces.RSAPrivateCrtKey) pk;
                    java.security.spec.RSAPublicKeySpec spec =
                            new java.security.spec.RSAPublicKeySpec(
                                    c.getModulus(), c.getPublicExponent());
                    return java.security.KeyFactory.getInstance("RSA")
                            .generatePublic(spec);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        /** Android 的 RSAPublicKey 结构 + " user@host"。 */
        private byte[] androidPubKey() throws IOException {
            try {
                KeyPair kp = keyPair();
                RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
                BigInteger n = pub.getModulus();
                BigInteger e = pub.getPublicExponent();

                byte[] mod = toFixedLittle(n, 256);
                byte[] rr = toFixedLittle(
                        BigInteger.ONE.shiftLeft(4096).mod(n), 256);
                int n0 = readLe(mod, 0);
                int n0inv = -(int) inverse32(n0);

                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeLe(b, 2048);            // len（位数）
                writeLe(b, n0inv);
                b.write(mod, 0, 256);
                b.write(rr, 0, 256);
                writeLe(b, e.intValue());

                String b64 = android.util.Base64.encodeToString(b.toByteArray(),
                        android.util.Base64.NO_WRAP);
                String s = b64 + " tfmanager@android\u0000";
                return s.getBytes("UTF-8");
            } catch (IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new IOException("生成 ADB 公钥失败：" + t);
            }
        }

        /** 大端 BigInteger → 定长小端字节数组。 */
        private static byte[] toFixedLittle(BigInteger v, int size) {
            byte[] be = v.toByteArray();
            byte[] out = new byte[size];
            for (int i = 0; i < size; i++) {
                int idx = be.length - 1 - i;
                out[i] = idx >= 0 ? be[idx] : 0;
            }
            return out;
        }

        /** 模 2^32 求逆（牛顿迭代，a 必须为奇数）。 */
        private static long inverse32(int a) {
            long x = 1;
            long aa = a & 0xFFFFFFFFL;
            for (int i = 0; i < 6; i++) {
                x = (x * (2 - aa * x)) & 0xFFFFFFFFL;
            }
            return x & 0xFFFFFFFFL;
        }

        // -------------------------------------------------------- 服务

        /** 执行 shell 命令，返回合并输出。 */
        public String shell(String cmd) throws IOException {
            return shellWithInput(cmd, null);
        }

        /** 执行 shell 命令并可写入 stdin。 */
        public String shellWithInput(String cmd, byte[] stdin) throws IOException {
            if (closed) {
                throw new IOException("会话已关闭");
            }
            final int localId = nextLocalId++;
            send(A_OPEN, localId, 0, ("shell:" + cmd + "\u0000").getBytes("UTF-8"));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int remoteId = -1;
            boolean stdinSent = stdin == null;
            long deadline = System.currentTimeMillis() + 180000;

            while (System.currentTimeMillis() < deadline) {
                Object[] p = readPacket(TIMEOUT_PKT);
                int c = (Integer) p[0];
                int a0 = (Integer) p[1];
                byte[] data = (byte[]) p[3];

                if (c == A_OKAY) {
                    if (remoteId < 0) {
                        remoteId = a0;
                        if (!stdinSent) {
                            sendStdin(localId, remoteId, stdin);
                            stdinSent = true;
                        }
                    } else {
                        // 对端确认收到 WRTE
                    }
                } else if (c == A_WRTE) {
                    if (data.length > 0) {
                        out.write(data, 0, data.length);
                    }
                    send(A_OKAY, localId, a0, null);
                } else if (c == A_CLSE) {
                    break;
                }
            }
            try {
                send(A_CLSE, localId, remoteId < 0 ? 0 : remoteId, null);
            } catch (Throwable ignored) {
            }
            return out.toString("UTF-8");
        }

        private void sendStdin(int localId, int remoteId, byte[] data) throws IOException {
            int off = 0;
            while (off < data.length) {
                int n = Math.min(MAXDATA, data.length - off);
                send(A_WRTE, localId, remoteId, slice(data, off, n));
                off += n;
                // 等 OKAY
                try {
                    Object[] p = readPacket(TIMEOUT_PKT);
                    if ((Integer) p[0] == A_CLSE) {
                        return;
                    }
                } catch (IOException ignored) {
                }
            }
            send(A_CLSE, localId, remoteId, null);
        }

        // -------------------------------------------------------- 文件操作

        /** 列目录。 */
        public List<Entry> list(String path) throws IOException {
            String out = shell("ls -la " + q(path) + " 2>&1");
            List<Entry> list = new ArrayList<Entry>();
            if (out == null) {
                return list;
            }
            for (String line : out.split("\n")) {
                Entry e = parseLsLine(line, path);
                if (e != null) {
                    list.add(e);
                }
            }
            return list;
        }

        /** 读文件（base64 传输，二进制安全）。 */
        public byte[] read(String path) throws IOException {
            String b64 = shell("toybox base64 " + q(path) + " 2>/dev/null");
            if (b64 == null) {
                throw new IOException("读取失败");
            }
            StringBuilder sb = new StringBuilder(b64.length());
            for (int i = 0; i < b64.length(); i++) {
                char c = b64.charAt(i);
                if (c != '\n' && c != '\r' && c != ' ') {
                    sb.append(c);
                }
            }
            try {
                return android.util.Base64.decode(sb.toString(),
                        android.util.Base64.DEFAULT);
            } catch (Throwable t) {
                throw new IOException("数据解码失败");
            }
        }

        /** 写文件（base64 经 stdin 写入）。 */
        public void write(String path, byte[] data) throws IOException {
            String b64 = android.util.Base64.encodeToString(data,
                    android.util.Base64.NO_WRAP);
            String out = shellWithInput("toybox base64 -d > " + q(path),
                    b64.getBytes("US-ASCII"));
            if (out != null && out.toLowerCase(Locale.US).contains("error")) {
                throw new IOException(out.trim());
            }
            String chk = shell("[ -s " + q(path) + " ] && echo ok || echo no");
            if (chk == null || !chk.contains("ok")) {
                throw new IOException("写入失败（可能无权限）");
            }
        }

        /** 删除（文件或目录）。 */
        public void delete(String path) throws IOException {
            String out = shell("rm -rf " + q(path) + " 2>&1");
            if (out != null && out.toLowerCase(Locale.US).contains("denied")) {
                throw new IOException(out.trim());
            }
        }

        /** 重命名 / 移动。 */
        public void rename(String from, String to) throws IOException {
            String out = shell("mv " + q(from) + " " + q(to) + " 2>&1");
            if (out != null && !out.trim().isEmpty()
                    && out.toLowerCase(Locale.US).contains("denied")) {
                throw new IOException(out.trim());
            }
        }

        /** 新建目录。 */
        public void mkdir(String path) throws IOException {
            String out = shell("mkdir -p " + q(path) + " 2>&1");
            if (out != null && out.toLowerCase(Locale.US).contains("denied")) {
                throw new IOException(out.trim());
            }
        }

        /** 设备信息。 */
        public String info() {
            StringBuilder sb = new StringBuilder();
            String[][] items = {
                    {"型号", "getprop ro.product.model"},
                    {"品牌", "getprop ro.product.brand"},
                    {"Android", "getprop ro.build.version.release"},
                    {"SDK", "getprop ro.build.version.sdk"},
                    {"序列号", "getprop ro.serialno"},
            };
            for (String[] it : items) {
                try {
                    String v = shell(it[1] + " 2>/dev/null");
                    if (v != null && !v.trim().isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(it[0]).append(": ").append(v.trim());
                    }
                } catch (Throwable ignored) {
                }
            }
            return sb.toString();
        }

        // -------------------------------------------------------- 解析 ls

        private static Entry parseLsLine(String line, String dir) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty() || t.startsWith("total ")) {
                return null;
            }
            String[] c = t.split("\\s+", 8);
            if (c.length < 8) {
                return null;
            }
            String perm = c[0];
            if (perm.length() < 10) {
                return null;
            }
            char type = perm.charAt(0);
            if (type != 'd' && type != '-' && type != 'l') {
                return null;
            }
            String name = c[7];
            if (".".equals(name) || "..".equals(name)) {
                return null;
            }
            Entry e = new Entry();
            e.perm = perm;
            e.dir = type == 'd';
            e.name = name;
            try {
                e.size = Long.parseLong(c[4]);
            } catch (Throwable ignored) {
                e.size = 0;
            }
            e.date = c[5] + " " + c[6];
            e.path = "/".equals(dir) ? "/" + name : dir + "/" + name;
            return e;
        }

        private static String q(String s) {
            if (s == null) {
                return "''";
            }
            return "'" + s.replace("'", "'\\''") + "'";
        }

        private static byte[] readFile(File f) throws IOException {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                conn.releaseInterface(itf);
            } catch (Throwable ignored) {
            }
            try {
                conn.close();
            } catch (Throwable ignored) {
            }
        }

        public boolean isClosed() {
            return closed;
        }
    }
}