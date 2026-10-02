package com.fire119.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Network-constrained queue consumer for Samsung recordings. No API key is stored on device. */
public final class CallAnalysisWorker extends Worker {
    private static final String KEY_CALL_ID = "call_id";
    public CallAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    public static void enqueue(Context context, long callId) {
        Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CallAnalysisWorker.class)
                .setConstraints(c).setInputData(new Data.Builder().putLong(KEY_CALL_ID, callId).build()).build();
        WorkManager.getInstance(context).enqueueUniqueWork("119fire_call_analysis_" + callId,
                androidx.work.ExistingWorkPolicy.KEEP, request);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        long id = getInputData().getLong(KEY_CALL_ID, 0);
        WorkDatabase.Store store = WorkDatabase.get(context).store();
        WorkDatabase.CallRecord call = store.callById(id);
        if (call == null) return Result.success();
        SharedPreferences prefs = context.getSharedPreferences("119fire_native", Context.MODE_PRIVATE);
        String base = prefs.getString("server_base", "").trim();
        String code = prefs.getString("app_access_code", "");
        if (base.isEmpty() || code.isEmpty()) return Result.success(); // stays as an honest local queue item.
        try {
            Uri uri = Uri.parse(call.sourceUri);
            DocumentFile file = DocumentFile.fromSingleUri(context, uri);
            if (file == null || !file.exists()) throw new IllegalStateException("녹음파일을 찾지 못했습니다.");
            if (file.length() > 4L * 1024L * 1024L) {
                store.updateCallAnalysis(id, "확인 필요", "", "Netlify 업로드 제한으로 4MB 초과 녹음은 자동 전사할 수 없습니다.", System.currentTimeMillis());
                AutomationLog.add(context, "통화녹음 1건은 파일 크기 확인이 필요합니다.");
                return Result.success();
            }
            String response = upload(context, trimBase(base) + "/.netlify/functions/transcribe", code, uri,
                    file.getName() == null ? "call.m4a" : file.getName(), file.getType() == null ? "audio/mp4" : file.getType());
            JSONObject parsed = new JSONObject(response);
            store.updateCallAnalysis(id, "확인 필요", parsed.optString("transcript", ""), parsed.optString("summary", ""), System.currentTimeMillis());
            WorkDatabase.AIAnalysis analysis = new WorkDatabase.AIAnalysis();
            analysis.callRecordId = id; analysis.kind = "통화 전사·분석";
            analysis.dataJson = parsed.optJSONObject("analysis") == null ? "{}" : parsed.optJSONObject("analysis").toString();
            analysis.status = "확인 필요"; store.insertAnalysis(analysis);
            createEstimateDraftIfCertain(context, store, call, parsed.optJSONObject("analysis"));
            AutomationLog.add(context, "새 통화 1건을 AI로 정리했습니다.");
            return Result.success();
        } catch (Exception e) {
            store.updateCallAnalysis(id, "실패", "", "AI 분석 재시도 대기", System.currentTimeMillis());
            return getRunAttemptCount() < 3 ? Result.retry() : Result.success();
        }
    }

    private static String trimBase(String value) {
        String out = value; while (out.endsWith("/")) out = out.substring(0, out.length() - 1); return out;
    }
    private static String upload(Context context, String target, String appCode, Uri uri, String name, String mime) throws Exception {
        String boundary = "----119Fire" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(20000); c.setReadTimeout(180000); c.setDoOutput(true);
        c.setChunkedStreamingMode(64 * 1024); c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setRequestProperty("X-App-Code", appCode);
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream()); InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("녹음파일을 읽을 수 없습니다.");
            out.writeBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"" + name.replace("\"", "") + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
            byte[] b = new byte[64 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.writeBytes("\r\n--" + boundary + "--\r\n"); out.flush();
        }
        int status = c.getResponseCode();
        InputStream body = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
        byte[] bytes = body == null ? new byte[0] : readAll(body);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) throw new IllegalStateException(text.isEmpty() ? "AI 서버 오류" : text);
        return text;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = source.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /** A draft is created only when the transcript gives exact quantities and every price matches one saved item. */
    private static void createEstimateDraftIfCertain(Context context, WorkDatabase.Store store,
                                                     WorkDatabase.CallRecord call, JSONObject analysis) {
        try {
            if (analysis == null) return;
            JSONArray requested = analysis.optJSONArray("estimateItems");
            JSONObject fields = analysis.optJSONObject("fields");
            String siteName = fields == null ? "" : fields.optString("siteName", "").trim();
            if (requested == null || requested.length() == 0 || siteName.isEmpty() || siteName.equals("확인 필요")) return;
            WorkDatabase.Site site = store.siteByName(siteName);
            if (site == null) { addEstimateReview(store, call, "견적 요청 현장명이 저장된 현장과 일치하지 않습니다."); return; }
            JSONArray snapshot = new JSONArray(); long supply = 0;
            java.util.List<WorkDatabase.EstimateFavorite> favorites = store.estimateFavorites(200);
            for (int i = 0; i < requested.length(); i++) {
                JSONObject asked = requested.optJSONObject(i); if (asked == null) { addEstimateReview(store, call, "견적 품목 확인이 필요합니다."); return; }
                String name = asked.optString("name", "").trim(); double quantity = asked.optDouble("quantity", 0);
                WorkDatabase.EstimateFavorite selected = uniqueFavorite(favorites, name);
                if (name.isEmpty() || quantity <= 0 || selected == null || selected.unitPrice <= 0) {
                    addEstimateReview(store, call, "견적 단가 또는 수량 확인이 필요합니다."); return;
                }
                JSONObject row = new JSONObject(); row.put("name", selected.name); row.put("specification", selected.specification);
                row.put("quantity", quantity); row.put("unitPrice", selected.unitPrice); snapshot.put(row);
                supply += Math.round(quantity * selected.unitPrice);
            }
            String title = site.name + " 견적서";
            WorkDatabase.Estimate previous = store.latestEstimateByTitle(title);
            WorkDatabase.Estimate estimate = new WorkDatabase.Estimate(); estimate.siteId = site.id; estimate.customerId = site.customerId;
            estimate.title = title; estimate.version = previous == null ? 1 : previous.version + 1;
            estimate.supplyAmount = supply; estimate.vat = Math.round(supply * .1); estimate.total = estimate.supplyAmount + estimate.vat;
            estimate.status = "견적 작성"; estimate.updatedAt = System.currentTimeMillis(); long estimateId = store.insertEstimate(estimate);
            for (int i=0;i<snapshot.length();i++) { JSONObject row=snapshot.getJSONObject(i); WorkDatabase.EstimateItem item=new WorkDatabase.EstimateItem();
                item.estimateId=estimateId;item.name=row.getString("name");item.specification=row.optString("specification","");item.quantity=row.getDouble("quantity");item.unitPrice=row.getLong("unitPrice");item.amount=Math.round(item.quantity*item.unitPrice);item.sortOrder=i;store.insertEstimateItem(item); }
            WorkDatabase.EstimateVersion version = new WorkDatabase.EstimateVersion(); version.estimateId=estimateId;version.version=estimate.version;
            version.snapshotJson=new JSONObject().put("source","통화 AI 분석").put("items",snapshot).put("total",estimate.total).toString();
            version.noteText="통화에서 확인된 수량과 저장 단가만 사용한 초안";store.insertEstimateVersion(version);
            AutomationLog.add(context, site.name + " 견적 초안 V" + estimate.version + "을 만들었습니다. 금액 확인이 필요합니다.");
        } catch (Exception ignored) { addEstimateReview(store, call, "통화 견적 초안 생성 중 확인이 필요합니다."); }
    }
    private static WorkDatabase.EstimateFavorite uniqueFavorite(java.util.List<WorkDatabase.EstimateFavorite> values,String asked) {
        WorkDatabase.EstimateFavorite found=null; String key=asked.replace(" ","");
        for (WorkDatabase.EstimateFavorite x:values) { String n=x.name.replace(" ",""); if(n.equals(key)) { if(found!=null)return null;found=x; } }
        return found;
    }
    private static void addEstimateReview(WorkDatabase.Store store, WorkDatabase.CallRecord call, String reason) {
        try { WorkDatabase.AutomationJob job=new WorkDatabase.AutomationJob();job.type="ESTIMATE_DRAFT_NEEDS_REVIEW";job.dedupeKey="estimate-review:"+call.id;job.payloadJson="{\"callId\":"+call.id+",\"reason\":\""+reason.replace("\"","\\\"")+"\"}";job.status="확인필요";store.insertJob(job); } catch(Exception ignored) { }
    }
}
