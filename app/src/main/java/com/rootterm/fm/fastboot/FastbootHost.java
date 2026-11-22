package com.rootterm.fm.fastboot;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fastboot 主机（USB Host 模式）。
 *
 * <p>用 Android 的 {@link UsbManager} 直接走 fastboot USB 协议：</p>
 * <ul>
 *   <li>命令 = 8 位十六进制长度 + ASCII 命令体；</li>
 *   <li>应答 = 4 字节状态（OKAY / FAIL / DATA / INFO / TEXT）(+ 8 位长度 + 负载)；</li>
 *   <li>刷写 = {@code download:<len>} → 发数据 → {@code flash:<分区>}。</li>
 * </ul>
 *
 * <p>要求：手机支持 USB Host（OTG），被刷设备处于 fastboot 模式。</p>
 */
public final class FastbootHost {

    /** USB 权限广播 action。 */
    public static final String ACTION_PERM = "com.rootterm.fm.USB_PERMISSION";

    /** fastboot 接口特征：class 0xFF / subclass 0x42 / protocol 0x03。 */
    private static final int FB_CLASS = 0xFF;
    private static final int FB_SUBCLASS = 0x42;
    private static final int FB_PROTOCOL = 0x03;

    private static final int TIMEOUT_IO = 5000;
    private static final int TIMEOUT_SHORT = 400;

    private FastbootHost() {
    }

    // ---------------------------------------------------------------- 数据模型

    /** 分区。 */
    public static final class Part {
        public String name = "";
        public long size;
        public String type = "";
    }

    /** 一次应答。 */
    public static final class Resp {
        public String status = "";
        public boolean okay;
        /** DATA 时可发送的字节数，-1 表示不是 DATA。 */
        public int dataLen = -1;
        /** INFO/TEXT/OKAY 后的负载。 */
        public String payload = "";
        /** 累积的所有 INFO/TEXT 行。 */
        public String log = "";

        @Override
        public String toString() {
            return status + (payload.isEmpty() ? "" : " " + payload);
        }
    }

    /** 进度回调。 */
    public interface Progress {
        void onProgress(long done, long total, String message);
    }

    // ---------------------------------------------------------------- 设备发现

    /** 判断是否 fastboot 设备。 */
    public static boolean isFastboot(UsbDevice d) {
        if (d == null) {
            return false;
        }
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() == FB_CLASS
                    && itf.getInterfaceSubclass() == FB_SUBCLASS
                    && itf.getInterfaceProtocol() == FB_PROTOCOL) {
                return true;
            }
        }
        return false;
    }

    /** 列出当前连接的 fastboot 设备。 */
    public static List<UsbDevice> findDevices(UsbManager m) {
        List<UsbDevice> out = new ArrayList<UsbDevice>();
        if (m == null) {
            return out;
        }
        for (UsbDevice d : m.getDeviceList().values()) {
            if (isFastboot(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** 设备描述（用于界面显示）。 */
    public static String describe(UsbDevice d) {
        if (d == null) {
            return "无设备";
        }
        return String.format(Locale.US, "%04X:%04X", d.getVendorId(), d.getProductId());
    }

    /** 打开设备（需已获得 USB 权限）。 */
    public static Device open(UsbManager m, UsbDevice d) throws IOException {
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
            if (itf.getInterfaceClass() != FB_CLASS
                    || itf.getInterfaceSubclass() != FB_SUBCLASS
                    || itf.getInterfaceProtocol() != FB_PROTOCOL) {
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
            throw new IOException("未找到 fastboot 接口");
        }
        UsbDeviceConnection conn = m.openDevice(d);
        if (conn == null) {
            throw new IOException("无法打开 USB 设备");
        }
        // claim 接口；部分设备需要先 SET_CONFIGURATION
        boolean claimed = false;
        try {
            claimed = conn.claimInterface(target, true);
        } catch (Throwable ignored) {
        }
        if (!claimed) {
            for (int ci = 0; ci < d.getConfigurationCount(); ci++) {
                try {
                    conn.setConfiguration(d.getConfiguration(ci));
                } catch (Throwable ignored) {
                }
                try {
                    claimed = conn.claimInterface(target, true);
                } catch (Throwable ignored) {
                }
                if (claimed) {
                    break;
                }
            }
        }
        if (!claimed) {
            conn.close();
            throw new IOException("无法占用 fastboot 接口（可能被系统占用，请拔插后重试）");
        }
        return new Device(conn, target, epIn, epOut);
    }

    // ---------------------------------------------------------------- 设备会话

    /** 一个已打开的 fastboot 会话。 */
    public static final class Device implements Closeable {

        private final UsbDeviceConnection conn;
        private final UsbInterface itf;
        private final UsbEndpoint epIn;
        private final UsbEndpoint epOut;
        private int chunkSize = 256 * 1024;
        private volatile boolean closed;

        Device(UsbDeviceConnection c, UsbInterface i, UsbEndpoint in, UsbEndpoint out) {
            conn = c;
            itf = i;
            epIn = in;
            epOut = out;
        }

        // -------------------------------------------------------- 底层读写

        private void write(String s) throws IOException {
            byte[] b = s.getBytes("US-ASCII");
            int off = 0;
            while (off < b.length) {
                int n = conn.bulkTransfer(epOut, slice(b, off, b.length - off),
                        b.length - off, TIMEOUT_IO);
                if (n <= 0) {
                    throw new IOException("发送失败");
                }
                off += n;
            }
        }

        private static byte[] slice(byte[] src, int off, int len) {
            byte[] out = new byte[len];
            System.arraycopy(src, off, out, 0, len);
            return out;
        }

        private byte[] readExact(int len) throws IOException {
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = conn.bulkTransfer(epIn, buf, off, len - off, TIMEOUT_IO);
                if (n <= 0) {
                    throw new IOException("读取超时");
                }
                off += n;
            }
            return buf;
        }

        /** 尝试读 len 字节，超时返回 null。 */
        private byte[] tryRead(int len, int timeoutMs) {
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = conn.bulkTransfer(epIn, buf, off, len - off, timeoutMs);
                if (n <= 0) {
                    return null;
                }
                off += n;
            }
            return buf;
        }

        private static int parseHex(byte[] b) throws IOException {
            try {
                return (int) Long.parseLong(new String(b, "US-ASCII").trim(), 16);
            } catch (Throwable t) {
                throw new IOException("非法长度字段");
            }
        }

        private static String hex8(int v) {
            return String.format(Locale.US, "%08x", v);
        }

        /** 读一次应答（自动吞掉 INFO/TEXT）。 */
        public Resp readResp() throws IOException {
            Resp r = new Resp();
            StringBuilder log = new StringBuilder();
            for (int guard = 0; guard < 64; guard++) {
                String st = new String(readExact(4), "US-ASCII");
                r.status = st;

                if ("INFO".equals(st) || "TEXT".equals(st)) {
                    int len = parseHex(readExact(8));
                    String msg = len > 0 ? new String(readExact(len), "UTF-8") : "";
                    log.append(msg).append('\n');
                    continue;
                }
                if ("DATA".equals(st)) {
                    r.dataLen = parseHex(readExact(8));
                    r.log = log.toString();
                    return r;
                }
                if ("OKAY".equals(st)) {
                    r.okay = true;
                    byte[] h = tryRead(8, TIMEOUT_SHORT);
                    if (h != null) {
                        int len = parseHex(h);
                        if (len > 0) {
                            byte[] p = tryRead(len, 2000);
                            if (p != null) {
                                r.payload = new String(p, "UTF-8");
                            }
                        }
                    }
                    r.log = log.toString();
                    return r;
                }
                // FAIL 或其他
                r.okay = false;
                byte[] h = tryRead(8, TIMEOUT_SHORT);
                if (h != null) {
                    int len = parseHex(h);
                    if (len > 0) {
                        byte[] p = tryRead(len, 2000);
                        if (p != null) {
                            r.payload = new String(p, "UTF-8");
                        }
                    }
                }
                r.log = log.toString();
                return r;
            }
            r.log = log.toString();
            throw new IOException("应答异常（INFO 过多）");
        }

        // -------------------------------------------------------- 命令

        /** 执行普通命令（无数据阶段）。 */
        public Resp exec(String cmd) throws IOException {
            if (closed) {
                throw new IOException("会话已关闭");
            }
            write(hex8(cmd.length()) + cmd);
            return readResp();
        }

        /** getvar 取值。 */
        public String getVar(String name) throws IOException {
            Resp r = exec("getvar:" + name);
            return r.payload == null ? "" : r.payload.trim();
        }

        /** 读取设备信息（产品/序列号/安全状态）。 */
        public String info() {
            StringBuilder sb = new StringBuilder();
            String[] keys = {"product", "serialno", "version", "secure", "unlocked",
                    "current-slot", "slot-count", "max-download-size"};
            for (String k : keys) {
                try {
                    String v = getVar(k);
                    if (v != null && !v.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(k).append(": ").append(v);
                    }
                } catch (Throwable ignored) {
                }
            }
            return sb.toString();
        }

        /** 解析分区列表（getvar all）。 */
        public List<Part> partitions() throws IOException {
            Resp r = exec("getvar:all");
            String all = (r.log == null ? "" : r.log) + "\n" + r.payload;
            List<Part> out = new ArrayList<Part>();
            Pattern size = Pattern.compile(
                    "partition-size:([A-Za-z0-9_.\\-]+):\\s*(0x[0-9a-fA-F]+|\\d+)");
            Pattern type = Pattern.compile(
                    "partition-type:([A-Za-z0-9_.\\-]+):\\s*([^\\s]+)");
            java.util.LinkedHashMap<String, Part> map =
                    new java.util.LinkedHashMap<String, Part>();
            Matcher ms = size.matcher(all);
            while (ms.find()) {
                Part p = new Part();
                p.name = ms.group(1);
                try {
                    String v = ms.group(2);
                    p.size = v.startsWith("0x") || v.startsWith("0X")
                            ? Long.parseLong(v.substring(2), 16) : Long.parseLong(v);
                } catch (Throwable ignored) {
                    p.size = 0;
                }
                map.put(p.name, p);
            }
            Matcher mt = type.matcher(all);
            while (mt.find()) {
                Part p = map.get(mt.group(1));
                if (p != null) {
                    p.type = mt.group(2);
                }
            }
            out.addAll(map.values());
            return out;
        }

        /** 读取 max-download-size，决定分块大小。 */
        public void prepare() {
            try {
                String v = getVar("max-download-size");
                long max = Long.parseLong(v.replace("0x", "").trim(),
                        v.startsWith("0x") ? 16 : 10);
                if (max > 0) {
                    chunkSize = (int) Math.min(max, 1024L * 1024L);
                }
            } catch (Throwable ignored) {
            }
            if (chunkSize < 4096) {
                chunkSize = 4096;
            }
        }

        // -------------------------------------------------------- 下载 + 刷写

        /** 把文件下载到设备缓冲区。 */
        public void download(File f, Progress p) throws IOException {
            long total = f.length();
            if (total <= 0) {
                throw new IOException("镜像为空");
            }
            FileInputStream in = new FileInputStream(f);
            long sent = 0;
            try {
                while (sent < total) {
                    int want = (int) Math.min(chunkSize, total - sent);
                    Resp r = exec("download:" + hex8(want));
                    if (r.dataLen <= 0) {
                        throw new IOException("download 被拒绝：" + r.status + " " + r.payload);
                    }
                    int n = (int) Math.min(r.dataLen, total - sent);
                    byte[] buf = new byte[n];
                    int got = in.read(buf, 0, n);
                    if (got <= 0) {
                        throw new IOException("读取镜像失败");
                    }
                    int off = 0;
                    while (off < got) {
                        int w = conn.bulkTransfer(epOut, slice(buf, off, got - off),
                                got - off, TIMEOUT_IO);
                        if (w <= 0) {
                            throw new IOException("发送数据失败");
                        }
                        off += w;
                    }
                    sent += got;
                    Resp ack = readResp();
                    if (!ack.okay) {
                        throw new IOException("数据被拒绝：" + ack.payload);
                    }
                    if (p != null) {
                        p.onProgress(sent, total, null);
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }

        /** 刷写分区。 */
        public void flash(String part, File img, Progress p) throws IOException {
            download(img, p);
            if (p != null) {
                p.onProgress(img.length(), img.length(), "写入分区 " + part + "…");
            }
            Resp r = exec("flash:" + part);
            if (!r.okay) {
                throw new IOException("刷写失败：" + r.payload);
            }
        }

        /** 擦除分区。 */
        public void erase(String part) throws IOException {
            Resp r = exec("erase:" + part);
            if (!r.okay) {
                throw new IOException("擦除失败：" + r.payload);
            }
        }

        /** 重启。 */
        public void reboot(String target) throws IOException {
            exec(target == null || target.isEmpty() ? "reboot" : "reboot-" + target);
        }

        /**
         * 尝试把分区内容读回主机（备份）。
         *
         * <p>fastboot 标准协议没有「读分区」，只能试各家 bootloader 的扩展命令。
         * 成功时写入 {@code out}，失败抛异常并给出原因。</p>
         */
        public void readPartition(String part, File out, Progress p) throws IOException {
            String[] cmds = {
                    "oem dump:" + part,
                    "oem read:" + part,
                    "oem dump " + part,
                    "fetch:" + part,
                    "read:" + part,
            };
            StringBuilder reasons = new StringBuilder();
            for (String cmd : cmds) {
                try {
                    Resp r = exec(cmd);
                    if (!r.okay) {
                        reasons.append(cmd).append(" → ").append(r.payload).append('\n');
                        continue;
                    }
                    String payload = r.payload == null ? "" : r.payload;
                    if (payload.isEmpty()) {
                        reasons.append(cmd).append(" → 无数据\n");
                        continue;
                    }
                    byte[] data = decodePayload(payload);
                    if (data == null || data.length == 0) {
                        reasons.append(cmd).append(" → 数据无法解码\n");
                        continue;
                    }
                    FileOutputStream fo = new FileOutputStream(out);
                    try {
                        fo.write(data);
                    } finally {
                        try {
                            fo.close();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (p != null) {
                        p.onProgress(data.length, data.length, "已保存 " + out.getName());
                    }
                    return;
                } catch (IOException e) {
                    reasons.append(cmd).append(" → ").append(e.getMessage()).append('\n');
                }
            }
            throw new IOException("该 bootloader 不支持读回分区（fastboot 标准无读命令）。\n\n"
                    + "已尝试：\n" + reasons
                    + "\n请改用：本机 root 的 dd 备份，或在电脑上用 fastboot/edl 工具备份。");
        }

        /** 负载可能是 base64 或十六进制文本，尽力解码。 */
        private static byte[] decodePayload(String payload) {
            String s = payload.trim();
            // base64
            try {
                byte[] b = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
                if (b != null && b.length > 16) {
                    return b;
                }
            } catch (Throwable ignored) {
            }
            // hex
            String hex = s.replaceAll("[^0-9a-fA-F]", "");
            if (hex.length() >= 32 && hex.length() % 2 == 0) {
                byte[] b = new byte[hex.length() / 2];
                for (int i = 0; i < b.length; i++) {
                    b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
                return b;
            }
            // 原样当文本
            try {
                return s.getBytes("UTF-8");
            } catch (Throwable t) {
                return null;
            }
        }

        /** 结束会话。 */
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