package com.didi4164.WhatsAppCallRecorder;

import java.net.URI;

/** Pure validation shared by the queue, REST client and host regression tests. */
public final class DriveBackupPolicy {
  public static final int MAX_ATTEMPTS = 8;
  public static final int CHUNK_BYTES = 1024 * 1024;
  private DriveBackupPolicy() { }

  public static boolean validRecordingId(String id) {
    return id != null && id.matches("[0-9]+-[a-f0-9]{8}");
  }
  public static boolean validDriveId(String id) {
    return id != null && id.matches("[A-Za-z0-9_-]{1,256}");
  }
  public static boolean validMd5(String digest) {
    return digest != null && digest.matches("[a-f0-9]{32}");
  }
  public static boolean isCurrent(long expected, long current, boolean enabled, String expectedDestination, String destination) {
    return enabled && expected == current && expectedDestination != null && expectedDestination.equals(destination);
  }
  public static boolean verified(String expectedId, String actualId, long localSize, long remoteSize,
      String localMd5, String remoteMd5, String folderId, String actualParent, String recordingId, String actualRecordingId) {
    return validDriveId(expectedId) && expectedId.equals(actualId) && localSize > 44 && localSize == remoteSize &&
        validMd5(localMd5) && localMd5.equals(remoteMd5) && validDriveId(folderId) && folderId.equals(actualParent) &&
        validRecordingId(recordingId) && recordingId.equals(actualRecordingId);
  }
  public static boolean retryable(int status, String reason) {
    return status == 408 || status == 429 || status >= 500 && status <= 599 ||
        status == 403 && ("rateLimitExceeded".equals(reason) || "userRateLimitExceeded".equals(reason));
  }
  public static boolean isolatedFileFailure(String code) {
    return "LOCAL_FILE_MISSING".equals(code) || "LOCAL_FILE_CHANGED".equals(code) ||
        "REMOTE_MISMATCH".equals(code) || "HTTP_ERROR".equals(code) || "RETRY_LIMIT".equals(code);
  }
  public static long acknowledgedOffset(String range, long size) {
    if (range == null || range.isEmpty()) return 0;
    if (!range.matches("bytes=0-[0-9]+")) throw new IllegalArgumentException("Invalid upload acknowledgement");
    long last;
    try { last = Long.parseLong(range.substring(8)); }
    catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid upload acknowledgement"); }
    if (last < 0 || last >= size || last == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid upload acknowledgement");
    return last + 1;
  }
  public static String trustedUploadUrl(String value) {
    try {
      URI uri = new URI(value);
      if (!"https".equals(uri.getScheme()) || !"www.googleapis.com".equals(uri.getHost()) ||
          uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1 ||
          !"/upload/drive/v3/files".equals(uri.getRawPath()) || value.length() > 4096) throw new IllegalArgumentException();
      return value;
    } catch (Exception invalid) { throw new IllegalArgumentException("Untrusted upload endpoint"); }
  }
}
