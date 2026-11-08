package com.sushilbaidyaindia.smartreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
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
        if (ttsReady) {
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
        if (!playing || doc == null || !ttsReady) return;
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
