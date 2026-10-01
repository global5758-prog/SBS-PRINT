package id.sbsqu.sbprint;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Layar utama aplikasi = halaman SB Print yang sama dengan versi web (sbsqu.com/print),
 * dijalankan di dalam aplikasi dan disambungkan ke Bluetooth HP lewat "AndroidBridge".
 * Semua fasilitasnya sama: kop struk + logo, pengaturan format, isi struk, pratinjau,
 * QR/barcode, test print, laci kas, cetak ulang, perkecil/keluar, sakelar default.
 */
public class MainActivity extends Activity {
    public static final String EXTRA_PENDING = "pending";
    private static final int REQ_PERM = 7;
    private static final int REQ_FILE = 8;
    private static final String PAGE = "file:///android_asset/print/index.html";

    private WebView web;
    private byte[] pending;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pending = getIntent().getByteArrayExtra(EXTRA_PENDING);

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    toast("Tidak bisa membuka pemilih file");
                    return false;
                }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl(PAGE);

        if (!Printer.hasPermission(this)) {
            requestPermissions(new String[]{Printer.PERM_CONNECT}, REQ_PERM);
        }
        if (pending != null) toast("Pilih printer di daftar — struk dari SB Sejahtera langsung dicetak");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        byte[] p = intent.getByteArrayExtra(EXTRA_PENDING);
        if (p != null) { pending = p; toast("Pilih printer di daftar — struk langsung dicetak"); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        notifyPage();
    }

    private void notifyPage() {
        if (web != null) web.evaluateJavascript("window.sbprintAppResumed && window.sbprintAppResumed()", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) notifyPage();
        else toast("Izin Bluetooth diperlukan supaya SB Print bisa mencetak");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void printPendingInBackground() {
        final byte[] p = pending;
        if (p == null) return;
        pending = null;
        new Thread(() -> {
            String err = null;
            try { Printer.send(getApplicationContext(), p); } catch (Exception e) { err = e.getMessage(); }
            final String msg = err == null ? "✅ Struk dari SB Sejahtera tercetak" : "⚠️ Gagal cetak: " + err;
            runOnUiThread(() -> { toast(msg); notifyPage(); });
        }).start();
    }

    /** Fungsi yang bisa dipanggil halaman SB Print (window.AndroidBridge.xxx). */
    private class Bridge {
        @JavascriptInterface
        public String getPairedDevices() {
            JSONArray arr = new JSONArray();
            try {
                if (!Printer.hasPermission(MainActivity.this)) return "[]";
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) return "[]";
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                List<JSONObject> printers = new ArrayList<>(), others = new ArrayList<>();
                for (BluetoothDevice d : bonded) {
                    JSONObject o = new JSONObject();
                    o.put("name", d.getName() == null ? d.getAddress() : d.getName());
                    o.put("address", d.getAddress());
                    BluetoothClass bc = d.getBluetoothClass();
                    boolean isPrinter = bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING;
                    (isPrinter ? printers : others).add(o);
                }
                for (JSONObject o : printers) arr.put(o);   // printer ditaruh paling atas
                for (JSONObject o : others) arr.put(o);
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface
        public String getDefaultPrinter() {
            String a = Printer.address(MainActivity.this);
            return a == null ? "" : a;
        }

        @JavascriptInterface
        public void setDefaultPrinter(String address, String name) {
            if (address == null || address.isEmpty()) return;
            Printer.save(MainActivity.this, address, name);
            runOnUiThread(MainActivity.this::printPendingInBackground);
        }

        @JavascriptInterface
        public String printBase64(String b64, String address) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                Printer.sendTo(MainActivity.this, address, data);
                return "ok";
            } catch (Exception e) {
                return e.getMessage() == null ? "Gagal mengirim ke printer" : e.getMessage();
            }
        }

        @JavascriptInterface
        public String getLastJob() { return Printer.lastJob(MainActivity.this); }

        @JavascriptInterface
        public void openBluetoothSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void exitApp() {
            Printer.close();
            runOnUiThread(MainActivity.this::finishAndRemoveTask);
        }
    }
}
