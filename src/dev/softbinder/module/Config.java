package dev.softbinder.module;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /data/system/softbinder.json, re-read whenever it changes (watcher)
 *
 * A bind's optional "window" (base | dialog | ime | any, default base) and
 * "activity" (suffix of the focused window's
 * component, default any) say when it applies; the most specific bind for a key
 * wins.
 */
final class Config {
    static final String FILE = "/data/system/softbinder.json";

    static final class Bind {
        final int slot;
        final String label, action, param, window, activity;

        Bind(int slot, String label, String action, String param, String window, String activity) {
            this.slot = slot;
            this.label = label;
            this.action = action;
            this.param = param;
            this.window = window;
            this.activity = activity;
        }

        /** Higher wins when two binds fit the same key. */
        int specificity() {
            return (activity.isEmpty() ? 0 : 2) + (window.equals("any") ? 0 : 1);
        }
    }

    private static final List<String> WINDOWS = Arrays.asList("base", "dialog", "ime", "any");

    private static volatile Map<String, List<Bind>> binds = Collections.emptyMap();
    private static long seen = -1;

    private Config() {
    }

    /**
     * @param window "base", "ime" (base window with a keyboard up) or "dialog"
     * @param title  the focused window's title, normally "package/activity"
     * @return the binds in force for a package indexed by slot (1..4, null =
     *         unbound), or
     *         null if none apply.
     */
    static Bind[] resolve(String pkg, String window, CharSequence title) {
        reload();
        List<Bind> all = binds.get(pkg);
        if (all == null)
            return null;
        Bind[] best = new Bind[5];
        boolean any = false;
        for (Bind b : all) {
            if (!appliesTo(b, window, title))
                continue;
            if (best[b.slot] == null || b.specificity() >= best[b.slot].specificity())
                best[b.slot] = b;
            any = true;
        }
        return any ? best : null;
    }

    private static boolean appliesTo(Bind b, String window, CharSequence title) {
        boolean windowOk = b.window.equals("any") || b.window.equals(window)
                || (b.window.equals("base") && window.equals("ime"));
        return windowOk && (b.activity.isEmpty() || (title != null && title.toString().endsWith(b.activity)));
    }

    static int slot(String key) {
        return key.length() == 2 && key.charAt(0) == 'F' && key.charAt(1) >= '1' && key.charAt(1) <= '4'
                ? key.charAt(1) - '0'
                : -1;
    }

    private static synchronized void reload() {
        File file = new File(FILE);
        long now = file.exists() ? file.lastModified() * 31 + file.length() : 0;
        if (now == seen)
            return;
        seen = now;
        Journal.debug = false;
        Map<String, List<Bind>> parsed = new HashMap<String, List<Bind>>();
        if (file.exists()) {
            try {
                parse(read(file), parsed);
            } catch (Throwable t) {
                Journal.e("config: " + t);
            }
        }
        binds = parsed;
        Journal.d("config loaded: " + parsed.keySet());
    }

    private static void parse(String text, Map<String, List<Bind>> out) throws Exception {
        JSONObject root = new JSONObject(text);
        Journal.debug = root.optBoolean("debug");
        JSONArray list = root.optJSONArray("binds");
        if (list == null)
            list = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            String app = o == null ? "" : o.optString("app");
            int slot = o == null ? -1 : slot(o.optString("key"));
            String window = o == null ? "" : o.optString("window", "base");
            if (app.isEmpty() || slot < 1 || !Actions.valid(o.optString("action")) || !WINDOWS.contains(window)) {
                Journal.e("config: skipped entry " + i);
                continue;
            }
            List<Bind> forApp = out.get(app);
            if (forApp == null)
                out.put(app, forApp = new ArrayList<Bind>());
            forApp.add(new Bind(slot, o.optString("label"), o.optString("action"), o.optString("param").trim(), window,
                    o.optString("activity").trim()));
        }
    }

    private static String read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0)
                    break;
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }
}
