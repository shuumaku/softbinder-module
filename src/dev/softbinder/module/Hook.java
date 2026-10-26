package dev.softbinder.module;

import android.view.KeyEvent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {
    private static final String SVC = "jp.kyocera.kcfp.internal.softkey.KCfpSoftkeyService";
    private static final String CALLBACK = SVC + "$Callback";
    private static final String SOFTKEYS = "jp.kyocera.kcfp.util.KCfpSoftkeyGuide$Softkeys";
    private static final String KEY_ACTION = "com.kyocera.hardkey.KCfpSoftkeyAction";
    private static final String WINDOW_STATE = "com.android.server.policy.WindowManagerPolicy$WindowState";
    private static final int TYPE_BASE_APPLICATION = 1;
    private static final int TYPE_APPLICATION_STARTING = 3;

    private static ClassLoader cl;
    private static volatile Config.Bind[] target; // binds of the focused app's base window; null = leave the bar alone
    private static String forced = ""; // "<package>|<softkeys>" a refresh was last forced for; "" = stock

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"android".equals(p.packageName))
            return;
        cl = p.classLoader;
        try {
            Class<?> ws = XposedHelpers.findClass(WINDOW_STATE, cl);
            XposedHelpers.findAndHookMethod(SVC, cl, "displaySoftkeys", ws, ws, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam m) {
                    try {
                        onDisplay(m);
                    } catch (Throwable t) {
                        Logger.e("display: " + t);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(KEY_ACTION, cl, "handleSoftkeyKeyEvent", int.class, KeyEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam m) {
                            try {
                                onKey(m);
                            } catch (Throwable t) {
                                Logger.e("key: " + t);
                            }
                        }
                    });
            Logger.d("hooks installed");
        } catch (Throwable t) {
            Logger.e("install failed: " + t);
        }
    }

    // displaySoftkeys(focusedApp, ime): the framework shows the IME window's
    // softkeys when a keyboard is up,
    // but which binds apply depends on the focused app window: its type, whether a
    // keyboard is up, and its activity.
    private static void onDisplay(XC_MethodHook.MethodHookParam m) {
        Object focus = m.args[0];
        if (focus == null)
            return;
        Object shown = m.args[1] != null ? m.args[1] : focus;
        String pkg = String.valueOf(XposedHelpers.callMethod(focus, "getOwningPackage"));
        Object attrs = XposedHelpers.callMethod(focus, "getAttrs");
        int type = (Integer) XposedHelpers.getObjectField(attrs, "type");
        CharSequence title = (CharSequence) XposedHelpers.callMethod(attrs, "getTitle");
        Object softkeys = XposedHelpers.getObjectField(XposedHelpers.callMethod(shown, "getAttrs"), "softkeys");

        String window = windowContext(type, shown != focus);
        Config.Bind[] binds = window == null ? null : Config.resolve(pkg, window, title);
        target = binds;
        if (Logger.debug) {
            Logger.d("focus " + pkg + " " + title + " type=" + type + " window=" + window + " bound=" + (binds != null)
                    + " " + dump(softkeys));
        }

        wrapCallback(m.thisObject);
        // The service only re-sends softkeys when they differ from the last ones sent;
        // a window that leaves every slot
        // unclaimed looks identical to the previous one, so clear the cache whenever
        // what we would show changes.
        String key = binds == null ? "" : pkg + "|" + window + "|" + labels(binds) + "|" + dump(softkeys);
        if (!key.equals(forced)) {
            forced = key;
            XposedHelpers.setObjectField(m.thisObject, "mLastSoftkeys", null);
        }
    }

    /**
     * @return "base", "ime" (base window with a keyboard up) or "dialog"; null for
     *         windows binds never apply to.
     */
    private static String windowContext(int type, boolean imeShown) {
        if (type == TYPE_BASE_APPLICATION)
            return imeShown ? "ime" : "base";
        boolean dialog = (type >= 2 && type < 100 && type != TYPE_APPLICATION_STARTING)
                || (type >= 1000 && type < 2000);
        return dialog ? "dialog" : null;
    }

    private static String labels(Config.Bind[] binds) {
        StringBuilder s = new StringBuilder();
        for (int i = 1; i <= 4; i++)
            s.append(binds[i] == null ? "-" : binds[i].label).append('/');
        return s.toString();
    }

    private static void wrapCallback(Object service) {
        Object callback = XposedHelpers.getObjectField(service, "mCallback");
        if (callback == null || Proxy.isProxyClass(callback.getClass()))
            return;
        final Object real = callback;
        Class<?> type = XposedHelpers.findClass(CALLBACK, cl);
        XposedHelpers.setObjectField(service, "mCallback",
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                Config.Bind[] binds = target;
                                if (binds != null && method.getName().equals("setSoftkeyState") && args != null
                                        && args[0] != null) {
                                    try {
                                        args = new Object[] { labelled(args[0], binds), args[1] };
                                    } catch (Throwable t) {
                                        Logger.e("labels: " + t);
                                    }
                                }
                                try {
                                    return method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            }
                        }));
        Logger.d("callback wrapped");
    }

    /**
     * A copy of the softkeys with our labels on the free slots; the app's own state
     * is untouched.
     */
    private static Object labelled(Object original, Config.Bind[] binds) {
        Object copy = XposedHelpers.newInstance(XposedHelpers.findClass(SOFTKEYS, cl), original);
        // the copy constructor drops the menu flag, which SystemUI and free() both
        // depend on
        XposedHelpers.callMethod(copy, "setMenuFlag", XposedHelpers.callMethod(original, "hasMenu"));
        for (int slot = 1; slot <= 4; slot++) {
            if (binds[slot] != null && free(copy, slot))
                XposedHelpers.callMethod(copy, "setText", slot, (CharSequence) binds[slot].label);
        }
        if (Logger.debug)
            Logger.d("labelled " + dump(copy) + " menu=" + XposedHelpers.callMethod(copy, "hasMenu"));
        return copy;
    }

    private static boolean free(Object softkeys, int slot) {
        CharSequence text = (CharSequence) XposedHelpers.callMethod(softkeys, "getText", slot);
        if (text == null || !text.toString().startsWith("@SK_AUTO"))
            return false;
        return !(slot == 1 && (Boolean) XposedHelpers.callMethod(softkeys, "hasMenu")); // F1 = system Menu
    }

    // KCfpSoftkeyAction.handleSoftkeyKeyEvent runs before the key is queued;
    // consuming here keeps it from the app.
    private static void onKey(XC_MethodHook.MethodHookParam m) {
        Config.Bind[] binds = target;
        int slot = (Integer) m.args[0] - KeyEvent.KEYCODE_F1 + 1;
        if (binds == null || slot < 1 || slot > 4 || binds[slot] == null)
            return;
        Object service = XposedHelpers.getObjectField(m.thisObject, "mSoftkeyService");
        Object shown = XposedHelpers.getObjectField(service, "mLastSoftkeys");
        if (shown == null || !free(shown, slot))
            return; // the app owns this key
        KeyEvent event = (KeyEvent) m.args[1];
        XposedHelpers.callMethod(m.thisObject, "setHandled");
        XposedHelpers.callMethod(m.thisObject, "setNotPassToUser");
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Actions.run((android.content.Context) XposedHelpers.callMethod(m.thisObject, "getContext"), binds[slot]);
        }
    }

    private static String dump(Object softkeys) {
        if (softkeys == null)
            return "none";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 5; i++)
            s.append(i).append('=').append(XposedHelpers.callMethod(softkeys, "getText", i)).append(' ');
        return s.toString().trim();
    }
}
