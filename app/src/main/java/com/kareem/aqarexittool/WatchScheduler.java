package com.kareem.aqarexittool;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Keeps one hourly background check scheduled while at least one watch exists. */
final class WatchScheduler {
    private static final String NAME = "ax-watch";

    private WatchScheduler() {
    }

    static void apply(Context ctx, int watches) {
        WorkManager wm = WorkManager.getInstance(ctx.getApplicationContext());
        if (watches <= 0) {
            wm.cancelUniqueWork(NAME);
            return;
        }
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(WatchWorker.class, 1, TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }
}
