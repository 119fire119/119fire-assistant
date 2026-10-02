package com.fire119.assistant;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Keeps the user's opted-in automation alive with a visible notification. It deliberately does
 * not continuously record the microphone: the notification action opens the foreground activity
 * and then starts Android's normal speech recognizer after an explicit user tap.
 */
public final class BackgroundAssistantService extends Service {
    public static final String ACTION_START = "com.fire119.assistant.START_BACKGROUND";
    public static final String ACTION_STOP = "com.fire119.assistant.STOP_BACKGROUND";
    public static final String EXTRA_START_VOICE = "start_voice_command";
    private static final String CHANNEL = "119fire_background_assistant";
    private static final int NOTIFICATION_ID = 11930;

    public static void start(Context context) {
        Intent i = new Intent(context, BackgroundAssistantService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i); else context.startService(i);
    }
    public static void stop(Context context) {
        context.stopService(new Intent(context, BackgroundAssistantService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        getSharedPreferences("119fire_native", MODE_PRIVATE).edit().putBoolean("background_assistant_enabled", true).apply();
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        AutomationLog.add(this, "백그라운드 비서를 켰습니다.");
        return START_STICKY;
    }

    private android.app.Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent voice = new Intent(this, MainActivity.class).putExtra(EXTRA_START_VOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent voiceAction = PendingIntent.getActivity(this, 11, voice, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("119파이어 비서 대기중")
                .setContentText("통화녹음·카메라 사진을 주기적으로 확인합니다.")
                .setContentIntent(content)
                .addAction(0, "🎙 비서에게 말하기", voiceAction)
                .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).build();
    }
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "119파이어 자동비서", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        super.onDestroy();
        SharedPreferences p = getSharedPreferences("119fire_native", MODE_PRIVATE);
        if (!p.getBoolean("background_assistant_enabled", false)) AutomationLog.add(this, "백그라운드 비서를 껐습니다.");
    }
}
