package com.kareem.aqarexittool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Posts one notification per watch that found new units. */
public final class Notifier {
    private static final String CHANNEL = "watch";

    private Notifier() {
    }

    public static void post(Context ctx, String json) {
        try {
            Object root = new JSONTokener(json).nextValue();
            if (!(root instanceof JSONArray)) return;
            JSONArray arr = (JSONArray) root;
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "وحدات جديدة", NotificationManager.IMPORTANCE_DEFAULT));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int n = o.optInt("count", 0);
                if (n <= 0) continue;
                String id = o.optString("id", "w");
                String label = o.optString("label", "");
                StringBuilder big = new StringBuilder(label);
                JSONArray top = o.optJSONArray("top");
                if (top != null) for (int k = 0; k < top.length(); k++) big.append("\n• ").append(top.optString(k));

                Intent open = new Intent(ctx, MainActivity.class);
                open.putExtra(MainActivity.EXTRA_URL, "https://aqarexit.com/opportunities#ax=watch&id=" + id);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pi = PendingIntent.getActivity(ctx, id.hashCode(), open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

                Notification note = new Notification.Builder(ctx, CHANNEL)
                        .setSmallIcon(R.drawable.ic_notify)
                        .setContentTitle(n + (n == 1 ? " وحدة جديدة" : " وحدات جديدة") + " — Aqar Finder")
                        .setContentText(label)
                        .setStyle(new Notification.BigTextStyle().bigText(big.toString()))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                nm.notify(id.hashCode(), note);
            }
        } catch (Exception ignored) {
        }
    }
}
