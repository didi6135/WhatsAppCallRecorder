package com.didi4164.WhatsAppCallRecorder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure host checks; never contacts Telegram or opens a real recording. */
public final class TelegramBackupCoreTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    private interface Action { void run() throws Exception; }
    private static void fails(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (TelegramBackupFailure failure) { check(code.equals(failure.code)); }
    }
    private static JSONObject ok(Object value) { return new JSONObject().put("ok", true).put("result", value); }
    private static JSONObject user(long id, boolean bot) { return new JSONObject().put("id", id).put("is_bot", bot); }
    private static JSONObject update(long id, long chat, String type, String text) {
        return new JSONObject().put("update_id", id).put("message", new JSONObject().put("message_id", 1)
            .put("from", user(chat, false)).put("chat", new JSONObject().put("id", chat).put("type", type)).put("text", text));
    }
    private static void protocol() throws Exception {
        // Construct synthetic shape instead of placing a credential-shaped literal in public source.
        String token = "123456" + ":" + "x".repeat(35);
        check(TelegramBotProtocol.validToken(token)); check(!TelegramBotProtocol.validToken("bad token"));
        JSONObject me = user(123456, true).put("username", "OwnPrivateBot");
        TelegramBotProtocol.Bot bot = TelegramBotProtocol.bot(ok(me));
        check(bot.id == 123456 && bot.username.equals("OwnPrivateBot"));
        fails("TOKEN_INVALID", () -> TelegramBotProtocol.bot(ok(user(123456, false))));
        fails("RESPONSE_INVALID", () -> TelegramBotProtocol.bot(ok(new JSONObject(me.toString()).put("id", 1.5))));
        TelegramBotProtocol.requireNoWebhook(ok(new JSONObject().put("url", "")));
        fails("BOT_IN_USE", () -> TelegramBotProtocol.requireNoWebhook(ok(new JSONObject().put("url", "https://example.invalid/hook"))));
        fails("BOT_IN_USE", () -> TelegramBotProtocol.requireFreshUpdates(ok(new JSONArray().put(update(1, 99, "private", "unrelated")))));
        TelegramBotProtocol.requireFreshUpdates(ok(new JSONArray()));
        String nonce = "wa_" + "a".repeat(32);
        JSONObject linked = ok(new JSONArray().put(update(1, 99, "private", "/start " + nonce)));
        TelegramBotProtocol.requireOwnedUpdates(linked, java.util.Collections.singleton(nonce));
        check(TelegramBotProtocol.ownedOffset(linked, java.util.Collections.singleton(nonce)) == 2);
        fails("BOT_IN_USE", () -> TelegramBotProtocol.requireOwnedUpdates(linked, java.util.Collections.singleton("wa_" + "f".repeat(32))));
        check(TelegramBotProtocol.matchPrivateChat(linked, nonce) == 99);
        fails("CONNECTION_NOT_CONFIRMED", () -> TelegramBotProtocol.matchPrivateChat(ok(new JSONArray()), nonce));
        fails("PRIVATE_CHAT_REQUIRED", () -> TelegramBotProtocol.matchPrivateChat(ok(new JSONArray().put(update(1, -99, "group", "/start " + nonce))), nonce));
        fails("CHAT_AMBIGUOUS", () -> TelegramBotProtocol.matchPrivateChat(ok(new JSONArray()
            .put(update(1, 99, "private", "/start " + nonce)).put(update(2, 100, "private", "/start " + nonce))), nonce));
        check(TelegramBotProtocol.matchPrivateChat(ok(new JSONArray().put(update(1, 99, "private", "/start " + nonce))
            .put(update(2, 99, "private", "/start " + nonce))), nonce) == 99);
        String oldNonce = "wa_" + "b".repeat(32);
        java.util.Set<String> owned = new java.util.HashSet<>(Arrays.asList(nonce, oldNonce));
        JSONObject reconnect = ok(new JSONArray().put(update(1, 99, "private", "/start " + oldNonce)).put(update(2, 99, "private", "/start " + nonce)));
        check(TelegramBotProtocol.matchPrivateChat(reconnect, nonce, owned) == 99);
        check(TelegramBotProtocol.ownedOffset(reconnect, owned) == 3);
        fails("BOT_IN_USE", () -> TelegramBotProtocol.matchPrivateChat(ok(new JSONArray()
            .put(update(1, 99, "private", "/start " + nonce)).put(update(2, 100, "private", "unrelated"))), nonce));
        JSONObject message = new JSONObject().put("message_id", 123).put("from", user(bot.id, true))
            .put("chat", new JSONObject().put("id", 99).put("type", "private"))
            .put("document", new JSONObject().put("file_name", "part.wav").put("file_size", 128)
                .put("file_id", "public_synthetic_file_id").put("file_unique_id", "public_synthetic_unique_id"));
        TelegramBotProtocol.Receipt receipt = TelegramBotProtocol.receipt(ok(message), bot.id, 99, "part.wav", 128);
        check(receipt.messageId == 123 && receipt.bytes == 128);
        fails("UNKNOWN_OUTCOME", () -> TelegramBotProtocol.receipt(ok(message), bot.id, 100, "part.wav", 128));
        fails("UNKNOWN_OUTCOME", () -> TelegramBotProtocol.receipt(ok(message), bot.id, 99, "other.wav", 128));
        fails("UNKNOWN_OUTCOME", () -> TelegramBotProtocol.receipt(ok(message), bot.id, 99, "part.wav", 127));
        fails("UNKNOWN_OUTCOME", () -> TelegramBotProtocol.receipt(ok(new JSONObject(message.toString()).put("from", user(1, true))), bot.id, 99, "part.wav", 128));
        fails("UNKNOWN_OUTCOME", () -> TelegramBotProtocol.receipt(ok(new JSONObject()), bot.id, 99, "part.wav", 128));
        check(TelegramBotProtocol.apiFailure(429, new JSONObject().put("ok", false).put("error_code", 429)
            .put("parameters", new JSONObject().put("retry_after", 60)), true).retryable);
        check(TelegramBotProtocol.apiFailure(500, new JSONObject().put("error_code", 500), true).unknownOutcome);
        check(!TelegramBotProtocol.apiFailure(401, new JSONObject().put("ok", false).put("error_code", 401), true).unknownOutcome);
        for (Object value : new Object[]{"429", 429.5, JSONObject.NULL}) {
            check(TelegramBotProtocol.apiFailure(429, new JSONObject().put("ok", false).put("error_code", value), true).unknownOutcome);
        }
        check(TelegramBotProtocol.apiFailure(200, new JSONObject().put("ok", false).put("error_code", 429), true).unknownOutcome);
        check(TelegramBotProtocol.apiFailure(400, new JSONObject().put("ok", false).put("error_code", 429), true).unknownOutcome);
        check(TelegramBotProtocol.apiFailure(429, new JSONObject().put("ok", false).put("error_code", 429)
            .put("parameters", new JSONObject().put("retry_after", "60")), true).unknownOutcome);
        for (String code : new String[]{"LOCAL_QUEUE_UNAVAILABLE", "LOCAL_FILE_CHANGED", "CANCELED", "NETWORK", "RESPONSE_INVALID"}) {
            TelegramBackupFailure classified = TelegramBackupFailure.afterDispatch(new TelegramBackupFailure(code));
            check(classified.unknownOutcome && classified.code.equals("UNKNOWN_OUTCOME") && !classified.retryable);
        }
        check(TelegramBackupFailure.afterDispatch(new java.io.IOException()).unknownOutcome);
        check(TelegramBackupFailure.afterDispatch(new TelegramBackupFailure("RATE_LIMIT", true, false, 60)).retryable);
    }
    private static void wav() throws Exception {
        String id = "1791280000000-abcdef12", named = RecordingNames.exportFileName(id, "דוד Smith 👩‍💻");
        check(TelegramWavParts.filename(id, 0, 1).equals("WA-reco-" + id + "-part-001-of-001.wav"));
        check(TelegramWavParts.filename(id, 0, 1, named).equals(named));
        check(TelegramWavParts.validFilename(id, named, 0, 1));
        check(!TelegramWavParts.validFilename(id, "../" + named, 0, 1));
        check(!TelegramWavParts.validFilename(id, named.replace(id, "1-deadbeef"), 0, 1));
        check(!TelegramWavParts.validFilename(id, "x".repeat(100000), 0, 1));
        String multiName = TelegramWavParts.filename(id, 1, 2, named);
        check(multiName.endsWith("-" + id + "-part-002-of-002.wav") && multiName.contains("דוד Smith 👩‍💻"));
        check(TelegramWavParts.validFilename(id, multiName, 1, 2));
        check(!TelegramWavParts.validFilename(id, multiName, 0, 2));
        check(!TelegramWavParts.validFilename(id, multiName, 1, 3));
        String longest = RecordingNames.exportFileName("9999999999999999999-abcdef12", "😀".repeat(1000));
        String largestPartName = TelegramWavParts.filename("9999999999999999999-abcdef12", 99, 100, longest);
        check(largestPartName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= RecordingNames.MAX_EXPORT_UTF8_BYTES);
        check(TelegramWavParts.validFilename("9999999999999999999-abcdef12", largestPartName, 99, 100));
        byte[] header = TelegramWavParts.header(200, 2, 16000);
        TelegramWavParts.Plan plan = TelegramWavParts.plan(header, 244, 100);
        check(plan.parts == 4); check(plan.channels == 2 && plan.dataBytes == 200);
        long sum = 0;
        for (int i = 0; i < plan.parts; i++) {
            TelegramWavParts.Part part = plan.part(i);
            check(part.bytes <= 100 && part.dataBytes % 4 == 0);
            check(part.offset == 44 + sum); sum += part.dataBytes;
        }
        check(sum == 200); check(plan.part(3).dataBytes == 32);
        check(TelegramWavParts.plan(TelegramWavParts.header(48_000_000 - 44, 1, 16000), 48_000_000, 48_000_000).parts == 1);
        check(TelegramWavParts.plan(TelegramWavParts.header(48_000_000 - 42, 1, 16000), 48_000_002, 48_000_000).parts == 2);
        byte[] wrong = header.clone(); wrong[0] = 'X';
        fails("UNSUPPORTED_WAV", () -> TelegramWavParts.plan(wrong, 244, 100));
        fails("UNSUPPORTED_WAV", () -> TelegramWavParts.plan(header, 245, 100));
        fails("UNSUPPORTED_WAV", () -> TelegramWavParts.plan(TelegramWavParts.header(0, 1, 16000), 44, 100));
        byte[] badChannels = header.clone(); badChannels[22] = 3;
        fails("UNSUPPORTED_WAV", () -> TelegramWavParts.plan(badChannels, 244, 100));
        File file = File.createTempFile("telegram-synthetic", ".wav");
        try {
            byte[] pcm = new byte[200]; for (int i = 0; i < pcm.length; i++) pcm[i] = (byte)i;
            try (RandomAccessFile source = new RandomAccessFile(file, "rw")) { source.write(header); source.write(pcm); }
            ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();
            try (RandomAccessFile source = new RandomAccessFile(file, "r")) {
                for (int i = 0; i < plan.parts; i++) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    TelegramWavParts.write(source, output, plan, i, () -> true);
                    byte[] part = output.toByteArray();
                    TelegramWavParts.plan(Arrays.copyOf(part, 44), part.length, 100);
                    reconstructed.write(part, 44, part.length - 44);
                }
            }
            check(Arrays.equals(pcm, reconstructed.toByteArray()));
            try (RandomAccessFile source = new RandomAccessFile(file, "r")) {
                fails("CANCELED", () -> TelegramWavParts.write(source, new ByteArrayOutputStream(), plan, 0, () -> false));
            }
        } finally { check(file.delete()); }
        // Exercise the real cloud part ceiling with a genuine >48MB synthetic
        // PCM file. Stream/reassemble through digests rather than allocating it.
        File large = File.createTempFile("telegram-large-synthetic", ".wav");
        try {
            long dataBytes = 48_000_000L;
            try (RandomAccessFile source = new RandomAccessFile(large, "rw")) {
                source.setLength(dataBytes + 44); source.seek(0); source.write(TelegramWavParts.header(dataBytes, 2, 16000));
                source.seek(48_000_000 - 4); source.write(new byte[]{11,22,33,44});
                source.seek(dataBytes + 40); source.write(new byte[]{55,66,77,88});
            }
            java.security.MessageDigest expected = java.security.MessageDigest.getInstance("SHA-256");
            java.security.MessageDigest actual = java.security.MessageDigest.getInstance("SHA-256");
            try (RandomAccessFile source = new RandomAccessFile(large, "r")) {
                TelegramWavParts.Plan genuine = TelegramWavParts.read(source);
                check(genuine.parts == 2); check(genuine.part(0).bytes == TelegramWavParts.MAX_PART_BYTES);
                source.seek(44); byte[] block = new byte[65536]; int count;
                while ((count = source.read(block)) > 0) expected.update(block, 0, count);
                for (int i = 0; i < genuine.parts; i++) {
                    final ByteArrayOutputStream partHeader = new ByteArrayOutputStream();
                    final long[] sent = {0};
                    java.io.OutputStream sink = new java.io.OutputStream() {
                        @Override public void write(int value) { throw new AssertionError("stream buffers expected"); }
                        @Override public void write(byte[] bytes, int offset, int length) {
                            int header = (int)Math.min(length, Math.max(0, 44 - sent[0]));
                            if (header > 0) partHeader.write(bytes, offset, header);
                            if (length > header) actual.update(bytes, offset + header, length - header);
                            sent[0] += length;
                        }
                    };
                    TelegramWavParts.write(source, sink, genuine, i, () -> true);
                    check(sent[0] == genuine.part(i).bytes);
                    TelegramWavParts.plan(partHeader.toByteArray(), sent[0], TelegramWavParts.MAX_PART_BYTES);
                }
            }
            check(Arrays.equals(expected.digest(), actual.digest()));
        } finally { check(large.delete()); }
    }
    public static void main(String[] ignored) throws Exception {
        protocol(); wav(); System.out.println("Telegram protocol/WAV checks passed: " + checks);
    }
}
