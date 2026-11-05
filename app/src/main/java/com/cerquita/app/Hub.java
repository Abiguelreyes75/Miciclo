package com.cerquita.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;

/** Una sola instancia de la red para toda la app, viva mientras exista el proceso. */
final class Hub {
    static final String CHANNEL_MSG = "mensajes";
    static final String CHANNEL_BG = "conexion";
    private static LanCore core;
    static volatile Runnable onChange;
    static volatile boolean visible;

    private Hub() {}

    static synchronized LanCore get(Context ctx) throws Exception {
        if (core == null) {
            final Context app = ctx.getApplicationContext();
            channels(app);
            core = new LanCore(new File(app.getFilesDir(), "cerquita"), LanCore.DISCOVERY_PORT, (notify, from) -> {
                Runnable r = onChange;
                if (r != null) r.run();
                if (notify && !visible) notifyMessage(app, from);
            });
        }
        return core;
    }

    private static void channels(Context c) {
        NotificationManager m = c.getSystemService(NotificationManager.class);
        m.createNotificationChannel(new NotificationChannel(CHANNEL_MSG, "Mensajes", NotificationManager.IMPORTANCE_HIGH));
        NotificationChannel bg = new NotificationChannel(CHANNEL_BG, "Conexión activa", NotificationManager.IMPORTANCE_MIN);
        bg.setShowBadge(false);
        m.createNotificationChannel(bg);
    }

    static PendingIntent openApp(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notifyMessage(Context c, String from) {
        Notification n = new Notification.Builder(c, CHANNEL_MSG)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Cerquita")
                .setContentText("Mensaje o llamada de " + from)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(openApp(c))
                .build();
        try {
            c.getSystemService(NotificationManager.class).notify(2, n);
        } catch (SecurityException ignored) {
            // Sin permiso de notificaciones: el mensaje se verá al abrir la app.
        }
    }

    static boolean tiramisuOrLater() {
        return Build.VERSION.SDK_INT >= 33;
    }
}
