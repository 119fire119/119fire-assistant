package com.fire119.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.CalendarContract;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1101;
    private static final int REQ_CAMERA_PERMISSION = 1102;
    private static final int REQ_FOLDER = 1103;
    private static final int REQ_NOTIFICATION = 1104;
    private static final int REQ_CALL_HISTORY = 1106;
    private static final int REQ_PHOTO_LIBRARY = 1107;

    private WebView webView;
    private SharedPreferences prefs;
    private WorkDatabase database;
    private TextToSpeech textToSpeech;
    private SpeechRecognizer speechRecognizer;
    private File pendingPhoto;
    private String pendingJobName = "미지정 현장";
    private int systemTopInset = 0;
    private int systemBottomInset = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("119fire_native", MODE_PRIVATE);
        database = WorkDatabase.get(this);
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) textToSpeech.setLanguage(Locale.KOREAN);
        });
        webView = new WebView(this);
        // Android 15 can draw WebView content behind the status/navigation bars.  Keep the red
        // header background there, but pass the real inset to CSS so brand text is never hidden.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(webView);

        ViewCompat.setOnApplyWindowInsetsListener(webView, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            systemTopInset = bars.top;
            systemBottomInset = bars.bottom;
            applySystemInsetsToPage();
            return insets;
        });
        ViewCompat.requestApplyInsets(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                applySystemInsetsToPage();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new NativeBridge(), "Native");
        webView.loadUrl("file:///android_asset/index.html");
        if (prefs.getBoolean("call_history_enabled", false) &&
                checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            syncPhoneCallHistoryAsync();
        }
        if (prefs.getBoolean("photo_sync_enabled", false) && MediaStorePhotoSync.hasPermission(this)) {
            syncCameraPhotosAsync();
        }
    }

    private void applySystemInsetsToPage() {
        if (webView == null) return;
        final String script = "document.documentElement.style.setProperty('--safe-top','" +
                systemTopInset + "px');document.documentElement.style.setProperty('--safe-bottom','" +
                systemBottomInset + "px');";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private void callJs(String fn, JSONObject payload) {
        final String js = "window." + fn + "(" + JSONObject.quote(payload.toString()) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("folderConnected", prefs.getString("call_folder_uri", "").length() > 0);
            o.put("monitorEnabled", prefs.getBoolean("monitor_enabled", false));
            o.put("lastScan", prefs.getLong("last_scan", 0));
            o.put("photoJob", pendingJobName);
            o.put("voiceReply", prefs.getBoolean("voice_reply", true));
            o.put("calendarConnected", prefs.getBoolean("calendar_enabled", false));
            o.put("driveBackup", prefs.getBoolean("drive_backup_enabled", false));
            o.put("callHistoryEnabled", prefs.getBoolean("call_history_enabled", false));
            o.put("callHistoryGranted", checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED);
            o.put("photoSyncEnabled", prefs.getBoolean("photo_sync_enabled", false));
            o.put("photoSyncGranted", MediaStorePhotoSync.hasPermission(MainActivity.this));
        } catch (Exception ignored) {}
        return o;
    }

    public class NativeBridge {
        @JavascriptInterface
        public String getStatus() {
            return statusJson().toString();
        }

        @JavascriptInterface
        public void pickCallFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                startActivityForResult(i, REQ_FOLDER);
            });
        }

        @JavascriptInterface
        public void scanRecordings() {
            new Thread(() -> {
                JSONObject out = new JSONObject();
                JSONArray arr = new JSONArray();
                try {
                    String raw = prefs.getString("call_folder_uri", "");
                    if (raw.isEmpty()) throw new Exception("통화녹음 폴더가 연결되지 않았습니다.");

                    DocumentFile folder = DocumentFile.fromTreeUri(MainActivity.this, Uri.parse(raw));
                    if (folder == null || !folder.exists()) throw new Exception("연결한 폴더를 열 수 없습니다.");

                    DocumentFile[] files = folder.listFiles();
                    Arrays.sort(files, Comparator.comparingLong(DocumentFile::lastModified).reversed());

                    int count = 0;
                    for (DocumentFile f : files) {
                        if (!f.isFile() || !isAudio(f)) continue;
                        WorkDatabase.CallRecord saved = database.store().callByUri(f.getUri().toString());
                        if (saved == null) {
                            WorkDatabase.CallRecord call = new WorkDatabase.CallRecord();
                            call.sourceUri = f.getUri().toString();
                            call.displayName = f.getName() == null ? "통화녹음" : f.getName();
                            call.fileSize = f.length(); call.modifiedAt = f.lastModified();
                            call.processingStatus = "대기";
                            long callId = database.store().insertCall(call);
                            if (callId > 0) {
                                WorkDatabase.AutomationJob job = new WorkDatabase.AutomationJob();
                                job.type = "AI_ANALYZE_CALL"; job.dedupeKey = "call:" + call.sourceUri;
                                job.payloadJson = new JSONObject().put("callId", callId).put("uri", call.sourceUri).toString();
                                database.store().insertJob(job);
                            }
                        }
                        JSONObject x = new JSONObject();
                        x.put("name", f.getName() == null ? "통화녹음" : f.getName());
                        x.put("uri", f.getUri().toString());
                        x.put("size", f.length());
                        x.put("modified", f.lastModified());
                        x.put("status", saved == null ? "대기" : saved.processingStatus);
                        arr.put(x);
                        if (++count >= 30) break;
                    }
                    prefs.edit().putLong("last_scan", System.currentTimeMillis()).apply();
                    out.put("ok", true);
                    out.put("files", arr);
                } catch (Exception e) {
                    try { out.put("ok", false); out.put("error", e.getMessage()); } catch (Exception ignored) {}
                }
                callJs("onRecordings", out);
            }).start();
        }

        @JavascriptInterface
        public void enableMonitor() {
            runOnUiThread(() -> {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
                }
                scheduleMonitor();
                prefs.edit()
                        .putBoolean("monitor_enabled", true)
                        .putLong("monitor_last_seen", System.currentTimeMillis())
                        .apply();
                callJs("onNativeStatus", statusJson());
            });
        }

        @JavascriptInterface
        public void disableMonitor() {
            if (!prefs.getBoolean("call_history_enabled", false) && !prefs.getBoolean("photo_sync_enabled", false))
                WorkManager.getInstance(MainActivity.this).cancelUniqueWork("119fire_call_monitor");
            prefs.edit().putBoolean("monitor_enabled", false).apply();
            callJs("onNativeStatus", statusJson());
        }

        @JavascriptInterface
        public void enableCallHistorySync() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG}, REQ_CALL_HISTORY);
                    return;
                }
                prefs.edit().putBoolean("call_history_enabled", true).apply();
                scheduleMonitor();
                syncPhoneCallHistoryAsync();
                callJs("onNativeStatus", statusJson());
            });
        }

        @JavascriptInterface
        public void disableCallHistorySync() {
            prefs.edit().putBoolean("call_history_enabled", false).apply();
            if (!prefs.getBoolean("monitor_enabled", false))
                WorkManager.getInstance(MainActivity.this).cancelUniqueWork("119fire_call_monitor");
            callJs("onNativeStatus", statusJson());
        }

        @JavascriptInterface
        public void syncCallHistory() {
            syncPhoneCallHistoryAsync();
        }

        /** Opt-in metadata scan for images newly saved by Samsung's normal Camera app. */
        @JavascriptInterface
        public void enableCameraPhotoSync() {
            runOnUiThread(() -> {
                if (!MediaStorePhotoSync.hasPermission(MainActivity.this)) {
                    String permission = android.os.Build.VERSION.SDK_INT >= 33
                            ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
                    requestPermissions(new String[]{permission}, REQ_PHOTO_LIBRARY);
                    return;
                }
                prefs.edit().putBoolean("photo_sync_enabled", true).apply();
                scheduleMonitor(); syncCameraPhotosAsync(); callJs("onNativeStatus", statusJson());
            });
        }

        @JavascriptInterface
        public void disableCameraPhotoSync() {
            prefs.edit().putBoolean("photo_sync_enabled", false).apply();
            if (!prefs.getBoolean("monitor_enabled", false) && !prefs.getBoolean("call_history_enabled", false))
                WorkManager.getInstance(MainActivity.this).cancelUniqueWork("119fire_call_monitor");
            callJs("onNativeStatus", statusJson());
        }

        @JavascriptInterface
        public void syncCameraPhotos() { syncCameraPhotosAsync(); }

        @JavascriptInterface
        public String assignPhotoToSite(long photoId, long siteId, String category) {
            String clean = category == null ? "미분류" : category.trim();
            if (!(clean.equals("공사 전") || clean.equals("공사 중") || clean.equals("공사 후") || clean.equals("기타") || clean.equals("미분류"))) clean = "미분류";
            try {
                database.store().assignPhotoToSite(photoId, siteId, clean);
                return result(true, "사진을 현장에 연결했습니다.", photoId).toString();
            } catch (Exception e) { return result(false, "사진 연결에 실패했습니다.", photoId).toString(); }
        }

        @JavascriptInterface
        public void takePhoto(String jobName) {
            pendingJobName = (jobName == null || jobName.trim().isEmpty()) ? "미지정 현장" : jobName.trim();
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
                } else {
                    launchCamera();
                }
            });
        }

        @JavascriptInterface
        public void askAi(String serverBase, String accessCode, String message, String contextJson) {
            new Thread(() -> {
                JSONObject out = new JSONObject();
                try {
                    String url = normalizeBase(serverBase) + "/.netlify/functions/assistant";
                    JSONObject body = new JSONObject();
                    body.put("code", accessCode);
                    body.put("message", message);
                    body.put("context", new JSONObject(contextJson == null || contextJson.isEmpty() ? "{}" : contextJson));
                    String resp = postJson(url, body.toString());
                    out = new JSONObject(resp);
                    out.put("ok", true);
                } catch (Exception e) {
                    try { out.put("ok", false); out.put("error", e.getMessage()); } catch (Exception ignored) {}
                }
                callJs("onAiResponse", out);
            }).start();
        }

        @JavascriptInterface
        public void transcribeRecording(String recordingUri, String serverBase, String accessCode) {
            new Thread(() -> {
                JSONObject out = new JSONObject();
                try {
                    Uri uri = Uri.parse(recordingUri);
                    DocumentFile df = DocumentFile.fromSingleUri(MainActivity.this, uri);
                    if (df == null || !df.exists()) throw new Exception("녹음파일을 열 수 없습니다.");
                    long size = df.length();
                    if (size > 5L * 1024L * 1024L)
                        throw new Exception("샘플 버전은 5MB 이하 녹음파일만 AI 요약할 수 있습니다.");

                    String name = df.getName() == null ? "call.m4a" : df.getName();
                    String mime = df.getType() == null ? "audio/mp4" : df.getType();
                    String url = normalizeBase(serverBase) + "/.netlify/functions/transcribe";

                    String response = uploadMultipart(url, accessCode, uri, name, mime);
                    out = new JSONObject(response);
                    WorkDatabase.CallRecord saved = database.store().callByUri(recordingUri);
                    if (saved != null) {
                        database.store().updateCallAnalysis(saved.id, "확인 필요", out.optString("transcript", ""),
                                out.optString("summary", ""), System.currentTimeMillis());
                    }
                    out.put("ok", true);
                } catch (Exception e) {
                    try { out.put("ok", false); out.put("error", e.getMessage()); } catch (Exception ignored) {}
                }
                callJs("onTranscribeResponse", out);
            }).start();
        }

        /** Dashboard is assembled only from Room records, never from invented AI facts. */
        @JavascriptInterface
        public String getDashboard() {
            JSONObject out = new JSONObject();
            try {
                WorkDatabase.Store store = database.store();
                out.put("inquiries", store.inquiryCount());
                out.put("openTasks", store.openTaskCount());
                out.put("waitingCalls", store.waitingCallCount());
                out.put("receivables", store.receivableCount());
                out.put("photos", store.photoCount());
                out.put("tasks", tasksJson(store.openTasks(8)));
                out.put("sites", sitesJson(store.sites(5)));
                out.put("calls", callsJson(store.calls(5)));
                JSONObject stages = new JSONObject();
                String[] flow = {V3Workflow.NEW_INQUIRY, V3Workflow.SITE_CHECK, V3Workflow.QUOTE_WAIT,
                        V3Workflow.QUOTE_SENT, V3Workflow.WORK_SCHEDULED, V3Workflow.WORKING,
                        V3Workflow.WORK_COMPLETE, "수금대기"};
                for (String stage : flow) {
                    int count = store.inquiryCountByStatus(stage) + store.siteCountByStatus(stage);
                    // V2's initial label included a space; keep it visible after V3 update.
                    if (V3Workflow.SITE_CHECK.equals(stage)) count += store.siteCountByStatus("현장확인 대기");
                    stages.put(stage, count);
                }
                out.put("stages", stages);
                JSONObject attention = new JSONObject();
                attention.put("unsentEstimates", store.unsentEstimateCount());
                attention.put("quoteFollowUp", store.quoteFollowUpCount(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.KOREA).format(new Date())));
                attention.put("unansweredInquiries", store.unansweredInquiryCount());
                attention.put("photoOnlySites", store.photoOnlySiteCount());
                attention.put("completedUnpaidSites", store.completedUnpaidSiteCount());
                attention.put("inbox", store.inboxCount());
                out.put("attention", attention);
                out.put("inbox", jobsJson(store.inboxJobs(20)));
            } catch (Exception e) {
                try { out.put("error", "업무 데이터를 불러오지 못했습니다."); } catch (Exception ignored) {}
            }
            return out.toString();
        }

        @JavascriptInterface
        public String getWorkspace() {
            JSONObject out = new JSONObject();
            try {
                WorkDatabase.Store store = database.store();
                out.put("inquiries", inquiriesJson(store.inquiries(100)));
                out.put("sites", sitesJson(store.sites(100)));
                out.put("tasks", tasksJson(store.openTasks(100)));
                out.put("estimates", estimatesJson(store.estimates(100)));
                out.put("photos", photosJson(store.photos(100)));
                out.put("calls", callsJson(store.calls(100)));
                out.put("inbox", jobsJson(store.inboxJobs(100)));
                out.put("favorites", estimateFavoritesJson(store.estimateFavorites(80)));
                out.put("noteTemplates", estimateNoteTemplatesJson(store.estimateNoteTemplates(80)));
                out.put("status", statusJson());
            } catch (Exception e) {
                try { out.put("error", "업무 데이터를 불러오지 못했습니다."); } catch (Exception ignored) {}
            }
            return out.toString();
        }

        /** One-time import of V1 WebView localStorage, retained only to make updating safe. */
        @JavascriptInterface
        public String migrateV1Data(String raw) {
            if (prefs.getBoolean("v1_room_migrated", false)) return result(true, "기존 데이터 이전이 이미 완료되었습니다.", 0).toString();
            try {
                JSONObject legacy = new JSONObject(raw == null ? "{}" : raw); WorkDatabase.Store store = database.store(); int imported = 0;
                JSONArray leads = legacy.optJSONArray("leads");
                if (leads != null) for (int i=0;i<leads.length();i++) { JSONObject x=leads.optJSONObject(i); if(x==null)continue;
                    WorkDatabase.Inquiry inquiry=new WorkDatabase.Inquiry(); inquiry.title=valueOr(x.optString("name",""),"기존 문의"); inquiry.content=x.optString("memo",""); inquiry.source="V1 이전"; store.insertInquiry(inquiry); imported++; }
                JSONArray photos = legacy.optJSONArray("photos");
                if (photos != null) for (int i=0;i<photos.length();i++) { JSONObject x=photos.optJSONObject(i); if(x==null)continue;
                    String path=x.optString("path",""); if(path.isEmpty())continue; WorkDatabase.Photo p=new WorkDatabase.Photo(); p.path=path;p.name=x.optString("name","");p.category="기타";store.insertPhoto(p); imported++; }
                prefs.edit().putBoolean("v1_room_migrated", true).apply(); return result(true, imported + "건의 V1 기록을 로컬 업무 DB로 이전했습니다.", imported).toString();
            } catch (Exception e) { return result(false, "기존 데이터 이전에 실패했습니다.", 0).toString(); }
        }

        @JavascriptInterface
        public String saveInquiry(String raw) {
            try {
                JSONObject input = new JSONObject(raw == null ? "{}" : raw);
                WorkDatabase.Store store = database.store();
                String phone = normalizePhone(input.optString("phone", ""));
                Long customerId = null;
                if (!phone.isEmpty()) {
                    WorkDatabase.Customer customer = store.customerByPhone(phone);
                    if (customer == null) {
                        customer = new WorkDatabase.Customer();
                        customer.phone = phone;
                        String name = input.optString("customerName", "").trim();
                        customer.name = name.isEmpty() ? "이름 미확인" : name;
                        customer.company = input.optString("company", "").trim();
                        customer.contactName = input.optString("contactName", "").trim();
                        customer.region = input.optString("region", "").trim();
                        customer.address = input.optString("address", "").trim();
                        customerId = store.insertCustomer(customer);
                    } else {
                        customerId = customer.id;
                        store.updateCustomerProfile(customer.id,
                                input.optString("customerName", "").trim(), input.optString("company", "").trim(),
                                input.optString("contactName", "").trim(), input.optString("region", "").trim(),
                                input.optString("address", "").trim(), System.currentTimeMillis());
                    }
                }
                String siteName = input.optString("siteName", "").trim();
                Long siteId = null;
                if (!siteName.isEmpty()) {
                    WorkDatabase.Site site = store.siteByName(siteName);
                    if (site == null) {
                        site = new WorkDatabase.Site();
                        site.customerId = customerId;
                        site.name = siteName;
                        site.address = input.optString("address", "").trim();
                        site.status = V3Workflow.SITE_CHECK;
                        siteId = store.insertSite(site);
                    } else siteId = site.id;
                }
                WorkDatabase.Inquiry inquiry = new WorkDatabase.Inquiry();
                inquiry.customerId = customerId;
                inquiry.siteId = siteId;
                inquiry.title = valueOr(input.optString("title", ""), siteName.isEmpty() ? "신규 문의" : siteName + " 문의");
                inquiry.content = input.optString("content", "").trim();
                inquiry.source = valueOr(input.optString("source", ""), "확인 불가");
                inquiry.status = V3Workflow.normalizeStatus(valueOr(input.optString("status", ""), V3Workflow.NEW_INQUIRY));
                long id = store.insertInquiry(inquiry);
                if (!input.optString("dueAt", "").trim().isEmpty()) {
                    WorkDatabase.Task task = new WorkDatabase.Task();
                    task.customerId = customerId; task.siteId = siteId; task.inquiryId = id;
                    task.title = valueOr(input.optString("taskTitle", ""), "고객 다시 연락");
                    task.dueAt = input.optString("dueAt", "").trim(); task.source = "문의 등록";
                    store.insertTask(task);
                }
                return result(true, "문의와 연결 기록을 저장했습니다.", id).toString();
            } catch (Exception e) { return result(false, "문의 저장에 실패했습니다.", 0).toString(); }
        }

        @JavascriptInterface
        public String saveTask(String raw) {
            try {
                JSONObject input = new JSONObject(raw == null ? "{}" : raw);
                WorkDatabase.Task task = new WorkDatabase.Task();
                task.title = valueOr(input.optString("title", ""), "확인 필요");
                task.dueAt = input.optString("dueAt", "").trim();
                task.source = valueOr(input.optString("source", ""), "직접 등록");
                task.requiresConfirmation = input.optBoolean("requiresConfirmation", false);
                task.siteId = numberOrNull(input, "siteId");
                task.customerId = numberOrNull(input, "customerId");
                long id = database.store().insertTask(task);
                return result(true, "할 일을 저장했습니다.", id).toString();
            } catch (Exception e) { return result(false, "할 일 저장에 실패했습니다.", 0).toString(); }
        }

        @JavascriptInterface
        public String completeTask(long id) {
            try { database.store().completeTask(id, System.currentTimeMillis()); return result(true, "처리 완료로 표시했습니다.", id).toString(); }
            catch (Exception e) { return result(false, "상태 변경에 실패했습니다.", id).toString(); }
        }

        @JavascriptInterface
        public String saveEstimate(String raw) {
            try {
                JSONObject input = new JSONObject(raw == null ? "{}" : raw);
                JSONArray items = input.optJSONArray("items");
                if (items == null || items.length() == 0) return result(false, "견적 항목을 하나 이상 넣어주세요.", 0).toString();
                long supply = 0;
                for (int i=0;i<items.length();i++) {
                    JSONObject item = items.getJSONObject(i);
                    supply += Math.round(item.optDouble("quantity", 0) * item.optLong("unitPrice", 0));
                }
                WorkDatabase.Estimate estimate = new WorkDatabase.Estimate();
                estimate.customerId = numberOrNull(input, "customerId");
                estimate.siteId = numberOrNull(input, "siteId");
                estimate.title = valueOr(input.optString("title", ""), "119파이어 견적서");
                estimate.supplyAmount = supply; estimate.vat = Math.round(supply * 0.1); estimate.total = supply + estimate.vat;
                WorkDatabase.Estimate previous = database.store().latestEstimateByTitle(estimate.title);
                estimate.version = previous == null ? 1 : previous.version + 1;
                estimate.status = valueOr(input.optString("status", ""), "견적 작성");
                estimate.followUpAt = input.optString("followUpAt", "").trim();
                estimate.updatedAt = System.currentTimeMillis();
                long estimateId = database.store().insertEstimate(estimate);
                for (int i=0;i<items.length();i++) {
                    JSONObject item = items.getJSONObject(i);
                    WorkDatabase.EstimateItem row = new WorkDatabase.EstimateItem();
                    row.estimateId = estimateId; row.name = valueOr(item.optString("name", ""), "기타");
                    row.specification = item.optString("specification", "").trim();
                    row.quantity = item.optDouble("quantity", 0); row.unitPrice = item.optLong("unitPrice", 0);
                    row.amount = Math.round(row.quantity * row.unitPrice); row.sortOrder = i;
                    database.store().insertEstimateItem(row);
                }
                WorkDatabase.EstimateVersion version = new WorkDatabase.EstimateVersion();
                version.estimateId = estimateId;
                version.version = estimate.version;
                version.snapshotJson = new JSONObject().put("title", estimate.title).put("supply", estimate.supplyAmount)
                        .put("vat", estimate.vat).put("total", estimate.total).put("items", items).toString();
                version.noteText = input.optString("noteText", "").trim();
                database.store().insertEstimateVersion(version);
                return result(true, "견적 V" + estimate.version + " 초안을 저장했습니다. 최종 확정·발송은 별도 확인이 필요합니다.", estimateId).put("total", estimate.total).put("version", estimate.version).toString();
            } catch (Exception e) { return result(false, "견적 저장에 실패했습니다.", 0).toString(); }
        }

        @JavascriptInterface
        public String saveEstimateFavorite(String raw) {
            try {
                JSONObject in = new JSONObject(raw == null ? "{}" : raw);
                String name = in.optString("name", "").trim();
                if (name.isEmpty()) return result(false, "즐겨찾기 품목명을 입력해주세요.", 0).toString();
                WorkDatabase.EstimateFavorite value = new WorkDatabase.EstimateFavorite();
                value.name = name; value.specification = in.optString("specification", "").trim();
                value.unit = valueOr(in.optString("unit", ""), "개"); value.unitPrice = in.optLong("unitPrice", 0);
                long id = database.store().insertEstimateFavorite(value);
                return result(true, "자주 쓰는 품목을 저장했습니다.", id).toString();
            } catch (Exception e) { return result(false, "품목 저장에 실패했습니다.", 0).toString(); }
        }

        @JavascriptInterface
        public String saveEstimateNoteTemplate(String raw) {
            try {
                JSONObject in = new JSONObject(raw == null ? "{}" : raw);
                String body = in.optString("body", "").trim();
                if (body.isEmpty()) return result(false, "특기사항 내용을 입력해주세요.", 0).toString();
                WorkDatabase.EstimateNoteTemplate value = new WorkDatabase.EstimateNoteTemplate();
                value.title = valueOr(in.optString("title", ""), "특기사항"); value.body = body;
                long id = database.store().insertEstimateNoteTemplate(value);
                return result(true, "특기사항 문구를 저장했습니다.", id).toString();
            } catch (Exception e) { return result(false, "특기사항 저장에 실패했습니다.", 0).toString(); }
        }

        @JavascriptInterface
        public String updateEstimateStatus(long estimateId, String status, String followUpAt) {
            try {
                String clean = V3Workflow.normalizeStatus(status);
                if (!(V3Workflow.QUOTE_SENT.equals(clean) || V3Workflow.HOLD.equals(clean) ||
                        V3Workflow.LOST.equals(clean) || V3Workflow.UNKNOWN.equals(clean))) {
                    return result(false, "견적 상태를 다시 선택해주세요.", estimateId).toString();
                }
                database.store().updateEstimateStatus(estimateId, clean, followUpAt == null ? "" : followUpAt.trim(), System.currentTimeMillis());
                return result(true, "견적 상태를 저장했습니다.", estimateId).toString();
            } catch (Exception e) { return result(false, "견적 상태 저장에 실패했습니다.", estimateId).toString(); }
        }

        @JavascriptInterface
        public String exportEstimatePdf(long estimateId) {
            PdfDocument pdf = new PdfDocument();
            try {
                WorkDatabase.Estimate estimate = database.store().estimateById(estimateId);
                if (estimate == null) return result(false, "견적서를 찾지 못했습니다.", estimateId).toString();
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
                Canvas canvas = page.getCanvas(); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(0xff191b1f);
                paint.setTextSize(24); paint.setFakeBoldText(true); canvas.drawText("119FIRE ESTIMATE", 42, 60, paint);
                paint.setFakeBoldText(false); paint.setTextSize(12); canvas.drawText("견적서 v" + estimate.version, 42, 88, paint);
                int y = 130; paint.setTextSize(13); canvas.drawText("항목", 44, y, paint); canvas.drawText("수량", 290, y, paint); canvas.drawText("단가", 365, y, paint); canvas.drawText("금액", 465, y, paint);
                y += 24; paint.setTextSize(11);
                for (WorkDatabase.EstimateItem item : database.store().estimateItems(estimateId)) {
                    canvas.drawText(item.name + (item.specification.isEmpty() ? "" : " (" + item.specification + ")"), 44, y, paint); canvas.drawText(String.valueOf(item.quantity), 290, y, paint);
                    canvas.drawText(String.format(Locale.KOREA, "%,d", item.unitPrice), 365, y, paint);
                    canvas.drawText(String.format(Locale.KOREA, "%,d", item.amount), 465, y, paint); y += 22;
                }
                y += 28; paint.setFakeBoldText(true); paint.setTextSize(13);
                canvas.drawText("공급가액", 350, y, paint); canvas.drawText(String.format(Locale.KOREA, "%,d원", estimate.supplyAmount), 455, y, paint); y += 22;
                canvas.drawText("부가세", 350, y, paint); canvas.drawText(String.format(Locale.KOREA, "%,d원", estimate.vat), 455, y, paint); y += 26;
                paint.setTextSize(16); canvas.drawText("합계", 350, y, paint); canvas.drawText(String.format(Locale.KOREA, "%,d원", estimate.total), 455, y, paint);
                paint.setFakeBoldText(false); paint.setTextSize(9); canvas.drawText("최종 발송 전 고객·현장·금액을 확인하세요.", 42, 790, paint);
                pdf.finishPage(page);
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "119Fire/estimates"); if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, "estimate_" + estimateId + "_v" + estimate.version + ".pdf");
                try (FileOutputStream stream = new FileOutputStream(out)) { pdf.writeTo(stream); }
                WorkDatabase.Document doc = new WorkDatabase.Document(); doc.siteId = estimate.siteId; doc.customerId = estimate.customerId;
                doc.type = "견적서"; doc.name = out.getName(); doc.path = out.getAbsolutePath(); database.store().insertDocument(doc);
                return result(true, "견적서 PDF를 만들었습니다.", estimateId).put("path", out.getAbsolutePath()).toString();
            } catch (Exception e) { return result(false, "견적서 PDF 생성에 실패했습니다.", estimateId).toString(); }
            finally { pdf.close(); }
        }

        @JavascriptInterface
        public String finishSite(long siteId, String note) {
            // V2 bridge compatibility: status is no longer changed here. V3 always asks first.
            return getSiteClosePreview(siteId, note);
        }

        /** Builds a factual close-out checklist but never marks work complete. */
        @JavascriptInterface
        public String getSiteClosePreview(long siteId, String note) {
            try {
                WorkDatabase.Store store = database.store();
                WorkDatabase.Site site = store.siteById(siteId);
                if (site == null) return result(false, "현장을 찾지 못했습니다.", siteId).toString();
                String actualNote = note == null || note.trim().isEmpty() ? site.workNote : note.trim();
                int before = store.photoCountForCategory(siteId, "공사 전");
                int during = store.photoCountForCategory(siteId, "공사 중");
                int after = store.photoCountForCategory(siteId, "공사 후");
                JSONObject out = result(true, V3Workflow.closeMessage(after, actualNote), siteId);
                out.put("siteName", site.name); out.put("note", actualNote); out.put("before", before); out.put("during", during); out.put("after", after);
                out.put("blogReadiness", V3Workflow.blogReadiness(before, during, after, actualNote));
                out.put("requiresConfirm", true);
                return out.toString();
            } catch (Exception e) { return result(false, "현장 마감 정보를 확인하지 못했습니다.", siteId).toString(); }
        }

        /** Called only after the user presses the final confirmation in the V3 dialog. */
        @JavascriptInterface
        public String confirmSiteCompletion(long siteId, String note) {
            try {
                WorkDatabase.Store store = database.store();
                WorkDatabase.Site site = store.siteById(siteId);
                if (site == null) return result(false, "현장을 찾지 못했습니다.", siteId).toString();
                String actualNote = note == null || note.trim().isEmpty() ? site.workNote : note.trim();
                long now = System.currentTimeMillis();
                store.confirmSiteComplete(siteId, V3Workflow.WORK_COMPLETE, actualNote, now);
                WorkDatabase.Task task = new WorkDatabase.Task();
                task.siteId = siteId; task.customerId = site.customerId; task.title = "수금 상태 확인";
                task.source = "V3 현장 완료 확인"; task.requiresConfirmation = true;
                store.insertTask(task);
                return result(true, "공사완료로 저장했고 수금 확인 할 일을 만들었습니다.", siteId).toString();
            } catch (Exception e) { return result(false, "현장 완료 처리에 실패했습니다.", siteId).toString(); }
        }

        @JavascriptInterface
        public String saveSiteVoiceMemo(String siteName, String note) {
            try {
                if (siteName == null || siteName.trim().isEmpty()) return result(false, "음성메모를 저장할 현장명을 먼저 입력하세요.", 0).toString();
                WorkDatabase.Store store = database.store(); WorkDatabase.Site site = store.siteByName(siteName.trim());
                long siteId;
                String previous = site == null ? "" : site.workNote;
                if (site == null) { site = new WorkDatabase.Site(); site.name = siteName.trim(); site.status = V3Workflow.WORKING; siteId = store.insertSite(site); }
                else siteId = site.id;
                String combined = (previous == null || previous.isEmpty()) ? note.trim() : previous + "\n" + note.trim();
                store.updateSiteNote(siteId, combined, System.currentTimeMillis());
                WorkDatabase.VoiceMemo memo = new WorkDatabase.VoiceMemo();
                memo.siteId = siteId; memo.text = note == null ? "" : note.trim(); memo.source = "현장 음성메모";
                store.insertVoiceMemo(memo);
                return result(true, "현장 음성메모를 저장했습니다.", siteId).toString();
            } catch (Exception e) { return result(false, "현장 음성메모 저장에 실패했습니다.", 0).toString(); }
        }

        @JavascriptInterface
        public String updatePhotoCategory(long photoId, String category) {
            String clean = category == null ? "미분류" : category.trim();
            if (!(clean.equals("공사 전") || clean.equals("공사 중") || clean.equals("공사 후") ||
                    clean.equals("기타") || clean.equals("미분류"))) clean = "미분류";
            try {
                database.store().updatePhotoCategory(photoId, clean);
                return result(true, "사진 분류를 저장했습니다.", photoId).toString();
            } catch (Exception e) {
                return result(false, "사진 분류 저장에 실패했습니다.", photoId).toString();
            }
        }

        @JavascriptInterface
        public void startVoiceAssistant() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1105); return;
                }
                beginVoiceRecognition();
            });
        }

        @JavascriptInterface
        public void speak(String text) {
            if (prefs.getBoolean("voice_reply", true) && textToSpeech != null)
                textToSpeech.speak(text == null ? "" : text, TextToSpeech.QUEUE_FLUSH, null, "119fire-reply");
        }

        @JavascriptInterface
        public void setVoiceReply(boolean enabled) { prefs.edit().putBoolean("voice_reply", enabled).apply(); }

        @JavascriptInterface
        public void saveServerConnection(String serverBase, String accessCode) {
            prefs.edit().putString("server_base", serverBase == null ? "" : serverBase.trim())
                    .putString("app_access_code", accessCode == null ? "" : accessCode.trim()).apply();
        }

        /** Sends only files that are in the local queue. The OpenAI key never leaves Netlify. */
        @JavascriptInterface
        public void processPendingCallAnalysis() {
            new Thread(() -> {
                JSONObject out = new JSONObject(); int done = 0;
                try {
                    String base = normalizeBase(prefs.getString("server_base", ""));
                    String code = prefs.getString("app_access_code", "");
                    if (code.isEmpty()) throw new Exception("AI 서버 연결을 먼저 설정해주세요.");
                    for (WorkDatabase.CallRecord call : database.store().pendingCalls(3)) {
                        Uri uri = Uri.parse(call.sourceUri);
                        DocumentFile file = DocumentFile.fromSingleUri(MainActivity.this, uri);
                        if (file == null || !file.exists()) { database.store().updateCallAnalysis(call.id, "실패", "", "파일을 찾지 못했습니다.", System.currentTimeMillis()); continue; }
                        String response = uploadMultipart(normalizeBase(base) + "/.netlify/functions/transcribe", code, uri,
                                file.getName() == null ? "call.m4a" : file.getName(), file.getType() == null ? "audio/mp4" : file.getType());
                        JSONObject parsed = new JSONObject(response);
                        String transcript = parsed.optString("transcript", ""); String summary = parsed.optString("summary", "");
                        database.store().updateCallAnalysis(call.id, "확인 필요", transcript, summary, System.currentTimeMillis());
                        WorkDatabase.AIAnalysis analysis = new WorkDatabase.AIAnalysis();
                        analysis.callRecordId = call.id; analysis.kind = "통화 전사·분석";
                        analysis.dataJson = parsed.optJSONObject("analysis") == null ? "{}" : parsed.optJSONObject("analysis").toString();
                        analysis.status = "확인 필요"; database.store().insertAnalysis(analysis); done++;
                    }
                    out.put("ok", true); out.put("done", done); out.put("message", done == 0 ? "분석 대기 통화가 없습니다." : done + "건을 분석해 확인 필요로 저장했습니다.");
                } catch (Exception e) { try { out.put("ok", false); out.put("error", "AI 분석에 실패했습니다. 인터넷과 서버 연결을 확인한 뒤 다시 시도해주세요."); } catch(Exception ignored){} }
                callJs("onQueuedAnalysis", out);
            }).start();
        }

        @JavascriptInterface
        public String createCalendarCandidate(String raw) {
            try {
                JSONObject input = new JSONObject(raw == null ? "{}" : raw);
                String start = input.optString("startAt", "").trim();
                if (start.isEmpty()) return result(false, "날짜와 시간이 확실하지 않아 일정 후보로만 남겼습니다.", 0).toString();
                WorkDatabase.CalendarEvent event = new WorkDatabase.CalendarEvent();
                event.title = valueOr(input.optString("title", ""), "119파이어 일정"); event.startAt = start;
                event.endAt = input.optString("endAt", "").trim(); event.address = input.optString("address", "").trim();
                event.siteId = numberOrNull(input, "siteId"); event.customerId = numberOrNull(input, "customerId");
                event.dedupeKey = (event.title + "|" + start + "|" + event.address).toLowerCase(Locale.ROOT);
                long id = database.store().insertCalendar(event);
                return result(id > 0, id > 0 ? "일정 후보를 저장했습니다. 캘린더 등록은 최종 확인 후 진행됩니다." : "같은 일정 후보가 이미 있습니다.", id).toString();
            } catch (Exception e) { return result(false, "일정 후보 저장에 실패했습니다.", 0).toString(); }
        }

        @JavascriptInterface
        public String getCustomerDetail(long customerId) {
            try {
                WorkDatabase.Store store = database.store();
                WorkDatabase.Customer customer = store.customerById(customerId);
                if (customer == null) return result(false, "고객 정보를 찾지 못했습니다.", customerId).toString();
                JSONObject out = result(true, "고객 연결 기록입니다.", customerId);
                JSONObject c = new JSONObject();
                c.put("id", customer.id); c.put("company", customer.company); c.put("name", customer.name);
                c.put("contactName", customer.contactName); c.put("phone", customer.phone); c.put("region", customer.region); c.put("address", customer.address); c.put("memo", customer.memo);
                out.put("customer", c);
                out.put("inquiries", inquiriesJson(store.inquiriesByCustomer(customerId, 30)));
                out.put("sites", sitesJson(store.sitesByCustomer(customerId, 30)));
                out.put("estimates", estimatesJson(store.estimatesByCustomer(customerId, 30)));
                out.put("calls", callsJson(store.callsByCustomer(customerId, 50)));
                return out.toString();
            } catch (Exception e) { return result(false, "고객 연결 기록을 불러오지 못했습니다.", customerId).toString(); }
        }

        /** Shows the factual call record. Spoken words appear only after a linked recording was transcribed. */
        @JavascriptInterface
        public String getCallDetail(long callId) {
            try {
                WorkDatabase.CallRecord call = database.store().callById(callId);
                if (call == null) return result(false, "통화기록을 찾지 못했습니다.", callId).toString();
                boolean hasTranscript = call.transcript != null && !call.transcript.trim().isEmpty();
                JSONObject out = result(true, hasTranscript ? "AI 전사 내용을 확인하세요." : "이 통화는 녹음 전사가 아직 연결되지 않았습니다.", callId);
                out.put("id", call.id); out.put("name", call.displayName); out.put("phone", call.phone);
                out.put("when", call.modifiedAt); out.put("status", call.processingStatus); out.put("summary", call.summary);
                out.put("transcript", hasTranscript ? call.transcript : ""); out.put("hasTranscript", hasTranscript);
                out.put("kind", call.sourceUri.startsWith("calllog:") ? "전화기록" : "통화녹음");
                out.put("documentHint", hasTranscript ? "전사와 요약을 PDF 통화기록으로 저장할 수 있습니다." : "전화기록만으로는 통화 내용을 알 수 없습니다. 같은 통화의 삼성 녹음파일을 연결하고 AI 분석하면 발언내용을 문서로 만들 수 있습니다.");
                return out.toString();
            } catch (Exception e) { return result(false, "통화 상세를 불러오지 못했습니다.", callId).toString(); }
        }

        /** Exports a local PDF record; it never invents a conversation when no transcription exists. */
        @JavascriptInterface
        public String exportCallDocument(long callId) {
            PdfDocument pdf = new PdfDocument();
            try {
                WorkDatabase.CallRecord call = database.store().callById(callId);
                if (call == null) return result(false, "통화기록을 찾지 못했습니다.", callId).toString();
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
                Canvas canvas = page.getCanvas(); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(0xff191b1f); paint.setTextSize(23); paint.setFakeBoldText(true);
                canvas.drawText("119FIRE CALL NOTE", 42, 58, paint);
                paint.setFakeBoldText(false); paint.setTextSize(11);
                long at = call.modifiedAt > 0 ? call.modifiedAt : call.createdAt;
                String time = new SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREA).format(new Date(at));
                int y = 92;
                y = drawPdfText(canvas, paint, "통화 상대: " + valueOr(call.displayName, "이름 미확인"), 42, y, 510, 15);
                y = drawPdfText(canvas, paint, "전화번호: " + valueOr(call.phone, "확인 필요"), 42, y, 510, 15);
                y = drawPdfText(canvas, paint, "통화 시각: " + time, 42, y, 510, 15);
                y = drawPdfText(canvas, paint, "기록 종류: " + (call.sourceUri.startsWith("calllog:") ? "전화기록" : "삼성 통화녹음"), 42, y, 510, 15);
                paint.setFakeBoldText(true); paint.setTextSize(13); y += 16; canvas.drawText("요약", 42, y, paint); y += 18;
                paint.setFakeBoldText(false); paint.setTextSize(11);
                y = drawPdfText(canvas, paint, valueOr(call.summary, "요약 확인 필요"), 42, y, 510, 15);
                paint.setFakeBoldText(true); paint.setTextSize(13); y += 14; canvas.drawText("통화 내용", 42, y, paint); y += 18;
                paint.setFakeBoldText(false); paint.setTextSize(11);
                String transcript = call.transcript == null ? "" : call.transcript.trim();
                String body = transcript.isEmpty()
                        ? "삼성 통화녹음 전사가 아직 연결되지 않았습니다. 전화기록만으로 실제 대화 내용을 작성하지 않습니다."
                        : transcript;
                drawPdfText(canvas, paint, body, 42, y, 510, 15);
                pdf.finishPage(page);
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "119Fire/call-notes"); if (!dir.exists()) dir.mkdirs();
                String safe = valueOr(call.displayName, "통화기록").replaceAll("[\\\\/:*?\"<>|]", "_");
                String baseName = "call_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(new Date(at)) + "_" + safe;
                File out = new File(dir, baseName + ".pdf");
                try (FileOutputStream stream = new FileOutputStream(out)) { pdf.writeTo(stream); }
                WorkDatabase.Document doc = new WorkDatabase.Document(); doc.customerId = call.customerId; doc.type = "통화기록"; doc.name = out.getName(); doc.path = out.getAbsolutePath(); database.store().insertDocument(doc);
                // TXT keeps the whole transcript intact even when a long call exceeds one PDF page.
                File textOut = new File(dir, baseName + ".txt");
                String fullText = "119파이어 통화기록\n\n통화 상대: " + valueOr(call.displayName, "이름 미확인") +
                        "\n전화번호: " + valueOr(call.phone, "확인 필요") + "\n통화 시각: " + time +
                        "\n\n[요약]\n" + valueOr(call.summary, "요약 확인 필요") + "\n\n[통화 내용]\n" + body + "\n";
                try (FileOutputStream stream = new FileOutputStream(textOut)) { stream.write(fullText.getBytes(StandardCharsets.UTF_8)); }
                WorkDatabase.Document textDoc = new WorkDatabase.Document(); textDoc.customerId = call.customerId; textDoc.type = "통화기록 원문"; textDoc.name = textOut.getName(); textDoc.path = textOut.getAbsolutePath(); database.store().insertDocument(textDoc);
                return result(true, transcript.isEmpty() ? "전사 없음 표시로 통화기록 PDF·TXT를 저장했습니다." : "통화 전사·요약 PDF·TXT를 저장했습니다.", callId).put("path", out.getAbsolutePath()).put("textPath", textOut.getAbsolutePath()).toString();
            } catch (Exception e) { return result(false, "통화기록 PDF 생성에 실패했습니다.", callId).toString(); }
            finally { pdf.close(); }
        }

        @JavascriptInterface
        public String getBlogReadiness(long siteId) {
            try {
                WorkDatabase.Store store = database.store(); WorkDatabase.Site site = store.siteById(siteId);
                if (site == null) return result(false, "현장을 찾지 못했습니다.", siteId).toString();
                int before = store.photoCountForCategory(siteId, "공사 전");
                int during = store.photoCountForCategory(siteId, "공사 중");
                int after = store.photoCountForCategory(siteId, "공사 후");
                String status = V3Workflow.blogReadiness(before, during, after, site.workNote);
                JSONObject out = result(true, status, siteId);
                out.put("status", status); out.put("before", before); out.put("during", during); out.put("after", after);
                out.put("missing", status.equals("블로그 작성 가능") ? "" : status);
                return out.toString();
            } catch (Exception e) { return result(false, "블로그 준비도를 확인하지 못했습니다.", siteId).toString(); }
        }

        /** Creates only a draft using local records. It never publishes or modifies a Naver post. */
        @JavascriptInterface
        public void createBlogDraft(long siteId) {
            new Thread(() -> {
                JSONObject out = new JSONObject();
                try {
                    WorkDatabase.Store store = database.store(); WorkDatabase.Site site = store.siteById(siteId);
                    if (site == null) throw new Exception("현장을 찾지 못했습니다.");
                    int before = store.photoCountForCategory(siteId, "공사 전");
                    int during = store.photoCountForCategory(siteId, "공사 중");
                    int after = store.photoCountForCategory(siteId, "공사 후");
                    if (!V3Workflow.blogReadiness(before, during, after, site.workNote).equals("블로그 작성 가능"))
                        throw new Exception("사진 또는 실제 작업내용이 부족해 초안을 만들 수 없습니다.");
                    String base = normalizeBase(prefs.getString("server_base", ""));
                    String code = prefs.getString("app_access_code", "");
                    if (code.isEmpty()) throw new Exception("AI 서버 연결을 먼저 설정해주세요.");
                    JSONObject facts = new JSONObject();
                    facts.put("siteName", site.name); facts.put("address", site.address); facts.put("workNote", site.workNote);
                    facts.put("status", site.status); facts.put("photos", photosJson(store.photosForSite(siteId)));
                    if (site.customerId != null) {
                        WorkDatabase.Customer customer = store.customerById(site.customerId);
                        if (customer != null) facts.put("customer", customer.company);
                        facts.put("relatedCalls", callsJson(store.callsByCustomer(site.customerId, 10)));
                    }
                    JSONObject body = new JSONObject(); body.put("code", code); body.put("facts", facts);
                    JSONObject response = new JSONObject(postJson(base + "/.netlify/functions/blog-draft", body.toString()));
                    WorkDatabase.BlogDraft draft = new WorkDatabase.BlogDraft();
                    draft.siteId = siteId; draft.content = response.optString("draft", "");
                    draft.title = site.name + " 블로그 초안"; draft.seoVersion = response.optString("seoVersion", "119파이어 스토리텔링 SEO 21.0");
                    draft.status = "초안 확인 필요"; draft.missingFacts = "사용자 확인 후만 게시";
                    long id = store.insertBlogDraft(draft);
                    out.put("ok", true); out.put("id", id); out.put("draft", draft.content); out.put("message", "블로그 초안을 저장했습니다. 실제 내용만 남았는지 확인해주세요.");
                } catch (Exception e) {
                    try { out.put("ok", false); out.put("error", e.getMessage() == null ? "블로그 초안 생성에 실패했습니다." : e.getMessage()); } catch (Exception ignored) {}
                }
                callJs("onBlogDraft", out);
            }).start();
        }
    }

    private JSONObject result(boolean ok, String message, long id) {
        JSONObject out = new JSONObject();
        try { out.put("ok", ok); out.put("message", message); out.put("id", id); } catch (Exception ignored) {}
        return out;
    }

    private String valueOr(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private String normalizePhone(String phone) { return phone == null ? "" : phone.replaceAll("[^0-9+]", ""); }
    private Long numberOrNull(JSONObject input, String key) {
        return input.has(key) && !input.isNull(key) && input.optLong(key, 0) > 0 ? input.optLong(key) : null;
    }
    private JSONArray inquiriesJson(java.util.List<WorkDatabase.Inquiry> items) {
        JSONArray a = new JSONArray(); for (WorkDatabase.Inquiry x:items) try { JSONObject o=new JSONObject(); o.put("id",x.id);o.put("title",x.title);o.put("content",x.content);o.put("status",x.status);o.put("source",x.source);o.put("siteId",x.siteId);o.put("customerId",x.customerId);o.put("updatedAt",x.updatedAt);a.put(o);}catch(Exception ignored){} return a;
    }
    private JSONArray sitesJson(java.util.List<WorkDatabase.Site> items) {
        JSONArray a = new JSONArray(); for (WorkDatabase.Site x:items) try { JSONObject o=new JSONObject(); o.put("id",x.id);o.put("name",x.name);o.put("address",x.address);o.put("status",x.status);o.put("workNote",x.workNote);o.put("customerId",x.customerId);o.put("visitAt",x.visitAt);o.put("startedAt",x.startedAt);o.put("finishedAt",x.finishedAt);o.put("revisitNote",x.revisitNote);o.put("updatedAt",x.updatedAt);a.put(o);}catch(Exception ignored){} return a;
    }
    private JSONArray tasksJson(java.util.List<WorkDatabase.Task> items) {
        JSONArray a = new JSONArray(); for (WorkDatabase.Task x:items) try { JSONObject o=new JSONObject(); o.put("id",x.id);o.put("title",x.title);o.put("dueAt",x.dueAt);o.put("status",x.status);o.put("source",x.source);o.put("siteId",x.siteId);o.put("requiresConfirmation",x.requiresConfirmation);a.put(o);}catch(Exception ignored){} return a;
    }
    private JSONArray estimatesJson(java.util.List<WorkDatabase.Estimate> items) {
        JSONArray a = new JSONArray(); for (WorkDatabase.Estimate x:items) try { JSONObject o=new JSONObject(); o.put("id",x.id);o.put("title",x.title);o.put("version",x.version);o.put("supplyAmount",x.supplyAmount);o.put("vat",x.vat);o.put("total",x.total);o.put("status",x.status);o.put("siteId",x.siteId);a.put(o);}catch(Exception ignored){} return a;
    }
    private JSONArray photosJson(java.util.List<WorkDatabase.Photo> items) {
        JSONArray a = new JSONArray(); for (WorkDatabase.Photo x:items) try { JSONObject o=new JSONObject(); o.put("id",x.id);o.put("name",x.name);o.put("path",x.path);o.put("category",x.category);o.put("siteId",x.siteId);o.put("createdAt",x.createdAt);a.put(o);}catch(Exception ignored){} return a;
    }
    private JSONArray callsJson(java.util.List<WorkDatabase.CallRecord> items) {
        JSONArray a = new JSONArray(); for (WorkDatabase.CallRecord x:items) try { JSONObject o=new JSONObject(); o.put("id",x.id);o.put("name",x.displayName);o.put("uri",x.sourceUri);o.put("size",x.fileSize);o.put("modified",x.modifiedAt);o.put("phone",x.phone);o.put("status",x.processingStatus);o.put("summary",x.summary);o.put("kind",x.sourceUri.startsWith("calllog:")?"통화기록":"통화녹음");a.put(o);}catch(Exception ignored){} return a;
    }
    private JSONArray estimateFavoritesJson(java.util.List<WorkDatabase.EstimateFavorite> items) {
        JSONArray a = new JSONArray(); for (WorkDatabase.EstimateFavorite x:items) try { JSONObject o=new JSONObject();o.put("id",x.id);o.put("name",x.name);o.put("specification",x.specification);o.put("unit",x.unit);o.put("unitPrice",x.unitPrice);a.put(o);}catch(Exception ignored){} return a;
    }
    private JSONArray estimateNoteTemplatesJson(java.util.List<WorkDatabase.EstimateNoteTemplate> items) {
        JSONArray a = new JSONArray(); for (WorkDatabase.EstimateNoteTemplate x:items) try { JSONObject o=new JSONObject();o.put("id",x.id);o.put("title",x.title);o.put("body",x.body);a.put(o);}catch(Exception ignored){} return a;
    }
    private JSONArray jobsJson(java.util.List<WorkDatabase.AutomationJob> items) {
        JSONArray a = new JSONArray(); for (WorkDatabase.AutomationJob x:items) try { JSONObject o=new JSONObject();o.put("id",x.id);o.put("type",x.type);o.put("payload",x.payloadJson);o.put("status",x.status);o.put("error",x.lastError);o.put("updatedAt",x.updatedAt);a.put(o);}catch(Exception ignored){} return a;
    }

    private void syncPhoneCallHistoryAsync() {
        new Thread(() -> {
            JSONObject out = new JSONObject();
            try {
                int count = PhoneCallHistorySync.sync(MainActivity.this);
                out.put("ok", true);
                out.put("count", count);
                out.put("message", count == 0 ? "새 통화기록이 없습니다." : count + "건의 기존 통화기록을 연결했습니다.");
            } catch (SecurityException e) {
                try { out.put("ok", false); out.put("error", "전화기록 접근 권한을 허용해주세요."); } catch (Exception ignored) {}
            } catch (Exception e) {
                try { out.put("ok", false); out.put("error", "전화기록을 불러오지 못했습니다."); } catch (Exception ignored) {}
            }
            callJs("onCallHistorySynced", out);
        }).start();
    }

    private void syncCameraPhotosAsync() {
        new Thread(() -> {
            JSONObject out = new JSONObject();
            try {
                int count = MediaStorePhotoSync.sync(MainActivity.this);
                out.put("ok", true); out.put("count", count);
                out.put("message", count == 0 ? "새 카메라 사진이 없습니다." : count + "장의 새 카메라 사진을 확인했습니다.");
            } catch (SecurityException e) {
                try { out.put("ok", false); out.put("error", "사진 접근 권한을 허용해주세요."); } catch (Exception ignored) {}
            } catch (Exception e) {
                try { out.put("ok", false); out.put("error", "카메라 사진을 확인하지 못했습니다."); } catch (Exception ignored) {}
            }
            callJs("onCameraPhotosSynced", out);
        }).start();
    }

    /** Draws a short wrapped paragraph in an on-device PDF. The full transcript is also saved as TXT. */
    private int drawPdfText(Canvas canvas, Paint paint, String text, int x, int y, int width, int lineHeight) {
        String[] paragraphs = (text == null ? "" : text).replace("\r", "").split("\n", -1);
        for (String paragraph : paragraphs) {
            String remaining = paragraph;
            if (remaining.isEmpty()) { y += lineHeight; continue; }
            while (!remaining.isEmpty()) {
                int count = paint.breakText(remaining, true, width, null);
                if (count <= 0) break;
                canvas.drawText(remaining.substring(0, count), x, y, paint);
                remaining = remaining.substring(count);
                y += lineHeight;
                if (y > 790) return y;
            }
        }
        return y;
    }

    private void beginVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { callJs("onVoiceError", result(false, "이 기기에서 음성 인식을 사용할 수 없습니다.", 0)); return; }
        if (speechRecognizer != null) speechRecognizer.destroy();
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) { callJs("onVoiceState", result(true, "듣고 있습니다.", 0)); }
            public void onBeginningOfSpeech() {} public void onRmsChanged(float rmsdB) {} public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() {} public void onPartialResults(Bundle b) {} public void onEvent(int t, Bundle b) {}
            public void onError(int e) { callJs("onVoiceError", result(false, "음성을 다시 말씀해주세요.", e)); }
            public void onResults(Bundle results) {
                ArrayList<String> text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                JSONObject out = result(text != null && !text.isEmpty(), text != null && !text.isEmpty() ? text.get(0) : "음성을 이해하지 못했습니다.", 0);
                callJs("onVoiceText", out);
            }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "119비서에게 말하기");
        speechRecognizer.startListening(intent);
    }

    private boolean isAudio(DocumentFile f) {
        String mime = f.getType() == null ? "" : f.getType().toLowerCase(Locale.ROOT);
        String n = f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT);
        return mime.startsWith("audio/") ||
                n.endsWith(".m4a") || n.endsWith(".mp3") || n.endsWith(".wav") ||
                n.endsWith(".amr") || n.endsWith(".3gp") || n.endsWith(".aac");
    }

    private void scheduleMonitor() {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CallRecordingWorker.class, 15, TimeUnit.MINUTES
        ).build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "119fire_call_monitor",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );
    }

    private void launchCamera() {
        try {
            File base = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "119Fire");
            String safe = pendingJobName.replaceAll("[\\\\/:*?\"<>|]", "_");
            File jobDir = new File(base, safe);
            if (!jobDir.exists()) jobDir.mkdirs();

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());
            pendingPhoto = new File(jobDir, "site_" + ts + ".jpg");
            Uri out = FileProvider.getUriForFile(this, getPackageName()+".fileprovider", pendingPhoto);

            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, out);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "카메라 실행 실패: "+e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String normalizeBase(String base) throws Exception {
        if (base == null || base.trim().isEmpty()) throw new Exception("서버 주소를 먼저 설정해주세요.");
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length()-1);
        if (!b.startsWith("https://")) throw new Exception("서버 주소는 https:// 주소여야 합니다.");
        return b;
    }

    private String postJson(String urlString, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        c.getOutputStream().write(b);
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(in);
        if (code < 200 || code >= 300) {
            String contentType = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
            if (code == 401 && contentType.contains("text/html") && text.contains("edge-access"))
                throw new Exception("Netlify 사이트 로그인 보호가 AI 함수를 막고 있습니다. 사이트 소유 계정에서 접근 설정을 확인해주세요.");
            if (code == 404) throw new Exception("Netlify AI 함수가 배포되지 않았습니다. 서버 배포 설정을 확인해주세요.");
            throw new Exception(extractError(text, "AI 서버 연결 오류 ("+code+")"));
        }
        return text;
    }

    private String uploadMultipart(String urlString, String accessCode, Uri uri, String name, String mime) throws Exception {
        String boundary = "----119Fire" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setDoOutput(true);
        c.setChunkedStreamingMode(64 * 1024);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setRequestProperty("X-App-Code", accessCode == null ? "" : accessCode);

        DataOutputStream out = new DataOutputStream(c.getOutputStream());
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"" +
                name.replace("\"","") + "\"\r\n");
        out.writeBytes("Content-Type: " + mime + "\r\n\r\n");

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("녹음파일을 읽을 수 없습니다.");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.writeBytes("\r\n--" + boundary + "--\r\n");
        out.flush();
        out.close();

        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(in);
        if (code < 200 || code >= 300) throw new Exception(extractError(text, "서버 오류 "+code));
        return text;
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private String extractError(String text, String fallback) {
        try {
            JSONObject o = new JSONObject(text);
            return o.optString("error", fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);

        if (req == REQ_FOLDER && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {}

            prefs.edit()
                    .putString("call_folder_uri", uri.toString())
                    .putLong("monitor_last_seen", System.currentTimeMillis())
                    .apply();

            JSONObject o = statusJson();
            callJs("onCallFolderSelected", o);
            return;
        }

        if (req == REQ_CAMERA && result == RESULT_OK && pendingPhoto != null && pendingPhoto.exists()) {
            JSONObject o = new JSONObject();
            try {
                WorkDatabase.Store store = database.store();
                WorkDatabase.Site site = store.siteByName(pendingJobName);
                long siteId;
                if (site == null) {
                    site = new WorkDatabase.Site();
                    site.name = pendingJobName;
                    site.status = V3Workflow.WORKING;
                    site.startedAt = System.currentTimeMillis();
                    siteId = store.insertSite(site);
                } else {
                    siteId = site.id;
                    store.markSiteStarted(siteId, System.currentTimeMillis());
                }
                WorkDatabase.Photo photo = new WorkDatabase.Photo();
                photo.siteId = siteId; photo.path = pendingPhoto.getAbsolutePath(); photo.name = pendingPhoto.getName();
                photo.category = "미분류";
                photo.createdAt = System.currentTimeMillis();
                store.insertPhoto(photo);
                o.put("ok", true);
                o.put("jobName", pendingJobName);
                o.put("siteId", siteId);
                o.put("path", pendingPhoto.getAbsolutePath());
                o.put("name", pendingPhoto.getName());
                o.put("created", System.currentTimeMillis());
            } catch (Exception ignored) {}
            callJs("onPhotoSaved", o);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        if (req == REQ_CAMERA_PERMISSION && results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        }
        if (req == REQ_NOTIFICATION) {
            scheduleMonitor();
        }
        if (req == REQ_CALL_HISTORY && results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("call_history_enabled", true).apply();
            scheduleMonitor();
            syncPhoneCallHistoryAsync();
            callJs("onNativeStatus", statusJson());
        }
        if (req == REQ_PHOTO_LIBRARY && results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("photo_sync_enabled", true).apply();
            scheduleMonitor(); syncCameraPhotosAsync(); callJs("onNativeStatus", statusJson());
        }
        if (req == 1105 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            beginVoiceRecognition();
        }
    }

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (textToSpeech != null) textToSpeech.shutdown();
        super.onDestroy();
    }
}
