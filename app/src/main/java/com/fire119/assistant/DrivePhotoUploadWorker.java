package com.fire119.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Uploads a confirmed site photo through Netlify after Google Drive OAuth is configured there. */
public final class DrivePhotoUploadWorker extends Worker {
    private static final String KEY_PHOTO_ID = "photo_id";
    public DrivePhotoUploadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }
    public static void enqueue(Context context, long photoId) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DrivePhotoUploadWorker.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(new Data.Builder().putLong(KEY_PHOTO_ID, photoId).build()).build();
        WorkManager.getInstance(context).enqueueUniqueWork("119fire_drive_photo_" + photoId,
                androidx.work.ExistingWorkPolicy.KEEP, request);
    }
    @NonNull @Override public Result doWork() {
        Context context=getApplicationContext(); WorkDatabase.Store store=WorkDatabase.get(context).store();
        WorkDatabase.Photo photo=store.photoById(getInputData().getLong(KEY_PHOTO_ID,0));
        if(photo==null||photo.siteId==null) return Result.success();
        WorkDatabase.Site site=store.siteById(photo.siteId); if(site==null) return Result.success();
        SharedPreferences prefs=context.getSharedPreferences("119fire_native",Context.MODE_PRIVATE);
        boolean useAi=prefs.getBoolean("drive_use_ai_connection",true);
        String base=(useAi?prefs.getString("server_base",""):prefs.getString("drive_server_base","")).trim();
        String code=useAi?prefs.getString("app_access_code",""):prefs.getString("drive_access_code","");
        if(!prefs.getBoolean("drive_backup_enabled",false)||base.isEmpty()||code.isEmpty()) return Result.success();
        try {
            String target=trimBase(base)+"/.netlify/functions/drive";
            String date=new SimpleDateFormat("yyyy",Locale.KOREA).format(new Date(photo.createdAt)) + "년도";
            String month=new SimpleDateFormat("M월",Locale.KOREA).format(new Date(photo.createdAt));
            JSONObject result=new JSONObject(upload(context,target,code,photo.path,photo.name,site.name,date,month));
            String fileId=result.optString("driveFileId",""); String folderId=result.optString("driveFolderId","");
            if(fileId.isEmpty()) throw new IllegalStateException(result.optString("error","Drive 업로드에 실패했습니다."));
            store.updatePhotoBackup(photo.id,"Drive 업로드 완료",fileId);
            if(!folderId.isEmpty()) store.updateSiteDriveFolder(site.id,folderId,System.currentTimeMillis());
            AutomationLog.add(context,site.name+" 사진 1장을 Google Drive에 정리했습니다.");
            return Result.success();
        } catch(Exception e) {
            String message=e.getMessage()==null?"Drive 업로드 재시도 대기":e.getMessage();
            if(message.contains("OAuth 환경변수")) {store.updatePhotoBackup(photo.id,"Drive 연결 대기",""); return Result.success();}
            store.updatePhotoBackup(photo.id,"Drive 업로드 재시도","");
            return getRunAttemptCount()<3?Result.retry():Result.success();
        }
    }
    private static String trimBase(String value){String out=value;while(out.endsWith("/"))out=out.substring(0,out.length()-1);return out;}
    private static String upload(Context context,String target,String code,String path,String fileName,String siteName,String year,String month) throws Exception {
        String boundary="----119FireDrive"+System.currentTimeMillis();
        HttpURLConnection c=(HttpURLConnection)new URL(target).openConnection(); c.setRequestMethod("POST");c.setConnectTimeout(20000);c.setReadTimeout(180000);c.setDoOutput(true);c.setChunkedStreamingMode(64*1024);
        c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);c.setRequestProperty("X-App-Code",code);
        try(DataOutputStream out=new DataOutputStream(c.getOutputStream());InputStream in=open(context,path)){
            if(in==null)throw new IllegalStateException("사진 원본을 읽을 수 없습니다.");
            field(out,boundary,"action","upload-photo");field(out,boundary,"siteName",siteName);field(out,boundary,"year",year);field(out,boundary,"month",month);
            out.writeBytes("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+fileName.replace("\"","")+"\"\r\nContent-Type: image/jpeg\r\n\r\n");
            byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);out.writeBytes("\r\n--"+boundary+"--\r\n");out.flush();
        }
        int status=c.getResponseCode();InputStream body=status>=200&&status<300?c.getInputStream():c.getErrorStream();String text=read(body);
        if(status<200||status>=300)throw new IllegalStateException(text.isEmpty()?"Drive 서버 오류":new JSONObject(text).optString("error",text));return text;
    }
    private static InputStream open(Context c,String path)throws Exception{return path.startsWith("content://")?c.getContentResolver().openInputStream(Uri.parse(path)):new java.io.FileInputStream(new File(path));}
    private static void field(DataOutputStream out,String boundary,String name,String value)throws Exception{out.writeBytes("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n");}
    private static String read(InputStream source)throws Exception{if(source==null)return"";try(InputStream in=source;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
}
