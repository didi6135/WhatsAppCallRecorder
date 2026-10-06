package com.didi4164.WhatsAppCallRecorder;

/** No Android/device/network dependencies: receipt identity and retry boundaries. */
public final class DriveBackupPolicyTest {
  private static int checks;
  private static final String MD5 = "0123456789abcdef0123456789abcdef";
  private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
  private static void rejected(Runnable action) { checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejection " + checks); }
  public static void main(String[] ignored) {
    check(DriveBackupPolicy.validRecordingId("1700000000-deadbeef"));
    for (String invalid : new String[]{null, "", "../a-deadbeef", "1-DEADBEEF", "1-deadbeef.wav", "1-deadbee", "x-deadbeef"}) check(!DriveBackupPolicy.validRecordingId(invalid));
    check(DriveBackupPolicy.validDriveId("a_-B09"));
    for (String invalid : new String[]{null, "", "a/b", "a?b", "a\nb", new String(new char[257]).replace('\0', 'a')}) check(!DriveBackupPolicy.validDriveId(invalid));
    check(DriveBackupPolicy.validMd5(MD5)); check(!DriveBackupPolicy.validMd5(MD5.toUpperCase())); check(!DriveBackupPolicy.validMd5(null));
    check(DriveBackupPolicy.isCurrent(4, 4, true, "account:folder", "account:folder"));
    check(!DriveBackupPolicy.isCurrent(4, 5, true, "account:folder", "account:folder"));
    check(!DriveBackupPolicy.isCurrent(4, 4, false, "account:folder", "account:folder"));
    check(!DriveBackupPolicy.isCurrent(4, 4, true, "account:folder", "other:folder"));
    check(!DriveBackupPolicy.isCurrent(4, 4, true, null, null));
    check(DriveBackupPolicy.verified("remote", "remote", 45, 45, MD5, MD5, "folder", "folder", "1-deadbeef", "1-deadbeef"));
    check(!DriveBackupPolicy.verified("remote", "other", 45, 45, MD5, MD5, "folder", "folder", "1-deadbeef", "1-deadbeef"));
    check(!DriveBackupPolicy.verified("remote", "remote", 45, 46, MD5, MD5, "folder", "folder", "1-deadbeef", "1-deadbeef"));
    check(!DriveBackupPolicy.verified("remote", "remote", 44, 44, MD5, MD5, "folder", "folder", "1-deadbeef", "1-deadbeef"));
    check(!DriveBackupPolicy.verified("remote", "remote", 45, 45, MD5, "ffffffffffffffffffffffffffffffff", "folder", "folder", "1-deadbeef", "1-deadbeef"));
    check(!DriveBackupPolicy.verified("remote", "remote", 45, 45, MD5, MD5, "folder", "elsewhere", "1-deadbeef", "1-deadbeef"));
    check(!DriveBackupPolicy.verified("remote", "remote", 45, 45, MD5, MD5, "folder", "folder", "1-deadbeef", "2-deadbeef"));
    for (int status : new int[]{408, 429, 500, 503, 599}) check(DriveBackupPolicy.retryable(status, ""));
    for (int status : new int[]{200, 308, 400, 401, 403, 404, 409, 600}) check(!DriveBackupPolicy.retryable(status, ""));
    check(DriveBackupPolicy.retryable(403, "rateLimitExceeded")); check(DriveBackupPolicy.retryable(403, "userRateLimitExceeded"));
    check(!DriveBackupPolicy.retryable(403, "storageQuotaExceeded"));
    for (String isolated : new String[]{"LOCAL_FILE_MISSING", "LOCAL_FILE_CHANGED", "REMOTE_MISMATCH", "HTTP_ERROR", "RETRY_LIMIT"}) check(DriveBackupPolicy.isolatedFileFailure(isolated));
    for (String global : new String[]{null, "AUTH_REQUIRED", "ACCOUNT_CHANGED", "FOLDER_UNAVAILABLE", "STORAGE_FULL", "CONFIGURATION_REQUIRED", "NETWORK", "LOCAL_QUEUE_UNAVAILABLE"}) check(!DriveBackupPolicy.isolatedFileFailure(global));
    check(DriveBackupPolicy.acknowledgedOffset(null, 100) == 0); check(DriveBackupPolicy.acknowledgedOffset("", 100) == 0);
    check(DriveBackupPolicy.acknowledgedOffset("bytes=0-0", 100) == 1); check(DriveBackupPolicy.acknowledgedOffset("bytes=0-99", 100) == 100);
    for (String invalid : new String[]{"bytes=1-3", "bytes=0-100", "bytes=0--1", "bytes=0-9223372036854775807", "bytes=0-999999999999999999999", "Bytes=0-5", "bytes=0-1\r\n", "other"}) rejected(() -> DriveBackupPolicy.acknowledgedOffset(invalid, 100));
    String safe = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=synthetic";
    check(safe.equals(DriveBackupPolicy.trustedUploadUrl(safe)));
    for (String invalid : new String[]{null, "http://www.googleapis.com/upload/drive/v3/files", "https://evil.example/upload/drive/v3/files", "https://www.googleapis.com.evil.example/upload/drive/v3/files", "https://user@www.googleapis.com/upload/drive/v3/files", "https://www.googleapis.com:443/upload/drive/v3/files", safe + "#fragment", "https://www.googleapis.com/upload/drive/v3/files-suffix", "https://www.googleapis.com/upload/drive/v3/files/id", "https://www.googleapis.com/upload/drive/v3/%66iles"}) rejected(() -> DriveBackupPolicy.trustedUploadUrl(invalid));
    System.out.println("Drive backup policy: " + checks + " checks passed.");
  }
}
