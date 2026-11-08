package com.sushilbaidyaindia.smartreader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.speech.tts.Voice;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;

/** Smart Reader: paste text or add a PDF, keep it offline in the library, and have the phone read it aloud. */
public class MainActivity extends AppCompatActivity {

    private static final String[] TAB_NAMES = {"পড়ুন", "যোগ করুন", "লাইব্রেরি", "সেটিংস"};
    private static final int REQ_PICK = 11;
    private static final int REQ_NOTIF = 77;

    // palette (light or dark, as the phone is set)
    private int BG, CARD, INK, MUTED, ACCENT, ON_ACCENT, HL, LINE;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View[] pages = new View[4];
    private final TextView[] tabs = new TextView[4];
    private int tab = 0;

    // reader
    private LinearLayout readerBody;
    private LinearLayout emptyCard;
    private TextView tvTitle, tvInfo, tvMsg, tvText, tvRate, tvTimer;
    private ScrollView textScroll;
    private Button btnPlay;
    private LinearLayout moreRow;
    private long shownDoc = -1;
    private int shownPage = -1;
    private String shownText = null;
    private BackgroundColorSpan hlSpan;
    private int fontSp = 20;

    // add
    private EditText pasteTitle, pasteBody;
    private LinearLayout importCard;
    private TextView tvImport;
    private ProgressBar importBar;
    private Button btnImportRead, btnImportStop;

    // library and settings
    private LinearLayout libList;
    private TextView tvKey, tvVoice;

    private final Player.Listener listener = this::refreshReader;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateImportCard();
            if (Importer.running) {
                if (Player.I.doc != null && Importer.activeDoc == Player.I.doc.id) Player.I.refreshPage();
                handler.postDelayed(this, 900);
            } else if (tab == 2) {
                refreshLibrary();
            }
        }
    };

    // ------------------------------------------------------------------ start
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            BG = 0xFF101614; CARD = 0xFF1B2522; INK = 0xFFECF0EE; MUTED = 0xFFA9B8B3;
            ACCENT = 0xFF5FC7BF; ON_ACCENT = 0xFF0B1513; HL = 0xFF5B4A12; LINE = 0xFF2E3A36;
        } else {
            BG = 0xFFF6F3EC; CARD = 0xFFFFFFFF; INK = 0xFF1F2A28; MUTED = 0xFF56635F;
            ACCENT = 0xFF0F5C5C; ON_ACCENT = 0xFFFFFFFF; HL = 0xFFFFE08A; LINE = 0xFFDDD6C8;
        }
        fontSp = prefs.getInt("font_sp", 20);
        Player.I.init(this);
        if (b != null) tab = b.getInt("tab", 0);
        else tab = Player.I.doc != null ? 0 : 1;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pages[0] = buildReader();
        pages[1] = buildAdd();
        pages[2] = buildLibrary();
        pages[3] = buildSettings();
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(CARD);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(TAB_NAMES[i]);
            t.setTextSize(16);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(60));
            t.setClickable(true);
            t.setFocusable(true);
            t.setOnClickListener(v -> select(idx));
            tabs[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        select(tab);
        refreshReader();
        refreshSettings();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Player.I.addListener(listener);
        refreshReader();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.getBoolean("keep_on", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        Player.I.removeListener(listener);
        handler.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    // ------------------------------------------------------------------ tabs
    private void select(int i) {
        tab = i;
        for (int k = 0; k < 4; k++) {
            boolean on = k == i;
            pages[k].setVisibility(on ? View.VISIBLE : View.GONE);
            tabs[k].setBackgroundColor(on ? ACCENT : CARD);
            tabs[k].setTextColor(on ? ON_ACCENT : ACCENT);
            tabs[k].setSelected(on);
            tabs[k].setContentDescription(TAB_NAMES[k] + ", ট্যাব " + BanglaText.digits(k + 1) + " এর ৪" + (on ? ", নির্বাচিত" : ""));
        }
        if (i == 2) refreshLibrary();
        if (i == 3) refreshSettings();
        updateImportCard();
    }

    // ------------------------------------------------------------------ tab 1: reader
    private View buildReader() {
        FrameLayout frame = new FrameLayout(this);

        // when nothing is open
        emptyCard = new LinearLayout(this);
        emptyCard.setOrientation(LinearLayout.VERTICAL);
        emptyCard.setPadding(dp(20), dp(24), dp(20), dp(20));
        TextView h = text("স্মার্ট রিডার", 28, true, ACCENT);
        ViewCompat.setAccessibilityHeading(h, true);
        emptyCard.addView(h);
        TextView e = text("এখনও কিছু খোলা নেই। লেখা পেস্ট করতে বা PDF দিতে 'যোগ করুন' ট্যাবে যান। আগে সংরক্ষিত কিছু পড়তে 'লাইব্রেরি' ট্যাবে যান।", 18, false, INK);
        e.setPadding(0, dp(12), 0, dp(16));
        emptyCard.addView(e);
        Button toAdd = primary("যোগ করুন");
        toAdd.setOnClickListener(v -> select(1));
        emptyCard.addView(toAdd, fullWidth(0));
        Button toLib = secondary("লাইব্রেরি খুলুন");
        toLib.setOnClickListener(v -> select(2));
        emptyCard.addView(toLib, fullWidth(8));
        frame.addView(emptyCard, new FrameLayout.LayoutParams(-1, -1));

        readerBody = new LinearLayout(this);
        readerBody.setOrientation(LinearLayout.VERTICAL);
        readerBody.setPadding(dp(14), dp(12), dp(14), dp(8));

        tvTitle = text("", 22, true, ACCENT);
        ViewCompat.setAccessibilityHeading(tvTitle, true);
        readerBody.addView(tvTitle);
        tvInfo = text("", 15, false, MUTED);
        readerBody.addView(tvInfo);
        tvMsg = text("", 15, true, INK);
        tvMsg.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tvMsg.setVisibility(View.GONE);
        readerBody.addView(tvMsg);

        textScroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(card());
        tvText = text("", fontSp, false, INK);
        tvText.setLineSpacing(0f, 1.4f);
        box.addView(tvText);
        textScroll.addView(box);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.setMargins(0, dp(8), 0, dp(8));
        readerBody.addView(textScroll, sp);

        // page and sentence buttons
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        Button bPP = small("◀ পেজ", "আগের পেজ");
        bPP.setOnClickListener(v -> Player.I.prevPage());
        Button bPS = small("◀ বাক্য", "আগের বাক্য");
        bPS.setOnClickListener(v -> Player.I.prevSentence());
        Button bNS = small("বাক্য ▶", "পরের বাক্য");
        bNS.setOnClickListener(v -> Player.I.nextSentence());
        Button bNP = small("পেজ ▶", "পরের পেজ");
        bNP.setOnClickListener(v -> Player.I.nextPage());
        addWeighted(r1, bPP, bPS, bNS, bNP);
        readerBody.addView(r1);

        btnPlay = primary("▶  প্লে");
        btnPlay.setTextSize(20);
        btnPlay.setMinHeight(dp(64));
        btnPlay.setOnClickListener(v -> {
            askNotifications();
            Player.I.toggle();
        });
        readerBody.addView(btnPlay, fullWidth(6));

        // speed
        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        Button slower = small("−", "গতি কমান");
        slower.setOnClickListener(v -> Player.I.setRate(Player.I.rate - 0.1f));
        tvRate = text("", 17, true, INK);
        tvRate.setGravity(Gravity.CENTER);
        Button faster = small("+", "গতি বাড়ান");
        faster.setOnClickListener(v -> Player.I.setRate(Player.I.rate + 0.1f));
        Button more = small("আরও ▾", "আরও নিয়ন্ত্রণ দেখান বা লুকান");
        more.setOnClickListener(v -> moreRow.setVisibility(moreRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        r2.addView(slower, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r2.addView(tvRate, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        r2.addView(faster, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r2.addView(more, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        readerBody.addView(r2);

        moreRow = new LinearLayout(this);
        moreRow.setOrientation(LinearLayout.HORIZONTAL);
        moreRow.setVisibility(View.GONE);
        Button jump = small("পেজে যান", "নির্দিষ্ট পেজে যান");
        jump.setOnClickListener(v -> jumpDialog());
        Button timer = small("টাইমার", "ঘুমের টাইমার");
        timer.setOnClickListener(v -> timerDialog());
        Button fs = small("A−", "অক্ষর ছোট করুন");
        fs.setOnClickListener(v -> changeFont(-2));
        Button fb = small("A+", "অক্ষর বড় করুন");
        fb.setOnClickListener(v -> changeFont(2));
        addWeighted(moreRow, jump, timer, fs, fb);
        readerBody.addView(moreRow);
        tvTimer = text("", 14, false, MUTED);
        readerBody.addView(tvTimer);

        frame.addView(readerBody, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private void refreshReader() {
        if (readerBody == null) return;
        Player p = Player.I;
        boolean has = p.doc != null;
        emptyCard.setVisibility(has ? View.VISIBLE : View.GONE);
        readerBody.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) {
            emptyCard.setVisibility(View.GONE);
        }
        if (!has) return;

        tvTitle.setText(p.doc.title);
        String info = "পেজ " + BanglaText.digits(p.page) + "/" + BanglaText.digits(p.doc.pages)
                + " · বাক্য " + BanglaText.digits(p.sents.isEmpty() ? 0 : p.idx + 1) + "/" + BanglaText.digits(p.sents.size());
        if (Importer.running && Importer.activeDoc == p.doc.id) {
            info += " · PDF পড়া চলছে " + BanglaText.digits(Importer.done) + "/" + BanglaText.digits(Importer.total);
        }
        tvInfo.setText(info);
        if (p.message == null || p.message.isEmpty()) {
            tvMsg.setVisibility(View.GONE);
        } else {
            if (!p.message.contentEquals(tvMsg.getText())) tvMsg.setText(p.message);
            tvMsg.setVisibility(View.VISIBLE);
        }

        // the page text is set only when the page changes, so a screen reader is not interrupted at every sentence
        if (shownDoc != p.doc.id || shownPage != p.page || shownText == null || !shownText.equals(p.text)) {
            shownDoc = p.doc.id;
            shownPage = p.page;
            shownText = p.text;
            hlSpan = null;
            String show = p.text.isEmpty()
                    ? (p.pageMissing ? "এই পেজ এখনও তৈরি হচ্ছে…" : "এই পেজে কোনো লেখা নেই।")
                    : p.text;
            tvText.setText(new SpannableString(show), TextView.BufferType.SPANNABLE);
        }
        Spannable sp = (Spannable) tvText.getText();
        if (hlSpan != null) sp.removeSpan(hlSpan);
        hlSpan = null;
        if (!p.text.isEmpty() && p.idx >= 0 && p.idx < p.sents.size()) {
            int[] s = p.sents.get(p.idx);
            hlSpan = new BackgroundColorSpan(HL);
            sp.setSpan(hlSpan, s[0], s[1], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            final int start = s[0];
            tvText.post(() -> scrollTo(start));
        }

        btnPlay.setText(p.playing ? "⏸  পজ" : "▶  প্লে");
        btnPlay.setContentDescription(p.playing ? "পজ করুন" : "প্লে করুন");
        tvRate.setText("গতি " + BanglaText.digits(String.format(java.util.Locale.US, "%.1f", p.rate)) + "×");
        tvRate.setContentDescription("পড়ার গতি " + BanglaText.digits(String.format(java.util.Locale.US, "%.1f", p.rate)) + " গুণ");
        if (p.sleepAt > System.currentTimeMillis()) {
            long mins = (p.sleepAt - System.currentTimeMillis() + 59999) / 60000;
            tvTimer.setText("ঘুমের টাইমার: আর প্রায় " + BanglaText.digits(mins) + " মিনিট");
            tvTimer.setVisibility(View.VISIBLE);
        } else {
            tvTimer.setVisibility(View.GONE);
        }
    }

    private void scrollTo(int charOffset) {
        Layout lay = tvText.getLayout();
        if (lay == null || charOffset > tvText.length()) return;
        int line = lay.getLineForOffset(charOffset);
        int y = lay.getLineTop(line);
        textScroll.smoothScrollTo(0, Math.max(0, y - dp(40)));
    }

    private void changeFont(int delta) {
        fontSp = Math.max(14, Math.min(40, fontSp + delta));
        prefs.edit().putInt("font_sp", fontSp).apply();
        tvText.setTextSize(fontSp);
    }

    private void jumpDialog() {
        if (Player.I.doc == null) return;
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setHint("১ থেকে " + BanglaText.digits(Player.I.doc.pages));
        in.setTextSize(20);
        new AlertDialog.Builder(this)
                .setTitle("কোন পেজে যাবেন?")
                .setView(pad(in))
                .setPositiveButton("যান", (d, w) -> {
                    try {
                        Player.I.gotoPage(Integer.parseInt(BanglaText.asciiDigits(in.getText().toString().trim())));
                    } catch (NumberFormatException ex) {
                        Toast.makeText(this, "পেজ নম্বর লিখুন", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void timerDialog() {
        final String[] names = {"বন্ধ", "১৫ মিনিট", "৩০ মিনিট", "৪৫ মিনিট", "১ ঘণ্টা", "২ ঘণ্টা"};
        final int[] mins = {0, 15, 30, 45, 60, 120};
        new AlertDialog.Builder(this)
                .setTitle("ঘুমের টাইমার: এতক্ষণ পরে পড়া থামবে")
                .setItems(names, (d, w) -> {
                    Player.I.setSleep(mins[w]);
                    Toast.makeText(this, mins[w] == 0 ? "টাইমার বন্ধ" : "টাইমার: " + names[w], Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ------------------------------------------------------------------ tab 2: add
    private View buildAdd() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(box);

        TextView head = text("যোগ করুন", 26, true, ACCENT);
        ViewCompat.setAccessibilityHeading(head, true);
        box.addView(head);

        // paste
        box.addView(heading("লেখা পেস্ট করুন"));
        pasteTitle = new EditText(this);
        pasteTitle.setHint("নাম (না দিলে লেখার শুরু থেকে নাম হবে)");
        pasteTitle.setTextSize(18);
        pasteTitle.setTextColor(INK);
        pasteTitle.setHintTextColor(MUTED);
        pasteTitle.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        pasteTitle.setMinHeight(dp(56));
        box.addView(pasteTitle);
        pasteBody = new EditText(this);
        pasteBody.setHint("এখানে লেখা পেস্ট করুন");
        pasteBody.setTextSize(18);
        pasteBody.setTextColor(INK);
        pasteBody.setHintTextColor(MUTED);
        pasteBody.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        pasteBody.setGravity(Gravity.TOP | Gravity.START);
        pasteBody.setMinLines(5);
        pasteBody.setMaxLines(10);
        box.addView(pasteBody);
        Button fromClip = secondary("ক্লিপবোর্ড থেকে বসান");
        fromClip.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (t != null && t.length() > 0) {
                    pasteBody.setText(t);
                    Toast.makeText(this, "বসানো হয়েছে", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            Toast.makeText(this, "ক্লিপবোর্ডে কোনো লেখা নেই", Toast.LENGTH_SHORT).show();
        });
        box.addView(fromClip, fullWidth(8));
        Button save = primary("লাইব্রেরিতে রেখে খুলুন");
        save.setOnClickListener(v -> savePasted());
        box.addView(save, fullWidth(8));

        // file
        box.addView(heading("PDF বা টেক্সট ফাইল"));
        box.addView(text("PDF-এর পেজগুলো ছবি হিসেবে Gemini দিয়ে পড়া যায় (ইন্টারনেট ও API কী লাগে), অথবা PDF-এর ভেতরের লেখা সরাসরি নেওয়া যায় (ইন্টারনেট ছাড়া, তবে বাংলায় বর্ণের ক্রম ভুল আসতে পারে)। পড়া হয়ে গেলে লেখা ফোনে থেকে যায়, তখন ইন্টারনেট লাগে না।", 16, false, MUTED));
        Button pick = primary("ফাইল বাছুন");
        pick.setOnClickListener(v -> pickFile());
        box.addView(pick, fullWidth(8));

        // progress
        importCard = new LinearLayout(this);
        importCard.setOrientation(LinearLayout.VERTICAL);
        importCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        importCard.setBackground(card());
        importCard.setVisibility(View.GONE);
        TextView ih = text("PDF পড়া", 18, true, INK);
        ViewCompat.setAccessibilityHeading(ih, true);
        importCard.addView(ih);
        tvImport = text("", 16, false, INK);
        importCard.addView(tvImport);
        importBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        importBar.setMax(100);
        importCard.addView(importBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));
        btnImportRead = primary("প্রথম থেকে পড়া শুরু করুন (বাকি পেজ পড়া চলতে থাকবে)");
        btnImportRead.setOnClickListener(v -> {
            if (Importer.activeDoc > 0) {
                Player.I.open(Importer.activeDoc, 1, 0);
                select(0);
            }
        });
        importCard.addView(btnImportRead, fullWidth(8));
        btnImportStop = secondary("PDF পড়া বন্ধ করুন");
        btnImportStop.setOnClickListener(v -> {
            Importer.cancel = true;
            Toast.makeText(this, "বন্ধ করা হচ্ছে…", Toast.LENGTH_SHORT).show();
        });
        importCard.addView(btnImportStop, fullWidth(8));
        LinearLayout.LayoutParams cp = fullWidth(16);
        box.addView(importCard, cp);
        return sv;
    }

    private void savePasted() {
        String body = pasteBody.getText().toString().trim();
        if (body.isEmpty()) {
            Toast.makeText(this, "আগে কিছু লেখা পেস্ট করুন", Toast.LENGTH_SHORT).show();
            return;
        }
        String title = pasteTitle.getText().toString().trim();
        if (title.isEmpty()) title = autoTitle(body);
        long id = saveText(title, "paste", body);
        pasteBody.setText("");
        pasteTitle.setText("");
        Player.I.open(id, 1, 0);
        select(0);
        Toast.makeText(this, "লাইব্রেরিতে রাখা হয়েছে", Toast.LENGTH_SHORT).show();
    }

    private static String autoTitle(String body) {
        String t = body.replaceAll("\\s+", " ").trim();
        if (t.length() > 40) t = t.substring(0, 40).trim() + "…";
        return t;
    }

    private long saveText(String title, String source, String body) {
        Store st = Store.get(this);
        List<String> parts = Splitter.paginate(body, 3000);
        long id = st.createDoc(title, source, parts.size(), 1);
        for (int i = 0; i < parts.size(); i++) st.putPage(id, i + 1, parts.get(i));
        st.setDone(id, parts.size());
        return id;
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "text/plain"});
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "ফাইল বাছাইয়ের অ্যাপ পাওয়া যায়নি", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = displayName(uri);
        String type = getContentResolver().getType(uri);
        boolean pdf = (type != null && type.contains("pdf")) || name.toLowerCase().endsWith(".pdf");
        String base = name.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
        if (base.isEmpty()) base = "নামহীন";
        final String title = base;
        if (pdf) {
            if (Importer.running) {
                Toast.makeText(this, "আরেকটা PDF পড়া চলছে। শেষ হওয়া পর্যন্ত অপেক্ষা করুন বা বন্ধ করুন।", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "PDF দেখা হচ্ছে…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    final Importer.Info info = Importer.prepare(getApplicationContext(), uri, title);
                    runOnUiThread(() -> pdfDialog(info));
                } catch (final Throwable t) {
                    runOnUiThread(() -> Toast.makeText(this, "PDF খোলা যায়নি: " + t.getMessage() + " (পাসওয়ার্ড দেওয়া বা নষ্ট ফাইল হতে পারে)", Toast.LENGTH_LONG).show());
                }
            }).start();
        } else {
            new Thread(() -> {
                try {
                    String body = readText(uri);
                    final long id = saveText(title, "txt", body);
                    runOnUiThread(() -> {
                        Player.I.open(id, 1, 0);
                        select(0);
                        Toast.makeText(this, "লাইব্রেরিতে রাখা হয়েছে", Toast.LENGTH_SHORT).show();
                    });
                } catch (final Throwable t) {
                    runOnUiThread(() -> Toast.makeText(this, "ফাইল পড়া যায়নি: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "নামহীন";
    }

    private String readText(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("ফাইল খোলা যায়নি");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > 30L * 1024 * 1024) throw new Exception("ফাইল খুব বড় (৩০ এমবির বেশি)");
        }
        in.close();
        String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) s = s.substring(1);
        return s;
    }

    private void pdfDialog(final Importer.Info info) {
        if (info.pages < 1) {
            Toast.makeText(this, "PDF-এ কোনো পেজ নেই", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        box.addView(text("PDF-এ মোট " + BanglaText.digits(info.pages) + " পেজ।", 17, false, INK));
        final EditText title = new EditText(this);
        title.setText(info.name);
        title.setHint("নাম");
        title.setTextSize(18);
        box.addView(title);
        final EditText from = new EditText(this);
        from.setInputType(InputType.TYPE_CLASS_NUMBER);
        from.setText("1");
        from.setHint("শুরুর পেজ");
        from.setTextSize(18);
        box.addView(labeled("কোন পেজ থেকে:", from));
        final EditText to = new EditText(this);
        to.setInputType(InputType.TYPE_CLASS_NUMBER);
        to.setText(String.valueOf(info.pages));
        to.setHint("শেষ পেজ");
        to.setTextSize(18);
        box.addView(labeled("কোন পেজ পর্যন্ত:", to));

        boolean hasKey = !prefs.getString("gemini_key", "").trim().isEmpty();
        RadioGroup rg = new RadioGroup(this);
        final RadioButton ocr = new RadioButton(this);
        ocr.setId(View.generateViewId());
        ocr.setText("Gemini দিয়ে পেজের ছবি পড়া (বাংলা ও স্ক্যান করা PDF-এর জন্য ভালো, ইন্টারনেট লাগে)");
        ocr.setTextSize(16);
        final RadioButton direct = new RadioButton(this);
        direct.setId(View.generateViewId());
        direct.setText("PDF-এর ভেতরের লেখা সরাসরি নেওয়া (ইন্টারনেট ছাড়া, ইংরেজি বা সাধারণ লেখার জন্য)");
        direct.setTextSize(16);
        rg.addView(ocr);
        rg.addView(direct);
        if (hasKey) ocr.setChecked(true);
        else direct.setChecked(true);
        box.addView(rg);
        if (!hasKey) box.addView(text("Gemini দিয়ে পড়তে সেটিংস ট্যাবে API কী দিতে হবে।", 15, false, MUTED));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("PDF যোগ করুন")
                .setView(sv)
                .setPositiveButton("শুরু করুন", (d, w) -> {
                    int a, z;
                    try {
                        a = Integer.parseInt(BanglaText.asciiDigits(from.getText().toString().trim()));
                        z = Integer.parseInt(BanglaText.asciiDigits(to.getText().toString().trim()));
                    } catch (NumberFormatException ex) {
                        Toast.makeText(this, "পেজ নম্বর ঠিকমতো লিখুন", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (a < 1 || z > info.pages || a > z) {
                        Toast.makeText(this, "পেজ ১ থেকে " + BanglaText.digits(info.pages) + "-এর মধ্যে, আর শুরু শেষের আগে হতে হবে", Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean useOcr = ocr.isChecked();
                    if (useOcr && prefs.getString("gemini_key", "").trim().isEmpty()) {
                        Toast.makeText(this, "আগে সেটিংস ট্যাবে Gemini API কী দিন, অথবা সরাসরি লেখা নেওয়া বাছুন", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String t = title.getText().toString().trim();
                    startImport(info, t.isEmpty() ? info.name : t, useOcr, a, z);
                })
                .setNegativeButton("বাতিল", (d, w) -> info.file.delete())
                .setOnCancelListener(d -> info.file.delete())
                .show();
    }

    private void startImport(Importer.Info info, String title, boolean ocr, int from, int to) {
        Importer.Job j = new Importer.Job();
        j.file = info.file;
        j.title = title;
        j.ocr = ocr;
        j.from = from;
        j.to = to;
        Importer.job = j;
        Importer.running = true;
        Importer.done = 0;
        Importer.total = to - from + 1;
        Importer.status = "শুরু হচ্ছে…";
        askNotifications();
        ImportService.start(this);
        handler.removeCallbacks(tick);
        handler.post(tick);
        Toast.makeText(this, "PDF পড়া শুরু হয়েছে। অ্যাপ ছেড়ে গেলেও চলবে।", Toast.LENGTH_LONG).show();
    }

    private void updateImportCard() {
        if (importCard == null) return;
        boolean show = Importer.running || (Importer.status != null && !Importer.status.isEmpty() && Importer.total > 0);
        importCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        tvImport.setText(Importer.status);
        importBar.setProgress(Importer.total > 0 ? Importer.done * 100 / Importer.total : 0);
        btnImportRead.setVisibility(Importer.running && Importer.done >= 1 && Importer.activeDoc > 0 ? View.VISIBLE : View.GONE);
        btnImportStop.setVisibility(Importer.running ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ tab 3: library
    private View buildLibrary() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(box);
        TextView head = text("লাইব্রেরি", 26, true, ACCENT);
        ViewCompat.setAccessibilityHeading(head, true);
        box.addView(head);
        box.addView(text("এখানকার সবকিছু ফোনে সংরক্ষিত, ইন্টারনেট ছাড়াই পড়া যায়।", 16, false, MUTED));
        libList = new LinearLayout(this);
        libList.setOrientation(LinearLayout.VERTICAL);
        box.addView(libList);
        return sv;
    }

    private static String sourceName(String s) {
        if ("pdf".equals(s)) return "PDF";
        if ("txt".equals(s)) return "টেক্সট ফাইল";
        return "পেস্ট করা লেখা";
    }

    private String dateText(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return BanglaText.digits(c.get(Calendar.DAY_OF_MONTH)) + " " + BanglaText.MONTHS[c.get(Calendar.MONTH)] + " " + BanglaText.digits(c.get(Calendar.YEAR));
    }

    private void refreshLibrary() {
        if (libList == null) return;
        libList.removeAllViews();
        List<Store.Doc> docs = Store.get(this).docs();
        if (docs.isEmpty()) {
            TextView t = text("লাইব্রেরি এখনও খালি। 'যোগ করুন' ট্যাব থেকে লেখা বা PDF যোগ করুন।", 18, false, INK);
            t.setPadding(0, dp(16), 0, 0);
            libList.addView(t);
            return;
        }
        for (final Store.Doc d : docs) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackground(card());
            card.addView(text(d.title, 19, true, INK));
            boolean importing = Importer.running && Importer.activeDoc == d.id;
            String info = sourceName(d.source) + " · " + BanglaText.digits(d.pages) + " পেজ"
                    + ("pdf".equals(d.source) && d.firstPage > 1 ? " (PDF-এর " + BanglaText.digits(d.firstPage) + " নম্বর পেজ থেকে)" : "")
                    + " · শেষ পড়া পেজ " + BanglaText.digits(d.lastPage)
                    + " · যোগ হয়েছে " + dateText(d.created);
            if (importing) info += " · PDF পড়া চলছে " + BanglaText.digits(Importer.done) + "/" + BanglaText.digits(Importer.total);
            TextView ti = text(info, 14, false, MUTED);
            ti.setPadding(0, dp(2), 0, dp(8));
            card.addView(ti);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button open = small("খুলুন", d.title + " খুলুন");
            open.setOnClickListener(v -> {
                Player.I.open(d.id, d.lastPage, d.lastSent);
                select(0);
            });
            Button ren = small("নাম বদলান", d.title + " এর নাম বদলান");
            ren.setOnClickListener(v -> renameDialog(d));
            Button del = small("মুছুন", d.title + " মুছে ফেলুন");
            del.setOnClickListener(v -> deleteDialog(d));
            addWeighted(row, open, ren, del);
            card.addView(row);
            LinearLayout.LayoutParams lp = fullWidth(10);
            libList.addView(card, lp);
        }
    }

    private void renameDialog(final Store.Doc d) {
        final EditText in = new EditText(this);
        in.setText(d.title);
        in.setTextSize(18);
        new AlertDialog.Builder(this)
                .setTitle("নতুন নাম")
                .setView(pad(in))
                .setPositiveButton("সংরক্ষণ", (x, w) -> {
                    String t = in.getText().toString().trim();
                    if (t.isEmpty()) return;
                    Store.get(this).rename(d.id, t);
                    if (Player.I.doc != null && Player.I.doc.id == d.id) Player.I.open(d.id, Player.I.page, Player.I.idx);
                    refreshLibrary();
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void deleteDialog(final Store.Doc d) {
        new AlertDialog.Builder(this)
                .setTitle("\"" + d.title + "\" মুছবেন?")
                .setMessage("লাইব্রেরি থেকে মুছে গেলে এটা আর ফিরে পাবেন না।")
                .setPositiveButton("মুছুন", (x, w) -> {
                    if (Importer.running && Importer.activeDoc == d.id) Importer.cancel = true;
                    Player.I.closeIfOpen(d.id);
                    Store.get(this).delete(d.id);
                    refreshLibrary();
                    refreshReader();
                    Toast.makeText(this, "মুছে গেছে", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("থাক", null)
                .show();
    }

    // ------------------------------------------------------------------ tab 4: settings
    private View buildSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(box);
        TextView head = text("সেটিংস", 26, true, ACCENT);
        ViewCompat.setAccessibilityHeading(head, true);
        box.addView(head);

        box.addView(heading("Gemini (PDF-এর ছবি পড়ার জন্য)"));
        box.addView(text("আপনার নিজের Gemini API কী এখানে একবার দিন। এটা শুধু এই ফোনে থাকে, কোথাও আপলোড হয় না। কী ছাড়াও পেস্ট করা লেখা, টেক্সট ফাইল আর সরাসরি-লেখার PDF চলে।", 16, false, MUTED));
        tvKey = text("", 16, true, INK);
        tvKey.setPadding(0, dp(8), 0, 0);
        box.addView(tvKey);
        Button key = secondary("API কী দিন বা বদলান");
        key.setOnClickListener(v -> keyDialog());
        box.addView(key, fullWidth(8));
        Button model = secondary("মডেলের নাম");
        model.setOnClickListener(v -> modelDialog());
        box.addView(model, fullWidth(8));

        box.addView(heading("পড়ার ভয়েস"));
        tvVoice = text("", 16, true, INK);
        box.addView(tvVoice);
        Button voice = secondary("ভয়েস বাছুন");
        voice.setOnClickListener(v -> voiceDialog());
        box.addView(voice, fullWidth(8));
        Button sample = secondary("নমুনা শুনুন");
        sample.setOnClickListener(v -> Player.I.sample("আমার সোনার বাংলা, আমি তোমায় ভালোবাসি।"));
        box.addView(sample, fullWidth(8));
        Button tts = secondary("ফোনের টেক্সট-টু-স্পিচ সেটিংস খুলুন (বাংলা ভয়েস ডাউনলোডের জন্য)");
        tts.setOnClickListener(v -> openTtsSettings());
        box.addView(tts, fullWidth(8));

        box.addView(heading("পড়ার সময়"));
        final CheckBox keep = new CheckBox(this);
        keep.setText("স্ক্রিন জাগিয়ে রাখো (অ্যাপ খোলা থাকলে)");
        keep.setTextSize(17);
        keep.setTextColor(INK);
        keep.setMinHeight(dp(56));
        keep.setChecked(prefs.getBoolean("keep_on", false));
        keep.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("keep_on", on).apply();
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        box.addView(keep);
        box.addView(text("পড়ার গতি, অক্ষরের মাপ আর ঘুমের টাইমার 'পড়ুন' ট্যাব থেকে বদলানো যায়।", 15, false, MUTED));

        box.addView(heading("অ্যাপ সম্পর্কে"));
        box.addView(text("স্মার্ট রিডার ১.০। লেখা পেস্ট করুন বা PDF যোগ করুন, লাইব্রেরিতে ফোনে রাখুন, আর ফোনের ভয়েসে পড়ে শোনান। স্ক্রিন বন্ধ থাকলেও পড়া চলে। Gemini ব্যবহার করলে শুধু PDF-এর পেজের ছবি Google-এ যায়, আর কিছু নয়।", 16, false, INK));
        return sv;
    }

    private void refreshSettings() {
        if (tvKey == null) return;
        String k = prefs.getString("gemini_key", "").trim();
        tvKey.setText(k.isEmpty() ? "API কী এখনও দেওয়া হয়নি" : "API কী সংরক্ষিত আছে (শেষে ..." + k.substring(Math.max(0, k.length() - 4)) + ")");
        String v = prefs.getString("voice", "");
        tvVoice.setText(v.isEmpty() ? "ভয়েস: ফোনের ডিফল্ট বাংলা ভয়েস" : "ভয়েস: " + v);
    }

    private void keyDialog() {
        final EditText in = new EditText(this);
        in.setHint("এখানে API কী পেস্ট করুন");
        in.setTextSize(16);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle("Gemini API কী")
                .setMessage("Google AI Studio থেকে পাওয়া নিজের কী দিন। ফাঁকা রেখে সংরক্ষণ করলে কী মুছে যাবে।")
                .setView(pad(in))
                .setPositiveButton("সংরক্ষণ", (d, w) -> {
                    prefs.edit().putString("gemini_key", in.getText().toString().trim()).apply();
                    refreshSettings();
                    Toast.makeText(this, "সংরক্ষিত", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void modelDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText m1 = new EditText(this);
        m1.setText(prefs.getString("ocr_model", "gemini-3.5-flash-lite"));
        m1.setTextSize(16);
        box.addView(labeled("প্রথমে এই মডেলে চেষ্টা:", m1));
        final EditText m2 = new EditText(this);
        m2.setText(prefs.getString("ocr_model2", "gemini-2.5-flash"));
        m2.setTextSize(16);
        box.addView(labeled("না হলে এই মডেলে:", m2));
        box.addView(text("Google মডেলের নাম বদলালে বা বন্ধ করলে এখানে নতুন নাম দিলেই চলবে।", 14, false, MUTED));
        new AlertDialog.Builder(this)
                .setTitle("ছবি পড়ার মডেল")
                .setView(box)
                .setPositiveButton("সংরক্ষণ", (d, w) -> {
                    String a = m1.getText().toString().trim();
                    String b = m2.getText().toString().trim();
                    SharedPreferences.Editor e = prefs.edit();
                    if (!a.isEmpty()) e.putString("ocr_model", a);
                    e.putString("ocr_model2", b);
                    e.apply();
                    Toast.makeText(this, "সংরক্ষিত", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void voiceDialog() {
        Toast.makeText(this, "ভয়েসের তালিকা আনা হচ্ছে…", Toast.LENGTH_SHORT).show();
        Player.I.prepareVoices(() -> {
            final List<Voice> vs = Player.I.bengaliVoices();
            if (vs.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("বাংলা ভয়েস পাওয়া যায়নি")
                        .setMessage("ফোনে বাংলা ভয়েস নেই। 'ফোনের টেক্সট-টু-স্পিচ সেটিংস' থেকে Google Text-to-speech-এ বাংলা ভয়েস ডাউনলোড করুন।")
                        .setPositiveButton("সেটিংস খুলুন", (d, w) -> openTtsSettings())
                        .setNegativeButton("থাক", null)
                        .show();
                return;
            }
            String[] names = new String[vs.size() + 1];
            names[0] = "ফোনের ডিফল্ট বাংলা ভয়েস";
            for (int i = 0; i < vs.size(); i++) {
                Voice v = vs.get(i);
                names[i + 1] = "বাংলা ভয়েস " + BanglaText.digits(i + 1) + " (" + v.getName() + ")"
                        + (v.isNetworkConnectionRequired() ? " · ইন্টারনেট লাগে" : " · অফলাইন");
            }
            new AlertDialog.Builder(this)
                    .setTitle("ভয়েস বাছুন")
                    .setItems(names, (d, w) -> {
                        prefs.edit().putString("voice", w == 0 ? "" : vs.get(w - 1).getName()).apply();
                        Player.I.voiceChanged();
                        refreshSettings();
                        Player.I.sample("এই ভয়েসে পড়া হবে।");
                    })
                    .show();
        });
    }

    private void openTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "সেটিংস খোলা যায়নি", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    // ------------------------------------------------------------------ small helpers
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 20, true, INK);
        ViewCompat.setAccessibilityHeading(t, true);
        t.setPadding(0, dp(22), 0, dp(6));
        return t;
    }

    private GradientDrawable card() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), LINE);
        return g;
    }

    private Button primary(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setTextColor(ON_ACCENT);
        b.setMinHeight(dp(56));
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        return b;
    }

    private Button secondary(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(ACCENT);
        b.setMinHeight(dp(52));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(2), ACCENT);
        b.setBackground(g);
        return b;
    }

    private Button small(String s, String description) {
        Button b = secondary(s);
        b.setTextSize(15);
        b.setMinHeight(dp(52));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setContentDescription(description);
        return b;
    }

    private LinearLayout.LayoutParams fullWidth(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(topDp), 0, 0);
        return lp;
    }

    private void addWeighted(LinearLayout row, View... views) {
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), dp(4), dp(3), 0);
            row.addView(v, lp);
        }
    }

    private View pad(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(v, new FrameLayout.LayoutParams(-1, -2));
        return f;
    }

    private View labeled(String label, View field) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(label, 15, false, MUTED);
        t.setPadding(0, dp(10), 0, 0);
        l.addView(t);
        l.addView(field);
        return l;
    }
}
