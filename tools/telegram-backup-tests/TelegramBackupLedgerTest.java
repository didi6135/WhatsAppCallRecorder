package com.didi4164.WhatsAppCallRecorder;

import org.json.JSONObject;

public final class TelegramBackupLedgerTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("ledger check " + checks); }
    private interface Action { void run() throws Exception; }
    private static void fails(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (TelegramBackupFailure error) { check(code.equals(error.code)); }
    }
    private static JSONObject connected(long chat) throws Exception {
        JSONObject value = TelegramBackupLedger.fresh();
        TelegramBackupLedger.prepareConnection(value, "123456" + ":" + "x".repeat(35), 123456, "OwnPrivateBot", "wa_" + "a".repeat(32), 10000);
        TelegramBackupLedger.completeConnection(value, chat, 100);
        return value;
    }
    private static void enqueue(JSONObject value, String id, long bytes) throws Exception {
        TelegramWavParts.Plan plan = TelegramWavParts.plan(TelegramWavParts.header(bytes - 44, 2, 16000), bytes, 48_000_000);
        TelegramBackupLedger.enqueue(value, id, bytes, 100, "b".repeat(64), plan);
    }
    private static TelegramBackupLedger.Selection selected(JSONObject value) throws Exception { return TelegramBackupLedger.next(value, 100); }
    private static TelegramBackupLedger.Lease claim(JSONObject value, String attempt) throws Exception {
        TelegramBackupLedger.Selection selection = selected(value);
        return TelegramBackupLedger.claim(value, selection, attempt, 100);
    }
    private static TelegramBotProtocol.Receipt receipt(TelegramBackupLedger.Lease lease, int message) {
        return new TelegramBotProtocol.Receipt(message, lease.selection.partBytes, "synthetic_file_" + message, "synthetic_unique_" + message);
    }
    public static void main(String[] ignored) throws Exception {
        JSONObject value = connected(99);
        check(!TelegramBackupLedger.config(value).enabled);
        enqueue(value, "1-aaaaaaaa", 100);
        check(value.getJSONArray("jobs").length() == 0); // Linking alone never grants upload consent.
        TelegramBackupLedger.setEnabled(value, true);
        enqueue(value, "1-aaaaaaaa", 100);
        enqueue(value, "1-aaaaaaaa", 100);
        check(value.getJSONArray("jobs").length() == 1);
        TelegramBackupLedger.Lease lease = claim(value, "c".repeat(32));
        check(lease != null && selected(value) == null); // Dispatch intent prevents another send.
        JSONObject restarted = new JSONObject(value.toString());
        check(TelegramBackupLedger.recoverInterrupted(restarted));
        check(selected(restarted) == null);
        check(TelegramBackupLedger.status(restarted, 101).get("unknownOutcome").equals(1));
        fails("UNKNOWN_OUTCOME_REQUIRES_CONFIRMATION", () -> TelegramBackupLedger.retry(restarted, false));
        TelegramBackupLedger.retry(restarted, true);
        check(selected(restarted) != null);
        TelegramBackupLedger.Lease second = claim(restarted, "d".repeat(32));
        check(!TelegramBackupLedger.delivered(restarted, lease, receipt(lease, 1))); // Old acknowledgment cannot attach to a new attempt.
        check(TelegramBackupLedger.delivered(restarted, second, receipt(second, 2)));
        check(TelegramBackupLedger.status(restarted, 102).get("uploaded").equals(1));
        TelegramBackupLedger.retry(restarted, true);
        check(selected(restarted) == null); // Known receipt is preserved through explicit retry.
        TelegramBackupLedger.validate(restarted);
        // A delayed positive dialog callback can authorize only the destination generation it captured.
        JSONObject approved = connected(99);
        long approvedGeneration = TelegramBackupLedger.config(approved).generation;
        TelegramBackupLedger.disconnect(approved);
        TelegramBackupLedger.prepareConnection(approved, "123456" + ":" + "x".repeat(35), 123456, "OwnPrivateBot", "wa_" + "f".repeat(32), 1000);
        TelegramBackupLedger.completeConnection(approved, 100, 100);
        String beforeOldApproval = approved.toString();
        try {
            TelegramBackupLedger.setEnabled(approved, true, approvedGeneration);
            throw new AssertionError("Old dialog approval must be rejected");
        } catch (TelegramBackupFailure failure) { check("CANCELED".equals(failure.code)); }
        check(beforeOldApproval.equals(approved.toString()));
        check(!TelegramBackupLedger.config(approved).enabled);
        TelegramBackupLedger.setEnabled(approved, true, TelegramBackupLedger.config(approved).generation);
        check(TelegramBackupLedger.config(approved).enabled);
        // Two parts: only the failed/unknown part retries; successful part never sends again.
        JSONObject longCall = connected(99); TelegramBackupLedger.setEnabled(longCall, true);
        enqueue(longCall, "2-bbbbbbbb", 48_000_048);
        TelegramBackupLedger.Lease firstPart = claim(longCall, "a".repeat(32));
        check(firstPart.selection.index == 0);
        check(TelegramBackupLedger.delivered(longCall, firstPart, receipt(firstPart, 10)));
        TelegramBackupLedger.Lease lastPart = claim(longCall, "b".repeat(32));
        check(lastPart.selection.index == 1);
        TelegramBackupLedger.failed(longCall, lastPart, new TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0), 101);
        check(selected(longCall) == null);
        TelegramBackupLedger.retry(longCall, true);
        check(selected(longCall).index == 1);
        // Disable/rebind cancels authority but retains late positively verified receipts on their original binding.
        JSONObject disabled = connected(99); TelegramBackupLedger.setEnabled(disabled, true); enqueue(disabled, "3-cccccccc", 100);
        TelegramBackupLedger.Lease inFlight = claim(disabled, "e".repeat(32));
        TelegramBackupLedger.setEnabled(disabled, false);
        check(selected(disabled) == null);
        check(TelegramBackupLedger.delivered(disabled, inFlight, receipt(inFlight, 20)));
        TelegramBackupLedger.setEnabled(disabled, true);
        check(selected(disabled) == null);
        TelegramBackupLedger.disconnect(disabled);
        check(!disabled.has("token") && !disabled.has("chatId"));
        check(TelegramBackupLedger.ownedNonces(disabled, 123456).contains("wa_" + "a".repeat(32)));
        check(disabled.getJSONArray("jobs").length() == 1);
        TelegramBackupLedger.prepareConnection(disabled, "123456" + ":" + "x".repeat(35), 123456, "OwnPrivateBot", "wa_" + "f".repeat(32), 1000);
        TelegramBackupLedger.completeConnection(disabled, 100, 100); TelegramBackupLedger.setEnabled(disabled, true);
        enqueue(disabled, "3-cccccccc", 100);
        check(disabled.getJSONArray("jobs").length() == 2);
        check(selected(disabled).config.chatId == 100);
        // Complete, explicit 429 failures can retry after the delay; no network timeout can do so.
        JSONObject limited = connected(99); TelegramBackupLedger.setEnabled(limited, true); enqueue(limited, "4-dddddddd", 100);
        TelegramBackupLedger.Lease rate = claim(limited, "1".repeat(32));
        TelegramBackupLedger.failed(limited, rate, new TelegramBackupFailure("RATE_LIMIT", true, false, 60), 100);
        check(TelegramBackupLedger.next(limited, 101) == null);
        check(TelegramBackupLedger.next(limited, 60100) != null);
        TelegramBackupLedger.validate(limited);
        // No stale foreground selection may dispatch after a generation change.
        TelegramBackupLedger.Selection stale = selected(disabled);
        TelegramBackupLedger.setEnabled(disabled, false);
        check(TelegramBackupLedger.claim(disabled, stale, "2".repeat(32), 100) == null);
        JSONObject bad = new JSONObject(restarted.toString());
        bad.getJSONArray("jobs").getJSONObject(0).getJSONArray("parts").getJSONObject(0).remove("receipt");
        fails("LOCAL_QUEUE_UNAVAILABLE", () -> TelegramBackupLedger.validate(bad));
        JSONObject expired = TelegramBackupLedger.fresh();
        TelegramBackupLedger.prepareConnection(expired, "123456" + ":" + "x".repeat(35), 123456, "OwnPrivateBot", "wa_" + "3".repeat(32), 99);
        fails("CONNECTION_EXPIRED", () -> TelegramBackupLedger.completeConnection(expired, 99, 100));
        check(TelegramBackupLedger.status(expired, 100).get("startLink") == null);
        JSONObject persistFailure = connected(99); TelegramBackupLedger.setEnabled(persistFailure, true); enqueue(persistFailure, "5-eeeeeeee", 100);
        TelegramBackupLedger.Lease ackLost = claim(persistFailure, "4".repeat(32));
        TelegramBackupLedger.failed(persistFailure, ackLost,
            TelegramBackupFailure.afterDispatch(new TelegramBackupFailure("LOCAL_QUEUE_UNAVAILABLE")), 100);
        check(selected(persistFailure) == null);
        fails("UNKNOWN_OUTCOME_REQUIRES_CONFIRMATION", () -> TelegramBackupLedger.retry(persistFailure, false));
        JSONObject named = connected(99); TelegramBackupLedger.setEnabled(named, true);
        String namedId = "6-abcdef12", namedName = RecordingNames.exportFileName(namedId, "דוד Smith 👩‍💻");
        TelegramWavParts.Plan smallPlan = TelegramWavParts.plan(TelegramWavParts.header(56, 2, 16000), 100, 48_000_000);
        TelegramBackupLedger.enqueue(named, namedId, 100, 100, "b".repeat(64), smallPlan, namedName);
        TelegramBackupLedger.enqueue(named, namedId, 100, 100, "b".repeat(64), smallPlan, RecordingNames.exportFileName(namedId, "Changed"));
        check(selected(named).filename.equals(namedName));
        JSONObject namedRestart = new JSONObject(named.toString()); TelegramBackupLedger.validate(namedRestart);
        check(selected(namedRestart).filename.equals(namedName));
        TelegramBackupLedger.Lease namedLease = claim(namedRestart, "5".repeat(32));
        check(TelegramBackupLedger.delivered(namedRestart, namedLease, receipt(namedLease, 24)));
        TelegramBackupLedger.enqueue(namedRestart, namedId, 100, 100, "b".repeat(64), smallPlan, RecordingNames.exportFileName(namedId, "Changed"));
        check(selected(namedRestart) == null && namedRestart.getJSONArray("jobs").length() == 1);
        // A legacy queued/delivered job is never renamed or re-sent when new metadata becomes available.
        JSONObject legacy = connected(99); TelegramBackupLedger.setEnabled(legacy, true); enqueue(legacy, "7-abcdef12", 100);
        String legacyName = selected(legacy).filename;
        TelegramBackupLedger.enqueue(legacy, "7-abcdef12", 100, 100, "b".repeat(64), smallPlan, RecordingNames.exportFileName("7-abcdef12", "New name"));
        check(selected(legacy).filename.equals(legacyName) && !legacy.getJSONArray("jobs").getJSONObject(0).has("fileName"));
        JSONObject multipart = connected(99); TelegramBackupLedger.setEnabled(multipart, true);
        long largeSize = 48_000_048;
        TelegramWavParts.Plan multiPlan = TelegramWavParts.plan(TelegramWavParts.header(largeSize - 44, 2, 16000), largeSize, 48_000_000);
        TelegramBackupLedger.enqueue(multipart, namedId, largeSize, 100, "b".repeat(64), multiPlan, namedName);
        String firstName = selected(multipart).filename;
        check(firstName.equals(TelegramWavParts.filename(namedId, 0, 2, namedName)) && firstName.contains("דוד Smith 👩‍💻"));
        TelegramBackupLedger.Lease deliveredPart = claim(multipart, "6".repeat(32));
        check(TelegramBackupLedger.delivered(multipart, deliveredPart, receipt(deliveredPart, 25)));
        TelegramBackupLedger.Selection secondName = selected(multipart);
        check(secondName.index == 1 && secondName.filename.equals(TelegramWavParts.filename(namedId, 1, 2, namedName)));
        TelegramBackupLedger.Lease unknownPart = claim(multipart, "7".repeat(32));
        TelegramBackupLedger.failed(multipart, unknownPart, new TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0), 101);
        TelegramBackupLedger.retry(multipart, true);
        check(selected(multipart).index == 1 && selected(multipart).filename.equals(secondName.filename));
        TelegramBackupLedger.validate(multipart);
        for (Object badName : new Object[]{"../bad.wav", RecordingNames.exportFileName("8-abcdef12", "Wrong recording"), 42}) {
            JSONObject badNamed = new JSONObject(named.toString());
            badNamed.getJSONArray("jobs").getJSONObject(0).put("fileName", badName);
            fails("LOCAL_QUEUE_UNAVAILABLE", () -> TelegramBackupLedger.validate(badNamed));
        }
        System.out.println("Telegram durable-ledger checks passed: " + checks);
    }
}
