package com.didi4164.WhatsAppCallRecorder;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic connections only: never contacts Google or reads real recordings. */
public final class DriveHttpTransportTest {
  private static int checks;
  private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
  private static final String ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files?upload_id=synthetic";
  private static final class Fake extends HttpURLConnection {
    final int reply; final byte[] bytes; boolean disconnected, inputClosed, outputClosed;
    final ByteArrayOutputStream written = new ByteArrayOutputStream();
    Fake(int reply, byte[] bytes) throws Exception { super(new URL(ENDPOINT)); this.reply = reply; this.bytes = bytes; }
    public void connect() { }
    public boolean usingProxy() { return false; }
    public void disconnect() { disconnected = true; }
    public int getResponseCode() { return reply; }
    private InputStream response() { return new ByteArrayInputStream(bytes) { public void close() throws IOException { inputClosed = true; super.close(); } }; }
    public InputStream getInputStream() { return response(); }
    public InputStream getErrorStream() { return response(); }
    public OutputStream getOutputStream() { return new FilterOutputStream(written) { public void close() throws IOException { outputClosed = true; super.close(); } }; }
    public String getHeaderField(String name) { return "Range".equals(name) ? "bytes=0-2" : "Location".equals(name) ? ENDPOINT : null; }
  }
  private interface Checked { void run() throws Exception; }
  private static void rejects(Checked action) throws Exception { checks++; try { action.run(); } catch (IOException expected) { return; } throw new AssertionError("Expected IOException " + checks); }
  public static void main(String[] ignored) throws Exception {
    Fake f = new Fake(308, new byte[0]); DriveHttpTransport transport = new DriveHttpTransport(url -> f);
    DriveHttpTransport.Response reply = transport.request("PUT", ENDPOINT, "synthetic-token", new byte[]{1,2,3}, Collections.singletonMap("Content-Range", "bytes 0-2/100"));
    check(reply.status == 308 && "bytes=0-2".equals(reply.range)); check(ENDPOINT.equals(reply.location));
    check(!f.getInstanceFollowRedirects()); check(f.getConnectTimeout() == 20000 && f.getReadTimeout() == 20000);
    check("Bearer synthetic-token".equals(f.getRequestProperty("Authorization"))); check("bytes 0-2/100".equals(f.getRequestProperty("Content-Range")));
    check(Arrays.equals(f.written.toByteArray(), new byte[]{1,2,3})); check(f.outputClosed && f.inputClosed && f.disconnected);
    Fake error = new Fake(401, "{}".getBytes("UTF-8"));
    check(new DriveHttpTransport(url -> error).request("GET", "https://www.googleapis.com/drive/v3/about", "synthetic", null, Collections.emptyMap()).status == 401);
    check(error.inputClosed && error.disconnected);
    Fake large = new Fake(200, new byte[65537]);
    rejects(() -> new DriveHttpTransport(url -> large).request("GET", ENDPOINT, "synthetic", null, Collections.emptyMap())); check(large.inputClosed && large.disconnected);
    Fake limit = new Fake(200, new byte[65536]);
    check(new DriveHttpTransport(url -> limit).request("GET", ENDPOINT, "synthetic", null, Collections.emptyMap()).body.length == 65536);
    AtomicInteger opens = new AtomicInteger(); DriveHttpTransport guarded = new DriveHttpTransport(url -> { opens.incrementAndGet(); return new FakeUnchecked(url); });
    for (String bad : new String[]{"http://www.googleapis.com/drive/v3/about", "https://evil.example/drive/v3/about", "https://user@www.googleapis.com/drive/v3/about", "https://www.googleapis.com:443/drive/v3/about", ENDPOINT + "#fragment", "https://www.googleapis.com/upload/drive/v3/files-suffix"}) rejects(() -> guarded.request("GET", bad, "synthetic", null, Collections.emptyMap()));
    rejects(() -> guarded.request("GET", ENDPOINT, "synthetic\r\nInjected", null, Collections.emptyMap()));
    rejects(() -> guarded.request("PUT", ENDPOINT, "synthetic", new byte[DriveBackupPolicy.CHUNK_BYTES+1], Collections.emptyMap()));
    check(opens.get() == 0);
    guarded.cancel(); rejects(() -> guarded.request("GET", ENDPOINT, "synthetic", null, Collections.emptyMap())); check(opens.get() == 0);
    Fake stopped = new Fake(200, new byte[0]); DriveHttpTransport[] holder = new DriveHttpTransport[1];
    holder[0] = new DriveHttpTransport(url -> { holder[0].cancel(); return stopped; });
    rejects(() -> holder[0].request("GET", ENDPOINT, "synthetic", null, Collections.emptyMap())); check(stopped.disconnected);
    System.out.println("Drive HTTP transport: " + checks + " checks passed.");
  }
  private static final class FakeUnchecked extends HttpURLConnection {
    FakeUnchecked(URL url) { super(url); }
    public void connect() { } public boolean usingProxy() { return false; } public void disconnect() { }
  }
}
