package android.content;
import java.io.File;
/** Host-test shim; deliberately does not model Activities, permissions or Android lifecycle. */
public class Context {
  private final File root;
  public Context(File root) { this.root = root; }
  public File getNoBackupFilesDir() { return root; }
}
