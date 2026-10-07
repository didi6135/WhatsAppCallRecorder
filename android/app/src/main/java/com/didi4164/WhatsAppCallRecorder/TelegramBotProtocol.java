package com.didi4164.WhatsAppCallRecorder;

import org.json.JSONArray;
import org.json.JSONObject;

/** Strict Bot API parsing. The application stores only required primitive binding/receipt fields. */
public final class TelegramBotProtocol {
    public static final long MAX_ID = (1L << 52) - 1;
    private TelegramBotProtocol() {}
    public static boolean validToken(String value) {
        return value != null && value.matches("[0-9]{6,15}:[A-Za-z0-9_-]{30,80}");
    }
    public static final class Bot {
        public final long id; public final String username;
        Bot(long id, String username) { this.id = id; this.username = username; }
    }
    public static final class Receipt {
        public final long messageId, bytes; public final String fileId, uniqueId;
        Receipt(long messageId, long bytes, String fileId, String uniqueId) {
            this.messageId = messageId; this.bytes = bytes; this.fileId = fileId; this.uniqueId = uniqueId;
        }
    }
    private static long integer(JSONObject object, String key, long min, long max) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("integer required");
        long result = ((Number)value).longValue();
        if (result < min || result > max) throw new IllegalArgumentException("integer bound");
        return result;
    }
    private static Object result(JSONObject response) throws Exception {
        if (!Boolean.TRUE.equals(response.get("ok"))) throw new IllegalArgumentException("API failure");
        return response.get("result");
    }
    public static Bot bot(JSONObject response) throws TelegramBackupFailure {
        try {
            JSONObject value = (JSONObject)result(response);
            if (!Boolean.TRUE.equals(value.get("is_bot"))) throw new TelegramBackupFailure("TOKEN_INVALID");
            long id = integer(value, "id", 1, MAX_ID);
            String username = value.getString("username");
            if (!username.matches("[A-Za-z][A-Za-z0-9_]{4,31}")) throw new IllegalArgumentException("bot username");
            return new Bot(id, username);
        } catch (TelegramBackupFailure failure) { throw failure; }
        catch (Exception ignored) { throw new TelegramBackupFailure("RESPONSE_INVALID"); }
    }
    public static void requireNoWebhook(JSONObject response) throws TelegramBackupFailure {
        try {
            JSONObject value = (JSONObject)result(response);
            if (!value.getString("url").isEmpty()) throw new TelegramBackupFailure("BOT_IN_USE");
        } catch (TelegramBackupFailure failure) { throw failure; }
        catch (Exception ignored) { throw new TelegramBackupFailure("RESPONSE_INVALID"); }
    }
    private static JSONArray updates(JSONObject response) throws TelegramBackupFailure {
        try {
            JSONArray rows = (JSONArray)result(response);
            if (rows.length() > 100) throw new IllegalArgumentException("update count");
            return rows;
        } catch (Exception ignored) { throw new TelegramBackupFailure("RESPONSE_INVALID"); }
    }
    public static void requireFreshUpdates(JSONObject response) throws TelegramBackupFailure {
        if (updates(response).length() != 0) throw new TelegramBackupFailure("BOT_IN_USE");
    }
    public static void requireOwnedUpdates(JSONObject response, java.util.Set<String> nonces) throws TelegramBackupFailure {
        JSONArray rows = updates(response);
        java.util.Set<Long> ids = new java.util.HashSet<>();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!ids.add(integer(row, "update_id", 0, Integer.MAX_VALUE))) throw new IllegalArgumentException("duplicate update");
                JSONObject message = row.getJSONObject("message"), chat = message.getJSONObject("chat"), from = message.getJSONObject("from");
                String text = message.getString("text");
                long id = integer(chat, "id", 1, MAX_ID);
                if (!"private".equals(chat.getString("type")) || integer(from, "id", 1, MAX_ID) != id
                        || !Boolean.FALSE.equals(from.get("is_bot")) || !text.startsWith("/start ")
                        || !nonces.contains(text.substring(7))) throw new TelegramBackupFailure("BOT_IN_USE");
            }
        } catch (TelegramBackupFailure failure) { throw failure; }
        catch (Exception ignored) { throw new TelegramBackupFailure("BOT_IN_USE"); }
    }
    public static long ownedOffset(JSONObject response, java.util.Set<String> nonces) throws TelegramBackupFailure {
        requireOwnedUpdates(response, nonces);
        long offset = 0;
        try {
            JSONArray rows = updates(response);
            for (int i = 0; i < rows.length(); i++) offset = Math.max(offset, integer(rows.getJSONObject(i), "update_id", 0, Integer.MAX_VALUE) + 1);
            return offset;
        } catch (Exception ignored) { throw new TelegramBackupFailure("RESPONSE_INVALID"); }
    }
    public static long matchPrivateChat(JSONObject response, String nonce) throws TelegramBackupFailure {
        return matchPrivateChat(response, nonce, java.util.Collections.singleton(nonce));
    }
    public static long matchPrivateChat(JSONObject response, String nonce, java.util.Set<String> ownNonces) throws TelegramBackupFailure {
        if (nonce == null || !nonce.matches("wa_[a-f0-9]{32}")) throw new TelegramBackupFailure("RESPONSE_INVALID");
        JSONArray rows = updates(response);
        long selected = 0;
        java.util.Set<Long> observedChats = new java.util.HashSet<>();
        java.util.Set<Long> updateIds = new java.util.HashSet<>();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!updateIds.add(integer(row, "update_id", 0, Integer.MAX_VALUE))) throw new IllegalArgumentException("duplicate update");
                JSONObject message = row.optJSONObject("message");
                if (message == null) continue;
                JSONObject chat = message.getJSONObject("chat");
                long id = integer(chat, "id", -MAX_ID, MAX_ID);
                observedChats.add(id);
                if (!("/start " + nonce).equals(message.optString("text", ""))) continue;
                JSONObject from = message.getJSONObject("from");
                if (!"private".equals(chat.getString("type")) || id <= 0 || integer(from, "id", 1, MAX_ID) != id
                        || !Boolean.FALSE.equals(from.get("is_bot"))) throw new TelegramBackupFailure("PRIVATE_CHAT_REQUIRED");
                if (selected != 0 && selected != id) throw new TelegramBackupFailure("CHAT_AMBIGUOUS");
                selected = id;
            }
            if (selected == 0) throw new TelegramBackupFailure("CONNECTION_NOT_CONFIRMED");
            if (observedChats.size() != 1) throw new TelegramBackupFailure("BOT_IN_USE");
            requireOwnedUpdates(response, ownNonces);
            return selected;
        } catch (TelegramBackupFailure failure) { throw failure; }
        catch (Exception ignored) { throw new TelegramBackupFailure("RESPONSE_INVALID"); }
    }
    public static Receipt receipt(JSONObject response, long bot, long chat, String filename, long bytes) throws TelegramBackupFailure {
        try {
            JSONObject message = (JSONObject)result(response);
            long messageId = integer(message, "message_id", 1, Integer.MAX_VALUE);
            JSONObject from = message.getJSONObject("from"), target = message.getJSONObject("chat");
            if (integer(from, "id", 1, MAX_ID) != bot || !Boolean.TRUE.equals(from.get("is_bot"))
                    || integer(target, "id", 1, MAX_ID) != chat || !"private".equals(target.getString("type"))) throw new IllegalArgumentException("binding mismatch");
            JSONObject document = message.getJSONObject("document");
            if (!filename.equals(document.getString("file_name")) || integer(document, "file_size", 45, TelegramWavParts.MAX_PART_BYTES) != bytes)
                throw new IllegalArgumentException("file mismatch");
            String fileId = document.getString("file_id"), unique = document.getString("file_unique_id");
            if (!fileId.matches("[A-Za-z0-9_-]{1,512}") || !unique.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException("receipt id");
            return new Receipt(messageId, bytes, fileId, unique);
        } catch (Exception ignored) { throw new TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0); }
    }
    public static TelegramBackupFailure apiFailure(int httpStatus, JSONObject response, boolean dispatch) {
        if (httpStatus >= 500) return new TelegramBackupFailure(dispatch ? "UNKNOWN_OUTCOME" : "NETWORK", !dispatch, dispatch, 0);
        int code;
        try {
            if (!Boolean.FALSE.equals(response.get("ok")) || !integralError(response, "error_code", 400, 499)) throw new IllegalArgumentException();
            code = response.getInt("error_code");
            if (httpStatus != code) throw new IllegalArgumentException();
        } catch (Exception ignored) { return new TelegramBackupFailure(dispatch ? "UNKNOWN_OUTCOME" : "RESPONSE_INVALID", false, dispatch, 0); }
        if (code == 401 || code == 404) return new TelegramBackupFailure("TOKEN_INVALID");
        if (code == 403) return new TelegramBackupFailure("CHAT_UNAVAILABLE");
        if (code == 409) return new TelegramBackupFailure("BOT_IN_USE");
        if (code == 429) {
            JSONObject parameters = response.optJSONObject("parameters");
            try {
                if (parameters != null && parameters.has("retry_after") && !integralError(parameters, "retry_after", 1, 3600)) throw new IllegalArgumentException();
            } catch (Exception ignored) { return new TelegramBackupFailure(dispatch ? "UNKNOWN_OUTCOME" : "RESPONSE_INVALID", false, dispatch, 0); }
            int wait = parameters == null ? 30 : parameters.optInt("retry_after", 30);
            return new TelegramBackupFailure("RATE_LIMIT", true, false, Math.max(1, Math.min(3600, wait)));
        }
        return new TelegramBackupFailure(dispatch ? "UPLOAD_REJECTED" : "RESPONSE_INVALID");
    }
    private static boolean integralError(JSONObject value, String key, long min, long max) throws Exception {
        integer(value, key, min, max); return true;
    }
}
