package id.sbsqu.sbprint;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Pengiriman byte ESC/POS ke printer thermal lewat Bluetooth Classic (SPP) —
 * cara yang sama dengan RawBT. Sambungan disimpan selama aplikasi hidup supaya
 * cetakan berikutnya langsung jalan tanpa menyambung ulang.
 */
public final class Printer {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String PREFS = "sbprint";
    public static final String PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    private static BluetoothSocket socket;
    private static String socketAddress;

    private Printer() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static String address(Context c) { return prefs(c).getString("address", null); }
    public static String name(Context c) { return prefs(c).getString("name", null); }

    public static void save(Context c, String address, String name) {
        prefs(c).edit().putString("address", address).putString("name", name).apply();
        close();
    }

    public static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return c.checkSelfPermission(PERM_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isReady(Context c) {
        return hasPermission(c) && address(c) != null;
    }

    /** Kirim byte ke printer default. Dipanggil dari thread latar belakang. */
    public static void send(Context c, byte[] data) throws IOException {
        sendTo(c, address(c), data);
    }

    /** Kirim byte ke printer tertentu (alamat Bluetooth). Dipanggil dari thread latar belakang. */
    public static synchronized void sendTo(Context c, String addr, byte[] data) throws IOException {
        if (addr == null || addr.isEmpty()) throw new IOException("Printer default belum dipilih");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IOException("HP ini tidak punya Bluetooth");
        if (!adapter.isEnabled()) throw new IOException("Bluetooth HP masih mati — nyalakan dulu");

        try {
            writeAll(ensureSocket(adapter, addr), data);
        } catch (IOException first) {
            // Sambungan lama mungkin sudah putus (printer dimatikan) — sambung ulang sekali
            close();
            writeAll(ensureSocket(adapter, addr), data);
        }
        saveLastJob(c, data);
    }

    /** Struk terakhir disimpan supaya bisa "Cetak Ulang Terakhir" dari layar aplikasi. */
    private static void saveLastJob(Context c, byte[] data) {
        try { prefs(c).edit().putString("lastJob", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)).apply(); }
        catch (Exception ignored) { }
    }
    public static String lastJob(Context c) { return prefs(c).getString("lastJob", ""); }

    private static BluetoothSocket ensureSocket(BluetoothAdapter adapter, String addr) throws IOException {
        if (socket != null && socket.isConnected() && addr.equals(socketAddress)) return socket;
        close();
        BluetoothDevice device = adapter.getRemoteDevice(addr);
        try { adapter.cancelDiscovery(); } catch (SecurityException ignored) { }

        IOException last = null;
        // 1) cara standar  2) tanpa enkripsi (banyak printer Cina)  3) kanal 1 langsung
        for (int attempt = 0; attempt < 3; attempt++) {
            BluetoothSocket s = null;
            try {
                if (attempt == 0) s = device.createRfcommSocketToServiceRecord(SPP);
                else if (attempt == 1) s = device.createInsecureRfcommSocketToServiceRecord(SPP);
                else {
                    Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                    s = (BluetoothSocket) m.invoke(device, 1);
                }
                s.connect();
                socket = s;
                socketAddress = addr;
                return s;
            } catch (IOException e) {
                last = e;
                try { if (s != null) s.close(); } catch (IOException ignored) { }
            } catch (Exception e) {
                last = new IOException(e.getMessage() == null ? e.toString() : e.getMessage());
                try { if (s != null) s.close(); } catch (IOException ignored) { }
            }
        }
        throw new IOException("Printer tidak tersambung. Pastikan printer menyala dan dekat HP. ("
                + (last == null ? "-" : last.getMessage()) + ")");
    }

    private static void writeAll(BluetoothSocket s, byte[] data) throws IOException {
        OutputStream out = s.getOutputStream();
        final int chunk = 512;
        for (int i = 0; i < data.length; i += chunk) {
            out.write(data, i, Math.min(chunk, data.length - i));
            out.flush();
            try { Thread.sleep(20); } catch (InterruptedException ignored) { }
        }
        // beri waktu printer menerima sisa data sebelum pekerjaan dianggap selesai
        try { Thread.sleep(Math.min(1500, 200 + data.length / 20)); } catch (InterruptedException ignored) { }
    }

    public static synchronized void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null;
        socketAddress = null;
    }
}
