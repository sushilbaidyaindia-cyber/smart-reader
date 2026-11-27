package com.sushilbaidyaindia.smartreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Reads the words in a picture, describes a picture, or describes the scenes of a video, with Gemini. */
final class MediaReader {
    private MediaReader() { }

    private static final String BASE = "https://generativelanguage.googleapis.com/";

    static final int IMG_TEXT = 0, IMG_DESC = 1, IMG_BOTH = 2, VID_DESC = 3, VID_BOTH = 4;

    private static String prompt(int mode) {
        switch (mode) {
            case IMG_TEXT:
                return "You are an OCR engine. Output the COMPLETE plain text visible in this image only. Reading order: top to bottom, left to right. "
                        + "Keep line breaks. Do not summarize or add commentary. Bengali and other scripts must stay exact. "
                        + "If there is no text at all, write exactly: (ছবিতে কোনো লেখা নেই)";
            case IMG_DESC:
                return "Describe this image in detail for a blind person: the people, objects, colours, place, actions and mood. "
                        + "Mention any visible text briefly. Write the whole answer in simple Bengali.";
            case IMG_BOTH:
                return "Do two things and write in Bengali. First a heading line 'ছবির বর্ণনা:' and then a detailed description of the image for a blind person "
                        + "(people, objects, colours, place, actions). Then a heading line 'ছবির লেখা:' and then the COMPLETE exact text visible in the image, "
                        + "keeping its own language and line breaks; if there is none, write (ছবিতে কোনো লেখা নেই).";
            case VID_DESC:
                return "Describe this video scene by scene for a blind person, in time order. Start each scene with its approximate time like 'মিনিট ০:১২ —'. "
                        + "Say who or what is seen, what happens, the place, and any text on screen. Write the whole answer in simple Bengali.";
            default:
                return "Do two things and write in Bengali. First a heading line 'দৃশ্যের বর্ণনা:' and then a scene-by-scene description in time order "
                        + "(start each scene with its approximate time like 'মিনিট ০:১২ —'; who or what is seen, what happens, the place, text on screen). "
                        + "Then a heading line 'কথাবার্তা:' and then a faithful summary or transcript of what is spoken, in Bengali; if nothing is spoken, say so.";
        }
    }

    /** image: returns the text to keep in the library */
    static String image(Context c, Uri uri, int mode, String key, String m1, String m2) throws Gemini.Failure {
        String k = Gemini.pick(key);
        if (k.isEmpty()) throw new Gemini.Failure("সেটিংসে Gemini API কী দেওয়া নেই", false);
        String b64;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("ছবি খোলা যায়নি");
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            int big = Math.max(o.outWidth, o.outHeight);
            int sample = 1;
            while (big / sample > 2600) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            in = c.getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (bmp == null) throw new Exception("এটা ছবির ফাইল নয় বা পড়া যায়নি");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos);
            bmp.recycle();
            b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new Gemini.Failure(String.valueOf(e.getMessage()), false);
        }
        try {
            JSONArray parts = new JSONArray()
                    .put(new JSONObject().put("text", prompt(mode)))
                    .put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "image/jpeg").put("data", b64)));
            return generate(key, m1, m2, parts, 120000);
        } catch (org.json.JSONException e) {
            throw new Gemini.Failure(String.valueOf(e.getMessage()), false);
        }
    }

    /** video: uploads it to Google's temporary file store, then asks for the scenes */
    static String video(Context c, Uri uri, int mode, String key, String m1, String m2, String[] progress) throws Gemini.Failure {
        String k = Gemini.pick(key);               // the uploaded file belongs to this one key, so use it all the way
        if (k.isEmpty()) throw new Gemini.Failure("সেটিংসে Gemini API কী দেওয়া নেই", false);
        File f = new File(c.getCacheDir(), "video_" + System.currentTimeMillis());
        String fileName = null;
        try {
            progress[0] = "ভিডিও ফোনে কপি হচ্ছে…";
            String mime = c.getContentResolver().getType(uri);
            if (mime == null || !mime.startsWith("video/")) mime = "video/mp4";
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) throw new Gemini.Failure("ভিডিও খোলা যায়নি", false);
            OutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 300L * 1024 * 1024) {
                    out.close();
                    in.close();
                    throw new Gemini.Failure("ভিডিও খুব বড় (৩০০ এমবির বেশি)", false);
                }
            }
            out.close();
            in.close();

            progress[0] = "ভিডিও Gemini-তে পাঠানো হচ্ছে (বড় হলে সময় লাগবে)…";
            JSONObject up = upload(k, f, mime);
            fileName = up.optString("name");
            String furi = up.optString("uri");
            String state = up.optString("state", "");
            long until = System.currentTimeMillis() + 6 * 60 * 1000L;
            while (!"ACTIVE".equals(state)) {
                if ("FAILED".equals(state)) throw new Gemini.Failure("Gemini ভিডিওটা প্রসেস করতে পারেনি", false);
                if (System.currentTimeMillis() > until) throw new Gemini.Failure("ভিডিও প্রসেস হতে বেশি সময় লাগছে, পরে চেষ্টা করুন", true);
                progress[0] = "Gemini ভিডিও প্রস্তুত করছে…";
                try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                JSONObject g = new JSONObject(get(BASE + "v1beta/" + fileName, k));
                state = g.optString("state", "");
            }
            progress[0] = "দৃশ্য বোঝা হচ্ছে…";
            JSONArray parts = new JSONArray()
                    .put(new JSONObject().put("file_data", new JSONObject().put("mime_type", mime).put("file_uri", furi)))
                    .put(new JSONObject().put("text", prompt(mode)));
            return generate(k, m1, m2, parts, 300000);
        } catch (Gemini.Failure e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new Gemini.Failure("ইন্টারনেট সংযোগের সমস্যা: " + e.getMessage(), true);
        } catch (Exception e) {
            throw new Gemini.Failure("ভিডিও ত্রুটি: " + e.getMessage(), false);
        } finally {
            try { f.delete(); } catch (Throwable ignored) { }
            if (fileName != null) {
                try {
                    HttpURLConnection d = (HttpURLConnection) new URL(BASE + "v1beta/" + fileName).openConnection();
                    d.setRequestMethod("DELETE");
                    d.setRequestProperty("x-goog-api-key", k);
                    d.setConnectTimeout(10000);
                    d.setReadTimeout(10000);
                    d.getResponseCode();
                    d.disconnect();
                } catch (Throwable ignored) { }
            }
        }
    }

    /** translates one stretch of text; the answer is only the translation */
    static String translate(String key, String m1, String m2, String text, String language) throws Gemini.Failure {
        try {
            JSONArray parts = new JSONArray().put(new JSONObject().put("text",
                    "Translate the following text into " + language + ". Output ONLY the translation, keeping the paragraphs and line breaks. "
                            + "Do not add notes or explanations.\n\n" + text));
            return generate(key, m1, m2, parts, 120000);
        } catch (org.json.JSONException e) {
            throw new Gemini.Failure(String.valueOf(e.getMessage()), false);
        }
    }

    // ------------------------------------------------------------------ plumbing
    private static String generate(String key, String m1, String m2, JSONArray parts, int timeout) throws Gemini.Failure {
        String[] models = m2 == null || m2.trim().isEmpty() || m2.trim().equals(m1.trim()) ? new String[]{m1} : new String[]{m1, m2};
        Gemini.Failure last = null;
        for (String model : models) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    JSONObject body = new JSONObject()
                            .put("contents", new JSONArray().put(new JSONObject().put("parts", parts)))
                            .put("generationConfig", new JSONObject().put("temperature", 0.2).put("maxOutputTokens", 8192));
                    String resp = request("POST", BASE + "v1beta/models/" + model.trim() + ":generateContent", Gemini.pick(key), body.toString(), timeout);
                    JSONArray cands = new JSONObject(resp).optJSONArray("candidates");
                    StringBuilder sb = new StringBuilder();
                    if (cands != null && cands.length() > 0) {
                        JSONObject cont = cands.getJSONObject(0).optJSONObject("content");
                        JSONArray ps = cont == null ? null : cont.optJSONArray("parts");
                        if (ps != null) for (int i = 0; i < ps.length(); i++) sb.append(ps.getJSONObject(i).optString("text", ""));
                    }
                    String t = Splitter.tidy(Gemini.clean(sb.toString()));
                    if (t.isEmpty()) throw new Gemini.Failure("Gemini কিছু লেখেনি (ছবি বা ভিডিও ব্লক হয়ে থাকতে পারে)", false);
                    return t;
                } catch (Gemini.Failure f) {
                    last = f;
                    if (!f.retryable) break;
                    try { Thread.sleep(3000L * (attempt + 1)); } catch (InterruptedException ie) { throw f; }
                } catch (java.io.IOException e) {
                    last = new Gemini.Failure("ইন্টারনেট সংযোগের সমস্যা: " + e.getMessage(), true);
                    try { Thread.sleep(3000L * (attempt + 1)); } catch (InterruptedException ie) { throw last; }
                } catch (Exception e) {
                    last = new Gemini.Failure("ত্রুটি: " + e.getMessage(), false);
                    break;
                }
            }
        }
        throw last == null ? new Gemini.Failure("ব্যর্থ", true) : last;
    }

    private static String request(String method, String url, String key, String json, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(timeout);
        c.setRequestMethod(method);
        c.setRequestProperty("x-goog-api-key", key.trim());
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.close();
        }
        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        String resp = is == null ? "" : readAll(is);
        c.disconnect();
        if (code == 200) return resp;
        throw failure(code, resp);
    }

    private static String get(String url, String key) throws Exception {
        return request("GET", url, key, null, 30000);
    }

    private static Gemini.Failure failure(int code, String resp) {
        String msg = "HTTP " + code;
        try {
            JSONObject e = new JSONObject(resp).optJSONObject("error");
            if (e != null && e.optString("message").length() > 0) msg = e.optString("message");
        } catch (Exception ignored) { }
        boolean retry = code == 429 || code >= 500 || code == 408;
        if (code == 429) msg = "ব্যবহারের সীমা পেরিয়েছে (HTTP 429): " + msg;
        if (code == 400 && msg.toLowerCase().contains("api key")) msg = "API কী ঠিক নয়: " + msg;
        if (code == 403) msg = "API কী কাজ করছে না বা অনুমতি নেই: " + msg;
        if (code == 404) msg = "মডেল বা ফাইল পাওয়া যায়নি (HTTP 404): " + msg;
        return new Gemini.Failure(msg, retry);
    }

    /** Files API, resumable upload in one go */
    private static JSONObject upload(String key, File f, String mime) throws Exception {
        HttpURLConnection s = (HttpURLConnection) new URL(BASE + "upload/v1beta/files").openConnection();
        s.setConnectTimeout(30000);
        s.setReadTimeout(60000);
        s.setRequestMethod("POST");
        s.setDoOutput(true);
        s.setRequestProperty("x-goog-api-key", key);
        s.setRequestProperty("X-Goog-Upload-Protocol", "resumable");
        s.setRequestProperty("X-Goog-Upload-Command", "start");
        s.setRequestProperty("X-Goog-Upload-Header-Content-Length", String.valueOf(f.length()));
        s.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime);
        s.setRequestProperty("Content-Type", "application/json");
        byte[] meta = "{\"file\":{\"display_name\":\"smart-reader-video\"}}".getBytes(StandardCharsets.UTF_8);
        s.setFixedLengthStreamingMode(meta.length);
        OutputStream os = s.getOutputStream();
        os.write(meta);
        os.close();
        int code = s.getResponseCode();
        if (code != 200) {
            InputStream is = s.getErrorStream();
            String r = is == null ? "" : readAll(is);
            s.disconnect();
            throw failure(code, r);
        }
        String upUrl = s.getHeaderField("X-Goog-Upload-URL");
        s.disconnect();
        if (upUrl == null) throw new Gemini.Failure("Gemini আপলোডের ঠিকানা দেয়নি", true);

        HttpURLConnection u = (HttpURLConnection) new URL(upUrl).openConnection();
        u.setConnectTimeout(30000);
        u.setReadTimeout(600000);
        u.setRequestMethod("POST");
        u.setDoOutput(true);
        u.setRequestProperty("Content-Length", String.valueOf(f.length()));
        u.setRequestProperty("X-Goog-Upload-Offset", "0");
        u.setRequestProperty("X-Goog-Upload-Command", "upload, finalize");
        u.setFixedLengthStreamingMode(f.length());
        OutputStream uo = u.getOutputStream();
        FileInputStream fi = new FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = fi.read(buf)) > 0) uo.write(buf, 0, n);
        fi.close();
        uo.close();
        code = u.getResponseCode();
        InputStream is = code < 400 ? u.getInputStream() : u.getErrorStream();
        String r = is == null ? "" : readAll(is);
        u.disconnect();
        if (code != 200) throw failure(code, r);
        JSONObject o = new JSONObject(r);
        JSONObject file = o.optJSONObject("file");
        return file != null ? file : o;
    }

    private static String readAll(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
