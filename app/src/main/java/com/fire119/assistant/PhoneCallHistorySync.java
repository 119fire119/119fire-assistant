package com.fire119.assistant;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;

/**
 * Imports only the device's ordinary phone-log metadata.  It never records a call, uploads the
 * log, or queues a call-log row for transcription.  Audio files stay in the separate Samsung
 * recording-folder flow.
 */
public final class PhoneCallHistorySync {
    private PhoneCallHistorySync() {}

    public static int sync(Context context) throws SecurityException {
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("전화기록 접근 권한이 필요합니다.");
        }

        String[] projection = {
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE
        };
        int imported = 0;
        WorkDatabase.Store store = WorkDatabase.get(context).store();
        try (Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC")) {
            if (cursor == null) return 0;
            int idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID);
            int numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
            int nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME);
            int dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE);
            int durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION);
            int typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE);
            while (cursor.moveToNext()) {
                long callLogId = cursor.getLong(idIndex);
                String source = "calllog:" + callLogId;
                if (store.callByUri(source) != null) continue;

                String phone = normalizePhone(cursor.getString(numberIndex));
                String cachedName = cursor.getString(nameIndex);
                long date = cursor.getLong(dateIndex);
                long durationSeconds = cursor.getLong(durationIndex);
                int type = cursor.getInt(typeIndex);

                WorkDatabase.CallRecord row = new WorkDatabase.CallRecord();
                row.sourceUri = source;
                row.phone = phone;
                row.displayName = cachedName == null || cachedName.trim().isEmpty()
                        ? (phone.isEmpty() ? "번호 미확인 통화" : phone) : cachedName.trim();
                row.modifiedAt = date;
                row.createdAt = date;
                row.fileSize = durationSeconds;
                row.summary = label(type) + " · " + duration(durationSeconds);
                row.processingStatus = "통화기록";
                WorkDatabase.Customer customer = phone.isEmpty() ? null : store.customerByPhone(phone);
                if (customer != null) row.customerId = customer.id;
                if (store.insertCall(row) > 0) imported++;
            }
        }
        return imported;
    }

    private static String normalizePhone(String value) {
        return value == null ? "" : value.replaceAll("[^0-9+]", "");
    }

    private static String label(int type) {
        if (type == CallLog.Calls.INCOMING_TYPE) return "수신";
        if (type == CallLog.Calls.OUTGOING_TYPE) return "발신";
        if (type == CallLog.Calls.MISSED_TYPE) return "부재중";
        if (type == CallLog.Calls.REJECTED_TYPE) return "거절";
        if (type == CallLog.Calls.BLOCKED_TYPE) return "차단";
        return "통화";
    }

    private static String duration(long seconds) {
        if (seconds <= 0) return "통화시간 없음";
        long minutes = seconds / 60;
        long remain = seconds % 60;
        return minutes > 0 ? minutes + "분 " + remain + "초" : remain + "초";
    }
}
