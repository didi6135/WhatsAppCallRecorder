package com.didi4164.WhatsAppCallRecorder;

/** Stable app-owned codes only. Never includes a token, provider body or private filename. */
public final class TelegramBackupFailure extends Exception {
    public final String code;
    public final boolean retryable;
    public final boolean unknownOutcome;
    public final int retryAfterSeconds;
    public TelegramBackupFailure(String code) { this(code, false, false, 0); }
    public TelegramBackupFailure(String code, boolean retryable, boolean unknownOutcome, int retryAfterSeconds) {
        super(code);
        this.code = code;
        this.retryable = retryable;
        this.unknownOutcome = unknownOutcome;
        this.retryAfterSeconds = Math.max(0, Math.min(3600, retryAfterSeconds));
    }
    /** Once an intent is durable, only an explicit complete HTTP rejection is safe to retry as failed. */
    public static TelegramBackupFailure afterDispatch(Exception error) {
        if (error instanceof TelegramBackupFailure) {
            TelegramBackupFailure failure = (TelegramBackupFailure)error;
            if (!failure.unknownOutcome && (failure.code.equals("TOKEN_INVALID") || failure.code.equals("CHAT_UNAVAILABLE")
                    || failure.code.equals("BOT_IN_USE") || failure.code.equals("RATE_LIMIT") || failure.code.equals("UPLOAD_REJECTED"))) return failure;
        }
        return new TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0);
    }
}
