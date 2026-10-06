package android.util;
import java.io.*;
import java.nio.file.*;
/** Real local-file host shim, testing queue recovery logic rather than Android AtomicFile internals. */
public final class AtomicFile {
  private final File base;
  public AtomicFile(File base) { this.base = base; }
  public File getBaseFile() { return base; }
  public FileInputStream openRead() throws IOException {
    File backup = new File(base + ".bak");
    if (backup.exists()) Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
    return new FileInputStream(base);
  }
  public FileOutputStream startWrite() throws IOException {
    base.getParentFile().mkdirs();
    if (base.exists()) Files.move(base.toPath(), new File(base + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
    return new FileOutputStream(base);
  }
  public void finishWrite(FileOutputStream output) throws IOException {
    output.getFD().sync(); output.close(); Files.deleteIfExists(new File(base + ".bak").toPath());
  }
  public void failWrite(FileOutputStream output) throws IOException {
    output.close(); File backup = new File(base + ".bak");
    if (backup.exists()) Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
  }
}
