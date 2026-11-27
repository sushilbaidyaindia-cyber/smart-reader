package com.sushilbaidyaindia.smartreader;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reading aloud with the Gemini voices of Google AI Studio (the Interactions API).
 * The key is the person's own and is typed in the settings. Nothing is stored anywhere but on the phone.
 */
final class GeminiTts {
    private GeminiTts() { }

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";

    static final String DEFAULT_MODEL = "gemini-3.8-flash-tts";
    static final String DEFAULT_VOICE = "Kore";

    static final String[] MODELS = {
            "gemini-3.8-flash-tts",
            "gemini-3.8-flash-lite-tts",
            "gemini-3.1-flash-tts-preview"
    };

    /** the 30 ready-made voices: name, character */
    static final String[][] VOICES = {
            {"Zephyr", "Bright"}, {"Puck", "Upbeat"}, {"Charon", "Informative"}, {"Kore", "Firm"},
            {"Fenrir", "Excitable"}, {"Leda", "Youthful"}, {"Orus", "Firm"}, {"Aoede", "Breezy"},
            {"Callirrhoe", "Easy-going"}, {"Autonoe", "Bright"}, {"Enceladus", "Breathy"}, {"Iapetus", "Clear"},
            {"Umbriel", "Easy-going"}, {"Algieba", "Smooth"}, {"Despina", "Smooth"}, {"Erinome", "Clear"},
            {"Algenib", "Gravelly"}, {"Rasalgethi", "Informative"}, {"Laomedeia", "Upbeat"}, {"Achernar", "Soft"},
            {"Alnilam", "Firm"}, {"Schedar", "Even"}, {"Gacrux", "Mature"}, {"Pulcherrima", "Forward"},
            {"Achird", "Friendly"}, {"Zubenelgenubi", "Casual"}, {"Vindemiatrix", "Gentle"}, {"Sadachbia", "Lively"},
            {"Sadaltager", "Knowledgeable"}, {"Sulafat", "Warm"}
    };

    /** ready-made delivery styles: Bangla label, instruction sent to the voice (English works best) */
    static final String[][] STYLES = {
            {"কিছু নয় (ভয়েসের নিজের ধরন)", ""},
            {"শান্ত ও পরিষ্কার", "calm and clear"},
            {"উষ্ণ গল্প-বলার ধরন", "warm storyteller, gentle pace"},
            {"খবর পড়ার ধরন", "professional news presenter, steady and clear"},
            {"হাসিখুশি ও উৎফুল্ল", "cheerful and friendly"},
            {"ধীরে ও গম্ভীর", "slow, serious and thoughtful"},
            {"ফিসফিস করে", "whispering softly"},
            {"উত্তেজিত ও দ্রুত", "excited, fast and energetic"}
    };

    static final class Audio {
        byte[] pcm;
        int rate = 24000;
    }

    static final class Designed {
        String id;
        Audio sample;
    }

    // ------------------------------------------------------------------ speak
    /** One piece of text to one voice. Returns raw 16-bit mono sound. */
    static Audio speak(String key, String model, String voice, String style, String text) throws Gemini.Failure {
        try {
            JSONObject textPart = new JSONObject().put("type", "text").put("text", text);
            if (style != null && !style.trim().isEmpty()) {
                textPart.put("annotations", new JSONArray().put(
                        new JSONObject().put("type", "speech_metadata").put("style", style.trim())));
            }
            JSONObject body = new JSONObject()
                    .put("model", model.trim())
                    .put("input", new JSONArray().put(new JSONObject()
                            .put("type", "user_input")
                            .put("content", new JSONArray().put(textPart))))
                    .put("response_format", new JSONObject().put("type", "audio").put("mime_type", "audio/wav"))
                    .put("generation_config", new JSONObject().put("speech_config",
                            new JSONArray().put(new JSONObject().put("voice", voice.trim()))));
            String resp = post(BASE + "interactions", key, body.toString(), 120000);
            JSONObject o = new JSONObject(resp);
            JSONObject err = o.optJSONObject("error");
            if (err != null) throw new Gemini.Failure(err.optString("message", "AI Studio ত্রুটি"), false);
            String b64 = findAudio(o);
            if (b64 == null) throw new Gemini.Failure("AI Studio থেকে শব্দ আসেনি", true);
            return fromWav(Base64.decode(b64, Base64.DEFAULT));
        } catch (Gemini.Failure f) {
            throw f;
        } catch (java.io.IOException e) {
            throw new Gemini.Failure("ইন্টারনেট সংযোগের সমস্যা: " + e.getMessage(), true);
        } catch (Exception e) {
            throw new Gemini.Failure("AI Studio ভয়েস ত্রুটি: " + e.getMessage(), false);
        }
    }

    /** steps[].content[].data of the last audio block */
    private static String findAudio(JSONObject o) {
        JSONArray steps = o.optJSONArray("steps");
        if (steps == null) return null;
        String found = null;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject st = steps.optJSONObject(i);
            if (st == null || !"model_output".equals(st.optString("type"))) continue;
            JSONArray cs = st.optJSONArray("content");
            if (cs == null) continue;
            for (int k = 0; k < cs.length(); k++) {
                JSONObject c = cs.optJSONObject(k);
                if (c != null && "audio".equals(c.optString("type")) && c.optString("data").length() > 0) {
                    found = c.optString("data");
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ automatic emotion
    /**
     * Asks a Gemini text model how this passage should be read (emotion, tone, pace).
     * Any failure gives an empty answer, and the reading carries on with the plain style.
     */
    static String autoStyle(String key, String textModel, String text, String base) {
        try {
            String prompt = "You direct a narrator who reads the passage below aloud to a listener. "
                    + "Write ONE short English delivery instruction (at most 18 words) for the emotion, tone, pace and energy "
                    + "that fit this passage. Output only the instruction, nothing else."
                    + (base == null || base.trim().isEmpty() ? "" : " General style wanted: " + base.trim() + ".")
                    + "\n\nPassage:\n" + (text.length() > 1200 ? text.substring(0, 1200) : text);
            JSONObject content = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)));
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(content))
                    .put("generationConfig", new JSONObject().put("temperature", 0.3).put("maxOutputTokens", 80));
            String resp = post(BASE + "models/" + textModel.trim() + ":generateContent", key, body.toString(), 20000);
            JSONArray cands = new JSONObject(resp).optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return "";
            JSONObject cont = cands.getJSONObject(0).optJSONObject("content");
            if (cont == null) return "";
            JSONArray parts = cont.optJSONArray("parts");
            if (parts == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
            String s = sb.toString().replace("\n", " ").replace("\"", "").trim();
            return s.length() > 200 ? s.substring(0, 200) : s;
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ voice design
    /** Makes a new voice from a description. It stays on the person's own Google account. */
    static Designed design(String key, String model, String name, String gender, String lang, String description) throws Gemini.Failure {
        try {
            JSONObject voice = new JSONObject()
                    .put("model", model.trim())
                    .put("type", "prompted")
                    .put("display_name", name)
                    .put("gender", gender)
                    .put("language_code", lang)
                    .put("prompted", new JSONObject().put("input", description));
            JSONObject body = new JSONObject().put("store", true).put("voice", voice);
            String resp = post(BASE + "voices", key, body.toString(), 120000);
            JSONObject o = new JSONObject(resp);
            JSONObject err = o.optJSONObject("error");
            if (err != null) throw new Gemini.Failure(err.optString("message", "ভয়েস বানানো যায়নি"), false);
            Designed d = new Designed();
            d.id = o.optString("id", "");
            if (d.id.isEmpty()) throw new Gemini.Failure("ভয়েসের পরিচয় (id) ফেরত আসেনি", false);
            JSONObject sa = o.optJSONObject("sample_audio");
            if (sa != null && sa.optString("data").length() > 0) {
                d.sample = fromWav(Base64.decode(sa.optString("data"), Base64.DEFAULT));
            }
            return d;
        } catch (Gemini.Failure f) {
            throw f;
        } catch (java.io.IOException e) {
            throw new Gemini.Failure("ইন্টারনেট সংযোগের সমস্যা: " + e.getMessage(), true);
        } catch (Exception e) {
            throw new Gemini.Failure("ভয়েস বানানোর ত্রুটি: " + e.getMessage(), false);
        }
    }

    // ------------------------------------------------------------------ plumbing
    private static String post(String url, String key, String json, int readTimeout) throws Exception {
        key = Gemini.pick(key);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key.trim());
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        OutputStream os = c.getOutputStream();
        os.write(bytes);
        os.close();
        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        String resp = is == null ? "" : readAll(is);
        c.disconnect();
        if (code == 200) return resp;
        String msg = "HTTP " + code;
        try {
            JSONObject e = new JSONObject(resp).optJSONObject("error");
            if (e != null && e.optString("message").length() > 0) msg = e.optString("message");
        } catch (Exception ignored) { }
        boolean retry = code == 429 || code >= 500 || code == 408;
        if (code == 429) msg = "ব্যবহারের সীমা পেরিয়েছে (HTTP 429): " + msg;
        if (code == 400 && msg.toLowerCase().contains("api key")) msg = "API কী ঠিক নয়: " + msg;
        if (code == 403) msg = "API কী কাজ করছে না বা অনুমতি নেই: " + msg;
        boolean mp = Gemini.isModelProblem(code, msg);
        if (code == 404) msg = "মডেল বা ঠিকানা পাওয়া যায়নি (HTTP 404): " + msg;
        Gemini.Failure fl = new Gemini.Failure(msg, retry);
        fl.modelProblem = mp;
        throw fl;
    }

    private static String readAll(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }

    /** takes the sound out of a WAV file (or treats the bytes as raw 24 kHz sound) */
    static Audio fromWav(byte[] w) {
        Audio a = new Audio();
        a.pcm = w;
        if (w.length > 44 && w[0] == 'R' && w[1] == 'I' && w[2] == 'F' && w[3] == 'F') {
            int p = 12;
            while (p + 8 <= w.length) {
                int len = le32(w, p + 4);
                boolean fmt = w[p] == 'f' && w[p + 1] == 'm' && w[p + 2] == 't';
                boolean data = w[p] == 'd' && w[p + 1] == 'a' && w[p + 2] == 't' && w[p + 3] == 'a';
                if (fmt && p + 16 <= w.length) {
                    int r = le32(w, p + 12);
                    if (r >= 8000 && r <= 48000) a.rate = r;
                }
                if (data) {
                    int s = p + 8;
                    int e = (len <= 0 || s + len > w.length) ? w.length : s + len;
                    a.pcm = Arrays.copyOfRange(w, s, e);
                    return a;
                }
                if (len < 0) break;
                p += 8 + len + (len & 1);
            }
        }
        return a;
    }
}
