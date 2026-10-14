package es.cuentasclaras.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Cuentas Claras: la interfaz es una página web empaquetada en la app (assets/index.html).
 * Los datos se guardan en el almacenamiento interno de la app (localStorage del WebView).
 * El puente "CuentasAndroid" permite a la página lanzar notificaciones del sistema
 * y guardar copias de seguridad en un archivo.
 */
public class MainActivity extends Activity {

    private static final String CHANNEL_ID = "avisos_presupuesto";
    private static final int REQ_SAVE = 11;
    private static final int REQ_OPEN = 12;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createChannel();
        askNotificationPermission();

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "CuentasAndroid");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_OPEN);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Avisos de presupuesto",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Te avisa cuando te acercas o superas tu límite de gasto mensual");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    private void showNotification(String title, String body) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nb.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) nb.setPriority(Notification.PRIORITY_HIGH);
        nm.notify((int) (System.currentTimeMillis() % 100000), nb.build());
    }

    /** Métodos que la página puede llamar como window.CuentasAndroid.xxx() */
    public class Bridge {
        @JavascriptInterface
        public void notify(final String title, final String body) {
            runOnUiThread(() -> showNotification(title, body));
        }

        @JavascriptInterface
        public void saveFile(final String name, final String content) {
            runOnUiThread(() -> {
                pendingSave = content;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (Exception e) {
                    pendingSave = null;
                    Toast.makeText(MainActivity.this, "No se pudo abrir el selector de archivos", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN) {
            if (fileCallback != null) {
                Uri[] result = (resultCode == RESULT_OK && data != null && data.getData() != null)
                        ? new Uri[]{data.getData()} : null;
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
        } else if (requestCode == REQ_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSave != null) {
                try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                    if (os == null) throw new Exception("sin destino");
                    os.write(pendingSave.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "Copia de seguridad guardada", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "No se pudo guardar la copia", Toast.LENGTH_LONG).show();
                }
            }
            pendingSave = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.__cuentasBack && window.__cuentasBack()) ? 'si' : 'no'", value -> {
            if (value == null || !value.contains("si")) finish();
        });
    }
}
