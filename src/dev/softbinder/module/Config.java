package dev.softbinder.module;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * /data/system/softbinder.json, re-read whenever it changes (watcher)
 */
final class Config {
    static final String FILE = "/data/system/softbinder.json";

    static final class Bind {
        final String label, action, param;

        Bind(String label, String action, String param) {
            this.label = label;
            this.action = action;
            this.param = param;
        }
    }

    private static volatile Map<String, Bind[]> binds = Collections.emptyMap();
    private static long seen = -1;

    private Config() {
    }

    /**
     * @return the binds for a package indexed by slot (1..4, null = unbound), or
     *         null if the package has none.
     */
    static Bind[] forPackage(String pkg) {
        reload();
        return binds.get(pkg);
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
        Map<String, Bind[]> parsed = new HashMap<String, Bind[]>();
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

    private static void parse(String text, Map<String, Bind[]> out) throws Exception {
        JSONObject root = new JSONObject(text);
        Journal.debug = root.optBoolean("debug");
        JSONArray list = root.optJSONArray("binds");
        if (list == null)
            list = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            String app = o == null ? "" : o.optString("app");
            int slot = o == null ? -1 : slot(o.optString("key"));
            if (app.isEmpty() || slot < 1 || !Actions.valid(o.optString("action"))) {
                Journal.e("config: skipped entry " + i);
                continue;
            }
            Bind[] slots = out.get(app);
            if (slots == null)
                out.put(app, slots = new Bind[5]);
            slots[slot] = new Bind(o.optString("label"), o.optString("action"), o.optString("param").trim());
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
