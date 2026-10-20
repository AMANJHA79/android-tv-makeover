package com.kulwant.atvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone control page: a tiny HTTP server on the home network (port 8080).
 *
 * A phone pairs once: it asks for a code, the TV shows a 6-digit code on screen, the phone sends it
 * back and gets a token it keeps. Every other call needs that token (header X-Token or ?t=), so
 * guests on the Wi-Fi can't use it. GET works for every action so Siri Shortcuts can use plain URLs.
 */
final class PhoneServer {

    static final int PORT = 8080;
    private static final int MAX_BODY = 12 * 1024 * 1024;

    private final Context c;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final SecureRandom rnd = new SecureRandom();
    private volatile ServerSocket server;
    private final KeyInjector keys;
    private volatile boolean connecting;

    // pairing: one 6-digit code at a time; wrong guesses count across codes, and too many lock
    // pairing for 10 minutes, doubling each time, so the code can't be brute-forced from the network
    private String pairCode;
    private long pairUntil;
    private int failures;
    private long lockedUntil;
    private long lockMs = 10 * 60_000L;

    // apps (with banners) are cached briefly: rendering them is the slow part
    private AppRepo.Result apps;
    private long appsAt;
    private final Map<String, byte[]> bannerPng = new HashMap<>();

    PhoneServer(Context c) {
        this.c = c.getApplicationContext();
        prefs = this.c.getSharedPreferences("phone", Context.MODE_PRIVATE);
        keys = new KeyInjector(this.c);
    }

    void start() {
        Thread t = new Thread(this::acceptLoop, "phone-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (true) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress((InetAddress) null, PORT));
                server = ss;
                while (!ss.isClosed()) {
                    Socket s = ss.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (Exception e) {
                if (pool.isShutdown()) return;
                try {
                    Thread.sleep(5000);   // port busy or network changed: try again
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static final class Req {
        String method, path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            Req r = read(in);
            if (r == null) return;
            route(r, out);
        } catch (Exception ignored) {
        }
    }

    private static Req read(InputStream in) throws Exception {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Req r = new Req();
        r.method = parts[0];
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) {
            for (String kv : target.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) r.query.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
                else if (!kv.isEmpty()) r.query.put(dec(kv), "");
            }
        }
        String h;
        while ((h = readLine(in)) != null && !h.isEmpty()) {
            int colon = h.indexOf(':');
            if (colon > 0) r.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.US), h.substring(colon + 1).trim());
        }
        int len = 0;
        try {
            len = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
        }
        if (len < 0 || len > MAX_BODY) return null;
        r.body = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(r.body, off, len - off);
            if (n < 0) return null;
            off += n;
        }
        return r;
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) return null;
        }
        return b < 0 && sb.length() == 0 ? null : sb.toString();
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String extra) throws Exception {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: " + (extra != null ? extra : "no-store") + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void json(OutputStream out, int code, JSONObject o) throws Exception {
        send(out, code, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("ok", true);
    }

    private static JSONObject err(String msg) throws Exception {
        return new JSONObject().put("ok", false).put("error", msg);
    }

    // ---------------------------------------------------------------- routes

    private void route(Req r, OutputStream out) throws Exception {
        // only answer requests addressed to an IP, never to a hostname: stops a web page on some
        // other site from reaching this server through the phone's browser (DNS rebinding)
        String host = r.headers.getOrDefault("host", "");
        if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?")) {
            send(out, 403, "text/plain", "use the TV's IP address".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        switch (r.path) {
            case "/":
            case "/index.html":
                send(out, 200, "text/html; charset=utf-8", asset("phone.html"), null);
                return;
            case "/icon.png":
            case "/favicon.ico":
                send(out, 200, "image/png", icon(), "max-age=86400");
                return;
            case "/api/pair":
                json(out, 200, startPairing());
                return;
            case "/api/pair/confirm":
                json(out, 200, confirmPairing(param(r, "code")));
                return;
        }
        if (!authorised(r)) {
            json(out, 401, err("not paired"));
            return;
        }
        switch (r.path) {
            case "/api/unpair":
                unpair(r);
                json(out, 200, ok());
                return;
            case "/api/state":
                json(out, 200, state());
                return;
            case "/api/open":
                json(out, 200, open(param(r, "app")));
                return;
            case "/api/home":
                runOnMain(() -> c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(c.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                json(out, 200, ok());
                return;
            case "/api/input":
                json(out, 200, input(param(r, "name")));
                return;
            case "/api/remote":
                json(out, 200, remoteStatus());
                return;
            case "/api/key":
                try {
                    int n = 1;
                    try {
                        n = Integer.parseInt(param(r, "n"));
                    } catch (NumberFormatException ignored) {
                    }
                    keys.press(param(r, "k"), n);
                    json(out, 200, ok());
                } catch (Exception e) {
                    json(out, 200, err(e.getMessage()).put("remote", remoteStatus()));
                }
                return;
            case "/api/text":
                try {
                    String text = r.query.containsKey("text") ? r.query.get("text") : param(r, "text");
                    keys.type(text);
                    json(out, 200, ok());
                } catch (Exception e) {
                    json(out, 200, err(e.getMessage()).put("remote", remoteStatus()));
                }
                return;
            case "/api/youtube":
                json(out, 200, youtube(param(r, "url")));
                return;
            case "/api/banner":
                byte[] png = banner(param(r, "pkg"));
                if (png == null) send(out, 404, "text/plain", new byte[0], null);
                else send(out, 200, "image/png", png, "max-age=86400");
                return;
            case "/api/photo":
                if ("POST".equals(r.method)) {
                    File f = Wallpapers.add(c, r.body);
                    json(out, 200, f == null ? err("not an image, or the album is full")
                            : ok().put("id", f.getName().replace(".jpg", "")));
                } else {
                    photo(r, out);
                }
                return;
            case "/api/photo/replace":
                File rf = Wallpapers.replace(c, param(r, "id"), r.body);
                json(out, 200, rf == null ? err("photo not found, or not an image") : ok());
                return;
            case "/api/photo/original":
                json(out, 200, Wallpapers.setOriginal(c, param(r, "id"), r.body) ? ok() : err("photo not found"));
                return;
            case "/api/photo/show":
                Wallpapers.show(c, param(r, "id"));
                json(out, 200, ok());
                return;
            case "/api/photo/delete":
                json(out, 200, Wallpapers.delete(c, param(r, "id")) ? ok() : err("not found"));
                return;
            default:
                send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), null);
        }
    }

    private static String param(Req r, String name) {
        String v = r.query.get(name);
        if (v == null && r.body.length > 0 && r.body.length < 4096) {
            try {
                JSONObject o = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
                v = o.optString(name, null);
            } catch (Exception ignored) {
            }
        }
        return v == null ? "" : v.trim();
    }

    // ---------------------------------------------------------------- pairing

    private boolean authorised(Req r) {
        String t = r.headers.get("x-token");
        if (t == null) t = r.query.get("t");
        return t != null && !t.isEmpty() && prefs.getStringSet("tokens", new HashSet<>()).contains(t);
    }

    /** Revokes the calling phone's token, so "Unpair this phone" really cuts it off. */
    private synchronized void unpair(Req r) {
        String t = r.headers.get("x-token");
        if (t == null) t = r.query.get("t");
        Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
        if (tokens.remove(t)) prefs.edit().putStringSet("tokens", tokens).apply();
    }

    private synchronized JSONObject startPairing() throws Exception {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return err(lockMessage(now));
        if (pairCode == null || now > pairUntil) {
            pairCode = String.format(Locale.US, "%06d", rnd.nextInt(1_000_000));
            pairUntil = now + 300_000;
        }
        String code = pairCode;
        runOnMain(() -> {
            // a toast shows over whatever app is open; repeat it so it stays up ~10 s
            for (int i = 0; i < 3; i++) {
                main.postDelayed(() -> Toast.makeText(c, "Phone pairing code:  " + code, Toast.LENGTH_LONG).show(), i * 3600L);
            }
        });
        return ok();
    }

    private synchronized JSONObject confirmPairing(String code) throws Exception {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return err(lockMessage(now));
        if (pairCode == null || now > pairUntil) return err("code expired, ask for a new one");
        if (!pairCode.equals(code)) {
            if (++failures >= 5) {
                failures = 0;
                pairCode = null;
                lockedUntil = now + lockMs;
                lockMs = Math.min(lockMs * 2, 24 * 3_600_000L);
                return err(lockMessage(now));
            }
            return err("wrong code");
        }
        pairCode = null;
        failures = 0;
        lockMs = 10 * 60_000L;
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        String token = sb.toString();
        Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
        tokens.add(token);
        prefs.edit().putStringSet("tokens", tokens).apply();
        runOnMain(() -> Toast.makeText(c, "Phone paired", Toast.LENGTH_SHORT).show());
        return ok().put("token", token);
    }

    private String lockMessage(long now) {
        long min = (lockedUntil - now + 59_999) / 60_000;
        return "too many wrong codes: try again in " + min + " min";
    }

    // ---------------------------------------------------------------- remote (trackpad, keyboard)

    /** Ready, or starts connecting in the background (the first time the TV asks to allow it). */
    private JSONObject remoteStatus() throws Exception {
        if (keys.isConnected()) return ok().put("ready", true).put("layout", keys.describe());
        if (!connecting) {
            connecting = true;
            Thread t = new Thread(() -> {
                keys.ready();
                connecting = false;
            }, "adb-connect");
            t.setDaemon(true);
            t.start();
        }
        String e = keys.lastError();
        return ok().put("ready", false)
                .put("message", e != null ? e : "Connecting… if the TV asks \"Allow USB debugging?\", tick Always allow and press OK");
    }

    // ---------------------------------------------------------------- actions

    private synchronized AppRepo.Result apps() {
        if (apps == null || System.currentTimeMillis() - appsAt > 60_000) {
            apps = AppRepo.load(c);
            appsAt = System.currentTimeMillis();
            bannerPng.clear();
        }
        return apps;
    }

    private JSONObject state() throws Exception {
        JSONObject o = ok();
        AppRepo.Result r = apps();
        JSONArray a = new JSONArray();
        for (AppRepo.App app : r.dock) a.put(new JSONObject().put("pkg", app.pkg).put("label", app.label).put("dock", true));
        for (AppRepo.App app : r.grid) a.put(new JSONObject().put("pkg", app.pkg).put("label", app.label));
        o.put("apps", a);
        JSONArray in = new JSONArray();
        for (InputsMenu.Item it : InputsMenu.list(c)) in.put(new JSONObject().put("label", it.label).put("connected", it.connected));
        o.put("inputs", in);
        JSONArray ph = new JSONArray();
        for (File f : Wallpapers.userPhotos(c)) ph.put(new JSONObject().put("id", f.getName().replace(".jpg", "")));
        o.put("photos", ph);
        o.put("maxPhotos", Wallpapers.MAX_PHOTOS);
        return o;
    }

    /** Matches a package name, or any part of an app's name ("netflix", "prime", "live tv"). */
    private AppRepo.App findApp(String q) {
        String needle = q.toLowerCase(Locale.US).replace(" ", "");
        if (needle.isEmpty()) return null;
        AppRepo.Result r = apps();
        AppRepo.App best = null;
        for (List<AppRepo.App> list : java.util.Arrays.asList(r.dock, r.grid)) {
            for (AppRepo.App a : list) {
                if (a.pkg.equalsIgnoreCase(q)) return a;
                String label = a.label.toLowerCase(Locale.US).replace(" ", "");
                if (label.equals(needle)) return a;
                if (best == null && (label.contains(needle) || a.pkg.toLowerCase(Locale.US).contains(needle))) best = a;
            }
        }
        return best;
    }

    private JSONObject open(String q) throws Exception {
        AppRepo.App a = findApp(q);
        if (a == null) return err("no app called " + q);
        Intent i = AppRepo.launchIntent(c.getPackageManager(), a);
        runOnMain(() -> c.startActivity(i));
        return ok().put("opened", a.label);
    }

    private JSONObject input(String q) throws Exception {
        String needle = q.toLowerCase(Locale.US).replace(" ", "");
        for (InputsMenu.Item it : InputsMenu.list(c)) {
            if (it.label.toLowerCase(Locale.US).replace(" ", "").equals(needle) && it.intent != null) {
                runOnMain(() -> c.startActivity(it.intent));
                return ok().put("opened", it.label);
            }
        }
        return err("no input called " + q);
    }

    /** Plays a YouTube link (watch, youtu.be, shorts) in the TV's YouTube app. */
    private JSONObject youtube(String url) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})").matcher(url);
        if (!m.find()) return err("not a YouTube link");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + m.group(1)))
                .setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        runOnMain(() -> c.startActivity(i));
        return ok();
    }

    private void photo(Req r, OutputStream out) throws Exception {
        String id = param(r, "id");
        File f = "1".equals(param(r, "orig")) ? Wallpapers.original(c, id) : Wallpapers.photo(c, id);
        if (f == null) {
            send(out, 404, "text/plain", new byte[0], null);
            return;
        }
        byte[] data;
        try (InputStream in = new FileInputStream(f)) {
            data = readAll(in);
        }
        // ids stay the same when a photo is re-framed, so don't let the phone cache it
        send(out, 200, "image/jpeg", data, null);
    }

    private synchronized byte[] banner(String pkg) {
        byte[] cached = bannerPng.get(pkg);
        if (cached != null) return cached;
        AppRepo.Result r = apps();
        for (List<AppRepo.App> list : java.util.Arrays.asList(r.dock, r.grid)) {
            for (AppRepo.App a : list) {
                if (a.pkg.equals(pkg) && a.banner != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    a.banner.compress(Bitmap.CompressFormat.PNG, 100, bo);
                    bannerPng.put(pkg, bo.toByteArray());
                    return bo.toByteArray();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private void runOnMain(Runnable r) {
        main.post(() -> {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        });
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** Home-screen icon for "Add to Home Screen" on the iPhone: a white TV on black. */
    private static byte[] icon() {
        int s = 180;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(10);
        cv.drawRoundRect(new RectF(34, 46, 146, 116), 14, 14, p);
        p.setStrokeCap(Paint.Cap.ROUND);
        cv.drawLine(70, 140, 110, 140, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFF3B30);
        cv.drawCircle(90, 81, 12, p);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return bo.toByteArray();
    }
}
