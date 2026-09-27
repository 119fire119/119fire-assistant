package com.fire119.assistant;

import android.content.Context;

import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.util.List;

/**
 * Local-first business store. Every record keeps the originating customer/site IDs so the same
 * phone number, address and site name do not have to be typed in on each screen.
 */
@Database(entities = {
        WorkDatabase.Customer.class, WorkDatabase.Inquiry.class, WorkDatabase.Site.class,
        WorkDatabase.Task.class, WorkDatabase.CalendarEvent.class, WorkDatabase.Estimate.class,
        WorkDatabase.EstimateItem.class, WorkDatabase.Payment.class, WorkDatabase.CallRecord.class,
        WorkDatabase.Photo.class, WorkDatabase.Document.class, WorkDatabase.BlogDraft.class,
        WorkDatabase.AIAnalysis.class, WorkDatabase.AutomationJob.class
}, version = 1, exportSchema = false)
public abstract class WorkDatabase extends RoomDatabase {
    private static volatile WorkDatabase instance;
    public abstract Store store();

    public static WorkDatabase get(Context context) {
        if (instance == null) synchronized (WorkDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), WorkDatabase.class,
                    "119fire_work_v2.db").build();
        }
        return instance;
    }

    @Entity(indices = {@Index(value = "phone", unique = true)})
    public static class Customer {
        @PrimaryKey(autoGenerate = true) public long id;
        public String phone = "";
        public String name = "이름 미확인";
        public String company = "";
        public String memo = "";
        public long createdAt = System.currentTimeMillis();
        public long updatedAt = System.currentTimeMillis();
    }

    @Entity(foreignKeys = @ForeignKey(entity = Customer.class, parentColumns = "id", childColumns = "customerId", onDelete = ForeignKey.SET_NULL), indices = {@Index("customerId"), @Index("status")})
    public static class Inquiry {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long customerId;
        public Long siteId;
        public String title = "신규 문의";
        public String content = "";
        public String status = "신규문의";
        public String source = "확인 불가";
        public String dueAt = "";
        public long createdAt = System.currentTimeMillis();
        public long updatedAt = System.currentTimeMillis();
    }

    @Entity(foreignKeys = @ForeignKey(entity = Customer.class, parentColumns = "id", childColumns = "customerId", onDelete = ForeignKey.SET_NULL), indices = {@Index("customerId"), @Index("name")})
    public static class Site {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long customerId;
        public String name = "미지정 현장";
        public String address = "";
        public String status = "현장확인 대기";
        public String workNote = "";
        public long createdAt = System.currentTimeMillis();
        public long updatedAt = System.currentTimeMillis();
    }

    @Entity(indices = {@Index("dueAt"), @Index("status"), @Index("siteId")})
    public static class Task {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long customerId;
        public Long inquiryId;
        public Long siteId;
        public String title = "확인 필요";
        public String dueAt = "";
        public String status = "대기";
        public String source = "직접 등록";
        public boolean requiresConfirmation = false;
        public long createdAt = System.currentTimeMillis();
        public long completedAt = 0;
    }

    @Entity(indices = {@Index(value = "dedupeKey", unique = true), @Index("startAt")})
    public static class CalendarEvent {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long customerId;
        public Long siteId;
        public String title = "일정 확인 필요";
        public String startAt = "";
        public String endAt = "";
        public String address = "";
        public String dedupeKey = "";
        public String syncStatus = "후보";
        public long createdAt = System.currentTimeMillis();
    }

    @Entity(indices = {@Index("siteId"), @Index("status")})
    public static class Estimate {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long customerId;
        public Long siteId;
        public String title = "견적서";
        public int version = 1;
        public long supplyAmount = 0;
        public long vat = 0;
        public long total = 0;
        public String status = "견적 작성";
        public String pdfPath = "";
        public long createdAt = System.currentTimeMillis();
    }

    @Entity(foreignKeys = @ForeignKey(entity = Estimate.class, parentColumns = "id", childColumns = "estimateId", onDelete = ForeignKey.CASCADE), indices = {@Index("estimateId")})
    public static class EstimateItem {
        @PrimaryKey(autoGenerate = true) public long id;
        public long estimateId;
        public String name = "기타";
        public double quantity = 1;
        public long unitPrice = 0;
        public long amount = 0;
        public int sortOrder = 0;
    }

    @Entity(indices = {@Index("siteId"), @Index("status")})
    public static class Payment {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long customerId;
        public Long siteId;
        public Long estimateId;
        public long contractAmount = 0;
        public long paidAmount = 0;
        public long balance = 0;
        public String paidAt = "";
        public String status = "수금 대기";
        public String taxInvoiceStatus = "확인 필요";
        public String memo = "";
    }

    @Entity(indices = {@Index(value = "sourceUri", unique = true), @Index("customerId"), @Index("processingStatus")})
    public static class CallRecord {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long customerId;
        public Long inquiryId;
        public String sourceUri = "";
        public String displayName = "통화녹음";
        public long fileSize = 0;
        public long modifiedAt = 0;
        public String phone = "";
        public String transcript = "";
        public String summary = "";
        public String processingStatus = "대기";
        public long createdAt = System.currentTimeMillis();
        public long analyzedAt = 0;
    }

    @Entity(indices = {@Index(value = "path", unique = true), @Index("siteId"), @Index("category")})
    public static class Photo {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long siteId;
        public String path = "";
        public String name = "";
        public String category = "기타";
        public boolean faceBlurReview = false;
        public String backupStatus = "대기";
        public long createdAt = System.currentTimeMillis();
    }

    @Entity(indices = {@Index("siteId"), @Index("type")})
    public static class Document {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long customerId;
        public Long siteId;
        public String type = "기타";
        public String name = "";
        public String path = "";
        public String backupStatus = "대기";
        public long createdAt = System.currentTimeMillis();
    }

    @Entity(indices = {@Index("siteId"), @Index("status")})
    public static class BlogDraft {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long siteId;
        public String title = "";
        public String content = "";
        public String seoVersion = "119파이어 스토리텔링 SEO 21.0";
        public String status = "자료 확인 필요";
        public String missingFacts = "";
        public long createdAt = System.currentTimeMillis();
    }

    @Entity(indices = {@Index("callRecordId"), @Index("status")})
    public static class AIAnalysis {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long callRecordId;
        public Long customerId;
        public Long inquiryId;
        public String kind = "통화 분석";
        public String dataJson = "{}";
        public String status = "확인 필요";
        public long createdAt = System.currentTimeMillis();
    }

    @Entity(indices = {@Index(value = "dedupeKey", unique = true), @Index("status")})
    public static class AutomationJob {
        @PrimaryKey(autoGenerate = true) public long id;
        public String type = "AI_ANALYZE_CALL";
        public String dedupeKey = "";
        public String payloadJson = "{}";
        public String status = "대기";
        public int attempts = 0;
        public String lastError = "";
        public long createdAt = System.currentTimeMillis();
        public long updatedAt = System.currentTimeMillis();
    }

    @Dao
    public interface Store {
        @Insert(onConflict = OnConflictStrategy.IGNORE) long insertCustomer(Customer value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertInquiry(Inquiry value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertSite(Site value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertTask(Task value);
        @Insert(onConflict = OnConflictStrategy.IGNORE) long insertCalendar(CalendarEvent value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertEstimate(Estimate value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertEstimateItem(EstimateItem value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertPayment(Payment value);
        @Insert(onConflict = OnConflictStrategy.IGNORE) long insertCall(CallRecord value);
        @Insert(onConflict = OnConflictStrategy.IGNORE) long insertPhoto(Photo value);
        @Insert(onConflict = OnConflictStrategy.IGNORE) long insertDocument(Document value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertBlogDraft(BlogDraft value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertAnalysis(AIAnalysis value);
        @Insert(onConflict = OnConflictStrategy.IGNORE) long insertJob(AutomationJob value);
        @Query("SELECT * FROM Customer WHERE phone=:phone LIMIT 1") Customer customerByPhone(String phone);
        @Query("SELECT * FROM Site WHERE name=:name ORDER BY updatedAt DESC LIMIT 1") Site siteByName(String name);
        @Query("SELECT * FROM Site WHERE id=:id LIMIT 1") Site siteById(long id);
        @Query("SELECT * FROM CallRecord WHERE sourceUri=:uri LIMIT 1") CallRecord callByUri(String uri);
        @Query("SELECT * FROM Inquiry ORDER BY updatedAt DESC LIMIT :limit") List<Inquiry> inquiries(int limit);
        @Query("SELECT * FROM Site ORDER BY updatedAt DESC LIMIT :limit") List<Site> sites(int limit);
        @Query("SELECT * FROM Task WHERE status!='완료' ORDER BY CASE WHEN dueAt='' THEN 1 ELSE 0 END, dueAt ASC LIMIT :limit") List<Task> openTasks(int limit);
        @Query("SELECT * FROM Estimate ORDER BY createdAt DESC LIMIT :limit") List<Estimate> estimates(int limit);
        @Query("SELECT * FROM Estimate WHERE id=:id LIMIT 1") Estimate estimateById(long id);
        @Query("SELECT * FROM EstimateItem WHERE estimateId=:estimateId ORDER BY sortOrder ASC") List<EstimateItem> estimateItems(long estimateId);
        @Query("SELECT * FROM Photo ORDER BY createdAt DESC LIMIT :limit") List<Photo> photos(int limit);
        @Query("SELECT * FROM CallRecord ORDER BY modifiedAt DESC LIMIT :limit") List<CallRecord> calls(int limit);
        @Query("SELECT * FROM CallRecord WHERE processingStatus='대기' ORDER BY modifiedAt ASC LIMIT :limit") List<CallRecord> pendingCalls(int limit);
        @Query("SELECT COUNT(*) FROM Inquiry") int inquiryCount();
        @Query("SELECT COUNT(*) FROM Task WHERE status!='완료'") int openTaskCount();
        @Query("SELECT COUNT(*) FROM CallRecord WHERE processingStatus IN ('대기','실패','확인 필요')") int waitingCallCount();
        @Query("SELECT COUNT(*) FROM Payment WHERE balance>0") int receivableCount();
        @Query("SELECT COUNT(*) FROM Photo") int photoCount();
        @Query("UPDATE Task SET status='완료', completedAt=:now WHERE id=:id") void completeTask(long id, long now);
        @Query("UPDATE Site SET status=:status, workNote=:note, updatedAt=:now WHERE id=:id") void finishSite(long id, String status, String note, long now);
        @Query("UPDATE Site SET workNote=:note, updatedAt=:now WHERE id=:id") void updateSiteNote(long id, String note, long now);
        @Query("UPDATE CallRecord SET processingStatus=:status, transcript=:transcript, summary=:summary, analyzedAt=:now WHERE id=:id") void updateCallAnalysis(long id, String status, String transcript, String summary, long now);
        @Query("SELECT * FROM Payment WHERE balance>0 ORDER BY balance DESC LIMIT :limit") List<Payment> receivables(int limit);
    }
}
