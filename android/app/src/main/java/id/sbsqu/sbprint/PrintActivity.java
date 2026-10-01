package id.sbsqu.sbprint;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.widget.Toast;

/**
 * Penerima cetak tanpa tampilan — dipanggil dari SB Sejahtera lewat alamat
 * "sbprint:base64,...." (format sama dengan "rawbt:base64,...."). Mencetak ke
 * printer default lalu langsung menutup diri, sehingga pengguna tetap di SB Sejahtera.
 */
public class PrintActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final byte[] data = readJob(getIntent());
        if (data == null || data.length == 0) {
            Toast.makeText(this, "SB Print: data struk kosong / tidak dikenali", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!Printer.isReady(this)) {
            // Belum ada izin / printer default — buka layar utama, struk dicetak setelah printer dipilih
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra(MainActivity.EXTRA_PENDING, data);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
            return;
        }
        Toast.makeText(this, "🖨️ SB Print: mencetak…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = null;
            try { Printer.send(getApplicationContext(), data); }
            catch (Exception e) { err = e.getMessage(); }
            final String msg = err == null ? "✅ Struk tercetak" : "⚠️ Gagal cetak: " + err;
            final boolean failed = err != null;
            runOnUiThread(() -> {
                Toast.makeText(getApplicationContext(), msg, failed ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                // jaga sambungan tetap terbuka untuk struk berikutnya
                PrinterService.connect(getApplicationContext());
                finish();
            });
        }).start();
    }

    /** Terima: sbprint:base64,XXXX  |  sbprint:XXXX (teks biasa)  |  bagikan teks (ACTION_SEND) */
    static byte[] readJob(Intent intent) {
        if (intent == null) return null;
        try {
            if (Intent.ACTION_SEND.equals(intent.getAction())) {
                String t = intent.getStringExtra(Intent.EXTRA_TEXT);
                return t == null ? null : (t + "\n\n\n").getBytes("ISO-8859-1");
            }
            String s = intent.getDataString();
            if (s == null) return null;
            int colon = s.indexOf(':');
            String body = colon >= 0 ? s.substring(colon + 1) : s;
            if (body.startsWith("//")) body = body.substring(2);
            if (body.contains("%")) body = Uri.decode(body);
            if (body.startsWith("base64,")) {
                String b64 = body.substring(7).replace(' ', '+').trim();
                return Base64.decode(b64, Base64.DEFAULT);
            }
            return body.getBytes("ISO-8859-1");
        } catch (Exception e) {
            return null;
        }
    }
}
