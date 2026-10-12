package com.avtc.mcsfox;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebSettings;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Fox");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    /** Передать результат обратно в JS: window.onFoxResult(jsonString). */
    private void callJs(final String fn, final String jsonPayload) {
        final String js = fn + "(" + JSONObject.quote(jsonPayload) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    /** Мост между WebView и Java-клиентом Fox. Методы вызываются из фонового потока WebView. */
    class Bridge {
        @JavascriptInterface
        public void discover(final String host, final int port, final String user,
                             final String pass, final int depth) {
            new Thread(() -> {
                FoxClient c = new FoxClient();
                JSONObject res = new JSONObject();
                try {
                    c.connect(host, port, 8000);
                    Map<String,String> info = c.hello();
                    c.login(user, pass);
                    FoxClient.Node root = c.discover(depth);
                    res.put("ok", true);
                    res.put("station", info.get("station.name"));
                    res.put("version", info.get("app.version"));
                    res.put("tree", nodeToJson(root));
                } catch (SecurityException e) {
                    put(res, "Ошибка входа: " + e.getMessage());
                } catch (Exception e) {
                    put(res, "Ошибка связи: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    c.close();
                }
                callJs("onFoxResult", res.toString());
            }).start();
        }
    }

    private static void put(JSONObject o, String err) {
        try { o.put("ok", false); o.put("error", err); } catch (Exception ignored) {}
    }

    private static JSONObject nodeToJson(FoxClient.Node n) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", n.name);
        if (n.type != null) o.put("type", n.type);
        if (n.value != null) o.put("value", n.value);
        if (n.handle != null) o.put("handle", n.handle);
        o.put("expandable", n.expandable);
        if (!n.children.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (FoxClient.Node c : n.children) arr.put(nodeToJson(c));
            o.put("children", arr);
        }
        return o;
    }
}
