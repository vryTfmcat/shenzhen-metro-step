package local.metrostep.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.io.IOException;

public final class AlarmPlaybackService extends Service {
    private static final String CHANNEL = "metro_playing_alarm";
    private static final int NOTIFICATION_ID = 2814;
    private static final AudioAttributes ALARM_AUDIO = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private ToneGenerator tone;
    private AudioFocusRequest focus;

    static boolean start(Context context, String title, String message, long duration) {
        try {
            context.startForegroundService(new Intent(context, AlarmPlaybackService.class)
                    .putExtra("title", title).putExtra("message", message).putExtra("duration", duration));
            return true;
        } catch (IllegalStateException | SecurityException error) {
            return false;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        releasePlayback();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "闹铃播放状态", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(intent.getStringExtra("title"))
                .setContentText(intent.getStringExtra("message"))
                .setContentIntent(TripReminder.openApp(this))
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止响铃", TripReminder.stopAction(this)).build());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        startForeground(NOTIFICATION_ID, builder.build());
        handler.postDelayed(() -> stopSelf(startId), Math.min(60_000L, Math.max(1_000L, intent.getLongExtra("duration", 8_000L))));
        playRingtone();
        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 650, 250, 650, 450, 950, 600}, 0), ALARM_AUDIO);
        }
        return START_NOT_STICKY;
    }

    private void playRingtone() {
        AudioManager audio = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ALARM_AUDIO).setOnAudioFocusChangeListener(change -> {}).build();
        audio.requestAudioFocus(focus);
        Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            playFallbackTone();
            return;
        }
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(ALARM_AUDIO);
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.setOnPreparedListener(ready -> {
                if (ready == player) ready.start();
            });
            player.setOnErrorListener((failed, what, extra) -> {
                if (failed == player) playFallbackTone();
                return true;
            });
            player.prepareAsync();
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException error) {
            playFallbackTone();
        }
    }

    private void playFallbackTone() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (tone != null) return;
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            pulse();
        } catch (RuntimeException ignored) {
            // The notification and vibration remain available if audio cannot be initialized.
        }
    }

    private void pulse() {
        if (tone == null) return;
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700);
        handler.postDelayed(this::pulse, 1_000L);
    }

    private void releasePlayback() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (tone != null) {
            tone.release();
            tone = null;
        }
        if (focus != null) {
            getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
            focus = null;
        }
        getSystemService(Vibrator.class).cancel();
    }

    @Override public void onDestroy() {
        releasePlayback();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
