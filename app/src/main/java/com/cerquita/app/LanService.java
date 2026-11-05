package com.cerquita.app;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;

/** Mantiene la app escuchando mensajes con la pantalla apagada o la app cerrada. */
public class LanService extends Service {
    private WifiManager.MulticastLock multicast;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            LanCore core = Hub.get(this); // crea también los canales de notificación
            Notification n = new Notification.Builder(this, Hub.CHANNEL_BG)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Cerquita está activa")
                    .setContentText("Lista para recibir mensajes en tu red")
                    .setContentIntent(Hub.openApp(this))
                    .build();
            startForeground(1, n);
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            multicast = wifi.createMulticastLock("cerquita");
            multicast.setReferenceCounted(false);
            multicast.acquire();
            core.start();
        } catch (Exception e) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (multicast != null && multicast.isHeld()) multicast.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
