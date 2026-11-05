package com.miciclo.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Guarda y programa el próximo aviso. Android borra las alarmas al reiniciar, por eso se guarda. */
final class Reminders {
    private static final String PREFS = "reminders";

    private Reminders() {}

    static void save(Context c, long at, String text) {
        prefs(c).edit().putLong("at", at).putString("text", text).apply();
        schedule(c);
    }

    static String text(Context c) {
        return prefs(c).getString("text", "Tu periodo podría llegar pronto.");
    }

    static void schedule(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = PendingIntent.getBroadcast(c, 0, new Intent(c, ReminderReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long at = prefs(c).getLong("at", 0);
        alarms.cancel(pending);
        if (at > System.currentTimeMillis()) {
            alarms.set(AlarmManager.RTC_WAKEUP, at, pending);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
