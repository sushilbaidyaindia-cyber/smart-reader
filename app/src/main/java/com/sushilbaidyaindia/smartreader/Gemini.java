package com.sushilbaidyaindia.smartreader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Reads the text of a page picture with Google's Gemini. The key is the person's own and is typed in the settings. */
final class Gemini {
    private Gemini() { }

    private static final String PROMPT = "You are a document OCR engine. Output the COMPLETE plain text of this entire page only. "
            + "Reading order: top to bottom, left to right. Keep line breaks. Do not summarize. Do not add labels or commentary. "
            + "Bengali and other scripts must stay exact.";

    private static int rot = 0;

    /** the settings may hold several keys (one per line); each call takes the next one, so a spent key is skipped by the retry */
    static synchronized String pick(String raw) {
        if (raw == null) return "";
        String[] parts = raw.trim().split("[\\s,;]+");
        java.util.ArrayList<String> ks = new java.util.ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) ks.add(p);
        if (ks.isEmpty()) return "";
        return ks.get((rot++ & 0x7fffffff) % ks.size());
    }

    static int keyCount(String raw) {
        if (raw == null) return 0;
        int n = 0;
        for (String p : raw.trim().split("[\\s,;]+")) if (!p.isEmpty()) n++;
        return n;
    }

    static final class Failure extends Exception {
        final boolean retryable;
        /** true when this model itself is the problem (closed, renamed, not for this key): try the next model */
        boolean modelProblem = false;
        Failure(String m, boolean retryable) {
            super(m);
            this.retryable = retryable;
        }
    }

    // ------------------------------------------------------------------ models
    /** the models Google lists as current (Stable) for reading pictures and documents */
    static final String DEFAULT_M1 = "gemini-3.8-flash";
    static final String DEFAULT_M2 = "gemini-3.5-flash-lite";
    static final String[] CURRENT = {"gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-4-argon"};
    static volatile String lastGood = null;

    /** an old or empty saved model name becomes the default, so a closed model never blocks reading */
    static String cleanModel(String m, String dflt) {
        if (m == null) return dflt;
        m = m.trim();
        if (m.isEmpty() || m.contains("gemini-2.") || m.contains("gemini-1.") || m.contains("gemini-3-pro") || m.contains("preview")) return dflt;
        return m;
    }

    /** the order to try: the model that worked last time, the person's two choices, then the current ones */
    static String[] chain(String m1, String m2) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        String g = lastGood;
        if (g != null) set.add(g);
        set.add(cleanModel(m1, DEFAULT_M1));
        set.add(cleanModel(m2, DEFAULT_M2));
        for (String c : CURRENT) set.add(c);
        return set.toArray(new String[0]);
    }

    static boolean isModelProblem(int code, String msg) {
        String m = msg == null ? "" : msg.toLowerCase();
        return code == 404
                || m.contains("no longer available") || m.contains("is not found") || m.contains("not supported for generatecontent")
                || m.contains("has been shut down") || m.contains("deprecated")
                || (code == 400 && m.contains("model"));
    }

    /** One call to one model. Returns the page text (may be empty). */
    static String ocr(String key, String model, String jpegBase64) throws Failure {
        key = pick(key);
        try {
            JSONObject part1 = new JSONObject().put("text", PROMPT);
            JSONObject inline = new JSONObject().put("mime_type", "image/jpeg").put("data", jpegBase64);
            JSONObject part2 = new JSONObject().put("inline_data", inline);
            JSONObject content = new JSONObject().put("parts", new JSONArray().put(part1).put(part2));
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(content))
                    .put("generationConfig", new JSONObject().put("temperature", 0.0).put("maxOutputTokens", 8192));

            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model.trim() + ":generateContent");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(30000);
            c.setReadTimeout(120000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key.trim());
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.close();

            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            String resp = is == null ? "" : readAll(is);
            c.disconnect();

            if (code == 200) {
                JSONObject o = new JSONObject(resp);
                JSONArray cands = o.optJSONArray("candidates");
                if (cands == null || cands.length() == 0) return "";
                JSONObject cont = cands.getJSONObject(0).optJSONObject("content");
                if (cont == null) return "";
                JSONArray parts = cont.optJSONArray("parts");
                if (parts == null) return "";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
                return sb.toString();
            }
            String msg = "HTTP " + code;
            try {
                JSONObject e = new JSONObject(resp).optJSONObject("error");
                if (e != null && e.optString("message").length() > 0) msg = e.optString("message");
            } catch (Exception ignored) { }
            boolean retry = code == 429 || code >= 500 || code == 408;
            if (code == 400 && msg.toLowerCase().contains("api key")) msg = "API কী ঠিক নয়: " + msg;
            boolean mp = isModelProblem(code, msg);
            if (code == 403) msg = "API কী কাজ করছে না বা অনুমতি নেই: " + msg;
            Failure fl = new Failure(msg, retry);
            fl.modelProblem = mp && code != 403;
            throw fl;
        } catch (Failure f) {
            throw f;
        } catch (java.io.IOException e) {
            throw new Failure("ইন্টারনেট সংযোগের সমস্যা: " + e.getMessage(), true);
        } catch (Exception e) {
            throw new Failure("OCR ত্রুটি: " + e.getMessage(), false);
        }
    }

    private static String readAll(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Strips a ```code fence``` the model sometimes wraps around the text. */
    static String clean(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }
}
