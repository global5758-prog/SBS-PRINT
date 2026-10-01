package id.sbsqu.sbprint;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Penjaga sambungan printer — seperti RawBT yang selalu siaga.
 * - Menyambung SEKALI ke printer default, lalu menjaga sambungan tetap terbuka.
 * - Kalau sambungan terputus (printer dimatikan / kejauhan), mencoba menyambung ulang
 *   beberapa kali secara otomatis. Kalau tetap gagal, baru muncul pemberitahuan
 *   "Printer terputus — ketuk untuk menyambung".
 * - Tombol "Putuskan" / "Keluar" menghentikan penjaga ini (printer tetap diingat).
 */
public class PrinterService extends Service {
    public static final String ACTION_CONNECT = "id.sbsqu.sbprint.CONNECT";
    public static final String ACTION_STOP = "id.sbsqu.sbprint.STOP";

    private static final String CH_STATUS = "sbprint_status";
    private static final String CH_ALERT = "sbprint_alert";
    private static final int NOTIF_ID = 11;
    private static final long[] RETRY_DELAYS = {3000, 8000, 20000};

    private static volatile boolean running = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retry = 0;
    private boolean stopping = false;
    private boolean connectingNow = false;

    public static boolean isRunning() { return running; }

    /** Mulai / sambung ulang penjaga sambungan (aman dipanggil berkali-kali). */
    public static void connect(Context c) {
        if (!Printer.isReady(c)) return;
        Intent i = new Intent(c, PrinterService.class).setAction(ACTION_CONNECT);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    /** Putuskan sambungan dengan sengaja dan hentikan penjaga. */
    public static void stop(Context c) {
        Printer.close(Printer.IDLE);   // diputus sengaja — bukan "terputus"
        try { c.startService(new Intent(c, PrinterService.class).setAction(ACTION_STOP)); } catch (Exception ignored) { }
    }

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (s == BluetoothAdapter.STATE_OFF) Printer.close(Printer.DISCONNECTED);
                else if (s == BluetoothAdapter.STATE_ON) { retry = 0; tryConnect(); }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannels();
        Printer.setListener(st -> handler.post(() -> onStateChanged(st)));
        try { registerReceiver(btReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)); } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_CONNECT : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopping = true;
            handler.removeCallbacksAndMessages(null);
            Printer.close(Printer.IDLE);
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Printer.isReady(this) || !goForeground()) { stopSelf(); return START_NOT_STICKY; }
        stopping = false;
        retry = 0;
        tryConnect();
        return START_STICKY;
    }

    private void tryConnect() {
        if (stopping || connectingNow) return;
        if (Printer.isConnected()) { updateNotification(); return; }
        connectingNow = true;
        new Thread(() -> {
            try { Printer.connect(getApplicationContext()); retry = 0; }
            catch (Exception ignored) { /* status DISCONNECTED sudah di-set oleh Printer */ }
            connectingNow = false;
            handler.post(this::updateNotification);
        }, "sbprint-connect").start();
    }

    private void onStateChanged(int st) {
        if (stopping) return;
        if (st == Printer.DISCONNECTED) {
            if (retry < RETRY_DELAYS.length) {
                long delay = RETRY_DELAYS[retry++];
                handler.postDelayed(this::tryConnect, delay);
            }
        } else if (st == Printer.CONNECTED) {
            retry = 0;
            handler.removeCallbacksAndMessages(null);
        }
        updateNotification();
    }

    // ---------- pemberitahuan ----------
    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(CH_STATUS, "Status printer", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("Menunjukkan printer thermal sedang tersambung");
        status.setShowBadge(false);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "Printer terputus", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("Memberi tahu saat sambungan printer terputus");
        nm.createNotificationChannel(status);
        nm.createNotificationChannel(alert);
    }

    private Notification buildNotification() {
        String name = Printer.name(this);
        if (name == null) name = "printer";
        int st = Printer.state();
        boolean lost = st == Printer.DISCONNECTED && retry >= RETRY_DELAYS.length && !connectingNow;
        String title, text;
        if (st == Printer.CONNECTED) { title = "🟢 SB Print — terhubung"; text = name + " siap mencetak"; }
        else if (lost) { title = "🔴 Printer terputus"; text = "Ketuk untuk menyambung lagi ke " + name; }
        else if (st == Printer.DISCONNECTED) { title = "🟠 Printer terputus"; text = "Mencoba menyambung lagi ke " + name + "…"; }
        else { title = "SB Print"; text = "Menyambungkan ke " + name + "…"; }

        Intent open = new Intent(this, MainActivity.class)
                .setAction(lost ? ACTION_CONNECT : Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piStop = PendingIntent.getService(this, 1,
                new Intent(this, PrinterService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, lost ? CH_ALERT : CH_STATUS)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(!lost)
                .addAction(new Notification.Action.Builder(null, "Putuskan", piStop).build());
        if (lost) {
            PendingIntent piRetry = PendingIntent.getService(this, 2,
                    new Intent(this, PrinterService.class).setAction(ACTION_CONNECT),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "Sambungkan", piRetry).build());
        }
        return b.build();
    }

    private boolean goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
            return true;
        } catch (Exception e) {
            return false;   // mis. izin Bluetooth belum diberikan
        }
    }

    private void updateNotification() {
        if (stopping) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        Printer.setListener(null);
        try { unregisterReceiver(btReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
