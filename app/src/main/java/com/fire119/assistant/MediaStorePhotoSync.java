package com.fire119.assistant;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.List;

/**
 * Imports metadata for newly captured Samsung Camera photos. The original image is never moved
 * or deleted. A record is linked automatically only while one field is clearly marked as active;
 * otherwise it becomes a review item rather than being guessed into the wrong site.
 */
public final class MediaStorePhotoSync {
    private MediaStorePhotoSync() {}

    public static boolean hasPermission(Context context) {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    public static int sync(Context context) throws SecurityException {
        if (!hasPermission(context)) throw new SecurityException("사진 접근 권한이 필요합니다.");
        SharedPreferences prefs = context.getSharedPreferences("119fire_native", Context.MODE_PRIVATE);
        // First sync must inspect the newest images. Oldest-first scanning could reach its
        // safety limit and then mark newer field photos as already seen.
        long last = prefs.getLong("photo_last_seen", 0L);
        if (last <= 0L) last = Math.max(0L, System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L);
        long queryFrom = Math.max(0L, last - 3000L);
        long newest = last;
        int imported = 0;
        WorkDatabase.Store store = WorkDatabase.get(context).store();
        boolean modernPath = Build.VERSION.SDK_INT >= 29;
        String pathColumn = modernPath ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.BUCKET_DISPLAY_NAME;
        String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE, pathColumn};
        String selection = "(" + MediaStore.Images.Media.DATE_TAKEN + ">? OR " +
                MediaStore.Images.Media.DATE_MODIFIED + ">?)";
        String[] args = {String.valueOf(queryFrom), String.valueOf(queryFrom / 1000L)};
        try (Cursor cursor = context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, MediaStore.Images.Media.DATE_TAKEN + " DESC")) {
            if (cursor == null) return 0;
            int idIx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameIx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int takenIx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
            int modifiedIx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
            int sizeIx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            int pathIx = cursor.getColumnIndexOrThrow(pathColumn);
            int inspected = 0;
            while (cursor.moveToNext() && inspected++ < 300) {
                String relative = cursor.isNull(pathIx) ? "" : cursor.getString(pathIx);
                // Do not index screenshots/downloads or unrelated images; Samsung Camera normally uses DCIM/Camera.
                if (!relative.toLowerCase().contains("dcim") && !relative.toLowerCase().contains("camera")) continue;
                long id = cursor.getLong(idIx);
                long taken = cursor.getLong(takenIx);
                if (taken <= 0) taken = cursor.getLong(modifiedIx) * 1000L;
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                // Photo.path is the durable content URI; no original file is copied or removed.
                WorkDatabase.Photo photo = new WorkDatabase.Photo();
                photo.path = uri.toString(); photo.name = cursor.getString(nameIx); photo.createdAt = taken;
                photo.category = "미분류"; photo.backupStatus = "Drive 연결 대기";
                WorkDatabase.Site active = uniqueActiveSite(store.sites(50));
                if (active != null) {
                    photo.siteId = active.id;
                    photo.category = categoryFor(active, taken);
                    photo.backupStatus = "Drive 업로드 대기";
                } else {
                    WorkDatabase.AutomationJob review = new WorkDatabase.AutomationJob();
                    review.type = "PHOTO_NEEDS_REVIEW"; review.dedupeKey = "photo:" + uri;
                    review.payloadJson = "{\"uri\":\"" + uri + "\",\"name\":\"" + jsonSafe(photo.name) + "\"}";
                    review.status = "확인필요"; store.insertJob(review);
                }
                // Photo.path has a unique index, so a repeated media scan cannot duplicate this row.
                long inserted = store.insertPhoto(photo);
                if (inserted > 0) {
                    imported++;
                    if (photo.siteId != null && prefs.getBoolean("drive_backup_enabled", false))
                        DrivePhotoUploadWorker.enqueue(context, inserted);
                }
                newest = Math.max(newest, taken);
            }
        }
        prefs.edit().putLong("photo_last_seen", newest).apply();
        if (imported > 0) AutomationLog.add(context, "삼성 카메라 새 사진 " + imported + "장을 확인했습니다.");
        return imported;
    }

    private static WorkDatabase.Site uniqueActiveSite(List<WorkDatabase.Site> sites) {
        WorkDatabase.Site candidate = null;
        for (WorkDatabase.Site site : sites) {
            if (!V3Workflow.WORKING.equals(site.status)) continue;
            if (candidate != null) return null; // two active fields = no guessing
            candidate = site;
        }
        return candidate;
    }

    private static String categoryFor(WorkDatabase.Site site, long takenAt) {
        if (site.startedAt > 0 && takenAt < site.startedAt) return "공사 전";
        if (site.finishedAt > 0 && takenAt >= site.finishedAt) return "공사 후";
        return "공사 중";
    }

    private static String jsonSafe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
