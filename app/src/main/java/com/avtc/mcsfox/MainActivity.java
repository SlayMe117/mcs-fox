package com.avtc.mcsfox;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebSettings;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private WebView web;
    private FoxClient client;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean connected = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Fox");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        keepAlive.scheduleWithFixedDelay(() -> {
            if (connected && client != null) exec.submit(() -> {
                try { client.ping(); } catch (Exception e) { connected = false; }
            });
        }, 45, 45, TimeUnit.SECONDS);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        keepAlive.shutdownNow(); exec.shutdownNow();
        if (client != null) client.close();
    }

    private void callJs(String fn, String json) {
        final String js = fn + "(" + JSONObject.quote(json) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private JSONObject pointsJson(List<FoxClient.Point> pts) throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        JSONArray arr = new JSONArray();
        for (FoxClient.Point p : pts) {
            JSONObject o = new JSONObject();
            o.put("path", p.path); o.put("name", p.name); o.put("kind", p.kind);
            o.put("type", p.type); o.put("value", p.value);
            o.put("writable", p.writable); o.put("handle", p.handle);
            arr.put(o);
        }
        r.put("points", arr);
        return r;
    }

    class Bridge {
        @JavascriptInterface
        public void connect(String host, int port, String user, String pass, int depth) {
            exec.submit(() -> {
                try {
                    if (client != null) client.close();
                    client = new FoxClient();
                    client.connect(host, port, 8000);
                    Map<String,String> info = client.hello();
                    client.login(user, pass);
                    connected = true;
                    JSONObject head = new JSONObject();
                    head.put("ok", true);
                    head.put("station", info.get("station.name"));
                    head.put("version", info.get("app.version"));
                    callJs("onConnected", head.toString());

                    List<FoxClient.Point> pts = client.discoverPoints(depth, new ScanProgress());
                    callJs("onConnectResult", pointsJson(pts).toString());
                } catch (SecurityException e) {
                    connected = false; callJs("onConnectResult", errJson("Ошибка входа: " + e.getMessage()));
                } catch (Exception e) {
                    connected = false; callJs("onConnectResult", errJson("Ошибка связи: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                }
            });
        }

        @JavascriptInterface
        public void refresh(int depth) {
            exec.submit(() -> {
                try {
                    if (client == null || !connected) throw new IllegalStateException("нет соединения");
                    List<FoxClient.Point> pts = client.discoverPoints(depth, new ScanProgress());
                    callJs("onConnectResult", pointsJson(pts).toString());
                } catch (Exception e) { callJs("onConnectResult", errJson(e.getMessage())); }
            });
        }

        @JavascriptInterface
        public void poll(int depth) {
            exec.submit(() -> {
                JSONObject r = new JSONObject();
                try {
                    if (client == null || !connected) throw new IllegalStateException("нет соединения");
                    List<FoxClient.Point> pts = client.discoverPoints(depth, null);
                    JSONObject vals = new JSONObject();
                    for (FoxClient.Point p : pts) vals.put(p.handle, p.value == null ? JSONObject.NULL : p.value);
                    r.put("ok", true); r.put("values", vals);
                } catch (Exception e) { err(r, e.getMessage()); }
                callJs("onValues", r.toString());
            });
        }

        @JavascriptInterface
        public void setValue(String handle, String kind, String value) {
            exec.submit(() -> {
                JSONObject r = new JSONObject();
                try {
                    r.put("handle", handle);
                    if (client == null || !connected) throw new IllegalStateException("нет соединения");
                    client.setValue(handle, kind, value);
                    r.put("ok", true); r.put("value", client.readValue(handle));
                } catch (Exception e) { err(r, e.getMessage()); }
                callJs("onSetResult", r.toString());
            });
        }
    }

    /** прогресс сканирования: не чаще раза в ~300 мс */
    class ScanProgress implements FoxClient.Progress {
        long last = 0;
        public void scanned(int c, int p) {
            long now = System.currentTimeMillis();
            if (now - last > 300) { last = now; callJs("onScan", "{\"c\":" + c + ",\"p\":" + p + "}"); }
        }
        public boolean cancelled() { return !connected; }
    }

    private String errJson(String msg) {
        JSONObject o = new JSONObject(); err(o, msg); return o.toString();
    }
    private static void err(JSONObject o, String msg) {
        try { o.put("ok", false); o.put("error", msg); } catch (Exception ignored) {}
    }
}
