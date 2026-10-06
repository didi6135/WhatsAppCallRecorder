package com.didi4164.WhatsAppCallRecorder;

import java.util.*;

/** Bounded process-local metadata only. This class has no queue, file, token or provider dependency. */
public final class DriveBackupActionDiagnostics {
  private static final Set<String> CODES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      "CONFIGURATION_REQUIRED", "AUTH_REQUIRED", "ACCOUNT_CHANGED", "FOLDER_UNAVAILABLE", "NETWORK",
      "RATE_LIMIT", "STORAGE_FULL", "LOCAL_QUEUE_UNAVAILABLE", "FOREGROUND_REQUIRED", "RECORDING_BUSY",
      "CONNECTION_BUSY", "NOT_CONNECTED", "UPLOAD_FAILED", "HTTP_ERROR", "REMOTE_MISMATCH")));
  public static final class Ticket {
    private final long generation;
    private final String action;
    private Ticket(long generation, String action) { this.generation = generation; this.action = action; }
  }
  private long generation;
  private Ticket active;
  private Map<String, Object> error;
  public static String safeCode(String code) { return CODES.contains(code) ? code : "UPLOAD_FAILED"; }
  private void observe(long currentGeneration) {
    if (generation != currentGeneration) { generation = currentGeneration; active = null; error = null; }
  }
  public synchronized Ticket begin(long currentGeneration, String action) {
    if (!"connect".equals(action) && !"folder".equals(action)) throw new IllegalArgumentException("Invalid action");
    observe(currentGeneration); active = new Ticket(currentGeneration, action); return active;
  }
  public synchronized boolean fail(Ticket ticket, long currentGeneration, String stage, String code, Integer authStatusCode, Integer activityResultCode) {
    if (!"authorize".equals(stage) && !"pickerResult".equals(stage) && !"selection".equals(stage)) throw new IllegalArgumentException("Invalid stage");
    observe(currentGeneration);
    if (ticket == null || ticket != active || ticket.generation != currentGeneration) return false;
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("action", ticket.action); record.put("stage", stage); record.put("code", safeCode(code));
    if (authStatusCode != null && authStatusCode >= 0 && authStatusCode <= 65535) record.put("authStatusCode", authStatusCode);
    if (activityResultCode != null && activityResultCode >= -65535 && activityResultCode <= 65535) record.put("activityResultCode", activityResultCode);
    error = record; active = null; return true;
  }
  public synchronized boolean clear(Ticket ticket, long currentGeneration) {
    observe(currentGeneration);
    if (ticket == null || ticket != active || ticket.generation != currentGeneration) return false;
    error = null; active = null; return true;
  }
  public synchronized Map<String, Object> snapshot(long currentGeneration) {
    observe(currentGeneration);
    return error == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(error));
  }
  public Map<String, Object> decorate(Map<String, ?> queueStatus, long currentGeneration) {
    Map<String, Object> result = new LinkedHashMap<>(queueStatus);
    result.put("lastActionError", snapshot(currentGeneration)); return result;
  }
}
