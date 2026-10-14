package es.cuentasclaras.app;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lee las notificaciones que llegan al móvil y, si una es un pago con tarjeta
 * (banco, Google Wallet, Samsung Wallet…), lo apunta como gasto.
 * Todo se procesa en el propio móvil: la app no tiene acceso a internet.
 */
public class PaymentListener extends NotificationListenerService {

    /** Apps de mensajería, correo y redes: nunca se leen. */
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
            "com.instagram.android", "com.facebook.orca", "com.facebook.katana", "com.twitter.android",
            "com.zhiliaoapp.musically", "com.discord", "com.snapchat.android", "com.google.android.youtube",
            "com.android.systemui", "android", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.wallapop", "com.amazon.mShop.android.shopping"));

    /** Monederos que muestran el comercio como título y el importe como texto, sin la palabra "pago". */
    private static final Set<String> WALLETS = new HashSet<>(Arrays.asList(
            "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user",
            "com.samsung.android.spay", "com.samsung.android.samsungpay.gear"));

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Exception ignored) { }
    }

    private void handle(StatusBarNotification sbn) throws Exception {
        if (sbn == null || !Store.autoEnabled(this)) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName()) || SKIP.contains(pkg)) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle ex = n.extras;
        if (ex == null) return;

        String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
        String big = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String body = big.length() > text.length() ? big : text;

        PaymentParser.Result r = PaymentParser.parse(title, body, WALLETS.contains(pkg));
        if (r == null) return;

        long now = System.currentTimeMillis();
        if (Store.isDuplicate(this, r.amount, now)) return;

        String app = appLabel(pkg);
        String merchant = r.merchant != null ? r.merchant : app;
        long when = sbn.getPostTime() > 0 ? sbn.getPostTime() : now;

        JSONObject p = new JSONObject();
        p.put("amt", r.amount);
        p.put("merchant", merchant);
        p.put("app", app);
        p.put("ts", when);
        Store.addPending(this, p);

        String ym = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date(when));
        double[] res = Store.addToSummary(this, r.amount, ym);
        NumberFormat eur = NumberFormat.getCurrencyInstance(new Locale("es", "ES"));
        String paid = eur.format(r.amount) + " en " + merchant;

        int level = (int) res[0];
        if (level >= 100) {
            Notifs.show(this, Notifs.CH_ALERTS, "Has superado tu presupuesto",
                    "Último pago: " + paid + ". Llevas " + eur.format(res[1]) + " de " + eur.format(res[2])
                            + " este mes.");
        } else if (level > 0) {
            Notifs.show(this, Notifs.CH_ALERTS, "Has llegado al " + level + " % de tu presupuesto",
                    "Último pago: " + paid + ". Te quedan " + eur.format(res[2] - res[1]) + " este mes.");
        } else {
            Notifs.show(this, Notifs.CH_PAYMENTS, "Gasto añadido: " + eur.format(r.amount),
                    merchant + " · detectado en " + app + ". Toca para revisarlo.");
        }

        MainActivity.refreshIfOpen();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l != null ? l.toString() : pkg;
        } catch (Exception e) {
            return "tu banco";
        }
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString();
    }
}
