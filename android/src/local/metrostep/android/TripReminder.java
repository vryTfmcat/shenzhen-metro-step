package local.metrostep.android;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;

public final class TripReminder extends BroadcastReceiver {
    private static final String RIDE_CHANNEL = "metro_ride";
    private static final String ALERT_CHANNEL = "metro_arrival_alarm";
    private static final String STOP_RING = "local.metrostep.android.STOP_RING";
    private static final String STATE = "metro_reminder";
    private static final int RIDE_ID = 2810;
    private static final int ALERT_ID = 2811;
    private static final int ALARM_ID = 2812;
    private static final int PREVIEW_ID = 2813;
    private static final long MAX_TIMER_MS = 4 * 60 * 60 * 1000L;

    @Override public void onReceive(Context context, Intent intent) {
        if (STOP_RING.equals(intent.getAction())) {
            stopRinging(context);
            return;
        }
        long expected = intent.getLongExtra("deadline", 0);
        JSONObject current = read(context);
        if (expected == 0 || expected != current.optLong("deadline") || current.optBoolean("fired")) return;
        fire(context, current);
    }

    static boolean exactAvailable(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static boolean canNotify(Context context) {
        return context.getSystemService(NotificationManager.class).areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    static boolean alertChannelEnabled(Context context) {
        setupChannels(context);
        NotificationManager notices = context.getSystemService(NotificationManager.class);
        return notices.getNotificationChannel(ALERT_CHANNEL).getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static JSONObject read(Context context) {
        try {
            return new JSONObject(prefs(context).getString("trip", "{}"));
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    static void update(Context context, String raw) {
        try {
            JSONObject current = new JSONObject(raw);
            if (current.optString("title").isEmpty()) {
                clear(context);
                return;
            }
            JSONObject old = read(context);
            long deadline = current.optLong("deadline");
            if (deadline > System.currentTimeMillis() + MAX_TIMER_MS || deadline < 0) return;
            boolean sameReminder = deadline == old.optLong("deadline")
                    && current.optString("title").equals(old.optString("title"));
            boolean fired = sameReminder && old.optBoolean("fired");
            current.put("fired", fired);
            save(context, current);
            if (!sameReminder) {
                cancelTimer(context, old.optLong("deadline"));
                stopRinging(context);
            }
            if (deadline > 0 && !fired) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    if (canNotify(context)) fire(context, current);
                    return;
                }
                PendingIntent wakeup = alarm(context, deadline);
                long elapsedDeadline = SystemClock.elapsedRealtime() + remaining;
                if (exactAvailable(context)) {
                    try {
                        alarms(context).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDeadline, wakeup);
                    } catch (SecurityException error) {
                        alarms(context).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDeadline, wakeup);
                    }
                } else {
                    alarms(context).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDeadline, wakeup);
                }
            }
            postRide(context, current);
        } catch (JSONException ignored) {
            // Discard malformed messages from the embedded page.
        }
    }

    private static void clear(Context context) {
        cancelTimer(context, read(context).optLong("deadline"));
        prefs(context).edit().remove("trip").apply();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.cancel(RIDE_ID);
        stopRinging(context);
    }

    private static void fire(Context context, JSONObject current) {
        if (!canNotify(context)) return;
        try {
            current.put("fired", true);
        } catch (JSONException error) {
            return;
        }
        save(context, current);
        postRide(context, current);
        ring(context, ALERT_ID, "预计接近 " + current.optString("station") + " · 请准备下车",
                "听车内报站，确认后下车。", 60_000L);
    }

    static void preview(Context context) {
        if (canNotify(context) && alertChannelEnabled(context)) {
            ring(context, PREVIEW_ID, "地铁下车闹铃试听", "约 8 秒后自动停止。", 8_000L);
        }
    }

    static void stopRinging(Context context) {
        context.stopService(new Intent(context, AlarmPlaybackService.class));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.cancel(ALERT_ID);
        manager.cancel(PREVIEW_ID);
    }

    private static void ring(Context context, int id, String title, String message, long timeout) {
        if (AlarmPlaybackService.start(context, title, message, timeout)) return;
        setupChannels(context);
        Notification alert = new Notification.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(openApp(context))
                .addAction(new Notification.Action.Builder(null, "停止响铃", stopAction(context)).build())
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setTimeoutAfter(timeout)
                .build();
        alert.flags |= Notification.FLAG_INSISTENT;
        context.getSystemService(NotificationManager.class).notify(id, alert);
    }

    static PendingIntent stopAction(Context context) {
        Intent intent = new Intent(context, TripReminder.class).setAction(STOP_RING);
        return PendingIntent.getBroadcast(context, PREVIEW_ID, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private static void postRide(Context context, JSONObject current) {
        if (!canNotify(context)) return;
        setupChannels(context);
        long deadline = current.optLong("deadline");
        boolean countdown = deadline > System.currentTimeMillis() && !current.optBoolean("fired");
        String detail = current.optBoolean("fired") ? "预计接近目标站，请听车内报站" : current.optString("subtitle");
        Notification.Builder builder = new Notification.Builder(context, RIDE_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(current.optString("title"))
                .setContentText(detail)
                .setContentIntent(openApp(context))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (countdown) {
            builder.setWhen(deadline).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true);
        } else {
            builder.setShowWhen(false);
        }
        if (Build.VERSION.SDK_INT >= 36) {
            Bundle extra = new Bundle();
            extra.putBoolean("android.requestPromotedOngoing", true);
            if (!countdown) extra.putCharSequence("android.shortCriticalText",
                    current.optBoolean("fired") ? "准备下车" : "地铁导航");
            builder.addExtras(extra);
        }
        context.getSystemService(NotificationManager.class).notify(RIDE_ID, builder.build());
    }

    private static void setupChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel ride = new NotificationChannel(RIDE_CHANNEL, "乘车进度", NotificationManager.IMPORTANCE_DEFAULT);
        ride.setSound(null, null);
        ride.enableVibration(false);
        ride.setShowBadge(false);
        manager.createNotificationChannel(ride);
        NotificationChannel alert = new NotificationChannel(ALERT_CHANNEL, "下车闹铃", NotificationManager.IMPORTANCE_HIGH);
        alert.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        alert.setVibrationPattern(new long[]{0, 650, 250, 650, 450, 950});
        alert.enableVibration(true);
        alert.setShowBadge(false);
        manager.createNotificationChannel(alert);
    }

    private static AlarmManager alarms(Context context) {
        return context.getSystemService(AlarmManager.class);
    }

    private static Intent alarmIntent(Context context, long deadline) {
        return new Intent(context, TripReminder.class)
                .setData(Uri.parse("metro-reminder:" + deadline)).putExtra("deadline", deadline);
    }

    private static PendingIntent alarm(Context context, long deadline) {
        return PendingIntent.getBroadcast(context, ALARM_ID, alarmIntent(context, deadline),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelTimer(Context context, long deadline) {
        if (deadline == 0) return;
        PendingIntent previous = PendingIntent.getBroadcast(context, ALARM_ID, alarmIntent(context, deadline),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (previous != null) {
            alarms(context).cancel(previous);
            previous.cancel();
        }
    }

    static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, RIDE_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }

    private static void save(Context context, JSONObject current) {
        prefs(context).edit().putString("trip", current.toString()).apply();
    }
}
