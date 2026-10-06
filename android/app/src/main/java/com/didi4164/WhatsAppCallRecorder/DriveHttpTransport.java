package com.didi4164.WhatsAppCallRecorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Bounded, redirect-free HTTPS transport. Injectable connection factory enables offline HTTP tests. */
public final class DriveHttpTransport {
  public interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
  public static final class Response {
    public final int status;
    public final byte[] body;
    public final String location, range;
    public Response(int status, byte[] body, String location, String range) {
      this.status = status; this.body = body; this.location = location; this.range = range;
    }
  }
  private final ConnectionFactory factory;
  private static final Set<HttpURLConnection> ACTIVE = ConcurrentHashMap.newKeySet();
  private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(action -> {
    Thread thread = new Thread(action, "DriveHttpDeadline"); thread.setDaemon(true); return thread;
  });
  private volatile HttpURLConnection current;
  private volatile boolean canceled;
  public DriveHttpTransport() { this(url -> (HttpURLConnection) url.openConnection()); }
  public DriveHttpTransport(ConnectionFactory factory) { this.factory = factory; }
  public void cancel() { canceled = true; HttpURLConnection connection = current; if (connection != null) connection.disconnect(); }
  public static void cancelAll() { for (HttpURLConnection connection : ACTIVE) connection.disconnect(); }
  public Response request(String method, String endpoint, String token, byte[] body, Map<String, String> headers) throws IOException {
    if (canceled) throw new IOException("Drive request canceled");
    URL url = new URL(endpoint);
    if (!"https".equals(url.getProtocol()) || !"www.googleapis.com".equals(url.getHost()) ||
        url.getPort() != -1 || url.getUserInfo() != null || url.getRef() != null ||
        !(url.getPath().startsWith("/drive/v3/") || "/upload/drive/v3/files".equals(url.getPath()))) {
      throw new IOException("Untrusted Drive endpoint");
    }
    if (token == null || token.isEmpty() || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0 ||
        body != null && body.length > DriveBackupPolicy.CHUNK_BYTES) throw new IOException("Invalid Drive request");
    HttpURLConnection connection = factory.open(url);
    current = connection; ACTIVE.add(connection);
    final long started = System.nanoTime();
    ScheduledFuture<?> deadline = DEADLINES.schedule(connection::disconnect, 45, TimeUnit.SECONDS);
    try {
      if (canceled) throw new IOException("Drive request canceled");
      connection.setConnectTimeout(20000); connection.setReadTimeout(20000);
      connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
      connection.setRequestProperty("Authorization", "Bearer " + token);
      connection.setRequestProperty("Accept", "application/json");
      for (Map.Entry<String, String> entry : headers.entrySet()) connection.setRequestProperty(entry.getKey(), entry.getValue());
      if (body != null) {
        connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
        try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
      }
      int status = connection.getResponseCode();
      InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      byte[] response;
      try (InputStream stream = input) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (stream != null) {
          byte[] buffer = new byte[4096]; int count;
          while ((count = stream.read(buffer)) != -1) {
            if (canceled || System.nanoTime() - started > TimeUnit.SECONDS.toNanos(45)) throw new IOException("Drive request canceled");
            if (bytes.size() + count > 65536) throw new IOException("Drive response exceeds limit");
            bytes.write(buffer, 0, count);
          }
        }
        response = bytes.toByteArray();
      }
      return new Response(status, response, connection.getHeaderField("Location"), connection.getHeaderField("Range"));
    } finally { deadline.cancel(false); ACTIVE.remove(connection); current = null; connection.disconnect(); }
  }
}
