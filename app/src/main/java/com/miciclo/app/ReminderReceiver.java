package com.miciclo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Muestra el aviso. En la pantalla de bloqueo el contenido queda oculto. */
public class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "periodo";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Reminders.schedule(c);
            return;
        }
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Recordatorios de periodo",
                    NotificationManager.IMPORTANCE_DEFAULT));
            builder = new Notification.Builder(c, CHANNEL);
        } else {
            builder = new Notification.Builder(c);
        }
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Mi Ciclo")
                .setContentText(Reminders.text(c))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(open);
        manager.notify(1, builder.build());
    }
}
