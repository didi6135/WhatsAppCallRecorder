package com.didi4164.WhatsAppCallRecorder;

import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.*;

/** Synthetic HTTPS fixtures only. No network, real bot or private recording. */
public final class TelegramBotHttpTest {
    private static int checks;
    private static final Queue<Fake> replies = new ArrayDeque<>();
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("transport check " + checks); }
    private interface Action { void run() throws Exception; }
    private static void fails(String code, boolean unknown, Action operation) throws Exception {
        try { operation.run(); throw new AssertionError("expected " + code); }
        catch (TelegramBackupFailure failure) { check(code.equals(failure.code)); check(failure.unknownOutcome == unknown); }
    }
    private static final class Fake extends HttpsURLConnection {
        final int status; final byte[] body; final ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        boolean released, failOutput, failRead, cancelRead; long expectedBytes;
        Fake(int status, byte[] body) throws Exception { super(new URL("https://api.telegram.org/")); this.status=status; this.body=body; }
        @Override public String getCipherSuite() { return "synthetic"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
        @Override public void connect() {}
        @Override public void disconnect() { released=true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void setFixedLengthStreamingMode(long length) { expectedBytes=length; }
        @Override public void setFixedLengthStreamingMode(int length) { expectedBytes=length; }
        @Override public OutputStream getOutputStream() throws IOException {
            if (failOutput) throw new IOException("synthetic transport failure"); return uploaded;
        }
        @Override public int getResponseCode() { return status; }
        private InputStream stream() { return new ByteArrayInputStream(body) {
            @Override public int read(byte[] data, int offset, int length) {
                if (cancelRead) { cancelRead=false; TelegramBotHttp.cancelAll(); }
                return super.read(data, offset, length);
            }
        }; }
        @Override public InputStream getInputStream() throws IOException { if (failRead) throw new IOException("synthetic read failure"); return stream(); }
        @Override public InputStream getErrorStream() { return stream(); }
    }
    private static Fake enqueue(int status, String body) throws Exception {
        Fake fake=new Fake(status,body.getBytes(StandardCharsets.UTF_8)); replies.add(fake); return fake;
    }
    private static JSONObject receipt(String filename, long bytes) {
        return new JSONObject().put("ok",true).put("result",new JSONObject().put("message_id",23)
            .put("from",new JSONObject().put("id",123456).put("is_bot",true))
            .put("chat",new JSONObject().put("id",99).put("type","private"))
            .put("document",new JSONObject().put("file_name",filename).put("file_size",bytes)
                .put("file_id","synthetic_file").put("file_unique_id","synthetic_unique")));
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) throws IOException {
                check("api.telegram.org".equals(url.getHost())); check(url.getPort()==-1);
                Fake next=replies.poll(); if(next==null) throw new IOException("unexpected fixture request");
                return next;
            }
        } : null);
        String token="123456" + ":" + "x".repeat(35);
        try (TelegramBotHttp http=new TelegramBotHttp(60000)) {
            Fake valid=enqueue(200,"{\"ok\":true,\"result\":{}}");
            check(http.request(token,"getMe",new JSONObject()).getBoolean("ok"));
            check(!valid.getInstanceFollowRedirects()); check(valid.expectedBytes==valid.uploaded.size()); check(valid.released);
            enqueue(200,"invalid-json"); fails("RESPONSE_INVALID",false,()->http.request(token,"getMe",null));
            enqueue(200,"{\"ok\":true} trailing"); fails("RESPONSE_INVALID",false,()->http.request(token,"getMe",null));
            Fake read=enqueue(200,"{}"); read.failRead=true;
            fails("NETWORK",false,()->http.request(token,"getMe",null));
            enqueue(302,""); fails("RESPONSE_INVALID",false,()->http.request(token,"getMe",null));
            enqueue(503,""); fails("NETWORK",false,()->http.request(token,"getMe",null));
            enqueue(429,"{\"ok\":false,\"error_code\":\"429\"}"); fails("RESPONSE_INVALID",false,()->http.request(token,"getMe",null));
            fails("RESPONSE_INVALID",false,()->http.request(token,"deleteWebhook",null));
            Fake large=new Fake(200,new byte[1024*1024+1]); replies.add(large);
            fails("RESPONSE_INVALID",false,()->http.request(token,"getUpdates",null));
        }
        File directory=java.nio.file.Files.createTempDirectory("telegram-http-fixture-").toFile();
        File wave=new File(directory,"1-abcdabcd.wav"); byte[] pcm={1,2,3,4,5,6,7,8};
        try {
            try(OutputStream output=new FileOutputStream(wave)) { output.write(TelegramWavParts.header(pcm.length,2,16000)); output.write(pcm); }
            TelegramWavParts.Plan plan;
            try(RandomAccessFile input=new RandomAccessFile(wave,"r")) { plan=TelegramWavParts.read(input); }
            String filename=TelegramWavParts.filename("1-abcdabcd",0,plan.parts);
            try(TelegramBotHttp http=new TelegramBotHttp(120000)) {
                Fake valid=enqueue(200,receipt(filename,52).toString());
                check(http.sendDocument(token,123456,99,filename,wave,plan,0,()->true).bytes==52);
                check(valid.expectedBytes==valid.uploaded.size()); check(valid.released);
                byte[] sent=valid.uploaded.toByteArray(); String all=new String(sent,StandardCharsets.ISO_8859_1);
                int data=all.indexOf("Content-Type: audio/wav\r\n\r\n")+"Content-Type: audio/wav\r\n\r\n".length();
                byte[] expected=java.nio.file.Files.readAllBytes(wave.toPath());
                check(data>=0); check(Arrays.equals(expected,Arrays.copyOfRange(sent,data,data+expected.length)));
                String namedFilename = RecordingNames.exportFileName("1-abcdabcd", "דוד Smith 👩‍💻");
                Fake unicode = enqueue(200, receipt(namedFilename, 52).toString());
                check(http.sendDocument(token,123456,99,namedFilename,wave,plan,0,()->true).bytes==52);
                String unicodeRequest = new String(unicode.uploaded.toByteArray(), StandardCharsets.UTF_8);
                check(unicodeRequest.contains("filename=\"" + namedFilename + "\"\r\n"));
                check(unicode.expectedBytes == unicode.uploaded.size());
                check(unicode.released);
                enqueue(200,receipt(filename,52).toString());
                fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,namedFilename,wave,plan,0,()->true));
                // A forged path/header/ID is rejected before a connection consumes a fixture reply.
                int repliesBeforeInvalid = replies.size();
                fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,"../"+namedFilename,wave,plan,0,()->true));
                fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,namedFilename+"\r\nX: injected",wave,plan,0,()->true));
                fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,RecordingNames.exportFileName("2-abcdabcd","Other"),wave,plan,0,()->true));
                check(repliesBeforeInvalid == replies.size());
                enqueue(200,receipt(filename,51).toString()); fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,filename,wave,plan,0,()->true));
                enqueue(500,"{\"ok\":false,\"error_code\":500}"); fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,filename,wave,plan,0,()->true));
                enqueue(429,"{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":2}}");
                fails("RATE_LIMIT",false,()->http.sendDocument(token,123456,99,filename,wave,plan,0,()->true));
                enqueue(403,"{\"ok\":false,\"error_code\":403}"); fails("CHAT_UNAVAILABLE",false,()->http.sendDocument(token,123456,99,filename,wave,plan,0,()->true));
                for (String malformed : new String[]{"{\"ok\":false}", "{\"ok\":false,\"error_code\":\"429\"}",
                        "{\"ok\":false,\"error_code\":429.5}", "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":-1}}",
                        "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":3601}}", "{\"ok\":false,\"error_code\":429,\"parameters\":[]} "}) {
                    enqueue(429,malformed); fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,filename,wave,plan,0,()->true));
                }
                enqueue(200,"{\"ok\":false,\"error_code\":429}"); fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,filename,wave,plan,0,()->true));
                enqueue(400,"{\"ok\":false,\"error_code\":429}"); fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,filename,wave,plan,0,()->true));
                Fake fail=enqueue(200,"{}"); fail.failOutput=true;
                fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,filename,wave,plan,0,()->true));
                fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,filename,wave,plan,0,()->false));
                Fake cancel=enqueue(200,receipt(filename,52).toString()); cancel.cancelRead=true;
                fails("UNKNOWN_OUTCOME",true,()->http.sendDocument(token,123456,99,filename,wave,plan,0,()->true));
            }
            check(replies.isEmpty());
        } finally { check(wave.delete()); check(directory.delete()); }
        System.out.println("Telegram fake HTTPS transport checks passed: "+checks);
    }
}
