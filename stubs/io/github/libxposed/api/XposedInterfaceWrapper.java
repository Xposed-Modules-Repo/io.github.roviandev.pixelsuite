package io.github.libxposed.api;

import android.content.SharedPreferences;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/** Compile-only stub matching framework.dex: public class, no-arg ctor, delegates to the framework. */
public class XposedInterfaceWrapper implements XposedInterface {
    public XposedInterfaceWrapper() {}
    @Override public int getApiVersion() { throw new RuntimeException("stub"); }
    @Override public String getFrameworkName() { throw new RuntimeException("stub"); }
    @Override public String getFrameworkVersion() { throw new RuntimeException("stub"); }
    @Override public long getFrameworkVersionCode() { throw new RuntimeException("stub"); }
    @Override public long getFrameworkProperties() { throw new RuntimeException("stub"); }
    @Override public HookBuilder hook(Executable origin) { throw new RuntimeException("stub"); }
    @Override public HookBuilder hookClassInitializer(Class<?> origin) { throw new RuntimeException("stub"); }
    @Override public boolean deoptimize(Executable executable) { throw new RuntimeException("stub"); }
    @Override public Invoker getInvoker(Method method) { throw new RuntimeException("stub"); }
    @Override public CtorInvoker getInvoker(Constructor<?> constructor) { throw new RuntimeException("stub"); }
    @Override public android.content.pm.ApplicationInfo getModuleApplicationInfo() { throw new RuntimeException("stub"); }
    @Override public SharedPreferences getRemotePreferences(String name) { throw new RuntimeException("stub"); }
    @Override public String[] listRemoteFiles() { throw new RuntimeException("stub"); }
    @Override public android.os.ParcelFileDescriptor openRemoteFile(String name) { throw new RuntimeException("stub"); }
    @Override public void log(int priority, String tag, String message) { throw new RuntimeException("stub"); }
    @Override public void log(int priority, String tag, String message, Throwable t) { throw new RuntimeException("stub"); }
}
