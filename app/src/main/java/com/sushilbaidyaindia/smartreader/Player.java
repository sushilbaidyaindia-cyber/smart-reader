package com.sushilbaidyaindia.smartreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The reader's brain: what is open, which page and sentence, and the phone's own voice reading it aloud.
 * One copy for the whole app; the screen and the notification both look at it. Everything here runs on the main thread.
 */
final class Player {

    interface Listener {
        void onChanged();
    }

    static final Player I = new Player();

    private Player() { }

    private Context app;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean initStarted = false;
    private Runnable afterInit;
    private boolean langOk = true;

    Store.Doc doc;                      // the open text, null when nothing is open
    int page = 1;
    String text = "";
    List<int[]> sents = new ArrayList<>();
    int idx = 0;
    boolean playing = false;
    boolean pageMissing = false;        // the page is still being read from the PDF
    String message = "";
    float rate = 1.0f;
    long sleepAt = 0;
    int idxEnd = 0;                     // with the AI Studio voice a chunk of sentences is read at once: idx..idxEnd

    // ---- AI Studio voice state
    private final ExecutorService net = Executors.newFixedThreadPool(2);
    private AudioTrack track;
    private long gTok = 0;
    private String wantKey = null;
    private int trackFrames = 0;
    private long trackStart = 0, trackMaxMs = 0;
    private final Set<String> inFlight = new HashSet<>();
    private final Map<String, GeminiTts.Audio> pcmCache = new LinkedHashMap<String, GeminiTts.Audio>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, GeminiTts.Audio> e) { return size() > 6; }
    };
    private final Runnable pollRun = new Runnable() {
        @Override public void run() {
            if (track == null) return;
            boolean done = false;
            try {
                done = track.getPlaybackHeadPosition() >= trackFrames - 1
                        || System.currentTimeMillis() - trackStart > trackMaxMs;
            } catch (Throwable t) { done = true; }
            if (done) {
                releaseTrack();
                main.post(Player.this::audioDone);
            } else {
                main.postDelayed(this, 150);
            }
        }
    };

    private long utt = 0;
    private int errors = 0;
    private int waits = 0;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private PowerManager.WakeLock wake;
    private AudioManager audio;
    private final Runnable sleepRun = new Runnable() {
        @Override public void run() {
            sleepAt = 0;
            if (playing) {
                message = "ঘুমের টাইমার শেষ, পড়া থেমেছে";
                pause();
            } else {
                changed();
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            main.post(Player.this::pause);
        }
    };

    // ------------------------------------------------------------------ set-up and listeners
    void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        rate = prefs().getFloat("rate", 1.0f);
        long last = prefs().getLong("last_doc", -1);
        if (last > 0) {
            Store.Doc d = Store.get(app).doc(last);
            if (d != null) open(d.id, d.lastPage, d.lastSent);
        }
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("reader", Context.MODE_PRIVATE);
    }

    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        for (Listener l : listeners) l.onChanged();
        ReaderService.refresh();
    }

    // ------------------------------------------------------------------ opening
    void open(long docId, int p, int sent) {
        stopSpeaking();
        playing = false;
        releaseWake();
        Store st = Store.get(app);
        doc = st.doc(docId);
        if (doc == null) {
            text = "";
            sents = new ArrayList<>();
            changed();
            return;
        }
        st.touch(docId);
        prefs().edit().putLong("last_doc", docId).apply();
        message = "";
        page = Math.max(1, Math.min(p, Math.max(1, doc.pages)));
        loadPage(page);
        idx = Math.max(0, Math.min(sent, Math.max(0, sents.size() - 1)));
        changed();
    }

    /** the open text was deleted */
    void closeIfOpen(long docId) {
        if (doc != null && doc.id == docId) {
            stopSpeaking();
            playing = false;
            releaseWake();
            doc = null;
            text = "";
            sents = new ArrayList<>();
            idx = 0;
            prefs().edit().remove("last_doc").apply();
            ReaderService.stop();
            changed();
        }
    }

    private void loadPage(int p) {
        Store st = Store.get(app);
        Store.Doc fresh = st.doc(doc.id);
        if (fresh != null) doc = fresh;
        String t = st.page(doc.id, p);
        pageMissing = t == null && Importer.activeDoc == doc.id;
        text = t == null ? "" : t;
        sents = Splitter.sentences(text);
    }

    /** reload the open page (a PDF page may have become ready) */
    void refreshPage() {
        if (doc == null) return;
        boolean wasEmpty = text.isEmpty();
        Store.Doc fresh = Store.get(app).doc(doc.id);
        if (fresh != null) doc = fresh;
        if (wasEmpty && !playing) {
            loadPage(page);
            idx = 0;
            changed();
        } else {
            changed();
        }
    }

    // ------------------------------------------------------------------ voice
    private void ensureTts(Runnable then) {
        if (ttsReady) {
            then.run();
            return;
        }
        afterInit = then;
        if (initStarted) return;
        initStarted = true;
        tts = new TextToSpeech(app, status -> main.post(() -> onTtsInit(status)));
    }

    private void onTtsInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            initStarted = false;
            playing = false;
            message = "ফোনের টেক্সট-টু-স্পিচ চালু হয়নি। ফোনের সেটিংসে টেক্সট-টু-স্পিচ ইঞ্জিন দেখুন।";
            changed();
            return;
        }
        try {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
        } catch (Throwable ignored) { }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }

            @Override public void onDone(String id) {
                main.post(() -> spoken(id, true));
            }

            @Override public void onError(String id) {
                main.post(() -> spoken(id, false));
            }
        });
        applyVoice();
        ttsReady = true;
        Runnable r = afterInit;
        afterInit = null;
        if (r != null) r.run();
    }

    private static boolean bad(int r) {
        return r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED;
    }

    private void applyVoice() {
        String vn = prefs().getString("voice", "");
        boolean set = false;
        if (!vn.isEmpty()) {
            try {
                for (Voice v : tts.getVoices()) {
                    if (v.getName().equals(vn)) {
                        tts.setVoice(v);
                        set = true;
                        break;
                    }
                }
            } catch (Throwable ignored) { }
        }
        if (!set) {
            int r = tts.setLanguage(new Locale("bn", "IN"));
            if (bad(r)) r = tts.setLanguage(new Locale("bn", "BD"));
            if (bad(r)) r = tts.setLanguage(new Locale("bn"));
            langOk = !bad(r);
        } else {
            langOk = true;
        }
        tts.setSpeechRate(rate);
    }

    /** Bengali voices the phone has (empty until the voice engine has started) */
    List<Voice> bengaliVoices() {
        List<Voice> out = new ArrayList<>();
        if (!ttsReady) return out;
        try {
            for (Voice v : tts.getVoices()) {
                if (v.getLocale() != null && "bn".equals(v.getLocale().getLanguage())) out.add(v);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    void prepareVoices(Runnable done) {
        ensureTts(done);
    }

    void voiceChanged() {
        if (!ttsReady) return;
        applyVoice();
        if (playing) speakCurrent();
    }

    void sample(String s) {
        ensureTts(() -> {
            applyVoice();
            tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "sample");
        });
    }

    boolean languageOk() { return langOk; }

    void setRate(float r) {
        rate = Math.max(0.5f, Math.min(2.5f, Math.round(r * 10f) / 10f));
        prefs().edit().putFloat("rate", rate).apply();
        if (gemini()) {
            if (track != null) {
                try {
                    PlaybackParams pp = new PlaybackParams();
                    pp.setSpeed(rate);
                    track.setPlaybackParams(pp);
                } catch (Throwable ignored) { }
            }
        } else if (ttsReady) {
            tts.setSpeechRate(rate);
            if (playing) speakCurrent();
        }
        changed();
    }

    // ------------------------------------------------------------------ playing
    void toggle() {
        if (playing) pause();
        else play();
    }

    void play() {
        if (doc == null || playing) return;
        message = "";
        playing = true;
        errors = 0;
        waits = 0;
        if (audio != null) audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        acquireWake();
        ReaderService.start(app);
        changed();
        if (gemini()) {
            if (prefs().getString("gemini_key", "").trim().isEmpty()) {
                message = "AI Studio ভয়েসের জন্য সেটিংস ট্যাবে Gemini কী দিন।";
                pause();
                return;
            }
            speakCurrent();
            return;
        }
        ensureTts(() -> {
            if (!playing) return;
            if (!langOk) message = "ফোনে বাংলা ভয়েস পাওয়া যায়নি। সেটিংস ট্যাব থেকে ভয়েস ঠিক করুন।";
            speakCurrent();
        });
    }

    void pause() {
        boolean was = playing;
        playing = false;
        stopSpeaking();
        main.removeCallbacks(waitRun);
        if (audio != null) audio.abandonAudioFocus(focus);
        releaseWake();
        savePos();
        if (was) changed();
    }

    void nextSentence() {
        if (doc == null) return;
        idx++;
        if (idx >= sents.size()) {
            if (page < doc.pages) {
                page++;
                loadPage(page);
                idx = 0;
            } else {
                idx = Math.max(0, sents.size() - 1);
            }
        }
        afterJump();
    }

    void prevSentence() {
        if (doc == null) return;
        idx--;
        if (idx < 0) {
            if (page > 1) {
                page--;
                loadPage(page);
                idx = Math.max(0, sents.size() - 1);
            } else {
                idx = 0;
            }
        }
        afterJump();
    }

    /** rewind (negative) or fast-forward (positive) by that many sentences */
    void skipSentences(int n) {
        if (doc == null || n == 0) return;
        int count = Math.abs(n);
        for (int k = 0; k < count; k++) {
            if (n > 0) {
                idx++;
                if (idx >= sents.size()) {
                    if (page < doc.pages) {
                        page++;
                        loadPage(page);
                        idx = 0;
                    } else {
                        idx = Math.max(0, sents.size() - 1);
                        break;
                    }
                }
            } else {
                idx--;
                if (idx < 0) {
                    if (page > 1) {
                        page--;
                        loadPage(page);
                        idx = Math.max(0, sents.size() - 1);
                    } else {
                        idx = 0;
                        break;
                    }
                }
            }
        }
        afterJump();
    }

    void gotoPage(int p) {
        if (doc == null) return;
        int np = Math.max(1, Math.min(p, Math.max(1, doc.pages)));
        page = np;
        loadPage(page);
        idx = 0;
        afterJump();
    }

    void nextPage() { gotoPage(page + 1); }

    void prevPage() { gotoPage(page - 1); }

    private void afterJump() {
        waits = 0;
        errors = 0;
        savePos();
        if (playing) {
            speakCurrent();
        } else {
            stopSpeaking();
            changed();
        }
    }

    private void stopSpeaking() {
        utt++;
        gTok++;
        wantKey = null;
        main.removeCallbacks(pollRun);
        releaseTrack();
        if (tts != null && ttsReady) {
            try { tts.stop(); } catch (Throwable ignored) { }
        }
    }

    private final Runnable waitRun = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            loadPage(page);
            if (pageMissing) {
                waits++;
                if (waits > 90) {
                    message = "পেজ " + BanglaText.digits(page) + " তৈরি হয়নি, তাই পড়া থেমেছে";
                    pause();
                } else {
                    changed();
                    main.postDelayed(this, 1500);
                }
            } else {
                waits = 0;
                idx = 0;
                speakCurrent();
            }
        }
    };

    private void speakCurrent() {
        if (!playing || doc == null || (!ttsReady && !gemini())) return;
        main.removeCallbacks(waitRun);
        while (idx >= sents.size()) {            // this page is finished, or has no text
            if (page >= doc.pages) {
                finish();
                return;
            }
            page++;
            loadPage(page);
            idx = 0;
            if (pageMissing) {
                message = "পেজ " + BanglaText.digits(page) + " তৈরি হচ্ছে, একটু অপেক্ষা…";
                changed();
                main.postDelayed(waitRun, 1500);
                return;
            }
        }
        message = message.startsWith("পেজ ") ? "" : message;
        savePos();
        changed();
        if (gemini()) {
            speakGemini();
            return;
        }
        idxEnd = idx;
        int[] s = sents.get(idx);
        String t = text.substring(s[0], s[1]);
        utt++;
        int r = tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u" + utt);
        if (r == TextToSpeech.ERROR) {
            message = "পড়া শুরু করা যায়নি। ফোনের টেক্সট-টু-স্পিচ ইঞ্জিন দেখুন।";
            pause();
        }
    }

    private void spoken(String id, boolean ok) {
        if (!playing || !id.equals("u" + utt)) return;
        if (!ok) {
            errors++;
            if (errors >= 4) {
                message = "বারবার পড়তে ব্যর্থ হয়েছে, তাই থেমেছে";
                pause();
                return;
            }
        } else {
            errors = 0;
        }
        idx++;
        speakCurrent();
    }

    private void finish() {
        playing = false;
        stopSpeaking();
        if (audio != null) audio.abandonAudioFocus(focus);
        releaseWake();
        message = "পড়া শেষ হয়েছে";
        page = 1;
        loadPage(1);
        idx = 0;
        savePos();
        changed();
        ReaderService.stop();
    }

    // ------------------------------------------------------------------ AI Studio voice
    boolean gemini() {
        return app != null && "gemini".equals(prefs().getString("engine", "system"));
    }

    private String gModel() { return prefs().getString("tts_model", GeminiTts.DEFAULT_MODEL); }

    private String gVoice() { return prefs().getString("g_voice", GeminiTts.DEFAULT_VOICE); }

    private String gStyle() { return prefs().getString("g_style", ""); }

    private boolean gAuto() { return prefs().getBoolean("g_auto", false); }

    private String gKey() { return prefs().getString("gemini_key", "").trim(); }

    /** last sentence of the chunk that starts at 'from' (about 400 letters at a time, fewer calls) */
    private static int chunkEnd(List<int[]> ss, String txt, int from) {
        int end = from;
        int len = 0;
        while (end < ss.size()) {
            int[] s = ss.get(end);
            len += s[1] - s[0];
            if (len >= 400) break;
            end++;
        }
        return Math.min(end, ss.size() - 1);
    }

    private String cacheKey(long docId, int pg, int from, int to, String t) {
        return docId + ":" + pg + ":" + from + ":" + to + ":" + (gModel() + "|" + gVoice() + "|" + gStyle() + "|" + gAuto() + "|" + t.hashCode());
    }

    private void speakGemini() {
        idxEnd = chunkEnd(sents, text, idx);
        int[] a = sents.get(idx);
        int[] b = sents.get(idxEnd);
        String t = text.substring(a[0], b[1]);
        String key = cacheKey(doc.id, page, idx, idxEnd, t);
        wantKey = key;
        GeminiTts.Audio hit = pcmCache.get(key);
        if (hit != null) {
            playAudio(hit);
            return;
        }
        message = message.startsWith("AI Studio") ? message : "";
        fetch(key, t, true);
    }

    private void fetch(final String key, final String t, final boolean wanted) {
        if (inFlight.contains(key)) return;
        inFlight.add(key);
        final long tok = gTok;
        final String k = gKey(), model = gModel(), voice = gVoice(), style = gStyle();
        final boolean auto = gAuto();
        final String textModel = prefs().getString("ocr_model", "gemini-3.5-flash-lite");
        net.execute(() -> {
            GeminiTts.Audio out = null;
            String err = null;
            try {
                String st = style;
                if (auto) {
                    String as = GeminiTts.autoStyle(k, textModel, t, style);
                    if (as != null && !as.isEmpty()) st = style.isEmpty() ? as : style + ", " + as;
                }
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        out = GeminiTts.speak(k, model, voice, st, t);
                        break;
                    } catch (Gemini.Failure f) {
                        err = f.getMessage();
                        if (!f.retryable || attempt == 3) break;
                        try { Thread.sleep(2500L * attempt); } catch (InterruptedException ie) { break; }
                    }
                }
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            final GeminiTts.Audio fo = out;
            final String fe = err;
            main.post(() -> {
                inFlight.remove(key);
                if (fo != null) pcmCache.put(key, fo);
                if (!wanted && fo != null) return;
                if (tok != gTok || !playing || !key.equals(wantKey)) return;
                if (fo == null) {
                    message = "AI Studio ভয়েস কাজ করেনি: " + (fe == null ? "অজানা কারণ" : fe);
                    pause();
                } else {
                    playAudio(fo);
                }
            });
        });
    }

    private void playAudio(GeminiTts.Audio a) {
        releaseTrack();
        main.removeCallbacks(pollRun);
        try {
            int frames = a.pcm.length / 2;
            if (frames < 10) { audioDone(); return; }
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(a.rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(a.pcm.length)
                    .build();
            t.write(a.pcm, 0, a.pcm.length);
            try {
                PlaybackParams pp = new PlaybackParams();
                pp.setSpeed(rate);
                t.setPlaybackParams(pp);
            } catch (Throwable ignored) { }
            track = t;
            trackFrames = frames;
            trackStart = System.currentTimeMillis();
            trackMaxMs = (long) (frames * 1000.0 / a.rate / Math.max(0.5f, rate)) + 3000;
            t.play();
            main.postDelayed(pollRun, 300);
            message = message.startsWith("AI Studio") ? "" : message;
            changed();
            prefetch();
        } catch (Throwable e) {
            message = "অডিও চালানো যায়নি: " + e.getMessage();
            pause();
        }
    }

    private void prefetch() {
        try {
            if (doc == null || idxEnd + 1 > sents.size()) return;
            if (idxEnd + 1 < sents.size()) {
                int to = chunkEnd(sents, text, idxEnd + 1);
                int[] a = sents.get(idxEnd + 1), b = sents.get(to);
                String t = text.substring(a[0], b[1]);
                String key = cacheKey(doc.id, page, idxEnd + 1, to, t);
                if (pcmCache.get(key) == null) fetch(key, t, false);
            } else if (page < doc.pages) {
                String nt = Store.get(app).page(doc.id, page + 1);
                if (nt == null || nt.isEmpty()) return;
                List<int[]> ns = Splitter.sentences(nt);
                if (ns.isEmpty()) return;
                int to = chunkEnd(ns, nt, 0);
                String t = nt.substring(ns.get(0)[0], ns.get(to)[1]);
                String key = cacheKey(doc.id, page + 1, 0, to, t);
                if (pcmCache.get(key) == null) fetch(key, t, false);
            }
        } catch (Throwable ignored) { }
    }

    private void audioDone() {
        if (!playing) return;
        errors = 0;
        idx = idxEnd + 1;
        speakCurrent();
    }

    private void releaseTrack() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.stop(); } catch (Throwable ignored) { }
            try { t.release(); } catch (Throwable ignored) { }
        }
    }

    /** a setting of the AI Studio voice changed: read the current part again with the new voice */
    void settingsChanged() {
        pcmCache.clear();
        if (playing && gemini()) {
            stopSpeaking();
            speakCurrent();
        }
    }

    /** speak a short sample with the AI Studio voice (reading is paused meanwhile) */
    void sampleGemini(final String sampleText, final java.util.function.Consumer<String> result) {
        if (playing) pause();
        final String k = gKey(), model = gModel(), voice = gVoice(), style = gStyle();
        if (k.isEmpty()) {
            result.accept("আগে Gemini কী দিন।");
            return;
        }
        result.accept("নমুনা তৈরি হচ্ছে…");
        net.execute(() -> {
            GeminiTts.Audio out = null;
            String err = null;
            try {
                out = GeminiTts.speak(k, model, voice, style, sampleText);
            } catch (Gemini.Failure f) {
                err = f.getMessage();
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            final GeminiTts.Audio fo = out;
            final String fe = err;
            main.post(() -> {
                if (fo == null) {
                    result.accept("ব্যর্থ: " + fe);
                } else {
                    playSample(fo);
                    result.accept("নমুনা চলছে");
                }
            });
        });
    }

    void playSample(GeminiTts.Audio a) {
        if (playing) pause();
        releaseTrack();
        main.removeCallbacks(pollRun);
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(a.rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(a.pcm.length)
                    .build();
            t.write(a.pcm, 0, a.pcm.length);
            track = t;
            trackFrames = a.pcm.length / 2;
            trackStart = System.currentTimeMillis();
            trackMaxMs = (long) (trackFrames * 1000.0 / a.rate) + 3000;
            t.play();
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (track == null) return;
                    boolean done;
                    try {
                        done = track.getPlaybackHeadPosition() >= trackFrames - 1
                                || System.currentTimeMillis() - trackStart > trackMaxMs;
                    } catch (Throwable x) { done = true; }
                    if (done) releaseTrack();
                    else main.postDelayed(this, 200);
                }
            }, 300);
        } catch (Throwable ignored) { }
    }

    private void savePos() {
        if (doc != null) Store.get(app).savePos(doc.id, page, idx);
    }

    // ------------------------------------------------------------------ sleep timer
    void setSleep(int minutes) {
        main.removeCallbacks(sleepRun);
        if (minutes <= 0) {
            sleepAt = 0;
        } else {
            sleepAt = System.currentTimeMillis() + minutes * 60000L;
            main.postDelayed(sleepRun, minutes * 60000L);
        }
        changed();
    }

    // ------------------------------------------------------------------ keeping the phone awake while reading
    private void acquireWake() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smartreader:read");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L);
        } catch (Throwable ignored) { }
    }

    private void releaseWake() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Throwable ignored) { }
    }

    void shutdown() {
        pause();
    }
}
