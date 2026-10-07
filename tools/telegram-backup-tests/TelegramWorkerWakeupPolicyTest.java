package com.didi4164.WhatsAppCallRecorder;

import org.json.JSONObject;

/** Worker yields never authorize a send; they preserve a future wakeup for still-pending work. */
public final class TelegramWorkerWakeupPolicyTest {
    private static int checks;
    private static void check(boolean value) {
        checks++; if (!value) throw new AssertionError("worker wakeup check " + checks);
    }
    public static void main(String[] ignored) throws Exception {
        // stopped, same authorized generation/destination, known pending, expected retry
        boolean[][] cases = {
            {false, false, false, false}, {false, false, true, false},
            {false, true, false, false}, {false, true, true, true},
            {true, false, false, false}, {true, false, true, false},
            {true, true, false, false}, {true, true, true, false}
        };
        for (boolean[] row : cases) {
            check(TelegramWorkerWakeupPolicy.shouldRetryAfterYield(row[0], row[1], row[2]) == row[3]);
        }
        JSONObject queue = TelegramBackupLedger.fresh();
        TelegramBackupLedger.prepareConnection(queue, "123456" + ":" + "x".repeat(35), 123456,
            "OwnPrivateBot", "wa_" + "a".repeat(32), 10000);
        TelegramBackupLedger.completeConnection(queue, 99, 100); TelegramBackupLedger.setEnabled(queue, true);
        TelegramWavParts.Plan plan = TelegramWavParts.plan(TelegramWavParts.header(56, 2, 16000), 100, 48_000_000);
        for (String id : new String[]{"1-aaaaaaaa", "2-bbbbbbbb"}) {
            TelegramBackupLedger.enqueue(queue, id, 100, 100, "b".repeat(64), plan);
        }
        TelegramBackupLedger.Selection first = TelegramBackupLedger.next(queue, 100);
        TelegramBackupLedger.Lease lease = TelegramBackupLedger.claim(queue, first, "c".repeat(32), 100);
        JSONObject latePositive = new JSONObject(queue.toString());
        TelegramBackupLedger.delivered(latePositive, lease, new TelegramBotProtocol.Receipt(1, 100, "synthetic_file", "synthetic_unique"));
        // A deadline after a late positive receipt retains a wakeup for job two.
        check(TelegramBackupLedger.next(latePositive, 100).id.equals("2-bbbbbbbb"));
        check(TelegramWorkerWakeupPolicy.shouldRetryAfterYield(false, true, TelegramBackupLedger.next(latePositive, 100) != null));
        TelegramBackupLedger.failed(queue, lease, new TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0), 100);
        // A deadline/cancelled send makes job one UNKNOWN; only independent known
        // pending job two receives the retained wakeup, without retrying job one.
        check(TelegramBackupLedger.next(queue, 100).id.equals("2-bbbbbbbb"));
        check(TelegramWorkerWakeupPolicy.shouldRetryAfterYield(false, true, TelegramBackupLedger.next(queue, 100) != null));
        queue.getJSONArray("jobs").remove(1);
        check(!TelegramWorkerWakeupPolicy.shouldRetryAfterYield(false, true, TelegramBackupLedger.next(queue, 100) != null));
        // Disable/rebind and WorkManager stop may not reschedule the obsolete owner.
        check(!TelegramWorkerWakeupPolicy.shouldRetryAfterYield(false, false, true));
        check(!TelegramWorkerWakeupPolicy.shouldRetryAfterYield(true, true, true));
        System.out.println("Telegram worker wakeup checks passed: " + checks);
    }
}
