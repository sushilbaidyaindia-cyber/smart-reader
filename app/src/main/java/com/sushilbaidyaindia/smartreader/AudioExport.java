package com.sushilbaidyaindia.smartreader;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

import de.sciss.jump3r.lowlevel.LameEncoder;

/** Reads pages aloud with the AI Studio voice and writes the whole thing as one MP3 file. */
final class AudioExport {
    private AudioExport() { }

    interface Progress {
        void update(String message);
    }

    /** same grouping as the player: about 600 letters of sentences per request */
    private static int chunkEnd(List<int[]> ss, int from) {
        int end = from;
        int len = 0;
        while (end < ss.size()) {
            int[] s = ss.get(end);
            len += s[1] - s[0];
            if (len >= 600) break;
            end++;
        }
        return Math.min(end, ss.size() - 1);
    }

    static File make(Context c, Store.Doc d, int from, int to, String key, String model, String voice, String style,
                     boolean auto, String textModel, Progress progress, AtomicBoolean cancel) throws Exception {
        Store st = Store.get(c);
        File dir = new File(c.getCacheDir(), "share");
        if (!dir.exists()) dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File o : old) o.delete();
        File out = new File(dir, ShareHelper.safeName(d, from, to) + ".mp3");

        LinkedHashSet<String> models = new LinkedHashSet<>();
        models.add(model);
        for (String m : GeminiTts.MODELS) models.add(m);

        OutputStream os = new FileOutputStream(out);
        LameEncoder enc = null;
        byte[] mp3 = null;
        long bytesWritten = 0;
        try {
            for (int p = from; p <= to; p++) {
                String text = st.page(d.id, p);
                if (text == null || text.trim().isEmpty()) continue;
                List<int[]> ss = Splitter.sentences(text);
                int i = 0;
                int chunk = 0;
                while (i < ss.size()) {
                    if (cancel.get()) throw new Exception("বন্ধ করা হয়েছে");
                    int j = chunkEnd(ss, i);
                    String t = text.substring(ss.get(i)[0], ss.get(j)[1]);
                    chunk++;
                    progress.update("পেজ " + BanglaText.digits(p) + " (" + BanglaText.digits(p - from + 1) + "/" + BanglaText.digits(to - from + 1)
                            + "), অংশ " + BanglaText.digits(chunk) + " পড়া হচ্ছে…");
                    String st2 = style;
                    if (auto) {
                        String as = GeminiTts.autoStyle(key, textModel, t, style);
                        if (as != null && !as.isEmpty()) st2 = style.isEmpty() ? as : style + ", " + as;
                    }
                    GeminiTts.Audio a = null;
                    String err = null;
                    for (String mdl : models) {
                        for (int attempt = 1; attempt <= 3 && a == null; attempt++) {
                            if (cancel.get()) throw new Exception("বন্ধ করা হয়েছে");
                            try {
                                a = GeminiTts.speak(key, mdl, voice, st2, t);
                            } catch (Gemini.Failure f) {
                                err = f.getMessage();
                                if (f.modelProblem || !f.retryable || attempt == 3) break;
                                try { Thread.sleep(2500L * attempt); } catch (InterruptedException ie) { throw new Exception("বন্ধ করা হয়েছে"); }
                            }
                        }
                        if (a != null) break;
                    }
                    if (a == null) throw new Exception(err == null ? "AI Studio ভয়েস কাজ করেনি" : err);

                    if (enc == null) {
                        AudioFormat af = new AudioFormat(a.rate, 16, 1, true, false);
                        enc = newEncoder(af);
                        mp3 = new byte[enc.getMP3BufferSize()];
                    }
                    bytesWritten += feed(enc, mp3, os, a.pcm);
                    bytesWritten += feed(enc, mp3, os, new byte[a.rate / 4 * 2]);     // a quarter second of silence between parts
                    i = j + 1;
                }
            }
            if (enc == null) throw new Exception("এই পেজগুলোতে পড়ার মতো লেখা নেই");
            int w = enc.encodeFinish(mp3);
            if (w > 0) {
                os.write(mp3, 0, w);
                bytesWritten += w;
            }
        } finally {
            try { os.close(); } catch (Throwable ignored) { }
            try { if (enc != null) enc.close(); } catch (Throwable ignored) { }
        }
        if (bytesWritten < 1000) throw new Exception("অডিও ফাইল তৈরি হয়নি");
        return out;
    }

    private static long feed(LameEncoder enc, byte[] mp3, OutputStream os, byte[] pcm) throws Exception {
        long total = 0;
        int step = enc.getPCMBufferSize();
        int off = 0;
        while (off < pcm.length) {
            int len = Math.min(step, pcm.length - off);
            int w = enc.encodeBuffer(pcm, off, len, mp3);
            if (w > 0) {
                os.write(mp3, 0, w);
                total += w;
            }
            off += len;
        }
        return total;
    }

    /** mono speech at 64 kbit/s; falls back to the plain encoder if this library version has no such constructor */
    private static LameEncoder newEncoder(AudioFormat af) {
        try {
            java.lang.reflect.Constructor<LameEncoder> k = LameEncoder.class.getConstructor(
                    AudioFormat.class, int.class, int.class, int.class, boolean.class);
            return k.newInstance(af, 64, -1, 7, false);
        } catch (Throwable t) {
            return new LameEncoder(af);
        }
    }
}
