package android.util;
import java.io.*;
import java.nio.file.*;
/** Real local-file host shim, testing queue recovery logic rather than Android AtomicFile internals. */
public final class AtomicFile {
  public static boolean failNextFinish;
  public static boolean silentlySkipNextCommit;
  private final File base;
  public AtomicFile(File base) { this.base = base; }
  public File getBaseFile() { return base; }
  public FileInputStream openRead() throws IOException {
    File backup = new File(base + ".bak");
    if (backup.exists()) Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
    if (base.exists()) Files.deleteIfExists(new File(base + ".new").toPath());
    return new FileInputStream(base);
  }
  public FileOutputStream startWrite() throws IOException {
    base.getParentFile().mkdirs();
    return new FileOutputStream(new File(base + ".new"));
  }
  public void finishWrite(FileOutputStream output) throws IOException {
    if (failNextFinish) { failNextFinish = false; throw new IOException("Synthetic journal commit failure"); }
    output.getFD().sync(); output.close();
    if (silentlySkipNextCommit) { silentlySkipNextCommit = false; return; }
    Files.move(new File(base + ".new").toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
  }
  public void failWrite(FileOutputStream output) throws IOException {
    output.close(); Files.deleteIfExists(new File(base + ".new").toPath());
  }
}
