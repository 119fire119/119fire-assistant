package com.fire119.assistant;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class CallRecordingWorker extends Worker {
    private static final String CHANNEL = "119fire_call_recordings";

    public CallRecordingWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences p = ctx.getSharedPreferences("119fire_native", Context.MODE_PRIVATE);
        boolean recordingMonitor = p.getBoolean("monitor_enabled", false);
        boolean callHistoryMonitor = p.getBoolean("call_history_enabled", false);
        if (!recordingMonitor && !callHistoryMonitor) return Result.success();

        try {
            if (callHistoryMonitor) PhoneCallHistorySync.sync(ctx);
        } catch (SecurityException ignored) {
            p.edit().putBoolean("call_history_enabled", false).apply();
        } catch (Exception ignored) {
            // A call-log refresh failure must not stop recording-folder detection.
        }

        if (!recordingMonitor) return Result.success();

        String raw = p.getString("call_folder_uri", "");
        if (raw.isEmpty()) return Result.success();

        long lastSeen = p.getLong("monitor_last_seen", System.currentTimeMillis());
        long newest = lastSeen;
        int count = 0;

        try {
            DocumentFile folder = DocumentFile.fromTreeUri(ctx, Uri.parse(raw));
            if (folder == null || !folder.exists()) return Result.success();

            for (DocumentFile f : folder.listFiles()) {
                if (!f.isFile()) continue;
                String mime = f.getType() == null ? "" : f.getType().toLowerCase();
                String name = f.getName() == null ? "" : f.getName().toLowerCase();
                boolean audio = mime.startsWith("audio/") ||
                        name.endsWith(".m4a") || name.endsWith(".mp3") ||
                        name.endsWith(".wav") || name.endsWith(".amr") ||
                        name.endsWith(".3gp") || name.endsWith(".aac");
                if (!audio) continue;

                long modified = f.lastModified();
                if (modified > lastSeen) {
                    WorkDatabase.Store store = WorkDatabase.get(ctx).store();
                    if (store.callByUri(f.getUri().toString()) == null) {
                        WorkDatabase.CallRecord call = new WorkDatabase.CallRecord();
                        call.sourceUri = f.getUri().toString();
                        call.displayName = f.getName() == null ? "통화녹음" : f.getName();
                        call.fileSize = f.length(); call.modifiedAt = modified; call.processingStatus = "대기";
                        long id = store.insertCall(call);
                        if (id > 0) {
                            WorkDatabase.AutomationJob job = new WorkDatabase.AutomationJob();
                            job.type = "AI_ANALYZE_CALL"; job.dedupeKey = "call:" + call.sourceUri;
                            job.payloadJson = "{\"callId\":" + id + "}";
                            store.insertJob(job);
                            count++;
                        }
                    }
                    if (modified > newest) newest = modified;
                }
            }

            if (count > 0) notifyNew(ctx, count);
            p.edit().putLong("monitor_last_seen", Math.max(newest, System.currentTimeMillis()-1000)).apply();
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private void notifyNew(Context ctx, int count) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "통화녹음 감지", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(com.fire119.assistant.R.drawable.ic_launcher)
                .setContentTitle("119파이어 비서")
                .setContentText("새 통화녹음 " + count + "개를 발견했습니다.")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        nm.notify(11901, b.build());
    }
}
