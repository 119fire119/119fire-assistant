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
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

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
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1101;
    private static final int REQ_CAMERA_PERMISSION = 1102;
    private static final int REQ_FOLDER = 1103;
    private static final int REQ_NOTIFICATION = 1104;

    private WebView webView;
    private SharedPreferences prefs;
    private File pendingPhoto;
    private String pendingJobName = "미지정 현장";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("119fire_native", MODE_PRIVATE);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new NativeBridge(), "Native");
        webView.loadUrl("file:///android_asset/index.html");
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
                        JSONObject x = new JSONObject();
                        x.put("name", f.getName() == null ? "통화녹음" : f.getName());
                        x.put("uri", f.getUri().toString());
                        x.put("size", f.length());
                        x.put("modified", f.lastModified());
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
            WorkManager.getInstance(MainActivity.this).cancelUniqueWork("119fire_call_monitor");
            prefs.edit().putBoolean("monitor_enabled", false).apply();
            callJs("onNativeStatus", statusJson());
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
                    out.put("ok", true);
                } catch (Exception e) {
                    try { out.put("ok", false); out.put("error", e.getMessage()); } catch (Exception ignored) {}
                }
                callJs("onTranscribeResponse", out);
            }).start();
        }
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
        if (code < 200 || code >= 300) throw new Exception(extractError(text, "서버 오류 "+code));
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
                o.put("ok", true);
                o.put("jobName", pendingJobName);
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
    }
}
