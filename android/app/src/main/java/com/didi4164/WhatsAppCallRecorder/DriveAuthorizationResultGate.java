package com.didi4164.WhatsAppCallRecorder;

/** Pure application result gate; Google's SDK remains responsible for decoding its Intent. */
public final class DriveAuthorizationResultGate {
  private static final int RESULT_OK = -1, RESULT_CANCELED = 0, GOOGLE_CANCELED = 16;
  private DriveAuthorizationResultGate() { }
  public enum Decision { ACCEPT, CANCEL, FAIL }
  public interface Decoder<T> { T decode() throws Exception; }
  public interface StatusReader { Integer status(Exception error); }
  public static final class Outcome<T> {
    public final Decision decision;
    public final T value;
    public final Exception failure;
    public final Integer authStatusCode;
    private Outcome(Decision decision, T value, Exception failure, Integer authStatusCode) {
      this.decision = decision; this.value = value; this.failure = failure; this.authStatusCode = authStatusCode;
    }
  }
  public static <T> Outcome<T> evaluate(int activityResultCode, boolean dataPresent, Decoder<T> decoder, StatusReader statuses) {
    if (!dataPresent) {
      return activityResultCode == RESULT_CANCELED ? canceled() : failed(new IllegalStateException("Missing authorization result"), null);
    }
    try {
      // Even a canceled Activity can contain an SDK-readable authorization error.
      T decoded = decoder.decode();
      if (activityResultCode == RESULT_CANCELED) return canceled();
      if (activityResultCode != RESULT_OK || decoded == null) return failed(new IllegalStateException("Invalid authorization result"), null);
      return new Outcome<>(Decision.ACCEPT, decoded, null, null);
    } catch (Exception error) {
      Integer status = statuses.status(error);
      if (status != null && status == GOOGLE_CANCELED) return canceled();
      return failed(error, status != null && status >= 0 && status <= 65535 ? status : null);
    }
  }
  private static <T> Outcome<T> canceled() { return new Outcome<>(Decision.CANCEL, null, null, null); }
  private static <T> Outcome<T> failed(Exception error, Integer status) { return new Outcome<>(Decision.FAIL, null, error, status); }
}
