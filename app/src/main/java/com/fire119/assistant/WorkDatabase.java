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
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

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
        WorkDatabase.AIAnalysis.class, WorkDatabase.AutomationJob.class,
        WorkDatabase.VoiceMemo.class, WorkDatabase.EstimateVersion.class,
        WorkDatabase.EstimateFavorite.class, WorkDatabase.EstimateNoteTemplate.class
}, version = 2, exportSchema = false)
public abstract class WorkDatabase extends RoomDatabase {
    private static volatile WorkDatabase instance;
    public abstract Store store();

    public static WorkDatabase get(Context context) {
        if (instance == null) synchronized (WorkDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), WorkDatabase.class,
                    "119fire_work_v2.db").addMigrations(MIGRATION_1_2).build();
        }
        return instance;
    }

    /** V2 업무 기록은 그대로 보존하고 V3에서 필요한 열·테이블만 더합니다. */
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE Customer ADD COLUMN contactName TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE Customer ADD COLUMN region TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE Customer ADD COLUMN address TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE Site ADD COLUMN visitAt TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE Site ADD COLUMN startedAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE Site ADD COLUMN finishedAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE Site ADD COLUMN revisitNote TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE Estimate ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE Estimate ADD COLUMN followUpAt TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE EstimateItem ADD COLUMN specification TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE TABLE IF NOT EXISTS `VoiceMemo` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `siteId` INTEGER, `text` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_VoiceMemo_siteId` ON `VoiceMemo` (`siteId`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `EstimateVersion` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `estimateId` INTEGER NOT NULL, `version` INTEGER NOT NULL, `snapshotJson` TEXT NOT NULL, `noteText` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_EstimateVersion_estimateId` ON `EstimateVersion` (`estimateId`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `EstimateFavorite` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `specification` TEXT NOT NULL, `unit` TEXT NOT NULL, `unitPrice` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `EstimateNoteTemplate` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)");
        }
    };

    @Entity(indices = {@Index(value = "phone", unique = true)})
    public static class Customer {
        @PrimaryKey(autoGenerate = true) public long id;
        public String phone = "";
        public String name = "이름 미확인";
        public String company = "";
        public String contactName = "";
        public String region = "";
        public String address = "";
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
        public String visitAt = "";
        public long startedAt = 0;
        public long finishedAt = 0;
        public String revisitNote = "";
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
        public long updatedAt = System.currentTimeMillis();
        public String followUpAt = "";
    }

    @Entity(foreignKeys = @ForeignKey(entity = Estimate.class, parentColumns = "id", childColumns = "estimateId", onDelete = ForeignKey.CASCADE), indices = {@Index("estimateId")})
    public static class EstimateItem {
        @PrimaryKey(autoGenerate = true) public long id;
        public long estimateId;
        public String name = "기타";
        public String specification = "";
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

    @Entity(indices = {@Index("siteId")})
    public static class VoiceMemo {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long siteId;
        public String text = "";
        public String source = "음성메모";
        public long createdAt = System.currentTimeMillis();
    }

    /** Snapshot rows preserve every saved estimate revision; a new revision never overwrites V1. */
    @Entity(indices = {@Index("estimateId")})
    public static class EstimateVersion {
        @PrimaryKey(autoGenerate = true) public long id;
        public long estimateId;
        public int version = 1;
        public String snapshotJson = "{}";
        public String noteText = "";
        public long createdAt = System.currentTimeMillis();
    }

    @Entity
    public static class EstimateFavorite {
        @PrimaryKey(autoGenerate = true) public long id;
        public String name = "";
        public String specification = "";
        public String unit = "개";
        public long unitPrice = 0;
        public long createdAt = System.currentTimeMillis();
    }

    @Entity
    public static class EstimateNoteTemplate {
        @PrimaryKey(autoGenerate = true) public long id;
        public String title = "특기사항";
        public String body = "";
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
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertVoiceMemo(VoiceMemo value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertEstimateVersion(EstimateVersion value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertEstimateFavorite(EstimateFavorite value);
        @Insert(onConflict = OnConflictStrategy.REPLACE) long insertEstimateNoteTemplate(EstimateNoteTemplate value);
        @Query("SELECT * FROM Customer WHERE phone=:phone LIMIT 1") Customer customerByPhone(String phone);
        @Query("SELECT * FROM Customer WHERE id=:id LIMIT 1") Customer customerById(long id);
        @Query("SELECT * FROM Site WHERE name=:name ORDER BY updatedAt DESC LIMIT 1") Site siteByName(String name);
        @Query("SELECT * FROM Site WHERE id=:id LIMIT 1") Site siteById(long id);
        @Query("SELECT * FROM CallRecord WHERE sourceUri=:uri LIMIT 1") CallRecord callByUri(String uri);
        @Query("SELECT * FROM CallRecord WHERE id=:id LIMIT 1") CallRecord callById(long id);
        @Query("SELECT * FROM Inquiry ORDER BY updatedAt DESC LIMIT :limit") List<Inquiry> inquiries(int limit);
        @Query("SELECT * FROM Site ORDER BY updatedAt DESC LIMIT :limit") List<Site> sites(int limit);
        @Query("SELECT * FROM Task WHERE status!='완료' ORDER BY CASE WHEN dueAt='' THEN 1 ELSE 0 END, dueAt ASC LIMIT :limit") List<Task> openTasks(int limit);
        @Query("SELECT * FROM Estimate ORDER BY createdAt DESC LIMIT :limit") List<Estimate> estimates(int limit);
        @Query("SELECT * FROM Estimate WHERE id=:id LIMIT 1") Estimate estimateById(long id);
        @Query("SELECT * FROM Estimate WHERE title=:title ORDER BY createdAt DESC LIMIT 1") Estimate latestEstimateByTitle(String title);
        @Query("SELECT * FROM EstimateItem WHERE estimateId=:estimateId ORDER BY sortOrder ASC") List<EstimateItem> estimateItems(long estimateId);
        @Query("SELECT * FROM Photo ORDER BY createdAt DESC LIMIT :limit") List<Photo> photos(int limit);
        @Query("SELECT * FROM CallRecord ORDER BY modifiedAt DESC LIMIT :limit") List<CallRecord> calls(int limit);
        @Query("SELECT * FROM CallRecord WHERE processingStatus='대기' ORDER BY modifiedAt ASC LIMIT :limit") List<CallRecord> pendingCalls(int limit);
        @Query("SELECT * FROM Inquiry WHERE customerId=:customerId ORDER BY updatedAt DESC LIMIT :limit") List<Inquiry> inquiriesByCustomer(long customerId, int limit);
        @Query("SELECT * FROM Site WHERE customerId=:customerId ORDER BY updatedAt DESC LIMIT :limit") List<Site> sitesByCustomer(long customerId, int limit);
        @Query("SELECT * FROM Estimate WHERE customerId=:customerId ORDER BY createdAt DESC LIMIT :limit") List<Estimate> estimatesByCustomer(long customerId, int limit);
        @Query("SELECT * FROM CallRecord WHERE customerId=:customerId ORDER BY modifiedAt DESC LIMIT :limit") List<CallRecord> callsByCustomer(long customerId, int limit);
        @Query("SELECT * FROM Photo WHERE siteId=:siteId ORDER BY createdAt DESC") List<Photo> photosForSite(long siteId);
        @Query("SELECT COUNT(*) FROM Photo WHERE siteId=:siteId AND category=:category") int photoCountForCategory(long siteId, String category);
        @Query("SELECT * FROM VoiceMemo WHERE siteId=:siteId ORDER BY createdAt DESC LIMIT :limit") List<VoiceMemo> voiceMemosForSite(long siteId, int limit);
        @Query("SELECT * FROM EstimateFavorite ORDER BY createdAt DESC LIMIT :limit") List<EstimateFavorite> estimateFavorites(int limit);
        @Query("SELECT * FROM EstimateNoteTemplate ORDER BY createdAt DESC LIMIT :limit") List<EstimateNoteTemplate> estimateNoteTemplates(int limit);
        @Query("SELECT * FROM EstimateVersion WHERE estimateId=:estimateId ORDER BY version DESC") List<EstimateVersion> estimateVersions(long estimateId);
        @Query("SELECT * FROM AutomationJob WHERE status IN ('확인필요','실패') ORDER BY updatedAt DESC LIMIT :limit") List<AutomationJob> inboxJobs(int limit);
        @Query("SELECT COUNT(*) FROM Inquiry") int inquiryCount();
        @Query("SELECT COUNT(*) FROM Task WHERE status!='완료'") int openTaskCount();
        @Query("SELECT COUNT(*) FROM CallRecord WHERE processingStatus IN ('대기','실패','확인 필요')") int waitingCallCount();
        @Query("SELECT COUNT(*) FROM Payment WHERE balance>0") int receivableCount();
        @Query("SELECT COUNT(*) FROM Photo") int photoCount();
        @Query("SELECT COUNT(*) FROM AutomationJob WHERE status IN ('확인필요','실패')") int inboxCount();
        @Query("SELECT COUNT(*) FROM Inquiry WHERE status=:status") int inquiryCountByStatus(String status);
        @Query("SELECT COUNT(*) FROM Site WHERE status=:status") int siteCountByStatus(String status);
        @Query("SELECT COUNT(*) FROM Estimate WHERE status='견적 작성'") int unsentEstimateCount();
        @Query("SELECT COUNT(*) FROM Estimate WHERE status='견적발송' AND followUpAt!='' AND followUpAt<=:now") int quoteFollowUpCount(String now);
        @Query("SELECT COUNT(*) FROM Inquiry WHERE status='신규문의'") int unansweredInquiryCount();
        @Query("SELECT COUNT(DISTINCT Photo.siteId) FROM Photo INNER JOIN Site ON Site.id=Photo.siteId WHERE Site.workNote='' OR Site.workNote IS NULL") int photoOnlySiteCount();
        @Query("SELECT COUNT(*) FROM Payment INNER JOIN Site ON Site.id=Payment.siteId WHERE Payment.balance>0 AND Site.status='공사완료'") int completedUnpaidSiteCount();
        @Query("UPDATE Task SET status='완료', completedAt=:now WHERE id=:id") void completeTask(long id, long now);
        @Query("UPDATE Site SET status=:status, workNote=:note, updatedAt=:now WHERE id=:id") void finishSite(long id, String status, String note, long now);
        @Query("UPDATE Site SET status=:status, workNote=:note, finishedAt=:now, updatedAt=:now WHERE id=:id") void confirmSiteComplete(long id, String status, String note, long now);
        @Query("UPDATE Site SET workNote=:note, updatedAt=:now WHERE id=:id") void updateSiteNote(long id, String note, long now);
        @Query("UPDATE Site SET startedAt=CASE WHEN startedAt=0 THEN :now ELSE startedAt END, updatedAt=:now WHERE id=:id") void markSiteStarted(long id, long now);
        @Query("UPDATE Photo SET category=:category WHERE id=:id") void updatePhotoCategory(long id, String category);
        @Query("UPDATE Photo SET siteId=:siteId, category=:category WHERE id=:id") void assignPhotoToSite(long id, long siteId, String category);
        @Query("UPDATE CallRecord SET processingStatus=:status, transcript=:transcript, summary=:summary, analyzedAt=:now WHERE id=:id") void updateCallAnalysis(long id, String status, String transcript, String summary, long now);
        @Query("UPDATE Customer SET name=CASE WHEN :name='' THEN name ELSE :name END, company=CASE WHEN :company='' THEN company ELSE :company END, contactName=CASE WHEN :contactName='' THEN contactName ELSE :contactName END, region=CASE WHEN :region='' THEN region ELSE :region END, address=CASE WHEN :address='' THEN address ELSE :address END, updatedAt=:now WHERE id=:id") void updateCustomerProfile(long id, String name, String company, String contactName, String region, String address, long now);
        @Query("UPDATE Estimate SET status=:status, followUpAt=:followUpAt, updatedAt=:now WHERE id=:id") void updateEstimateStatus(long id, String status, String followUpAt, long now);
        @Query("SELECT * FROM Payment WHERE balance>0 ORDER BY balance DESC LIMIT :limit") List<Payment> receivables(int limit);
    }
}
