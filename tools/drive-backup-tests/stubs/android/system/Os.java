package android.system;
import java.io.*;
/** Explicit host syscall shim: checks directory paths/failure handling, not Android fsync implementation. */
public final class Os {
  public static boolean failNextSync;
  public static int directorySyncs;
  public static FileDescriptor open(String path, int flags, int mode) throws IOException {
    if (!new File(path).isDirectory() || flags != OsConstants.O_RDONLY) throw new IOException("Invalid synthetic directory");
    return new FileDescriptor();
  }
  public static void fsync(FileDescriptor fd) throws IOException {
    if (failNextSync) { failNextSync = false; throw new IOException("Synthetic directory sync failure"); }
    directorySyncs++;
  }
  public static void close(FileDescriptor fd) { }
}
