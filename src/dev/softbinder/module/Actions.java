package dev.softbinder.module;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

/**
 * What a bind does. Params:
 * launch package | package/.Class | scheme://uri
 * intent <action> [<data uri>]
 * broadcast <action> [<package>]
 * swipe up | down | left | right
 * tap <x> <y>   (screen pixels, as in a screenshot)
 * key KEYCODE_NAME | number
 * Everything runs on one worker thread so the key path never blocks.
 */
final class Actions {
    private static final Set<String> TYPES = new HashSet<String>(
            Arrays.asList("launch", "intent", "broadcast", "swipe", "tap", "key"));
    private static Handler worker;

    private Actions() {
    }

    static boolean valid(String type) {
        return TYPES.contains(type);
    }

    static void run(final Context ctx, final Config.Bind b) {
        worker().post(new Runnable() {
            @Override
            public void run() {
                try {
                    exec(ctx, b);
                    Logger.d("ran " + b.action + " " + b.param);
                } catch (Throwable t) {
                    Logger.e(b.action + " " + b.param + ": " + t);
                }
            }
        });
    }

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread t = new HandlerThread("softbinder");
            t.start();
            worker = new Handler(t.getLooper());
        }
        return worker;
    }

    private static void exec(Context ctx, Config.Bind b) throws Throwable {
        String[] p = b.param.split("\\s+");
        Object user = Class.forName("android.os.UserHandle").getField("CURRENT").get(null);
        if (b.action.equals("launch")) {
            Intent i;
            if (b.param.contains("://"))
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(b.param));
            else if (b.param.contains("/"))
                i = new Intent(Intent.ACTION_MAIN).setComponent(ComponentName.unflattenFromString(b.param));
            else
                i = ctx.getPackageManager().getLaunchIntentForPackage(b.param);
            if (i == null)
                throw new IllegalArgumentException("nothing to launch");
            start(ctx, i, user);
        } else if (b.action.equals("intent")) {
            Intent i = new Intent(p[0]);
            if (p.length > 1)
                i.setData(Uri.parse(p[1]));
            start(ctx, i, user);
        } else if (b.action.equals("broadcast")) {
            Intent i = new Intent(p[0]);
            if (p.length > 1)
                i.setPackage(p[1]);
            XposedHelpers.callMethod(ctx, "sendBroadcastAsUser", i, user);
        } else if (b.action.equals("swipe")) {
            swipe(ctx, b.param);
        } else if (b.action.equals("tap")) {
            tap(p);
        } else {
            key(b.param);
        }
    }

    private static void start(Context ctx, Intent i, Object user) {
        XposedHelpers.callMethod(ctx, "startActivityAsUser", i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), user);
    }

    private static void key(String name) {
        int code = name.matches("\\d+") ? Integer.parseInt(name) : KeyEvent.keyCodeFromString(name);
        if (code == KeyEvent.KEYCODE_UNKNOWN)
            throw new IllegalArgumentException("unknown key " + name);
        long down = SystemClock.uptimeMillis();
        inject(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD));
        inject(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    /** Emulate a swipe */
    private static void swipe(Context ctx, String dir) throws InterruptedException {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        float w = dm.widthPixels, h = dm.heightPixels * 0.85f;
        float x0, y0, x1, y1;
        if (dir.equals("down")) {
            x0 = x1 = w / 2;
            y0 = h * 0.30f;
            y1 = h * 0.75f;
        } else if (dir.equals("up")) {
            x0 = x1 = w / 2;
            y0 = h * 0.75f;
            y1 = h * 0.30f;
        } else if (dir.equals("left")) {
            y0 = y1 = h / 2;
            x0 = w * 0.80f;
            x1 = w * 0.20f;
        } else if (dir.equals("right")) {
            y0 = y1 = h / 2;
            x0 = w * 0.20f;
            x1 = w * 0.80f;
        } else
            throw new IllegalArgumentException("swipe " + dir);
        long down = SystemClock.uptimeMillis();
        final int steps = 12;
        for (int s = 0; s <= steps + 1; s++) {
            int action = s == 0 ? MotionEvent.ACTION_DOWN
                    : s == steps + 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            float f = Math.min(s, steps) / (float) steps;
            MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x0 + (x1 - x0) * f,
                    y0 + (y1 - y0) * f, 0);
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            inject(e);
            Thread.sleep(30);
        }
    }

    /** Emulate a short touch at pixel (x, y). */
    private static void tap(String[] p) throws InterruptedException {
        if (p.length != 2 || !p[0].matches("\\d+") || !p[1].matches("\\d+"))
            throw new IllegalArgumentException("tap needs x y");
        float x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]);
        long down = SystemClock.uptimeMillis();
        for (int action : new int[] { MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP }) {
            MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            inject(e);
            Thread.sleep(50);
        }
    }

    private static void inject(Object event) {
        Object im = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.hardware.input.InputManager", null),
                "getInstance");
        XposedHelpers.callMethod(im, "injectInputEvent", event, 0); // 0 = async
    }
}
