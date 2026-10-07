package com.didi4164.WhatsAppCallRecorder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure durable state machine. Callers commit a copied state BEFORE dispatching HTTP. */
public final class TelegramBackupLedger {
    public static final int MAX_JOBS = 2000, MAX_PARTS = 10000, MAX_ATTEMPTS = 3;
    private TelegramBackupLedger() {}
    public static final class Config {
        public final long generation, botId, chatId, expiresAt;
        public final boolean enabled, connected;
        public final String token, botUsername, nonce, destination;
        Config(JSONObject value) {
            generation = value.optLong("generation"); enabled = value.optBoolean("enabled");
            botId = value.optLong("botId"); chatId = value.optLong("chatId");
            token = value.optString("token"); botUsername = value.optString("botUsername"); nonce = value.optString("nonce");
            expiresAt = value.optLong("expiresAt");
            connected = botId > 0 && chatId > 0 && TelegramBotProtocol.validToken(token);
            destination = botId + "." + chatId;
        }
    }
    public static final class Selection {
        public final Config config;
        public final String id, sha256, filename;
        public final long size, modifiedAt, partBytes;
        public final int index, count;
        Selection(Config config, JSONObject job, int index) throws Exception {
            this.config = config; this.id = job.getString("id"); this.sha256 = job.getString("sha256");
            this.size = job.getLong("size"); this.modifiedAt = job.getLong("modifiedAt"); this.index = index;
            this.count = job.getJSONArray("parts").length();
            this.filename = TelegramWavParts.filename(id, index, count);
            this.partBytes = job.getJSONArray("parts").getJSONObject(index).getLong("bytes");
        }
    }
    public static final class Lease {
        public final Selection selection; public final String attempt;
        Lease(Selection selection, String attempt) { this.selection = selection; this.attempt = attempt; }
    }
    public static JSONObject fresh() throws Exception {
        return new JSONObject().put("version", 1).put("generation", 1L).put("enabled", false)
            .put("jobs", new JSONArray()).put("starts", new JSONArray());
    }
    public static Config config(JSONObject value) { return new Config(value); }
    private static boolean integral(JSONObject value, String key, long min, long max) throws Exception {
        Object field = value.get(key);
        if (!(field instanceof Long || field instanceof Integer)) return false;
        long number = ((Number)field).longValue(); return number >= min && number <= max;
    }
    public static void validate(JSONObject value) throws TelegramBackupFailure {
        try {
            if (!integral(value, "version", 1, 1) || !integral(value, "generation", 1, Long.MAX_VALUE - 1)
                    || !(value.get("enabled") instanceof Boolean)) throw new IllegalArgumentException();
            Config cfg = config(value);
            if (cfg.enabled && !cfg.connected) throw new IllegalArgumentException();
            if (value.has("token") && (!TelegramBotProtocol.validToken(cfg.token) || cfg.botId < 1 || cfg.botId > TelegramBotProtocol.MAX_ID
                    || !cfg.botUsername.matches("[A-Za-z][A-Za-z0-9_]{4,31}"))) throw new IllegalArgumentException();
            if (value.has("chatId") && !integral(value, "chatId", 1, TelegramBotProtocol.MAX_ID)) throw new IllegalArgumentException();
            if (value.has("nonce") && (!cfg.nonce.matches("wa_[a-f0-9]{32}") || cfg.expiresAt <= 0 || cfg.connected)) throw new IllegalArgumentException();
            if (value.has("pendingAck") && (!cfg.connected || !integral(value, "pendingAck", 1, 2147483648L))) throw new IllegalArgumentException();
            JSONArray starts = value.getJSONArray("starts");
            if (starts.length() > 128) throw new IllegalArgumentException();
            Set<String> challenges = new HashSet<>();
            for (int i = 0; i < starts.length(); i++) {
                JSONObject start = starts.getJSONObject(i);
                if (!integral(start, "botId", 1, TelegramBotProtocol.MAX_ID) || !integral(start, "createdAt", 1, Long.MAX_VALUE)
                        || !start.getString("nonce").matches("wa_[a-f0-9]{32}") || !challenges.add(start.getString("nonce"))) throw new IllegalArgumentException();
            }
            JSONArray jobs = value.getJSONArray("jobs");
            if (jobs.length() > MAX_JOBS) throw new IllegalArgumentException();
            Set<String> identities = new HashSet<>(), messageIds = new HashSet<>();
            int total = 0;
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject job = jobs.getJSONObject(i);
                String id = job.getString("id"), target = job.getString("destination");
                if (!id.matches("[0-9]{1,19}-[a-f0-9]{8}") || !target.matches("[1-9][0-9]{0,15}\\.[1-9][0-9]{0,15}")
                        || !identities.add(target + "/" + id) || !job.getString("sha256").matches("[a-f0-9]{64}")
                        || !integral(job, "modifiedAt", 0, Long.MAX_VALUE) || !integral(job, "size", 45, 0xffffffffL + 8)) throw new IllegalArgumentException();
                int channels = job.getInt("channels"); long size = job.getLong("size");
                TelegramWavParts.Plan plan = TelegramWavParts.plan(TelegramWavParts.header(size - 44, channels, 16000), size, TelegramWavParts.MAX_PART_BYTES);
                JSONArray parts = job.getJSONArray("parts"); total += parts.length();
                if (parts.length() != plan.parts || total > MAX_PARTS) throw new IllegalArgumentException();
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.getJSONObject(j); String state = part.getString("state");
                    if (!integral(part, "bytes", plan.part(j).bytes, plan.part(j).bytes)
                            || !integral(part, "attempts", 0, MAX_ATTEMPTS)
                            || !java.util.Arrays.asList("PENDING", "IN_FLIGHT", "DELIVERED", "FAILED", "UNKNOWN").contains(state)) throw new IllegalArgumentException();
                    if ((state.equals("IN_FLIGHT") || state.equals("UNKNOWN") || state.equals("DELIVERED"))
                            && !part.getString("attempt").matches("[a-f0-9]{32}")) throw new IllegalArgumentException();
                    if (state.equals("DELIVERED")) {
                        JSONObject receipt = part.getJSONObject("receipt");
                        if (!integral(receipt, "messageId", 1, Integer.MAX_VALUE)
                                || !integral(receipt, "bytes", part.getLong("bytes"), part.getLong("bytes"))
                                || !receipt.getString("fileId").matches("[A-Za-z0-9_-]{1,512}")
                                || !receipt.getString("uniqueId").matches("[A-Za-z0-9_-]{1,256}")
                                || !messageIds.add(target + "/" + receipt.getLong("messageId"))) throw new IllegalArgumentException();
                    } else if (part.has("receipt")) throw new IllegalArgumentException();
                }
            }
        } catch (Exception ignored) { throw new TelegramBackupFailure("LOCAL_QUEUE_UNAVAILABLE"); }
    }
    public static boolean recoverInterrupted(JSONObject value) throws Exception {
        boolean changed = false; JSONArray jobs = value.getJSONArray("jobs");
        for (int i = 0; i < jobs.length(); i++) {
            JSONArray parts = jobs.getJSONObject(i).getJSONArray("parts");
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.getJSONObject(j);
                if (part.getString("state").equals("IN_FLIGHT")) {
                    part.put("state", "UNKNOWN").put("errorCode", "UNKNOWN_OUTCOME"); changed = true;
                }
            }
        }
        return changed;
    }
    private static void changed(JSONObject value) throws Exception {
        recoverInterrupted(value);
        value.put("generation", Math.addExact(config(value).generation, 1));
    }
    private static void clearError(JSONObject value) { value.remove("lastErrorCode"); value.remove("lastErrorAt"); }
    public static void error(JSONObject value, String code, long now) throws Exception {
        value.put("lastErrorCode", code).put("lastErrorAt", now);
    }
    public static void prepareConnection(JSONObject value, String token, long botId, String username, String nonce, long expiresAt) throws Exception {
        if (!TelegramBotProtocol.validToken(token) || botId < 1 || botId > TelegramBotProtocol.MAX_ID
                || !username.matches("[A-Za-z][A-Za-z0-9_]{4,31}") || !nonce.matches("wa_[a-f0-9]{32}")) throw new TelegramBackupFailure("TOKEN_INVALID");
        JSONArray starts = value.getJSONArray("starts"), retained = new JSONArray();
        long created = Math.max(1, expiresAt - 600000);
        for (int i = 0; i < starts.length(); i++) {
            JSONObject start = starts.getJSONObject(i);
            // Telegram retains incoming updates for at most 24h. Allow an extra
            // day for wall-clock drift; never discard a still-live challenge to drain it.
            if (start.getLong("createdAt") >= created - 48 * 60 * 60 * 1000L) retained.put(start);
        }
        if (retained.length() >= 128) throw new TelegramBackupFailure("LOCAL_QUEUE_FULL");
        retained.put(new JSONObject().put("botId", botId).put("nonce", nonce).put("createdAt", created));
        changed(value); clearError(value); value.remove("chatId"); value.remove("pendingAck"); value.put("starts", retained);
        value.put("enabled", false).put("token", token).put("botId", botId).put("botUsername", username)
            .put("nonce", nonce).put("expiresAt", expiresAt);
    }
    public static void completeConnection(JSONObject value, long chatId, long now) throws Exception {
        Config cfg = config(value);
        if (cfg.nonce.isEmpty()) throw new TelegramBackupFailure("CONNECTION_NOT_CONFIRMED");
        if (now >= cfg.expiresAt) throw new TelegramBackupFailure("CONNECTION_EXPIRED");
        if (chatId < 1 || chatId > TelegramBotProtocol.MAX_ID) throw new TelegramBackupFailure("PRIVATE_CHAT_REQUIRED");
        changed(value); clearError(value); value.put("chatId", chatId).put("enabled", false);
        value.remove("nonce"); value.remove("expiresAt");
    }
    public static void setEnabled(JSONObject value, boolean enabled) throws Exception {
        if (enabled && !config(value).connected) throw new TelegramBackupFailure("NOT_CONNECTED");
        changed(value); value.put("enabled", enabled); clearError(value);
    }
    public static void disconnect(JSONObject value) throws Exception {
        changed(value); value.put("enabled", false); clearError(value);
        for (String key : new String[]{"token", "botId", "chatId", "botUsername", "nonce", "expiresAt", "pendingAck"}) value.remove(key);
    }
    public static Set<String> ownedNonces(JSONObject value, long botId) throws Exception {
        Set<String> result = new HashSet<>(); JSONArray starts = value.getJSONArray("starts");
        for (int i = 0; i < starts.length(); i++) {
            JSONObject start = starts.getJSONObject(i); if (start.getLong("botId") == botId) result.add(start.getString("nonce"));
        }
        return result;
    }
    public static void retry(JSONObject value, boolean confirm) throws Exception {
        Config cfg = config(value);
        if (!cfg.connected) throw new TelegramBackupFailure("NOT_CONNECTED");
        JSONArray jobs = value.getJSONArray("jobs");
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i); if (!cfg.destination.equals(job.getString("destination"))) continue;
            JSONArray parts = job.getJSONArray("parts");
            for (int j = 0; j < parts.length(); j++) {
                String state = parts.getJSONObject(j).getString("state");
                if (!confirm && (state.equals("UNKNOWN") || state.equals("IN_FLIGHT"))) throw new TelegramBackupFailure("UNKNOWN_OUTCOME_REQUIRES_CONFIRMATION");
            }
        }
        changed(value); clearError(value);
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i); if (!cfg.destination.equals(job.getString("destination"))) continue;
            JSONArray parts = job.getJSONArray("parts");
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.getJSONObject(j);
                if (part.getString("state").equals("FAILED") || part.getString("state").equals("UNKNOWN")) {
                    part.put("state", "PENDING").put("attempts", 0); part.remove("attempt"); part.remove("errorCode"); part.remove("notBefore");
                }
            }
        }
    }
    public static void enqueue(JSONObject value, String id, long size, long modifiedAt, String sha256, TelegramWavParts.Plan plan) throws Exception {
        Config cfg = config(value); if (!cfg.enabled || !cfg.connected) return;
        if (!id.matches("[0-9]{1,19}-[a-f0-9]{8}") || !sha256.matches("[a-f0-9]{64}") || size != plan.dataBytes + 44)
            throw new TelegramBackupFailure("LOCAL_FILE_CHANGED");
        JSONArray jobs = value.getJSONArray("jobs"); int total = plan.parts;
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i);
            if (id.equals(job.getString("id")) && cfg.destination.equals(job.getString("destination"))) return;
            total += job.getJSONArray("parts").length();
        }
        if (jobs.length() >= MAX_JOBS || total > MAX_PARTS) throw new TelegramBackupFailure("LOCAL_QUEUE_FULL");
        JSONArray parts = new JSONArray();
        for (int i = 0; i < plan.parts; i++) parts.put(new JSONObject().put("state", "PENDING").put("attempts", 0).put("bytes", plan.part(i).bytes));
        jobs.put(new JSONObject().put("id", id).put("destination", cfg.destination).put("size", size).put("modifiedAt", modifiedAt)
            .put("sha256", sha256).put("channels", plan.channels).put("parts", parts));
    }
    public static boolean blocked(JSONObject value) {
        String code = value.optString("lastErrorCode");
        return code.equals("TOKEN_INVALID") || code.equals("CHAT_UNAVAILABLE") || code.equals("BOT_IN_USE") || code.equals("LOCAL_QUEUE_UNAVAILABLE");
    }
    public static Selection next(JSONObject value, long now) throws Exception {
        Config cfg = config(value); if (!cfg.enabled || !cfg.connected || blocked(value)) return null;
        JSONArray jobs = value.getJSONArray("jobs");
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i); if (!cfg.destination.equals(job.getString("destination"))) continue;
            JSONArray parts = job.getJSONArray("parts");
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.getJSONObject(j); String state = part.getString("state");
                if (state.equals("DELIVERED")) continue;
                if (state.equals("PENDING") && part.optLong("notBefore") <= now) return new Selection(cfg, job, j);
                break; // Preserve part order; failed/ambiguous earlier part blocks this recording only.
            }
        }
        return null;
    }
    private static JSONObject find(JSONObject value, String target, String id, int index) throws Exception {
        JSONArray jobs = value.getJSONArray("jobs");
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i);
            if (target.equals(job.getString("destination")) && id.equals(job.getString("id"))) return job.getJSONArray("parts").getJSONObject(index);
        }
        return null;
    }
    public static Lease claim(JSONObject value, Selection selection, String attempt, long now) throws Exception {
        Config cfg = config(value);
        if (!cfg.enabled || !cfg.connected || cfg.generation != selection.config.generation || !cfg.destination.equals(selection.config.destination)) return null;
        Selection next = next(value, now);
        if (next == null || !next.id.equals(selection.id) || next.index != selection.index) return null;
        if (!attempt.matches("[a-f0-9]{32}")) throw new TelegramBackupFailure("LOCAL_QUEUE_UNAVAILABLE");
        JSONObject part = find(value, cfg.destination, selection.id, selection.index);
        part.put("state", "IN_FLIGHT").put("attempt", attempt).put("attempts", part.getInt("attempts") + 1);
        part.remove("errorCode"); part.remove("notBefore");
        return new Lease(selection, attempt);
    }
    private static JSONObject matching(JSONObject value, Lease lease) throws Exception {
        Selection selected = lease.selection;
        JSONObject part = find(value, selected.config.destination, selected.id, selected.index);
        return part != null && lease.attempt.equals(part.optString("attempt")) ? part : null;
    }
    public static boolean delivered(JSONObject value, Lease lease, TelegramBotProtocol.Receipt receipt) throws Exception {
        JSONObject part = matching(value, lease);
        if (part == null || !(part.getString("state").equals("IN_FLIGHT") || part.getString("state").equals("UNKNOWN"))) return false;
        if (receipt.bytes != lease.selection.partBytes) throw new TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0);
        part.put("state", "DELIVERED").put("receipt", new JSONObject().put("messageId", receipt.messageId)
            .put("bytes", receipt.bytes).put("fileId", receipt.fileId).put("uniqueId", receipt.uniqueId));
        part.remove("errorCode"); part.remove("notBefore");
        if (config(value).generation == lease.selection.config.generation) clearError(value);
        return true;
    }
    public static void failed(JSONObject value, Lease lease, TelegramBackupFailure failure, long now) throws Exception {
        JSONObject part = matching(value, lease); if (part == null || part.getString("state").equals("DELIVERED")) return;
        int attempts = part.getInt("attempts"); String code = failure.retryable && attempts >= MAX_ATTEMPTS ? "RETRY_LIMIT" : failure.code;
        String state = failure.unknownOutcome ? "UNKNOWN" : failure.retryable && attempts < MAX_ATTEMPTS ? "PENDING" : "FAILED";
        part.put("state", state).put("errorCode", code);
        if (state.equals("PENDING")) part.put("notBefore", now + Math.max(30, failure.retryAfterSeconds) * 1000L);
        if (config(value).generation == lease.selection.config.generation) error(value, code, now);
    }
    public static void failedBeforeDispatch(JSONObject value, Selection selection, String code, long now) throws Exception {
        if (config(value).generation != selection.config.generation) return;
        JSONObject part = find(value, selection.config.destination, selection.id, selection.index);
        if (part != null && part.getString("state").equals("PENDING")) part.put("state", "FAILED").put("errorCode", code);
        error(value, code, now);
    }
    public static Map<String, Object> status(JSONObject value, long now) throws Exception {
        Config cfg = config(value); JSONArray jobs = value.getJSONArray("jobs");
        int queued = 0, uploaded = 0, failed = 0, unknown = 0; boolean uploading = false;
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.getJSONObject(i); if (!cfg.connected || !cfg.destination.equals(job.getString("destination"))) continue;
            JSONArray parts = job.getJSONArray("parts"); boolean all = true, bad = false, unclear = false, active = false;
            for (int j = 0; j < parts.length(); j++) {
                String state = parts.getJSONObject(j).getString("state"); all &= state.equals("DELIVERED");
                bad |= state.equals("FAILED"); unclear |= state.equals("UNKNOWN"); active |= state.equals("IN_FLIGHT");
            }
            if (all) uploaded++; else if (unclear) unknown++; else if (bad) failed++; else queued++;
            uploading |= active;
        }
        boolean pending = !cfg.nonce.isEmpty() && now < cfg.expiresAt;
        String code = value.optString("lastErrorCode");
        if (!cfg.nonce.isEmpty() && !pending) code = "CONNECTION_EXPIRED";
        if (unknown > 0) code = "UNKNOWN_OUTCOME";
        String phase = pending ? "AWAITING_CHAT" : !cfg.connected ? "DISCONNECTED" : !cfg.enabled ? "DISABLED"
            : unknown > 0 || failed > 0 || !code.isEmpty() ? "ACTION_REQUIRED" : uploading ? "UPLOADING" : "READY";
        Map<String, Object> result = new HashMap<>();
        result.put("connected", cfg.connected); result.put("enabled", cfg.enabled); result.put("connectionPending", pending);
        result.put("botUsername", cfg.botUsername.isEmpty() ? null : cfg.botUsername);
        result.put("startLink", pending ? "https://t.me/" + cfg.botUsername + "?start=" + cfg.nonce : null);
        result.put("connectionExpiresAt", pending ? cfg.expiresAt : null);
        result.put("queued", queued); result.put("uploaded", uploaded); result.put("failed", failed); result.put("unknownOutcome", unknown);
        result.put("uploading", uploading); result.put("lastErrorCode", code.isEmpty() ? null : code);
        result.put("lastErrorAt", value.has("lastErrorAt") ? value.getLong("lastErrorAt") : null); result.put("statusCode", phase);
        return result;
    }
}
