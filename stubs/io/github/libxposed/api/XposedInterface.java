package io.github.libxposed.api;

import android.content.SharedPreferences;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;

/** Compile-only stub of libxposed api 102; real class provided by LSPosed at runtime. */
public interface XposedInterface {
    int API_101 = 101;
    int API_102 = 102;
    int LIB_API = 102;
    int PRIORITY_DEFAULT = 50;
    int PRIORITY_HIGHEST = 10000;
    int PRIORITY_LOWEST = -10000;
    long PROP_CAP_REMOTE = 1L;
    long PROP_CAP_SYSTEM = 2L;
    long PROP_RT_API_PROTECTION = 4L;

    default int getApiVersion() { return LIB_API; }
    String getFrameworkName();
    String getFrameworkVersion();
    long getFrameworkVersionCode();
    long getFrameworkProperties();

    HookBuilder hook(Executable origin);
    HookBuilder hookClassInitializer(Class<?> origin);
    boolean deoptimize(Executable executable);

    Invoker getInvoker(Method method);
    CtorInvoker getInvoker(Constructor<?> constructor);

    android.content.pm.ApplicationInfo getModuleApplicationInfo();
    SharedPreferences getRemotePreferences(String name);
    String[] listRemoteFiles();
    android.os.ParcelFileDescriptor openRemoteFile(String name);

    void log(int priority, String tag, String message);
    void log(int priority, String tag, String message, Throwable throwable);

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    interface Chain {
        Executable getExecutable();
        Object getThisObject();
        List<Object> getArgs();
        Object getArg(int index);
        Object proceed() throws Throwable;
        Object proceed(Object[] args) throws Throwable;
        Object proceedWith(Object thisObject) throws Throwable;
        Object proceedWith(Object thisObject, Object[] args) throws Throwable;
    }

    interface HookBuilder {
        HookBuilder setId(String id);
        HookBuilder setPriority(int priority);
        HookBuilder setExceptionMode(ExceptionMode mode);
        HookHandle intercept(Hooker hooker);
    }

    interface HookHandle {
        Executable getExecutable();
        String getId();
        void unhook();
        HookHandle replaceHook(Hooker hooker);
    }

    enum ExceptionMode { SWALLOW, THROW }

    interface Invoker { Object invoke(Object thisObject, Object... args) throws Throwable; }
    interface CtorInvoker { Object newInstance(Object... args) throws Throwable; }
}
