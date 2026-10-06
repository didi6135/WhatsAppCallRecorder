package com.codaki.usbaudio;

import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Context for an actual shell process; it never changes the caller's UID or permissions. */
public final class ShellAudioContext extends ContextWrapper {
  private static final String SHELL_PACKAGE = "com.android.shell";
  private static ShellAudioContext instance;
  private final ApplicationInfo shellInfo;

  private ShellAudioContext(Context systemContext) throws PackageManager.NameNotFoundException {
    super(systemContext);
    shellInfo = systemContext.getPackageManager().getApplicationInfo(SHELL_PACKAGE, 0);
    if (shellInfo.uid != Process.myUid() || !SHELL_PACKAGE.equals(shellInfo.packageName)) {
      throw new SecurityException("The shell package must belong to the actual caller UID.");
    }
  }

  public static synchronized ShellAudioContext create() throws Exception {
    if (Process.myUid() != 2000) throw new SecurityException("Audio shell context requires real shell UID 2000.");
    if (instance != null) return instance;
    if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
    Class<?> activityClass = Class.forName("android.app.ActivityThread");
    Object activity = activityClass.getDeclaredMethod("currentActivityThread").invoke(null);
    if (activity == null) activity = activityClass.getDeclaredMethod("systemMain").invoke(null);
    if (activity == null) throw new IllegalStateException("Framework shell environment was not initialized.");
    // systemMain() creates a system Application attributed to "android". AudioPolicy's
    // contextless sink constructor uses that Application, so install the real shell
    // context there as well. Preserve an existing correctly attributed shell Application.
    ensureConfiguration(activityClass, activity);
    Context base = (Context) activityClass.getDeclaredMethod("getSystemContext").invoke(activity);
    if (base == null) throw new IllegalStateException("Framework system context is unavailable.");
    ShellAudioContext shell = new ShellAudioContext(base);
    Field initialApplication = field(activityClass, "mInitialApplication");
    Application previous = (Application) initialApplication.get(activity);
    if (replaceInitialApplication(previous == null ? null : previous.getOpPackageName())) {
      Application application = Instrumentation.newApplication(Application.class, shell);
      initialApplication.set(activity, application);
    }
    Field boundApplication = field(activityClass, "mBoundApplication");
    Class<?> bindClass = Class.forName("android.app.ActivityThread$AppBindData");
    Object bindData = boundApplication.get(activity);
    if (bindData == null) {
      Constructor<?> constructor = bindClass.getDeclaredConstructor(); constructor.setAccessible(true);
      bindData = constructor.newInstance();
      boundApplication.set(activity, bindData);
    }
    field(bindClass, "appInfo").set(bindData, shell.shellInfo);
    Application installed = (Application) initialApplication.get(activity);
    AttributionSource attribution = installed.getAttributionSource();
    if (!SHELL_PACKAGE.equals(installed.getOpPackageName()) || attribution.getUid() != Process.myUid()
        || !SHELL_PACKAGE.equals(attribution.getPackageName())) {
      throw new SecurityException("Default AudioRecord context does not match the actual shell caller.");
    }
    System.out.println("USB_SHELL_CONTEXT uid=" + Process.myUid() + " package=" + shell.getOpPackageName()
        + " initialPackage=" + installed.getOpPackageName());
    instance = shell;
    return shell;
  }

  static boolean replaceInitialApplication(String currentPackage) {
    if (currentPackage == null || "android".equals(currentPackage)) return true;
    if (SHELL_PACKAGE.equals(currentPackage)) return false;
    throw new SecurityException("Unexpected non-shell Application in the shell helper process.");
  }

  private static Field field(Class<?> owner, String name) throws Exception {
    Field value = owner.getDeclaredField(name); value.setAccessible(true); return value;
  }

  private static void ensureConfiguration(Class<?> activityClass, Object activity) throws Exception {
    Field configuration = field(activityClass, "mConfigurationController");
    if (configuration.get(activity) != null) return;
    Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
    Constructor<?> constructor = controllerClass.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
    constructor.setAccessible(true); configuration.set(activity, constructor.newInstance(activity));
  }

  @Override public String getPackageName() { return SHELL_PACKAGE; }
  @Override public String getOpPackageName() { return SHELL_PACKAGE; }
  @Override public ApplicationInfo getApplicationInfo() { return shellInfo; }
  @Override public AttributionSource getAttributionSource() {
    return new AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build();
  }
  @Override public Context getApplicationContext() { return this; }
  @Override public Context createPackageContext(String packageName, int flags) { return this; }
  @Override public int getDeviceId() { return 0; }
}
