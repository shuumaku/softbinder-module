package de.robv.android.xposed;
public final class XposedHelpers {
  public static Class<?> findClass(String n, ClassLoader c){return null;}
  public static XC_MethodHook.Unhook findAndHookMethod(String c, ClassLoader l, String m, Object... a){return null;}
  public static XC_MethodHook.Unhook findAndHookMethod(Class<?> c, String m, Object... a){return null;}
  public static Object getObjectField(Object o, String f){return null;}
  public static void setObjectField(Object o, String f, Object v){}
  public static Object callMethod(Object o, String m, Object... a){return null;}
  public static Object callStaticMethod(Class<?> c, String m, Object... a){return null;}
  public static Object newInstance(Class<?> c, Object... a){return null;}
}
