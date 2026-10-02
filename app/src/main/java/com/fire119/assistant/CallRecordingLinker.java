package com.fire119.assistant;

import java.util.List;

/** Links a Galaxy call-log row to only a clearly nearby Samsung recording. */
public final class CallRecordingLinker {
    private static final long WINDOW_MS = 45L * 60L * 1000L;
    private CallRecordingLinker() {}

    public static WorkDatabase.CallRecord find(WorkDatabase.Store store, WorkDatabase.CallRecord call) {
        if (call == null || !isCallLog(call)) return call;
        List<WorkDatabase.CallRecord> candidates = store.recordingsInWindow(
                Math.max(0L, call.modifiedAt - WINDOW_MS), call.modifiedAt + WINDOW_MS);
        WorkDatabase.CallRecord selected = null;
        long bestDistance = Long.MAX_VALUE;
        String phoneTail = tail(call.phone);
        for (WorkDatabase.CallRecord candidate : candidates) {
            long distance = Math.abs(candidate.modifiedAt - call.modifiedAt);
            String label = safe(candidate.displayName) + " " + safe(candidate.sourceUri);
            boolean numberMatch = !phoneTail.isEmpty() && digits(label).contains(phoneTail);
            if (!numberMatch && distance > 8L * 60L * 1000L) continue;
            if (selected != null && !numberMatch && distance == bestDistance) return null;
            if (numberMatch || distance < bestDistance) { selected = candidate; bestDistance = distance; }
        }
        return selected;
    }

    public static boolean isCallLog(WorkDatabase.CallRecord call) {
        return call != null && call.sourceUri != null && call.sourceUri.startsWith("calllog:");
    }
    private static String tail(String value) { String d = digits(value); return d.length() >= 8 ? d.substring(d.length() - 8) : d; }
    private static String digits(String value) { return safe(value).replaceAll("[^0-9]", ""); }
    private static String safe(String value) { return value == null ? "" : value; }
}
