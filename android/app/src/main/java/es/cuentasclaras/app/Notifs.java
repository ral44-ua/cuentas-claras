package es.cuentasclaras.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Canales y envío de notificaciones de la app. */
final class Notifs {
    static final String CH_ALERTS = "avisos_presupuesto";
    static final String CH_PAYMENTS = "pagos_detectados";

    private Notifs() { }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel a = new NotificationChannel(CH_ALERTS, "Avisos de presupuesto",
                NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Te avisa cuando te acercas o superas tu límite de gasto mensual");
        nm.createNotificationChannel(a);
        NotificationChannel p = new NotificationChannel(CH_PAYMENTS, "Pagos añadidos automáticamente",
                NotificationManager.IMPORTANCE_DEFAULT);
        p.setDescription("Confirma cada pago con tarjeta que se apunta solo");
        nm.createNotificationChannel(p);
    }

    static void show(Context c, String channel, String title, String body) {
        ensureChannels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, channel)
                : new Notification.Builder(c);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nb.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) {
            nb.setPriority(CH_ALERTS.equals(channel) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
        }
        nm.notify((int) (System.nanoTime() % 1000000), nb.build());
    }
}
