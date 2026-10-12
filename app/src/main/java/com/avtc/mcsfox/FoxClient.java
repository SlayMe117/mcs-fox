package com.avtc.mcsfox;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Mac;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.SecretKeySpec;

/**
 * Клиент MCS network (Fox, MCS 3.8 / Niagara AX).
 * Чистая Java без Android-зависимостей — тот же класс работает в APK и в тестах.
 * Протокол восстановлен по дампу Workbench 3.8.111 <-> Station 3.8.401.
 */
public class FoxClient {

    public static boolean DEBUG = false;

    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private byte[] buf = new byte[0];
    private int msgNum = 0;        // сквозной счётчик исходящих сообщений
    private int circuitId = 1;     // нечётные id цепей: 1,3,5,...
    private String rootHandle = "1";

    // ----- узел дерева -----
    public static class Node {
        public String handle, name, type, value;
        public boolean expandable;
        public final List<Node> children = new ArrayList<>();
        Node(String h, String n, String t, String v, boolean e) {
            handle=h; name=n; type=t; value=v; expandable=e;
        }
    }

    // ----- кадр Fox -----
    static class Frame {
        String kind, cmd;
        Map<String,String> fields;
        Frame(String k, String c, Map<String,String> f){ kind=k; cmd=c; fields=f; }
    }

    // ============================================================ соединение

    public void connect(String host, int port, int timeoutMs) throws IOException {
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), timeoutMs);
        sock.setSoTimeout(timeoutMs);
        in = sock.getInputStream();
        out = sock.getOutputStream();
    }

    public void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
    }

    private void send(String kind, int num, String chan, String cmd, String body) throws IOException {
        String msg = "fox " + kind + " " + num + " " + chan + " " + cmd + "\n{\n" + body + "};;\n";
        if (DEBUG) System.out.println("\n>>>\n" + msg);
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Прочитать следующий кадр (до разделителя ';;'). */
    private Frame readFrame() throws IOException {
        while (true) {
            int idx = indexOf(buf, ";;");
            if (idx >= 0) {
                String text = new String(buf, 0, idx, StandardCharsets.UTF_8);
                int rest = idx + 2;
                while (rest < buf.length && (buf[rest]=='\r' || buf[rest]=='\n')) rest++;
                buf = Arrays.copyOfRange(buf, rest, buf.length);
                if (DEBUG) System.out.println("\n<<<\n" + text);
                return parseFrame(text);
            }
            byte[] chunk = new byte[65536];
            int n = in.read(chunk);
            if (n < 0) throw new IOException("станция закрыла соединение");
            byte[] nb = new byte[buf.length + n];
            System.arraycopy(buf, 0, nb, 0, buf.length);
            System.arraycopy(chunk, 0, nb, buf.length, n);
            buf = nb;
        }
    }

    private static int indexOf(byte[] a, String s) {
        byte[] p = s.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + p.length <= a.length; i++) {
            for (int j = 0; j < p.length; j++) if (a[i+j] != p[j]) continue outer;
            return i;
        }
        return -1;
    }

    private Frame parseFrame(String text) {
        int nl = text.indexOf('\n');
        String header = nl < 0 ? text : text.substring(0, nl);
        String[] h = header.split(" ");
        String kind = h.length > 1 ? h[1] : "";
        String cmd = h.length > 5 ? (h[4] + " " + join(h, 5)) : "";
        int b1 = text.indexOf('{');
        int b2 = text.lastIndexOf('}');
        String body = (b1 >= 0 && b2 > b1) ? text.substring(b1 + 1, b2) : "";
        return new Frame(kind, cmd, parseBody(body));
    }

    private static String join(String[] a, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < a.length; i++) { if (i>from) sb.append(' '); sb.append(a[i]); }
        return sb.toString();
    }

    /**
     * Разбор тела кадра. Поля вида key=type:value.
     * Для b:/o: значение может быть length-prefixed: b:484[....] — берём ровно 484 символа.
     * При повторах ключей (как ord в unsub) остаётся последний — для наших задач хватает.
     */
    private static Map<String,String> parseBody(String body) {
        Map<String,String> f = new LinkedHashMap<>();
        int i = 0, n = body.length();
        while (i < n) {
            while (i < n && (body.charAt(i)==' '||body.charAt(i)=='\t'||body.charAt(i)=='\r'||body.charAt(i)=='\n')) i++;
            if (i >= n) break;
            int eq = body.indexOf('=', i);
            int lineEnd = body.indexOf('\n', i); if (lineEnd < 0) lineEnd = n;
            if (eq < 0 || eq > lineEnd) { i = lineEnd + 1; continue; }
            String key = body.substring(i, eq).trim();
            String seg = body.substring(eq + 1, lineEnd);        // type:rest
            int colon = seg.indexOf(':');
            String type = colon >= 0 ? seg.substring(0, colon) : seg;
            String rest = colon >= 0 ? seg.substring(colon + 1) : "";
            int bracket = rest.indexOf('[');
            Matcher m = bracket >= 0 ? LEN_BEFORE_BRACKET.matcher(rest.substring(0, bracket)) : null;
            if (bracket >= 0 && m.find()) {
                int len = Integer.parseInt(m.group(1));
                int payloadStart = eq + 1 + colon + 1 + bracket + 1;
                int payloadEnd = Math.min(payloadStart + len, n);
                String payload = body.substring(payloadStart, payloadEnd);
                f.put(key, payload);
                i = payloadEnd;
                if (i < n && body.charAt(i) == ']') i++;
            } else {
                f.put(key, rest);
                i = lineEnd + 1;
            }
        }
        return f;
    }
    private static final Pattern LEN_BEFORE_BRACKET = Pattern.compile("(\\d+)\\s*$");

    // ============================================================ строки Fox

    /** #43e; -> символ U+043E. */
    public static String foxDecode(String s) {
        Matcher m = Pattern.compile("#([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(
                new String(Character.toChars(Integer.parseInt(m.group(1), 16)))));
        m.appendTail(sb);
        return sb.toString();
    }

    /** baja$3aServiceContainer -> baja:ServiceContainer ; $u0410 -> А. */
    public static String slotDecode(String s) {
        Matcher m = Pattern.compile("\\$u([0-9a-fA-F]{4})").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        m.appendTail(sb);
        m = Pattern.compile("\\$([0-9a-fA-F]{2})").matcher(sb.toString());
        StringBuffer sb2 = new StringBuffer();
        while (m.find()) m.appendReplacement(sb2, Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        m.appendTail(sb2);
        return sb2.toString();
    }

    // ============================================================ hello/login

    public Map<String,String> hello() throws IOException {
        send("a", ++msgNum, "-1", "fox hello",
            "fox.version=s:1.0.1\nid=i:1\nhostName=s:pyclient\nhostAddress=s:0.0.0.0\n" +
            "app.name=s:mcsfox\napp.version=s:0.1\nvm.name=s:Android\nvm.version=s:1\n" +
            "os.name=s:Android\nos.version=s:1\nlang=s:ru\n");
        Frame f = readFrame();                         // station hello
        Map<String,String> info = new LinkedHashMap<>();
        for (Map.Entry<String,String> e : f.fields.entrySet())
            info.put(e.getKey(), foxDecode(e.getValue()));
        return info;
    }

    /** SCRAM-SHA-256, как в дампе. Бросает SecurityException при отказе. */
    public void login(String user, String password) throws IOException {
        Frame ch = readFrame();                        // fox challenge {method=digest}
        if (!ch.cmd.contains("challenge")) throw new IOException("нет challenge: " + ch.cmd);

        String cNonce = b64(randomBytes(16));
        String clientFirstBare = "n=" + user + ",r=" + cNonce;
        send("a", ++msgNum, "-1", "fox authMessage1",
            "authInput=s:authInputScram\nauthHandshake1=s:n,," + clientFirstBare + "\n");

        Frame sf = readFrame();                         // r=..,s=..,i=..
        String serverFirst = sf.fields.get("authHandshake1");
        if (serverFirst == null) throw new IOException("нет authHandshake1");
        if (serverFirst.startsWith("s:")) serverFirst = serverFirst.substring(2);
        Map<String,String> d = kv(serverFirst);
        String sNonce = d.get("r");
        byte[] salt = b64d(d.get("s"));
        int iters = Integer.parseInt(d.get("i"));

        byte[] saltedPassword = pbkdf2(password.toCharArray(), salt, iters, 32);
        byte[] clientKey = hmac(saltedPassword, "Client Key");
        byte[] storedKey = sha256(clientKey);
        String clientFinalNoProof = "c=biws,r=" + sNonce;
        String authMessage = clientFirstBare + "," + serverFirst + "," + clientFinalNoProof;
        byte[] clientSig = hmac(storedKey, authMessage);
        byte[] proof = new byte[clientKey.length];
        for (int i = 0; i < proof.length; i++) proof[i] = (byte)(clientKey[i] ^ clientSig[i]);

        send("a", ++msgNum, "-1", "fox authMessage2",
            "authHandshake2=s:" + clientFinalNoProof + ",p=" + b64(proof) + "\n");

        Frame r = readFrame();
        if (r.cmd.contains("rejected"))
            throw new SecurityException("станция отклонила логin (неверный пользователь/пароль)");
        // server final (v=..) затем welcome
        if (r.cmd.contains("authMessage2")) r = readFrame();
        if (!r.cmd.contains("welcome"))
            throw new SecurityException("нет welcome: " + r.cmd);

        // инициализация сессии (как Workbench)
        send("s", ++msgNum, "0", "user fetchPrefs", "");
        send("s", ++msgNum, "0", "sys makeBrokerChannel", "ord=s:station:\n");
        send("s", ++msgNum, "0", "station loadRoot", "");
        for (int k = 0; k < 8; k++) {
            Frame g = readFrame();
            if (g.cmd.contains("loadRoot") && g.fields.containsKey("handle")) {
                rootHandle = strip(g.fields.get("handle"));
                break;
            }
        }
    }

    // ============================================================ дерево

    /** Загрузить один уровень детей узла (station load depth=0). */
    public String loadNode(String handle) throws IOException {
        return loadTree(handle, 0, false);
    }

    /**
     * Загрузить поддерево узла на глубину depth одним запросом.
     * useSub=true -> station sub (так Workbench тянет большие поддеревья за один раз),
     * иначе station load. Склеивает все кадры circuit stream до закрытия цепи.
     */
    public String loadTree(String handle, int depth, boolean useSub) throws IOException {
        String cmd = useSub ? "station sub" : "station load";
        send("s", ++msgNum, "0", cmd, "ord=s:h:" + handle + "\ndepth=i:" + depth + "\n");
        for (int k = 0; k < 12; k++) {            // ack (n/r)
            Frame a = readFrame();
            if (a.cmd.contains(cmd)) break;
        }
        int cid = circuitId; circuitId += 2;
        send("a", ++msgNum, "-1", "circuit open",
            "id=i:" + cid + "\nchannel=s:station\ncommand=s:syncFromMaster\n");
        send("a", ++msgNum, "-1", "circuit stream", "id=i:" + cid + "\ndata=b:3[{\n}]\n");
        StringBuilder sync = new StringBuilder();
        for (int k = 0; k < 200000; k++) {        // большие поддеревья приходят многими кадрами
            Frame a = readFrame();
            String id = strip(a.fields.get("id"));
            if (a.cmd.contains("circuit stream") && String.valueOf(cid).equals(id)) {
                String data = a.fields.get("data");
                if (data != null) sync.append(data);
            }
            if (a.cmd.contains("circuit close") && String.valueOf(cid).equals(id)) break;
        }
        send("a", ++msgNum, "-1", "circuit close", "id=i:" + cid + "\n");
        if (useSub)                                // снять подписку, чтобы не шёл поток обновлений
            send("a", ++msgNum, "-1", "station unsub", "ord=s:h:" + handle + "\n");
        return sync.toString();
    }

    /** Разобрать детей из <sync>…</sync>, пропустив сам контейнер handle. */
    public List<Node> parseChildren(String sync, String containerHandle) {
        List<Node> out = new ArrayList<>();
        Matcher m = P_TAG.matcher(sync);
        boolean first = true;
        while (m.find()) {
            String attrs = m.group(1);
            String h  = attr(attrs, "h");
            String nm = attr(attrs, "n");
            String t  = attr(attrs, "t");
            String v  = attr(attrs, "v");
            boolean stub = "true".equals(attr(attrs, "stub"));
            if (first) { first = false; if (h != null && h.equals(containerHandle)) continue; }
            if (nm == null && h == null) continue;
            boolean expandable = h != null && stub;
            out.add(new Node(h, nm == null ? "?" : slotDecode(nm),
                             t, v == null ? null : foxDecode(v), expandable));
        }
        return out;
    }
    private static final Pattern P_TAG = Pattern.compile("<p\\s+([^>]*?)/?>");
    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    /** Рекурсивный обход дерева от корня на maxDepth уровней. */
    public Node discover(int maxDepth) throws IOException {
        Node root = new Node(rootHandle, "Station", "b:Station", null, true);
        walk(root, maxDepth);
        return root;
    }
    private void walk(Node node, int depthLeft) throws IOException {
        if (depthLeft <= 0 || node.handle == null) return;
        String sync = loadNode(node.handle);
        for (Node c : parseChildren(sync, node.handle)) {
            node.children.add(c);
            if (c.expandable) walk(c, depthLeft - 1);
        }
    }

    // ============================================================ точки

    public static class Point {
        public String path, name, kind, type, value, handle;
        public boolean writable;
    }

    /** numeric / boolean / enum — по имени типа компонента, иначе null (не точка). */
    public static String pointKind(String type) {
        if (type == null) return null;
        String t = type.toLowerCase();
        if (t.contains("numeric")) return "numeric";
        if (t.contains("boolean")) return "boolean";
        if (t.contains("enum"))    return "enum";
        return null;
    }
    public static boolean isWritable(String type) {
        return type != null && type.toLowerCase().contains("writable");
    }

    /** Обратная связь по ходу обхода (прогресс + возможность отмены). */
    public interface Progress {
        void scanned(int containers, int points);
        boolean cancelled();
    }

    // служебные ветки драйверов/сети — не точки, их не обходим (ускоряет на порядок)
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
        "health", "monitor", "alarmSourceInfo", "tuningPolicies", "historyPolicies",
        "virtualPolicies", "workers", "sysDefProvider", "foxService", "localStation",
        "serverConnections", "pollScheduler", "onDemandPollScheduler", "cachePolicy",
        "retryTrigger", "pollConfig", "discoveryPreferences"));

    private int maxPoints = 5000;

    public List<Point> discoverPoints(int maxDepth) throws IOException {
        return discoverPoints(maxDepth, null);
    }

    /**
     * Собрать точки numeric/boolean/enum из веток Drivers и Logic.
     * Каждую ветку тянем ОДНИМ запросом (station sub на глубину), дерево разбираем локально.
     */
    public List<Point> discoverPoints(int maxDepth, Progress pr) throws IOException {
        List<Point> out = new ArrayList<>();
        int depth = maxDepth + 4;                               // +запас, чтобы out/value точки попали в выборку
        String rootSync = loadNode(rootHandle);                 // 1 запрос — найти Drivers/Logic
        for (Node c : parseChildren(rootSync, rootHandle)) {
            if (c.handle == null) continue;
            if (!c.name.equals("Drivers") && !c.name.equals("Logic")) continue;
            if (pr != null && pr.cancelled()) break;
            String sub = loadTree(c.handle, depth, true);       // 1 запрос на всю ветку
            parseTreePoints(sub, c.name, out, pr);
        }
        return out;
    }

    /** Рекурсивный разбор вложенного <sync> поддерева, сбор точек с путями и значениями из out. */
    private void parseTreePoints(String sync, String baseName, List<Point> out, Progress pr) {
        Deque<Lvl> frames = new ArrayDeque<>();
        List<String> path = new ArrayList<>();
        int skipDepth = 0, containers = 0;
        Point cur = null;                              // точка, внутри которой ищем out/value
        Matcher m = TAG.matcher(sync);
        while (m.find()) {
            if (out.size() >= maxPoints) break;
            boolean closing = "/".equals(m.group(1));
            String tag = m.group(2);
            String attrs = m.group(3) == null ? "" : m.group(3);
            boolean self = "/".equals(m.group(4));

            if (tag.equals("l")) {
                if (closing) { if (!frames.isEmpty()) popFrame(frames, path); }
                else if (!self) frames.push(new Lvl(false, false, null));
                continue;
            }
            String n = attr(attrs, "n"), h = attr(attrs, "h"),
                   t = attr(attrs, "t"), v = attr(attrs, "v");
            String kind = pointKind(t);

            if (self) {                                // лист
                if (skipDepth > 0) captureValue(cur, n, v);          // out/value внутри точки
                else if (kind != null && h != null) out.add(point(path, n, h, t, v));
                continue;
            }
            if (closing) {                             // </p>
                Lvl f = frames.isEmpty() ? new Lvl(false,false,null) : frames.pop();
                if (f.pushedName && !path.isEmpty()) path.remove(path.size()-1);
                if (f.skip) skipDepth--;
                if (f.point != null) cur = null;
                continue;
            }
            // открывающий <p ...>
            if (skipDepth > 0) { captureValue(cur, n, v); frames.push(new Lvl(false, false, null)); continue; }
            if (kind != null && h != null) {           // точка — записать, значение добираем из out
                Point p = point(path, n, h, t, v); out.add(p);
                frames.push(new Lvl(false, true, p)); skipDepth++; cur = p;
            } else if (n != null && SKIP.contains(n)) {
                frames.push(new Lvl(false, true, null)); skipDepth++;
            } else {
                String name = (path.isEmpty() && n == null) ? baseName : (n != null ? slotDecode(n) : baseName);
                path.add(name); frames.push(new Lvl(true, false, null));
                containers++;
                if (pr != null && (containers % 20 == 0)) pr.scanned(containers, out.size());
            }
        }
        if (pr != null) pr.scanned(containers, out.size());
    }

    /** Значение точки лежит в out (а иногда в out>value). Берём его, out приоритетнее. */
    private static void captureValue(Point cur, String n, String v) {
        if (cur == null || v == null) return;
        if ("out".equals(n)) cur.value = foxDecode(v);
        else if ("value".equals(n) && cur.value == null) cur.value = foxDecode(v);
    }

    private static void popFrame(Deque<Lvl> frames, List<String> path) {
        Lvl f = frames.pop();
        if (f.pushedName && !path.isEmpty()) path.remove(path.size()-1);
    }

    private static Point point(List<String> path, String n, String h, String t, String v) {
        Point p = new Point();
        p.path = String.join(" / ", path);
        p.name = n != null ? slotDecode(n) : "?";
        p.kind = pointKind(t); p.type = t; p.handle = h;
        p.writable = isWritable(t);
        p.value = v != null ? foxDecode(v) : null;
        return p;
    }

    private static final Pattern TAG = Pattern.compile("<(/?)(p|l)\\b([^>]*?)(/?)>");

    static class Lvl {
        final boolean pushedName, skip;
        final Point point;
        Lvl(boolean pushedName, boolean skip, Point point){ this.pushedName=pushedName; this.skip=skip; this.point=point; }
    }

    /** Публичное чтение текущего значения точки (например, после set): грузим point depth 3, берём out. */
    public String readValue(String handle) throws IOException {
        String sync = loadTree(handle, 3, false);
        Matcher m = Pattern.compile("n=\"out\"[^>]*\\sv=\"([^\"]*)\"").matcher(sync);
        if (m.find()) return foxDecode(m.group(1));
        m = Pattern.compile("n=\"value\"[^>]*\\sv=\"([^\"]*)\"").matcher(sync);
        if (m.find()) return foxDecode(m.group(1));
        return loadValue(handle);
    }

    /** Лёгкий keepalive, чтобы станция не закрывала сессию по таймауту. */
    public void ping() throws IOException {
        send("s", ++msgNum, "0", "sys summary", "");
        for (int k = 0; k < 60; k++) {
            Frame f = readFrame();
            if (f.cmd.contains("sys summary")) return;
        }
    }

    /** Значение точки из слота out (запасной путь, если его не было в списке). */
    private String loadValue(String handle) throws IOException {
        String sync = loadNode(handle);
        for (Node c : parseChildren(sync, handle)) {
            if ("out".equals(c.name) && c.value != null) return c.value;
        }
        for (Node c : parseChildren(sync, handle)) {
            if (c.value != null) return c.value;
        }
        return null;
    }

    /** Запись значения точки через действие set (station invoke) в рамках текущей сессии. */
    public String setValue(String handle, String kind, String value) throws IOException {
        String t, v;
        switch (kind) {
            case "boolean": t = "b:Boolean"; v = value; break;         // true/false
            case "enum":    t = "b:DynamicEnum"; v = value; break;     // ordinal
            default:        t = "b:Double"; v = value; break;          // numeric
        }
        String bog = "<bog version=\"1.0\">\n<p m=\"b=baja\" t=\"" + t + "\" v=\"" + v + "\"/>\n</bog>\n";
        send("s", ++msgNum, "0", "station invoke",
            "ord=s:h:" + handle + "\naction=s:set\narg=o:bog " + bog.length() + "[" + bog + "]\n");
        for (int k = 0; k < 200; k++) {
            Frame f = readFrame();
            if (f.cmd.contains("station invoke")) {
                if (f.kind.equals("e")) throw new IOException("set отклонён станцией");
                return "ok";
            }
        }
        return "нет ответа на set";
    }

    // ============================================================ crypto утилиты

    private static byte[] pbkdf2(char[] pw, byte[] salt, int iters, int len) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(pw, salt, iters, len * 8)).getEncoded();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static byte[] hmac(byte[] key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n]; new java.security.SecureRandom().nextBytes(b); return b;
    }
    private static String b64(byte[] b){ return Base64.getEncoder().encodeToString(b); }
    private static byte[] b64d(String s){ return Base64.getDecoder().decode(s); }
    private static String strip(String v){ // убрать префикс типа s:/i:/z:
        if (v == null) return null;
        int c = v.indexOf(':'); return c >= 0 && c <= 2 ? v.substring(c+1) : v;
    }
    private static Map<String,String> kv(String s){
        Map<String,String> m = new LinkedHashMap<>();
        for (String part : s.split(",")) { int e = part.indexOf('='); if (e>0) m.put(part.substring(0,e), part.substring(e+1)); }
        return m;
    }
}
