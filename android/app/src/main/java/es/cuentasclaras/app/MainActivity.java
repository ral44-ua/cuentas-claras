package es.cuentasclaras.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
 * El puente "CuentasAndroid" permite a la página lanzar notificaciones, guardar copias de
 * seguridad y recoger los pagos con tarjeta detectados por PaymentListener.
 */
public class MainActivity extends Activity {

    private static final int REQ_SAVE = 11;
    private static final int REQ_OPEN = 12;

    private static volatile MainActivity current;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifs.ensureChannels(this);
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

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        syncWeb();
    }

    @Override
    protected void onPause() {
        if (current == this) current = null;
        super.onPause();
    }

    /** Llamado por el servicio cuando detecta un pago con la app abierta. */
    static void refreshIfOpen() {
        final MainActivity a = current;
        if (a != null) a.runOnUiThread(a::syncWeb);
    }

    private void syncWeb() {
        if (web != null) web.evaluateJavascript("window.__cuentasSync && window.__cuentasSync()", null);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    private boolean listenerAllowed() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        ComponentName me = new ComponentName(this, PaymentListener.class);
        return flat.contains(me.flattenToString()) || flat.contains(me.flattenToShortString());
    }

    /** Métodos que la página puede llamar como window.CuentasAndroid.xxx() */
    public class Bridge {
        @JavascriptInterface
        public void notify(final String title, final String body) {
            runOnUiThread(() -> Notifs.show(MainActivity.this, Notifs.CH_ALERTS, title, body));
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

        /** Pagos detectados pendientes de añadir (JSON). Los entrega una sola vez. */
        @JavascriptInterface
        public String takePending() {
            return Store.takePending(MainActivity.this);
        }

        /** Avisos con importe que no se han apuntado (JSON). */
        @JavascriptInterface
        public String getSkipped() {
            return Store.getSkipped(MainActivity.this);
        }

        /** Quita un aviso de la lista ("*" para vaciarla). */
        @JavascriptInterface
        public void removeSkipped(String id) {
            Store.removeSkipped(MainActivity.this, id);
        }

        /** Resumen del mes para poder avisar del límite con la app cerrada. */
        @JavascriptInterface
        public void syncSummary(String json) {
            Store.syncSummary(MainActivity.this, json);
        }

        /** ¿Tiene la app permiso para leer notificaciones? */
        @JavascriptInterface
        public boolean isAutoAllowed() {
            return listenerAllowed();
        }

        /** Abre la pantalla de Android para dar acceso a las notificaciones. */
        @JavascriptInterface
        public void openAutoSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        }

        /** Abre la ficha de la app (para "Permitir ajustes restringidos" en Android 13+). */
        @JavascriptInterface
        public void openAppInfo() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public int sdk() {
            return Build.VERSION.SDK_INT;
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
