package de.robv.android.xposed;
public abstract class XC_MethodHook {
  public class Unhook { }
  public static class MethodHookParam { public java.lang.reflect.Member method; public Object thisObject; public Object[] args;
    public Object getResult(){return null;} public void setResult(Object o){} public Throwable getThrowable(){return null;} public boolean hasThrowable(){return false;} }
  protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
  protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
}
