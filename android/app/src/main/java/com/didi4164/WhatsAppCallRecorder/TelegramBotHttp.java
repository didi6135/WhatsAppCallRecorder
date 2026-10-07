package com.didi4164.WhatsAppCallRecorder;

import org.json.JSONObject;
import org.json.JSONTokener;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded direct Bot API transport. Provider text and credential URLs never escape. */
public final class TelegramBotHttp implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Set<TelegramBotHttp> CLIENTS = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "TelegramBackupDeadline"); thread.setDaemon(true); return thread;
    });
    private final long deadlineNanos;
    private final ScheduledFuture<?> watchdog;
    private volatile boolean canceled;
    private volatile HttpsURLConnection active;

    public TelegramBotHttp(long totalTimeoutMillis) {
        if (totalTimeoutMillis < 1000 || totalTimeoutMillis > 120000) throw new IllegalArgumentException("timeout bound");
        deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis);
        CLIENTS.add(this);
        watchdog = DEADLINES.schedule(this::cancel, totalTimeoutMillis, TimeUnit.MILLISECONDS);
    }
    private boolean current() { return !canceled && System.nanoTime() < deadlineNanos; }
    private void check() throws TelegramBackupFailure { if (!current()) throw new TelegramBackupFailure("CANCELED"); }
    private synchronized HttpsURLConnection open(String token, String method) throws Exception {
        check();
        if (!TelegramBotProtocol.validToken(token)) throw new TelegramBackupFailure("TOKEN_INVALID");
        HttpsURLConnection connection = (HttpsURLConnection)new URL("https://api.telegram.org/bot" + token + "/" + method).openConnection();
        connection.setInstanceFollowRedirects(false);
        int remaining = (int)Math.max(1, Math.min(20000, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
        connection.setConnectTimeout(remaining); connection.setReadTimeout(remaining);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setUseCaches(false);
        active = connection;
        return connection;
    }
    private JSONObject response(HttpsURLConnection connection, boolean dispatch) throws Exception {
        check();
        int status = connection.getResponseCode();
        if (status >= 500) throw new TelegramBackupFailure(dispatch ? "UNKNOWN_OUTCOME" : "NETWORK", !dispatch, dispatch, 0);
        if (status >= 300 && status < 400) throw new TelegramBackupFailure(dispatch ? "UNKNOWN_OUTCOME" : "RESPONSE_INVALID", false, dispatch, 0);
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) throw new TelegramBackupFailure(dispatch ? "UNKNOWN_OUTCOME" : "RESPONSE_INVALID", false, dispatch, 0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = stream) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                check();
                if (bytes.size() + count > MAX_RESPONSE_BYTES) throw new TelegramBackupFailure(dispatch ? "UNKNOWN_OUTCOME" : "RESPONSE_INVALID", false, dispatch, 0);
                bytes.write(buffer, 0, count);
            }
        }
        String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        JSONTokener parser = new JSONTokener(body);
        Object parsed = parser.nextValue();
        if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) throw new TelegramBackupFailure(dispatch ? "UNKNOWN_OUTCOME" : "RESPONSE_INVALID", false, dispatch, 0);
        JSONObject object = (JSONObject)parsed;
        // A structured rejection is the only known failed dispatch outcome.
        // A 5xx, contradictory status, or malformed success remains ambiguous.
        if (Boolean.FALSE.equals(object.opt("ok"))) {
            Object code = object.opt("error_code");
            boolean validRejection = status >= 400 && status < 500 && (code instanceof Integer || code instanceof Long)
                    && ((Number)code).longValue() == status;
            if (status == 429 && object.has("parameters")) {
                JSONObject parameters = object.optJSONObject("parameters");
                if (parameters == null) validRejection = false;
                else if (parameters.has("retry_after")) {
                    Object wait = parameters.opt("retry_after");
                    validRejection &= (wait instanceof Integer || wait instanceof Long)
                            && ((Number)wait).longValue() >= 1 && ((Number)wait).longValue() <= 3600;
                }
            }
            if (!validRejection) throw new TelegramBackupFailure(dispatch ? "UNKNOWN_OUTCOME" : "RESPONSE_INVALID", false, dispatch, 0);
            throw TelegramBotProtocol.apiFailure(status, object, dispatch);
        }
        if (status < 200 || status >= 300 || !Boolean.TRUE.equals(object.opt("ok")))
            throw new TelegramBackupFailure(dispatch ? "UNKNOWN_OUTCOME" : "RESPONSE_INVALID", false, dispatch, 0);
        return object;
    }
    public JSONObject request(String token, String method, JSONObject parameters) throws TelegramBackupFailure {
        if (!("getMe".equals(method) || "getWebhookInfo".equals(method) || "getUpdates".equals(method)))
            throw new TelegramBackupFailure("RESPONSE_INVALID");
        HttpsURLConnection connection = null;
        boolean reading = false;
        try {
            byte[] body = (parameters == null ? "{}" : parameters.toString()).getBytes(StandardCharsets.UTF_8);
            if (body.length > 16384) throw new TelegramBackupFailure("RESPONSE_INVALID");
            connection = open(token, method);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { check(); output.write(body); }
            reading = true;
            return response(connection, false);
        } catch (TelegramBackupFailure failure) { throw failure; }
        catch (CharacterCodingException ignored) { throw new TelegramBackupFailure("RESPONSE_INVALID"); }
        catch (IOException ignored) { throw new TelegramBackupFailure("NETWORK", true, false, 0); }
        catch (Exception ignored) { throw new TelegramBackupFailure(reading ? "RESPONSE_INVALID" : "NETWORK", !reading, false, 0); }
        finally { release(connection); }
    }
    public TelegramBotProtocol.Receipt sendDocument(String token, long botId, long chatId, String filename,
            File source, TelegramWavParts.Plan plan, int partIndex, BooleanSupplier generationCurrent) throws TelegramBackupFailure {
        HttpsURLConnection connection = null;
        try {
            if (chatId < 1 || chatId > TelegramBotProtocol.MAX_ID || botId < 1 || botId > TelegramBotProtocol.MAX_ID
                    || !TelegramWavParts.validFilename(source.getName().replaceFirst("\\.wav$", ""), filename, partIndex, plan.parts))
                throw new TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0);
            TelegramWavParts.Part part = plan.part(partIndex);
            String boundary = "waReco" + UUID.randomUUID().toString().replace("-", "");
            byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n" + chatId
                    + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"disable_content_type_detection\"\r\n\r\ntrue"
                    + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"" + filename
                    + "\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
            BooleanSupplier valid = () -> current() && generationCurrent.getAsBoolean();
            if (!valid.getAsBoolean()) throw new TelegramBackupFailure("CANCELED");
            try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
                if (input.length() != plan.dataBytes + TelegramWavParts.HEADER_BYTES) throw new TelegramBackupFailure("LOCAL_FILE_CHANGED");
                connection = open(token, "sendDocument");
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setFixedLengthStreamingMode(prefix.length + part.bytes + suffix.length);
                try (OutputStream output = connection.getOutputStream()) {
                    if (!valid.getAsBoolean()) throw new TelegramBackupFailure("CANCELED");
                    output.write(prefix);
                    TelegramWavParts.write(input, output, plan, partIndex, valid);
                    if (!valid.getAsBoolean()) throw new TelegramBackupFailure("CANCELED");
                    output.write(suffix);
                }
                return TelegramBotProtocol.receipt(response(connection, true), botId, chatId, filename, part.bytes);
            }
        } catch (TelegramBackupFailure failure) {
            if (failure.unknownOutcome || "TOKEN_INVALID".equals(failure.code) || "CHAT_UNAVAILABLE".equals(failure.code)
                    || "BOT_IN_USE".equals(failure.code) || "RATE_LIMIT".equals(failure.code) || "UPLOAD_REJECTED".equals(failure.code)) throw failure;
            throw new TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0);
        } catch (Exception ignored) { throw new TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0); }
        finally { release(connection); }
    }
    private synchronized void release(HttpsURLConnection connection) {
        if (connection != null) connection.disconnect();
        if (active == connection) active = null;
    }
    public synchronized void cancel() {
        canceled = true;
        if (active != null) active.disconnect();
    }
    public static void cancelAll() { for (TelegramBotHttp client : CLIENTS) client.cancel(); }
    @Override public void close() { cancel(); watchdog.cancel(false); CLIENTS.remove(this); }
}
