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
import android.widget.SeekBar;
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

    private static final String[] TAB_NAMES = {"রিডার", "টুলস", "লাইব্রেরি", "সেটিংস"};
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
    private ScrollView readerScroll;
    private LinearLayout textCard;
    private EditText jumpIn;
    private Button btnPlay;
    private TextView tvPageInd, tvProc;
    private ProgressBar procBar;
    private SeekBar seekPage;
    private boolean seekBusy = false;
    private Button btnShowText;
    private boolean autoStart = false;
    private boolean backWarned = false;
    private LinearLayout draftRow;
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
    private EditText libSearch;
    private TextView tvLibCount;
    private TextView tvKey, tvVoice, tvEngine, tvGv;
    private LinearLayout gBox, sysBox;
    private RadioGroup engGroup;
    private RadioButton rbSys, rbG;
    private boolean engBusy = false;

    private final Player.Listener listener = this::refreshReader;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateImportCard();
            if (autoStart && Importer.activeDoc > 0 && Importer.done >= 1 && Importer.running) {
                autoStart = false;
                Player.I.open(Importer.activeDoc, 1, 0);
                askNotifications();
                Player.I.play();
            }
            if (Importer.running) {
                if (Player.I.doc != null && Importer.activeDoc == Player.I.doc.id) Player.I.refreshPage();
                handler.postDelayed(this, 900);
            } else if (tab == 2) {
                backWarned = false;
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
        else tab = 0;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pages[0] = buildReader();
        pages[1] = buildTools();
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

    // ------------------------------------------------------------------ back button
    @Override
    public void onBackPressed() {
        // processing not finished: one warning first
        if (Importer.running && !backWarned && Importer.total > 0) {
            int pct = Importer.done * 100 / Importer.total;
            new AlertDialog.Builder(this)
                    .setTitle("প্রসেস এখনও শেষ হয়নি")
                    .setMessage("এখন পর্যন্ত " + BanglaText.digits(pct) + "% হয়েছে (" + BanglaText.digits(Importer.done) + "/" + BanglaText.digits(Importer.total)
                            + " পেজ)। এখন বেরোলে বাকি পেজগুলো তৈরি নাও হতে পারে।")
                    .setPositiveButton("তবুও বের হব", (d, w) -> {
                        backWarned = true;
                        onBackPressed();
                    })
                    .setNegativeButton("থাকুন", null)
                    .show();
            return;
        }
        final Store.Doc open = Player.I.doc;
        if (open != null && Drafts.has(this, open.id)) {
            new AlertDialog.Builder(this)
                    .setTitle("এই লেখা এখনও সেভ হয়নি")
                    .setMessage("সেভ করলে লাইব্রেরিতে থাকবে। সেভ না করে বেরোলে এটা মুছে যাবে।")
                    .setPositiveButton("সেভ করুন", (d, w) -> saveDraftDialog(() -> {
                        Player.I.pause();
                        finish();
                    }))
                    .setNegativeButton("সেভ না করে বের হন", (d, w) -> {
                        dropDraft();
                        finish();
                    })
                    .setNeutralButton("থাকুন", null)
                    .show();
            return;
        }
        if (Importer.running) {
            new AlertDialog.Builder(this)
                    .setTitle("PDF পড়া চলছে")
                    .setMessage(Importer.status + "\n\nবের হয়ে গেলে কী করবেন?")
                    .setPositiveButton("এ পর্যন্ত যা হয়েছে সংরক্ষণ করে বন্ধ করুন", (d, w) -> {
                        Importer.cancel = true;
                        finish();
                    })
                    .setNeutralButton("পিছনে চলতে থাকুক", (d, w) -> finish())
                    .setNegativeButton("বাতিল", null)
                    .show();
            return;
        }
        if (Player.I.doc != null && Player.I.playing) {
            new AlertDialog.Builder(this)
                    .setTitle("পড়া চলছে")
                    .setMessage("বের হয়ে গেলে কী করবেন?")
                    .setPositiveButton("সংরক্ষণ করে বের হন", (d, w) -> {
                        Player.I.pause();          // saves the place
                        finish();
                    })
                    .setNeutralButton("পিছনে পড়া চলতে থাকুক", (d, w) -> finish())
                    .setNegativeButton("বাতিল", null)
                    .show();
            return;
        }
        super.onBackPressed();
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
        if (i == 1) refreshTools();
        if (i == 2) refreshLibrary();
        if (i == 3) refreshSettings();
        updateImportCard();
    }

    // ------------------------------------------------------------------ tab 1: reader
    private View buildReader() {
        readerScroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(12), dp(14), dp(20));
        readerScroll.addView(page);

        TextView hd = text("স্মার্ট রিডার", 26, true, ACCENT);
        ViewCompat.setAccessibilityHeading(hd, true);
        page.addView(hd);
        page.addView(buildAddCard(), fullWidth(8));

        emptyCard = new LinearLayout(this);
        emptyCard.setOrientation(LinearLayout.VERTICAL);
        TextView e = text("এখনও কিছু খোলা নেই। ওপরে ফাইল বাছুন বা লেখা পেস্ট করুন। আগে সংরক্ষিত কিছু পড়তে 'লাইব্রেরি' ট্যাবে যান।", 17, false, INK);
        e.setPadding(0, dp(16), 0, dp(8));
        emptyCard.addView(e);
        page.addView(emptyCard);

        readerBody = new LinearLayout(this);
        readerBody.setOrientation(LinearLayout.VERTICAL);
        readerBody.setPadding(0, dp(12), 0, 0);
        page.addView(readerBody, new LinearLayout.LayoutParams(-1, -2));

        tvTitle = text("", 22, true, ACCENT);
        ViewCompat.setAccessibilityHeading(tvTitle, true);
        readerBody.addView(tvTitle);
        tvInfo = text("", 15, false, MUTED);
        readerBody.addView(tvInfo);
        tvMsg = text("", 15, true, INK);
        tvMsg.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tvMsg.setText(" ");
        tvMsg.setVisibility(View.INVISIBLE);
        readerBody.addView(tvMsg);

        // save / discard (shown only while the text is not in the library yet)
        draftRow = new LinearLayout(this);
        draftRow.setOrientation(LinearLayout.HORIZONTAL);
        draftRow.setVisibility(View.GONE);
        Button bSaveD = colored("💾 লাইব্রেরিতে সেভ করুন", 0xFF0F9D58, 0xFFFFFFFF, "এই লেখা বইয়ের নাম দিয়ে লাইব্রেরিতে সেভ করুন");
        bSaveD.setOnClickListener(v -> saveDraftDialog(null));
        Button bDrop = colored("🗑 বাদ দিন", 0xFFD93025, 0xFFFFFFFF, "এই লেখা সেভ না করে মুছে ফেলুন");
        bDrop.setOnClickListener(v -> dropDraftDialog());
        addWeighted(draftRow, bSaveD, bDrop);
        readerBody.addView(draftRow);

        // player: big coloured buttons
        TextView pl = text("প্লেয়ার কন্ট্রোল:", 18, true, INK);
        ViewCompat.setAccessibilityHeading(pl, true);
        pl.setPadding(0, dp(10), 0, dp(2));
        readerBody.addView(pl);

        LinearLayout ra = new LinearLayout(this);
        ra.setOrientation(LinearLayout.HORIZONTAL);
        Button bNext = colored("নেক্সট ➡️", 0xFFD93025, 0xFFFFFFFF, "পরের পেজ");
        bNext.setOnClickListener(v -> Player.I.nextPage());
        Button bFast = colored("⏩ ফাস্ট", 0xFF1A73E8, 0xFFFFFFFF, "ফাস্ট ফরওয়ার্ড: দশ বাক্য সামনে");
        bFast.setOnClickListener(v -> Player.I.skipSentences(10));
        btnPlay = colored("▶️ প্লে", 0xFF0F9D58, 0xFFFFFFFF, "প্লে করুন");
        btnPlay.setOnClickListener(v -> {
            askNotifications();
            Player.I.toggle();
        });
        addWeighted(ra, bNext, bFast, btnPlay);
        readerBody.addView(ra);

        LinearLayout rb = new LinearLayout(this);
        rb.setOrientation(LinearLayout.HORIZONTAL);
        Button bRew = colored("⏪ রিওয়াইন্ড", 0xFF1A73E8, 0xFFFFFFFF, "রিওয়াইন্ড: দশ বাক্য পিছনে");
        bRew.setOnClickListener(v -> Player.I.skipSentences(-10));
        Button bPrev = colored("⬅️ প্রিভিয়াস", 0xFFD93025, 0xFFFFFFFF, "আগের পেজ");
        bPrev.setOnClickListener(v -> Player.I.prevPage());
        addWeighted(rb, bRew, bPrev);
        readerBody.addView(rb);

        LinearLayout rc = new LinearLayout(this);
        rc.setOrientation(LinearLayout.HORIZONTAL);
        Button bPS = small("◀ বাক্য", "আগের বাক্য");
        bPS.setOnClickListener(v -> Player.I.prevSentence());
        Button bNS = small("বাক্য ▶", "পরের বাক্য");
        bNS.setOnClickListener(v -> Player.I.nextSentence());
        btnShowText = small(prefs.getBoolean("show_text", false) ? "📖 লেখা লুকান" : "📖 লেখা দেখান", "পড়ার লেখা দেখান বা লুকান");
        btnShowText.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("show_text", false);
            prefs.edit().putBoolean("show_text", on).apply();
            textCard.setVisibility(on ? View.VISIBLE : View.GONE);
            btnShowText.setText(on ? "📖 লেখা লুকান" : "📖 লেখা দেখান");
        });
        addWeighted(rc, bPS, bNS, btnShowText);
        readerBody.addView(rc);

        boolean dark = ACCENT == 0xFF5FC7BF;
        tvPageInd = text("", 18, true, dark ? 0xFFF48FB1 : 0xFFC2185B);
        tvPageInd.setGravity(Gravity.CENTER);
        tvPageInd.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(dark ? 0xFF3B1F2B : 0xFFFCE4EC);
        pg.setCornerRadius(dp(10));
        tvPageInd.setBackground(pg);
        readerBody.addView(tvPageInd, fullWidth(10));

        seekPage = new SeekBar(this);
        seekPage.setContentDescription("পেজ বাছুন");
        seekPage.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean touching = false;
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && !seekBusy && !touching) Player.I.gotoPage(progress + 1);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { touching = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                touching = false;
                if (!seekBusy) Player.I.gotoPage(sb.getProgress() + 1);
            }
        });
        readerBody.addView(seekPage, new LinearLayout.LayoutParams(-1, dp(40)));

        tvProc = text("", 14, false, MUTED);
        tvProc.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        tvProc.setVisibility(View.GONE);
        readerBody.addView(tvProc);
        procBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        procBar.setMax(100);
        procBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);   // the text above says it; the bar stays silent
        procBar.setVisibility(View.GONE);
        readerBody.addView(procBar, new LinearLayout.LayoutParams(-1, dp(10)));

        // jump to a page: a visible number box
        LinearLayout jr = new LinearLayout(this);
        jr.setOrientation(LinearLayout.HORIZONTAL);
        jr.setGravity(Gravity.CENTER_VERTICAL);
        jumpIn = new EditText(this);
        jumpIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        jumpIn.setHint("পেজ নম্বর লিখুন");
        jumpIn.setContentDescription("যে পেজে যেতে চান তার নম্বর");
        jumpIn.setTextSize(18);
        jumpIn.setTextColor(INK);
        jumpIn.setHintTextColor(MUTED);
        jumpIn.setMinHeight(dp(56));
        Button go = colored("যাও", 0xFFFBBC04, 0xFF000000, "এই পেজে যান");
        go.setOnClickListener(v -> {
            try {
                Player.I.gotoPage(Integer.parseInt(BanglaText.asciiDigits(jumpIn.getText().toString().trim())));
                jumpIn.setText("");
            } catch (NumberFormatException ex) {
                Toast.makeText(this, "পেজ নম্বর লিখুন", Toast.LENGTH_SHORT).show();
            }
        });
        jr.addView(jumpIn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        gl.setMargins(dp(8), 0, 0, 0);
        jr.addView(go, gl);
        readerBody.addView(jr, fullWidth(10));


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
        Button timer = small("টাইমার", "ঘুমের টাইমার");
        timer.setOnClickListener(v -> timerDialog());
        Button fs = small("A−", "অক্ষর ছোট করুন");
        fs.setOnClickListener(v -> changeFont(-2));
        Button fb = small("A+", "অক্ষর বড় করুন");
        fb.setOnClickListener(v -> changeFont(2));
        Button shareBtn = small("শেয়ার", "এই লেখা শেয়ার করুন");
        shareBtn.setOnClickListener(v -> {
            if (Player.I.doc != null) shareDialog(Player.I.doc, Player.I.page);
        });
        addWeighted(moreRow, timer, shareBtn, fs, fb);
        readerBody.addView(moreRow);
        tvTimer = text("", 14, false, MUTED);
        readerBody.addView(tvTimer);




        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        Button cp = small("📋 এই পেজ কপি", "এই পেজের লেখা কপি করুন");
        cp.setOnClickListener(v -> {
            if (Player.I.text.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("page", Player.I.text));
            Toast.makeText(this, "কপি হয়েছে", Toast.LENGTH_SHORT).show();
        });
        Button again = small("🔁 আবার পড়", "এই পেজ আবার প্রথম থেকে পড়ুন");
        again.setOnClickListener(v -> Player.I.gotoPage(Player.I.page));
        Button first = small("⏮ প্রথম পেজ", "প্রথম পেজে যান");
        first.setOnClickListener(v -> Player.I.gotoPage(1));
        addWeighted(r3, cp, again, first);
        readerBody.addView(r3);

        // the text itself, at the bottom of the page
        textCard = new LinearLayout(this);
        textCard.setOrientation(LinearLayout.VERTICAL);
        textCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        textCard.setBackground(card());
        tvText = text("", fontSp, false, INK);
        tvText.setLineSpacing(0f, 1.4f);
        textCard.addView(tvText);
        textCard.setVisibility(prefs.getBoolean("show_text", false) ? View.VISIBLE : View.GONE);
        readerBody.addView(textCard, fullWidth(12));

        return readerScroll;
    }

    private void refreshReader() {
        if (readerBody == null) return;
        if (tab == 1) refreshTools();
        Player p = Player.I;
        boolean has = p.doc != null;
        emptyCard.setVisibility(has ? View.VISIBLE : View.GONE);
        readerBody.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) {
            emptyCard.setVisibility(View.GONE);
        }
        if (!has) return;

        tvTitle.setText(p.doc.title);
        boolean isDraft = Drafts.has(this, p.doc.id);
        draftRow.setVisibility(isDraft ? View.VISIBLE : View.GONE);
        tvPageInd.setText("বর্তমান পেজ: " + BanglaText.digits(p.page) + " / " + BanglaText.digits(p.doc.pages)
                + " · বাকি: " + BanglaText.digits(Math.max(0, p.doc.pages - p.page)));
        seekBusy = true;
        seekPage.setMax(Math.max(1, p.doc.pages - 1));
        seekPage.setProgress(Math.max(0, p.page - 1));
        seekBusy = false;
        if (Importer.running && Importer.activeDoc == p.doc.id && Importer.total > 0) {
            int pct = Importer.done * 100 / Importer.total;
            tvProc.setText("প্রসেসিং: " + BanglaText.digits(pct) + "% (" + BanglaText.digits(Importer.done) + "/" + BanglaText.digits(Importer.total) + " পেজ তৈরি)");
            procBar.setProgress(pct);
            tvProc.setVisibility(View.VISIBLE);
            procBar.setVisibility(View.VISIBLE);
        } else {
            tvProc.setVisibility(View.GONE);
            procBar.setVisibility(View.GONE);
        }
        String info = "বাক্য " + BanglaText.digits(p.sents.isEmpty() ? 0 : p.idx + 1) + "/" + BanglaText.digits(p.sents.size())
                + (isDraft ? " · এখনও সেভ হয়নি" : "");
        tvInfo.setText(info);
        if (p.message == null || p.message.isEmpty()) {
            tvMsg.setVisibility(View.INVISIBLE);
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
            int endSent = p.playing ? Math.min(Math.max(p.idxEnd, p.idx), p.sents.size() - 1) : p.idx;
            hlSpan = new BackgroundColorSpan(HL);
            sp.setSpan(hlSpan, s[0], p.sents.get(endSent)[1], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            final int start = s[0];
            tvText.post(() -> scrollTo(start));
        }

        btnPlay.setText(p.playing ? "⏸ পজ" : "▶️ প্লে");
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
        // the page does not move by itself while reading: the player buttons stay where they are
    }

    /** distance from the top of an ancestor's content to the top of a view inside it */
    private int offsetIn(View v, View ancestor) {
        int y = 0;
        View cur = v;
        while (cur != null && cur != ancestor) {
            y += cur.getTop();
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return y;
    }

    private void changeFont(int delta) {
        fontSp = Math.max(14, Math.min(40, fontSp + delta));
        prefs.edit().putInt("font_sp", fontSp).apply();
        tvText.setTextSize(fontSp);
    }

    /** an unsaved text that is open and is not the one we are about to open is thrown away */
    private void discardCurrentDraft(long keepId) {
        Store.Doc cur = Player.I.doc;
        if (cur == null || cur.id == keepId || !Drafts.has(this, cur.id)) return;
        if (Importer.running && Importer.activeDoc == cur.id) Importer.cancel = true;
        Player.I.closeIfOpen(cur.id);
        Store.get(this).delete(cur.id);
        Drafts.remove(this, cur.id);
    }

    private void saveDraftDialog(final Runnable after) {
        final Store.Doc d = Player.I.doc;
        if (d == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText nm = new EditText(this);
        nm.setText(d.title);
        nm.setTextSize(18);
        nm.setSelectAllOnFocus(true);
        box.addView(labeled("বইয়ের নাম:", nm));
        final EditText from = new EditText(this);
        from.setInputType(InputType.TYPE_CLASS_NUMBER);
        from.setText("1");
        from.setTextSize(18);
        box.addView(labeled("কোন পেজ থেকে সেভ করবেন:", from));
        final EditText to = new EditText(this);
        to.setInputType(InputType.TYPE_CLASS_NUMBER);
        to.setText(String.valueOf(d.pages));
        to.setTextSize(18);
        box.addView(labeled("কোন পেজ পর্যন্ত (মোট " + BanglaText.digits(d.pages) + " পেজ):", to));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("লাইব্রেরিতে সেভ করুন")
                .setView(sv)
                .setPositiveButton("সেভ করুন", (x, w) -> {
                    String t = nm.getText().toString().trim();
                    if (t.isEmpty()) t = d.title;
                    int a, z;
                    try {
                        a = Integer.parseInt(BanglaText.asciiDigits(from.getText().toString().trim()));
                        z = Integer.parseInt(BanglaText.asciiDigits(to.getText().toString().trim()));
                    } catch (NumberFormatException ex) {
                        Toast.makeText(this, "পেজ নম্বর ঠিকমতো লিখুন", Toast.LENGTH_LONG).show();
                        return;
                    }
                    a = Math.max(1, Math.min(a, d.pages));
                    z = Math.max(a, Math.min(z, d.pages));
                    if (a == 1 && z >= d.pages) {
                        Store.get(this).rename(d.id, t);
                        Drafts.remove(this, d.id);
                        Player.I.refreshPage();
                        refreshReader();
                        Toast.makeText(this, "লাইব্রেরিতে সেভ হয়েছে", Toast.LENGTH_SHORT).show();
                    } else {
                        String body = ShareHelper.gather(Store.get(this), d, a, z);
                        if (body.isEmpty()) {
                            Toast.makeText(this, "এই পেজগুলোতে এখনও কোনো লেখা নেই", Toast.LENGTH_LONG).show();
                            return;
                        }
                        saveText(t, "paste", body);
                        Toast.makeText(this, "পেজ " + BanglaText.digits(a) + " থেকে " + BanglaText.digits(z) + " লাইব্রেরিতে সেভ হয়েছে", Toast.LENGTH_LONG).show();
                    }
                    if (after != null) after.run();
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void dropDraft() {
        Store.Doc d = Player.I.doc;
        if (d == null) return;
        if (Importer.running && Importer.activeDoc == d.id) Importer.cancel = true;
        Player.I.closeIfOpen(d.id);
        Store.get(this).delete(d.id);
        Drafts.remove(this, d.id);
        refreshReader();
        refreshLibrary();
    }

    private void dropDraftDialog() {
        new AlertDialog.Builder(this)
                .setTitle("এই লেখা বাদ দেবেন?")
                .setMessage("সেভ করা হয়নি, তাই বাদ দিলে এটা আর ফিরে পাবেন না।")
                .setPositiveButton("বাদ দিন", (x, w) -> {
                    dropDraft();
                    Toast.makeText(this, "বাদ দেওয়া হয়েছে", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("থাক", null)
                .show();
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
    private View buildAddCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(card());

        TextView hd = text("১. ফাইল, ছবি বা ভিডিও বেছে নিন", 18, true, INK);
        ViewCompat.setAccessibilityHeading(hd, true);
        box.addView(hd);
        box.addView(text("PDF, টেক্সট, ছবি বা ভিডিও। PDF আর ছবি Gemini দিয়ে পড়া হয়, ছবির বর্ণনা ও ভিডিওর দৃশ্যের বর্ণনাও পাওয়া যায় (ইন্টারনেট ও সেটিংসে API কী লাগে)। পড়া হয়ে গেলে লেখা ফোনে থাকে, তখন ইন্টারনেট লাগে না।", 14, false, MUTED));
        pasteTitle = new EditText(this);
        pasteTitle.setHint("বইয়ের নাম (না দিলে ফাইল বা লেখা থেকে নিজে হবে)");
        pasteTitle.setContentDescription("বইয়ের নাম, ঐচ্ছিক");
        pasteTitle.setTextSize(17);
        pasteTitle.setTextColor(INK);
        pasteTitle.setHintTextColor(MUTED);
        pasteTitle.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        pasteTitle.setMinHeight(dp(52));
        box.addView(pasteTitle, fullWidth(6));
        Button pick = primary("ফাইল বাছুন");
        pick.setOnClickListener(v -> pickFile());
        box.addView(pick, fullWidth(8));

        TextView h2 = text("অথবা টেক্সট পেস্ট করুন", 18, true, INK);
        h2.setPadding(0, dp(14), 0, 0);
        ViewCompat.setAccessibilityHeading(h2, true);
        box.addView(h2);
        pasteBody = new EditText(this);
        pasteBody.setHint("এখানে লেখা পেস্ট করুন");
        pasteBody.setTextSize(17);
        pasteBody.setTextColor(INK);
        pasteBody.setHintTextColor(MUTED);
        pasteBody.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        pasteBody.setGravity(Gravity.TOP | Gravity.START);
        pasteBody.setMinLines(2);
        pasteBody.setMaxLines(6);
        box.addView(pasteBody);
        LinearLayout pr = new LinearLayout(this);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        Button fromClip = small("📋 ক্লিপবোর্ড থেকে", "ক্লিপবোর্ড থেকে বসান");
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
        Button save = small("প্রসেস ও খুলুন", "লাইব্রেরিতে রেখে খুলুন");
        save.setOnClickListener(v -> savePasted());
        addWeighted(pr, fromClip, save);
        box.addView(pr);

        importCard = new LinearLayout(this);
        importCard.setOrientation(LinearLayout.VERTICAL);
        importCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        importCard.setBackground(card());
        importCard.setVisibility(View.GONE);
        TextView ih = text("PDF পড়া", 17, true, INK);
        ViewCompat.setAccessibilityHeading(ih, true);
        importCard.addView(ih);
        tvImport = text("", 15, false, INK);
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
        box.addView(importCard, fullWidth(12));
        return box;
    }

    // ------------------------------------------------------------------ tab 2: tools
    private String toolAction = "";
    private int toolFrom = 1, toolTo = 1;
    private TextView tvToolDoc, tvToolStatus;
    private LinearLayout toolRangeBox, toolFormatBox;
    private EditText toolFromIn, toolToIn;
    private boolean translating = false;
    private boolean customNamed = false;

    private View buildTools() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(20));
        sv.addView(box);
        TextView head = text("টুলস", 26, true, ACCENT);
        ViewCompat.setAccessibilityHeading(head, true);
        box.addView(head);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(card());
        box.addView(card, fullWidth(8));

        tvToolDoc = text("", 16, true, INK);
        card.addView(tvToolDoc);
        card.addView(text("যে লেখাটা এখন রিডারে খোলা আছে, তার ওপর কাজ হবে। কোন পেজ থেকে কোন পেজ, সেটা ঠিক করা যায়।", 14, false, MUTED));

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        Button bCopy = small("📋 কপি", "লেখা কপি করুন");
        bCopy.setOnClickListener(v -> startTool("copy"));
        Button bShare = small("📤 শেয়ার", "লেখা শেয়ার করুন");
        bShare.setOnClickListener(v -> startTool("share"));
        Button bDown = small("⬇️ ডাউনলোড", "ফোনে ডাউনলোড করুন");
        bDown.setOnClickListener(v -> startTool("download"));
        addWeighted(r1, bCopy, bShare, bDown);
        card.addView(r1, fullWidth(10));
        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        Button bTr = small("🌐 অনুবাদ", "অন্য ভাষায় অনুবাদ করুন");
        bTr.setOnClickListener(v -> startTool("translate"));
        Button bSave = small("📚 সেভ", "এই অংশ আলাদা করে লাইব্রেরিতে রাখুন");
        bSave.setOnClickListener(v -> startTool("save"));
        addWeighted(r2, bTr, bSave);
        card.addView(r2);

        toolRangeBox = new LinearLayout(this);
        toolRangeBox.setOrientation(LinearLayout.HORIZONTAL);
        toolRangeBox.setGravity(Gravity.CENTER_VERTICAL);
        toolRangeBox.setPadding(dp(10), dp(10), dp(10), dp(10));
        toolRangeBox.setBackground(card());
        toolRangeBox.setVisibility(View.GONE);
        toolRangeBox.addView(text("ফ্রম:", 16, true, INK));
        toolFromIn = new EditText(this);
        toolFromIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        toolFromIn.setContentDescription("শুরুর পেজ");
        toolFromIn.setTextColor(INK);
        toolFromIn.setMinEms(3);
        toolRangeBox.addView(toolFromIn);
        TextView to = text(" টু:", 16, true, INK);
        toolRangeBox.addView(to);
        toolToIn = new EditText(this);
        toolToIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        toolToIn.setContentDescription("শেষ পেজ");
        toolToIn.setTextColor(INK);
        toolToIn.setMinEms(3);
        toolRangeBox.addView(toolToIn);
        Button ok = primary("ওকে");
        ok.setOnClickListener(v -> toolRangeOk());
        LinearLayout.LayoutParams okp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        okp.setMargins(dp(8), 0, 0, 0);
        toolRangeBox.addView(ok, okp);
        card.addView(toolRangeBox, fullWidth(12));

        toolFormatBox = new LinearLayout(this);
        toolFormatBox.setOrientation(LinearLayout.VERTICAL);
        toolFormatBox.setVisibility(View.GONE);
        TextView fh = text("ফরম্যাট বেছে নিন:", 16, true, INK);
        ViewCompat.setAccessibilityHeading(fh, true);
        toolFormatBox.addView(fh);
        LinearLayout fr = new LinearLayout(this);
        fr.setOrientation(LinearLayout.HORIZONTAL);
        Button fTxt = small("📄 TXT", "টেক্সট ফাইল");
        fTxt.setOnClickListener(v -> toolFormat(ShareHelper.TXT));
        Button fPdf = small("📕 PDF", "পিডিএফ ফাইল");
        fPdf.setOnClickListener(v -> toolFormat(ShareHelper.PDF));
        Button fDoc = small("📘 DOC", "ডক ফাইল, ওয়ার্ডে খোলে");
        fDoc.setOnClickListener(v -> toolFormat(ShareHelper.DOC));
        addWeighted(fr, fTxt, fPdf, fDoc);
        toolFormatBox.addView(fr);
        Button fCancel = secondary("বাতিল");
        fCancel.setOnClickListener(v -> toolCancel());
        toolFormatBox.addView(fCancel, fullWidth(6));
        card.addView(toolFormatBox, fullWidth(12));

        tvToolStatus = text("", 15, true, INK);
        tvToolStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tvToolStatus.setPadding(0, dp(10), 0, 0);
        tvToolStatus.setVisibility(View.GONE);
        card.addView(tvToolStatus);

        box.addView(text("PDF আর DOC ফাইল এই অ্যাপই বানায়। DOC আসলে ওয়ার্ডে খোলা যায় এমন ফাইল, নতুন .docx নয়।", 13, false, MUTED), fullWidth(10));
        return sv;
    }

    private void refreshTools() {
        if (tvToolDoc == null) return;
        Store.Doc d = Player.I.doc;
        tvToolDoc.setText(d == null ? "এখন কোনো লেখা খোলা নেই। আগে রিডারে বা লাইব্রেরি থেকে একটা খুলুন।"
                : "খোলা আছে: " + d.title + " (" + BanglaText.digits(d.pages) + " পেজ)");
    }

    private void toolStatus(String m) {
        tvToolStatus.setText(m);
        tvToolStatus.setVisibility(m == null || m.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void toolCancel() {
        toolAction = "";
        toolRangeBox.setVisibility(View.GONE);
        toolFormatBox.setVisibility(View.GONE);
        toolStatus("");
    }

    private void startTool(String action) {
        refreshTools();
        Store.Doc d = Player.I.doc;
        if (d == null) {
            toolStatus("আগে রিডারে একটা লেখা খুলুন।");
            return;
        }
        toolAction = action;
        toolFormatBox.setVisibility(View.GONE);
        toolFromIn.setText(String.valueOf(Player.I.page));
        toolToIn.setText(String.valueOf(d.pages));
        toolRangeBox.setVisibility(View.VISIBLE);
        toolStatus("পেজ রেঞ্জ ঠিক করে ওকে চাপুন। এখন পেজ " + BanglaText.digits(Player.I.page) + " থেকে শেষ পর্যন্ত বসানো আছে।");
        toolFromIn.requestFocus();
    }

    private void toolRangeOk() {
        Store.Doc d = Player.I.doc;
        if (d == null || toolAction.isEmpty()) return;
        try {
            toolFrom = Integer.parseInt(BanglaText.asciiDigits(toolFromIn.getText().toString().trim()));
            toolTo = Integer.parseInt(BanglaText.asciiDigits(toolToIn.getText().toString().trim()));
        } catch (NumberFormatException e) {
            toolStatus("পেজ নম্বর লিখুন।");
            return;
        }
        toolFrom = Math.max(1, Math.min(toolFrom, d.pages));
        toolTo = Math.max(toolFrom, Math.min(toolTo, d.pages));
        toolFromIn.setText(String.valueOf(toolFrom));
        toolToIn.setText(String.valueOf(toolTo));
        if ("share".equals(toolAction) || "download".equals(toolAction)) {
            toolFormatBox.setVisibility(View.VISIBLE);
            toolStatus("এবার ফরম্যাট বেছে নিন।");
            return;
        }
        toolRangeBox.setVisibility(View.GONE);
        try {
            if ("copy".equals(toolAction)) {
                String t = ShareHelper.gather(Store.get(this), d, toolFrom, toolTo);
                if (t.isEmpty()) throw new Exception("এই পেজগুলোতে কোনো লেখা নেই");
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", t));
                toolStatus("কপি হয়েছে (পেজ " + BanglaText.digits(toolFrom) + " থেকে " + BanglaText.digits(toolTo) + ")।");
            } else if ("save".equals(toolAction)) {
                String t = ShareHelper.gather(Store.get(this), d, toolFrom, toolTo);
                if (t.isEmpty()) throw new Exception("এই পেজগুলোতে কোনো লেখা নেই");
                final String body = t;
                final EditText nm = new EditText(this);
                nm.setText(d.title + " (পেজ " + BanglaText.digits(toolFrom) + "-" + BanglaText.digits(toolTo) + ")");
                nm.setTextSize(18);
                new AlertDialog.Builder(this)
                        .setTitle("বইয়ের নাম দিন")
                        .setView(pad(nm))
                        .setPositiveButton("সেভ করুন", (x, w) -> {
                            String nn = nm.getText().toString().trim();
                            if (nn.isEmpty()) nn = autoTitle(body);
                            saveText(nn, "paste", body);
                            toolStatus("\"" + nn + "\" নামে লাইব্রেরিতে রাখা হয়েছে।");
                        })
                        .setNegativeButton("বাতিল", null)
                        .show();
            } else if ("translate".equals(toolAction)) {
                translateDialog(d, toolFrom, toolTo);
            }
        } catch (Exception e) {
            toolStatus("হয়নি: " + e.getMessage());
        }
    }

    private void toolFormat(int fmt) {
        Store.Doc d = Player.I.doc;
        if (d == null) return;
        toolFormatBox.setVisibility(View.GONE);
        toolRangeBox.setVisibility(View.GONE);
        try {
            if ("share".equals(toolAction)) {
                ShareHelper.share(this, d, fmt, toolFrom, toolTo);
                toolStatus("শেয়ারের তালিকা খোলা হয়েছে।");
            } else {
                toolStatus(ShareHelper.download(this, d, fmt, toolFrom, toolTo));
            }
        } catch (Throwable t) {
            toolStatus("হয়নি: " + t.getMessage());
        }
    }

    private void translateDialog(final Store.Doc d, final int from, final int to) {
        if (prefs.getString("gemini_key", "").trim().isEmpty()) {
            toolStatus("অনুবাদের জন্য আগে সেটিংসে Gemini API কী দিন।");
            return;
        }
        if (translating) {
            toolStatus("আরেকটা অনুবাদ চলছে।");
            return;
        }
        final String[] names = {"বাংলা", "English", "हिन्दी (Hindi)"};
        final String[] langs = {"Bengali", "English", "Hindi"};
        new AlertDialog.Builder(this)
                .setTitle("কোন ভাষায় অনুবাদ?")
                .setItems(names, (x, w) -> runTranslate(d, from, to, langs[w], names[w]))
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void runTranslate(final Store.Doc d, final int from, final int to, final String lang, final String label) {
        if (to - from + 1 > 40) {
            toolStatus("একবারে সর্বোচ্চ ৪০ পেজ অনুবাদ করা যায়। রেঞ্জ ছোট করুন।");
            return;
        }
        translating = true;
        final String key = prefs.getString("gemini_key", "");
        final String m1 = Gemini.cleanModel(prefs.getString("ocr_model", ""), Gemini.DEFAULT_M1);
        final String m2 = Gemini.cleanModel(prefs.getString("ocr_model2", ""), Gemini.DEFAULT_M2);
        final Store st = Store.get(this);
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            String err = null;
            try {
                for (int p = from; p <= to; p++) {
                    final int cur = p;
                    runOnUiThread(() -> toolStatus("অনুবাদ চলছে: পেজ " + BanglaText.digits(cur) + " (" + BanglaText.digits(cur - from + 1) + "/" + BanglaText.digits(to - from + 1) + ")। অ্যাপ খোলা রাখুন।"));
                    String t = st.page(d.id, p);
                    if (t == null || t.trim().isEmpty()) continue;
                    String r = MediaReader.translate(key, m1, m2, t, lang);
                    if (out.length() > 0) out.append("\n\n");
                    out.append(r);
                    try { Thread.sleep(700); } catch (InterruptedException ie) { break; }
                }
            } catch (Gemini.Failure f) {
                err = f.getMessage();
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final String fo = out.toString(), fe = err;
            runOnUiThread(() -> {
                translating = false;
                if (fo.isEmpty()) {
                    toolStatus("অনুবাদ হয়নি: " + (fe == null ? "লেখা নেই" : fe));
                    return;
                }
                saveText("অনুবাদ (" + label + "): " + d.title, "paste", fo);
                toolStatus("অনুবাদ লাইব্রেরিতে রাখা হয়েছে" + (fe == null ? "।" : "। তবে মাঝপথে থেমেছে: " + fe));
            });
        }).start();
    }

    private void savePasted() {
        String body = pasteBody.getText().toString().trim();
        if (body.isEmpty()) {
            Toast.makeText(this, "আগে কিছু লেখা পেস্ট করুন", Toast.LENGTH_SHORT).show();
            return;
        }
        String title = pasteTitle.getText().toString().trim();
        if (title.isEmpty()) title = autoTitle(body);
        long id = saveDraft(title, "paste", body);
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

    /** a text that stays out of the library until the person saves it */
    private long saveDraft(String title, String source, String body) {
        discardCurrentDraft(-1);
        long id = saveText(title, source, body);
        Drafts.add(this, id);
        return id;
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
        String custom = pasteTitle == null ? "" : pasteTitle.getText().toString().trim();
        customNamed = !custom.isEmpty();
        if (customNamed) {
            base = custom;
            pasteTitle.setText("");
        }
        final String title = base;
        final boolean isImg = (type != null && type.startsWith("image/"))
                || name.toLowerCase().matches(".*\\.(jpg|jpeg|png|webp|bmp|gif|heic)$");
        final boolean isVid = (type != null && type.startsWith("video/"))
                || name.toLowerCase().matches(".*\\.(mp4|mkv|webm|3gp|mov|avi)$");
        if (isImg || isVid) {
            mediaDialog(uri, title, isVid);
            return;
        }
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
                    String head = body.substring(0, Math.min(body.length(), 4000));
                    int bad = 0;
                    for (int q = 0; q < head.length(); q++) {
                        char ch = head.charAt(q);
                        if (ch == 0 || ch == 0xFFFD) bad++;
                    }
                    if (bad > head.length() / 20) {
                        throw new Exception("এটা লেখার ফাইল নয় (শুধু PDF আর টেক্সট ফাইল পড়া যায়)");
                    }
                    if (body.trim().isEmpty()) throw new Exception("ফাইলে কোনো লেখা নেই");
                    final long id = saveDraft(title, "txt", body);
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

    /** a PDF starts being read by Gemini at once, from page 1; reading begins as soon as page 1 is ready */
    private void autoPdf(Importer.Info info) {
        if (info.pages < 1) {
            Toast.makeText(this, "PDF-এ কোনো পেজ নেই", Toast.LENGTH_LONG).show();
            return;
        }
        if (prefs.getString("gemini_key", "").trim().isEmpty()) {
            Toast.makeText(this, "আগে সেটিংস ট্যাবে Gemini API কী দিন", Toast.LENGTH_LONG).show();
            info.file.delete();
            select(3);
            return;
        }
        Importer.activeDoc = 0;
        autoStart = true;
        startImport(info, info.name, true, 1, info.pages);
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
        TextView how = text("এই PDF-এর পেজগুলো Gemini AI দিয়ে পড়া হবে (ইন্টারনেট লাগবে)।", 16, false, INK);
        how.setPadding(0, dp(10), 0, 0);
        box.addView(how);
        if (!hasKey) box.addView(text("আগে সেটিংস ট্যাবে Gemini API কী দিতে হবে।", 15, true, MUTED));

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
                    boolean useOcr = true;
                    if (prefs.getString("gemini_key", "").trim().isEmpty()) {
                        Toast.makeText(this, "আগে সেটিংস ট্যাবে Gemini API কী দিন", Toast.LENGTH_LONG).show();
                        info.file.delete();
                        select(3);
                        return;
                    }
                    String t = title.getText().toString().trim();
                    Importer.activeDoc = 0;
                    autoStart = true;          // reading starts by itself as soon as the first page is ready
                    startImport(info, t.isEmpty() ? info.name : t, useOcr, a, z);
                })
                .setNegativeButton("বাতিল", (d, w) -> info.file.delete())
                .setOnCancelListener(d -> info.file.delete())
                .show();
    }

    private void startImport(Importer.Info info, String title, boolean ocr, int from, int to) {
        discardCurrentDraft(-1);
        Importer.Job j = new Importer.Job();
        j.file = info.file;
        j.title = title;
        j.ocr = ocr;
        j.from = from;
        j.to = to;
        backWarned = false;
        Importer.job = j;
        Importer.running = true;
        Importer.done = 0;
        Importer.total = to - from + 1;
        Importer.status = "শুরু হচ্ছে…";
        askNotifications();
        ImportService.start(this);
        handler.removeCallbacks(tick);
        handler.post(tick);
        Toast.makeText(this, "প্রসেসিং শুরু হয়েছে। প্রথম পেজ তৈরি হলেই পড়া শুরু হবে।", Toast.LENGTH_LONG).show();
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
        libSearch = new EditText(this);
        libSearch.setHint("বইয়ের নাম দিয়ে খুঁজুন");
        libSearch.setContentDescription("লাইব্রেরিতে বইয়ের নাম দিয়ে খুঁজুন");
        libSearch.setTextSize(17);
        libSearch.setTextColor(INK);
        libSearch.setHintTextColor(MUTED);
        libSearch.setSingleLine(true);
        libSearch.setMinHeight(dp(52));
        libSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { refreshLibrary(); }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });
        box.addView(libSearch, fullWidth(8));
        tvLibCount = text("", 14, false, MUTED);
        tvLibCount.setPadding(0, dp(4), 0, dp(4));
        box.addView(tvLibCount);
        libList = new LinearLayout(this);
        libList.setOrientation(LinearLayout.VERTICAL);
        box.addView(libList);
        return sv;
    }

    private static String sourceName(String s) {
        if ("pdf".equals(s)) return "PDF";
        if ("txt".equals(s)) return "টেক্সট ফাইল";
        if ("img".equals(s)) return "ছবি";
        if ("vid".equals(s)) return "ভিডিও";
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
        List<Store.Doc> docs = new java.util.ArrayList<>();
        java.util.Set<Long> dr = Drafts.all(this);
        for (Store.Doc x : Store.get(this).docs()) if (!dr.contains(x.id)) docs.add(x);
        int totalDocs = docs.size();
        String q = libSearch == null ? "" : libSearch.getText().toString().trim().toLowerCase();
        if (!q.isEmpty()) {
            List<Store.Doc> f = new java.util.ArrayList<>();
            for (Store.Doc x : docs) if (x.title.toLowerCase().contains(q)) f.add(x);
            docs = f;
        }
        if (tvLibCount != null) {
            tvLibCount.setText(q.isEmpty() ? "মোট " + BanglaText.digits(totalDocs) + "টি বই"
                    : BanglaText.digits(docs.size()) + "টি পাওয়া গেছে (মোট " + BanglaText.digits(totalDocs) + "টি)");
        }
        if (docs.isEmpty()) {
            TextView t = text(totalDocs == 0 ? "লাইব্রেরি এখনও খালি। রিডার ট্যাব থেকে লেখা, PDF, ছবি বা ভিডিও যোগ করুন।" : "এই নামে কোনো বই নেই।", 18, false, INK);
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
                discardCurrentDraft(d.id);
                Player.I.open(d.id, d.lastPage, d.lastSent);
                select(0);
            });
            Button ren = small("নাম বদলান", d.title + " এর নাম বদলান");
            ren.setOnClickListener(v -> renameDialog(d));
            Button del = small("মুছুন", d.title + " মুছে ফেলুন");
            del.setOnClickListener(v -> deleteDialog(d));
            addWeighted(row, open, ren, del);
            card.addView(row);
            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            Button shr = small("শেয়ার", d.title + " শেয়ার করুন");
            shr.setOnClickListener(v -> shareDialog(d, 0));
            addWeighted(row2, shr);
            card.addView(row2);
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
        String vn = "";
        try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) { }
        box.addView(text("অ্যাপের ভার্সন: " + vn, 15, false, MUTED));

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

        box.addView(heading("প্লেয়ার"));
        final CheckBox cbText = new CheckBox(this);
        cbText.setText("পড়ার সময় লেখা দেখান (বন্ধ থাকলে শুধু প্লেয়ার থাকে)");
        cbText.setTextSize(17);
        cbText.setTextColor(INK);
        cbText.setMinHeight(dp(56));
        cbText.setChecked(prefs.getBoolean("show_text", false));
        cbText.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("show_text", on).apply();
            if (textCard != null) textCard.setVisibility(on ? View.VISIBLE : View.GONE);
            if (btnShowText != null) btnShowText.setText(on ? "📖 লেখা লুকান" : "📖 লেখা দেখান");
        });
        box.addView(cbText);
        final CheckBox cbPage = new CheckBox(this);
        cbPage.setText("পেজ বদলালে শব্দ ও পেজ নম্বর বলুন");
        cbPage.setTextSize(17);
        cbPage.setTextColor(INK);
        cbPage.setMinHeight(dp(56));
        cbPage.setChecked(prefs.getBoolean("page_say", true));
        cbPage.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("page_say", on).apply());
        box.addView(cbPage);

        box.addView(heading("কোন ভয়েসে পড়া হবে"));
        tvEngine = text("", 16, true, INK);
        box.addView(tvEngine);
        engGroup = new RadioGroup(this);
        engGroup.setOrientation(RadioGroup.VERTICAL);
        rbSys = new RadioButton(this);
        rbSys.setText("ফোনের ভয়েস (ডিফল্ট, অফলাইনেও চলে)");
        rbSys.setTextSize(17);
        rbSys.setTextColor(INK);
        rbSys.setMinHeight(dp(56));
        rbSys.setId(View.generateViewId());
        rbG = new RadioButton(this);
        rbG.setText("AI Studio ভয়েস (ইন্টারনেট ও API কী লাগে)");
        rbG.setTextSize(17);
        rbG.setTextColor(INK);
        rbG.setMinHeight(dp(56));
        rbG.setId(View.generateViewId());
        engGroup.addView(rbSys);
        engGroup.addView(rbG);
        engGroup.setOnCheckedChangeListener((grp, id) -> {
            if (engBusy) return;
            boolean g = id == rbG.getId();
            prefs.edit().putString("engine", g ? "gemini" : "system").apply();
            Player.I.settingsChanged();
            refreshSettings();
            if (g && prefs.getString("gemini_key", "").trim().isEmpty()) {
                Toast.makeText(this, "AI Studio-র জন্য আগে ওপরে Gemini API কী দিন", Toast.LENGTH_LONG).show();
            }
        });
        box.addView(engGroup);

        gBox = new LinearLayout(this);
        gBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(gBox);
        gBox.addView(heading("AI Studio ভয়েসের সেটিংস"));
        gBox.addView(text("Google AI Studio-র Gemini ভয়েস। ইন্টারনেট আর উপরের API কী লাগে। ফ্রি কোটা শেষ হলে পড়া মাঝপথে থামতে পারে (কোড ৪২৯)।", 15, false, MUTED));
        tvGv = text("", 16, true, INK);
        tvGv.setPadding(0, dp(8), 0, 0);
        gBox.addView(tvGv);
        Button gm = secondary("TTS মডেল বাছুন");
        gm.setOnClickListener(v -> ttsModelDialog());
        gBox.addView(gm, fullWidth(8));
        Button gv = secondary("ভয়েস বাছুন (৩০টি + আপনার বানানো)");
        gv.setOnClickListener(v -> gVoiceDialog());
        gBox.addView(gv, fullWidth(8));
        Button gs = secondary("পড়ার ধরন / আবেগ (স্টাইল)");
        gs.setOnClickListener(v -> gStyleDialog());
        gBox.addView(gs, fullWidth(8));
        final CheckBox auto = new CheckBox(this);
        auto.setText("অটো ইমোশন: লেখা বুঝে প্রতি অংশের আবেগ নিজে ঠিক হবে");
        auto.setTextSize(17);
        auto.setTextColor(INK);
        auto.setMinHeight(dp(56));
        auto.setChecked(prefs.getBoolean("g_auto", false));
        auto.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("g_auto", on).apply();
            Player.I.settingsChanged();
        });
        gBox.addView(auto);
        gBox.addView(text("অটো ইমোশনে প্রতি অংশের জন্য একটি বাড়তি Gemini অনুরোধ যায়, তাই কোটা বেশি খরচ হয়।", 14, false, MUTED));
        Button nv = secondary("বর্ণনা দিয়ে নতুন ভয়েস বানান");
        nv.setOnClickListener(v -> designDialog());
        gBox.addView(nv, fullWidth(8));
        Button dv = secondary("আমার বানানো ভয়েস মুছুন");
        dv.setOnClickListener(v -> deleteDesignedDialog());
        gBox.addView(dv, fullWidth(8));
        Button gsmp = secondary("AI Studio ভয়েসের নমুনা শুনুন");
        gsmp.setOnClickListener(v -> Player.I.sampleGemini("আমার সোনার বাংলা, আমি তোমায় ভালোবাসি। চিরদিন তোমার আকাশ, তোমার বাতাস, আমার প্রাণে বাজায় বাঁশি।", m -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()));
        gBox.addView(gsmp, fullWidth(8));

        sysBox = new LinearLayout(this);
        sysBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(sysBox);
        sysBox.addView(heading("ফোনের ভয়েসের সেটিংস"));
        tvVoice = text("", 16, true, INK);
        sysBox.addView(tvVoice);
        Button voice = secondary("ভয়েস বাছুন");
        voice.setOnClickListener(v -> voiceDialog());
        sysBox.addView(voice, fullWidth(8));
        Button sample = secondary("নমুনা শুনুন");
        sample.setOnClickListener(v -> Player.I.sample("আমার সোনার বাংলা, আমি তোমায় ভালোবাসি।"));
        sysBox.addView(sample, fullWidth(8));
        Button tts = secondary("ফোনের টেক্সট-টু-স্পিচ সেটিংস খুলুন (বাংলা ভয়েস ডাউনলোডের জন্য)");
        tts.setOnClickListener(v -> openTtsSettings());
        sysBox.addView(tts, fullWidth(8));

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
        int kc = Gemini.keyCount(k);
        StringBuilder ends = new StringBuilder();
        for (String one : k.split("[\\s,;]+")) {
            if (one.isEmpty()) continue;
            if (ends.length() > 0) ends.append(", ");
            ends.append("...").append(one.substring(Math.max(0, one.length() - 4)));
        }
        tvKey.setText(kc == 0 ? "API কী এখনও দেওয়া হয়নি" : BanglaText.digits(kc) + "টি API কী সংরক্ষিত আছে (শেষ অংশ: " + ends + ")");
        String v = prefs.getString("voice", "");
        boolean g = "gemini".equals(prefs.getString("engine", "system"));
        tvEngine.setText(g ? "এখন পড়া হচ্ছে: AI Studio ভয়েসে" : "এখন পড়া হচ্ছে: ফোনের ভয়েসে (ডিফল্ট)");
        engBusy = true;
        if (g) rbG.setChecked(true); else rbSys.setChecked(true);
        engBusy = false;
        gBox.setVisibility(g ? View.VISIBLE : View.GONE);
        sysBox.setVisibility(g ? View.GONE : View.VISIBLE);
        String gvn = prefs.getString("g_voice", GeminiTts.DEFAULT_VOICE);
        tvGv.setText("মডেল: " + prefs.getString("tts_model", GeminiTts.DEFAULT_MODEL)
                + "\nভয়েস: " + designedName(gvn)
                + "\nস্টাইল: " + (prefs.getString("g_style", "").isEmpty() ? "নেই" : prefs.getString("g_style", ""))
                + "\nঅটো ইমোশন: " + (prefs.getBoolean("g_auto", false) ? "চালু" : "বন্ধ"));
        tvVoice.setText(v.isEmpty() ? "ভয়েস: ফোনের ডিফল্ট বাংলা ভয়েস" : "ভয়েস: " + v);
    }

    private void keyDialog() {
        final EditText in = new EditText(this);
        in.setHint("এখানে API কী পেস্ট করুন (একাধিক হলে প্রতি লাইনে একটি)");
        in.setTextSize(16);
        in.setMinLines(3);
        in.setGravity(Gravity.TOP | Gravity.START);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int have = Gemini.keyCount(prefs.getString("gemini_key", ""));
        new AlertDialog.Builder(this)
                .setTitle("Gemini API কী")
                .setMessage("Google AI Studio থেকে পাওয়া নিজের কী দিন। একাধিক কী দিলে অ্যাপ পালা করে ব্যবহার করবে, তাই একটার কোটা শেষ হলে অন্যটা চলবে। এখন সংরক্ষিত: " + BanglaText.digits(have) + "টি।")
                .setView(pad(in))
                .setPositiveButton("যোগ করুন", (d, w) -> {
                    String add = in.getText().toString().trim();
                    if (add.isEmpty()) return;
                    String old = prefs.getString("gemini_key", "").trim();
                    prefs.edit().putString("gemini_key", old.isEmpty() ? add : old + "\n" + add).apply();
                    refreshSettings();
                    Toast.makeText(this, "সংরক্ষিত", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("সব বদলে এটাই রাখুন", (d, w) -> {
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
        m1.setText(Gemini.cleanModel(prefs.getString("ocr_model", ""), Gemini.DEFAULT_M1));
        m1.setTextSize(16);
        box.addView(labeled("প্রথমে এই মডেলে চেষ্টা:", m1));
        final EditText m2 = new EditText(this);
        m2.setText(Gemini.cleanModel(prefs.getString("ocr_model2", ""), Gemini.DEFAULT_M2));
        m2.setTextSize(16);
        box.addView(labeled("না হলে এই মডেলে:", m2));
        Button pick = secondary("তালিকা থেকে বাছুন (বর্তমান মডেল)");
        final String[] known = Gemini.CURRENT;
        pick.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("মডেল").setItems(known, (d, w) -> m1.setText(known[w])).show());
        box.addView(pick, fullWidth(4));
        box.addView(text("কোনো মডেল বন্ধ বা ব্যর্থ হলে অ্যাপ নিজেই পরের মডেলে চেষ্টা করবে (৩.৮ ফ্ল্যাশ, ৩.৬ ফ্ল্যাশ, ৩.৫ ফ্ল্যাশ-লাইট, ৩.১ ফ্ল্যাশ-লাইট, ৪ আর্গন), আর যেটা চলে সেটা মনে রাখবে। পুরোনো ২.x মডেল নিলে নিজে বাদ পড়ে যাবে।", 14, false, MUTED));
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

    // ------------------------------------------------------------------ AI Studio voice dialogs
    private void engineDialog() {
        final String[] names = {"ফোনের ভয়েস (ডিফল্ট, অফলাইনেও চলে)", "AI Studio ভয়েস (ইন্টারনেট ও API কী লাগে)"};
        int cur = "gemini".equals(prefs.getString("engine", "system")) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("কোন ভয়েসে পড়া হবে")
                .setSingleChoiceItems(names, cur, (d, w) -> {
                    prefs.edit().putString("engine", w == 1 ? "gemini" : "system").apply();
                    Player.I.settingsChanged();
                    refreshSettings();
                    d.dismiss();
                    if (w == 1 && prefs.getString("gemini_key", "").trim().isEmpty()) {
                        Toast.makeText(this, "AI Studio-র জন্য আগে Gemini API কী দিন", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void ttsModelDialog() {
        final String[] names = new String[GeminiTts.MODELS.length + 1];
        System.arraycopy(GeminiTts.MODELS, 0, names, 0, GeminiTts.MODELS.length);
        names[names.length - 1] = "অন্য নাম নিজে লিখব…";
        new AlertDialog.Builder(this)
                .setTitle("TTS মডেল")
                .setItems(names, (d, w) -> {
                    if (w < GeminiTts.MODELS.length) {
                        prefs.edit().putString("tts_model", GeminiTts.MODELS[w]).apply();
                        Player.I.settingsChanged();
                        refreshSettings();
                    } else {
                        final EditText in = new EditText(this);
                        in.setText(prefs.getString("tts_model", GeminiTts.DEFAULT_MODEL));
                        new AlertDialog.Builder(this)
                                .setTitle("মডেলের নাম")
                                .setView(pad(in))
                                .setPositiveButton("সংরক্ষণ", (d2, w2) -> {
                                    String m = in.getText().toString().trim();
                                    if (!m.isEmpty()) prefs.edit().putString("tts_model", m).apply();
                                    Player.I.settingsChanged();
                                    refreshSettings();
                                })
                                .setNegativeButton("বাতিল", null)
                                .show();
                    }
                })
                .show();
    }

    /** voices made from a description: [{id,name}] */
    private org.json.JSONArray designedList() {
        try {
            return new org.json.JSONArray(prefs.getString("g_designed", "[]"));
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    private String designedName(String id) {
        org.json.JSONArray a = designedList();
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o.optString("name") + " (আপনার বানানো)";
        }
        for (String[] v : GeminiTts.VOICES) if (v[0].equals(id)) return v[0] + " — " + v[1];
        return id;
    }

    private void deleteDesignedDialog() {
        final org.json.JSONArray des = designedList();
        if (des.length() == 0) {
            Toast.makeText(this, "আপনার বানানো কোনো ভয়েস নেই", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[des.length()];
        for (int i = 0; i < names.length; i++) names[i] = des.optJSONObject(i).optString("name");
        new AlertDialog.Builder(this)
                .setTitle("কোন ভয়েস মুছবেন?")
                .setItems(names, (d, w) -> {
                    final int at = w;
                    new AlertDialog.Builder(this)
                            .setTitle("\"" + names[at] + "\" মুছবেন?")
                            .setMessage("এটা এই অ্যাপের তালিকা থেকে মুছে যাবে।")
                            .setPositiveButton("মুছুন", (d2, w2) -> {
                                try {
                                    String id = des.optJSONObject(at).optString("id");
                                    org.json.JSONArray keep = new org.json.JSONArray();
                                    for (int i = 0; i < des.length(); i++) if (i != at) keep.put(des.get(i));
                                    SharedPreferences.Editor e = prefs.edit().putString("g_designed", keep.toString());
                                    if (id.equals(prefs.getString("g_voice", ""))) e.putString("g_voice", GeminiTts.DEFAULT_VOICE);
                                    e.apply();
                                } catch (Exception ignored) { }
                                Player.I.settingsChanged();
                                refreshSettings();
                                Toast.makeText(this, "মুছে গেছে", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("থাক", null)
                            .show();
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void gVoiceDialog() {
        final org.json.JSONArray des = designedList();
        final int n = des.length();
        final String[] names = new String[n + GeminiTts.VOICES.length];
        final String[] ids = new String[names.length];
        for (int i = 0; i < n; i++) {
            org.json.JSONObject o = des.optJSONObject(i);
            ids[i] = o.optString("id");
            names[i] = o.optString("name") + " (আপনার বানানো)";
        }
        for (int i = 0; i < GeminiTts.VOICES.length; i++) {
            ids[n + i] = GeminiTts.VOICES[i][0];
            names[n + i] = GeminiTts.VOICES[i][0] + " — " + GeminiTts.VOICES[i][1];
        }
        new AlertDialog.Builder(this)
                .setTitle("AI Studio ভয়েস")
                .setItems(names, (d, w) -> {
                    prefs.edit().putString("g_voice", ids[w]).apply();
                    Player.I.settingsChanged();
                    refreshSettings();
                    Player.I.sampleGemini("এই ভয়েসে পড়া হবে। আমার সোনার বাংলা।", m -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
                })
                .show();
    }

    private void gStyleDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText in = new EditText(this);
        in.setText(prefs.getString("g_style", ""));
        in.setHint("যেমন: warm storyteller, slow pace");
        in.setTextSize(16);
        box.addView(labeled("পড়ার ধরন (ইংরেজিতে লিখলে ভালো কাজ করে):", in));
        final String[] labels = new String[GeminiTts.STYLES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = GeminiTts.STYLES[i][0];
        Button presets = secondary("তৈরি ধরন থেকে বাছুন");
        presets.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("ধরন")
                .setItems(labels, (d, w) -> in.setText(GeminiTts.STYLES[w][1]))
                .show());
        box.addView(presets, fullWidth(8));
        new AlertDialog.Builder(this)
                .setTitle("পড়ার ধরন / আবেগ")
                .setView(box)
                .setPositiveButton("সংরক্ষণ", (d, w) -> {
                    prefs.edit().putString("g_style", in.getText().toString().trim()).apply();
                    Player.I.settingsChanged();
                    refreshSettings();
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void designDialog() {
        if (prefs.getString("gemini_key", "").trim().isEmpty()) {
            Toast.makeText(this, "আগে Gemini API কী দিন", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText nm = new EditText(this);
        nm.setHint("ভয়েসের নাম");
        box.addView(labeled("নাম:", nm));
        final EditText gd = new EditText(this);
        gd.setHint("female / male");
        gd.setText("female");
        box.addView(labeled("লিঙ্গ (female বা male):", gd));
        final EditText ds = new EditText(this);
        ds.setHint("যেমন: a warm, middle-aged Bengali woman, calm like a grandmother telling a story");
        ds.setMinLines(3);
        box.addView(labeled("কেমন ভয়েস চান (বর্ণনা, ইংরেজিতে ভালো):", ds));
        new AlertDialog.Builder(this)
                .setTitle("নতুন ভয়েস বানান")
                .setView(box)
                .setPositiveButton("বানান", (d, w) -> {
                    final String name = nm.getText().toString().trim();
                    final String gender = gd.getText().toString().trim();
                    final String desc = ds.getText().toString().trim();
                    if (name.isEmpty() || desc.isEmpty()) {
                        Toast.makeText(this, "নাম আর বর্ণনা দুটোই লাগবে", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, "ভয়েস বানানো হচ্ছে, একটু অপেক্ষা করুন…", Toast.LENGTH_LONG).show();
                    final String key = prefs.getString("gemini_key", "").trim();
                    String cm = prefs.getString("tts_model", GeminiTts.DEFAULT_MODEL);
                    final String model = cm.startsWith("gemini-3.8") ? cm : GeminiTts.DEFAULT_MODEL;
                    new Thread(() -> {
                        GeminiTts.Designed out = null;
                        String err = null;
                        try {
                            out = GeminiTts.design(key, model, name, gender.isEmpty() ? "female" : gender, "bn-IN", desc);
                        } catch (Gemini.Failure f) {
                            err = f.getMessage();
                        } catch (Throwable t) {
                            err = String.valueOf(t.getMessage());
                        }
                        final GeminiTts.Designed fo = out;
                        final String fe = err;
                        runOnUiThread(() -> {
                            if (fo == null) {
                                new AlertDialog.Builder(this).setTitle("ভয়েস বানানো যায়নি").setMessage(fe).setPositiveButton("ঠিক আছে", null).show();
                                return;
                            }
                            try {
                                org.json.JSONArray a = designedList();
                                org.json.JSONObject o = new org.json.JSONObject();
                                o.put("id", fo.id);
                                o.put("name", name);
                                a.put(o);
                                prefs.edit().putString("g_designed", a.toString()).putString("g_voice", fo.id).apply();
                            } catch (Exception ignored) { }
                            Player.I.settingsChanged();
                            refreshSettings();
                            Toast.makeText(this, "ভয়েস তৈরি, এখন এটাই বাছা আছে", Toast.LENGTH_LONG).show();
                            if (fo.sample != null) Player.I.playSample(fo.sample);
                        });
                    }).start();
                })
                .setNegativeButton("বাতিল", null)
                .show();
    }

    // ------------------------------------------------------------------ pictures and videos
    private void mediaDialog(final Uri uri, final String title, final boolean video) {
        if (prefs.getString("gemini_key", "").trim().isEmpty()) {
            Toast.makeText(this, "ছবি বা ভিডিওর জন্য আগে সেটিংসে Gemini API কী দিন", Toast.LENGTH_LONG).show();
            return;
        }
        final String[] names = video
                ? new String[]{"দৃশ্যের বর্ণনা (কী কী দেখা যাচ্ছে)", "দৃশ্যের বর্ণনা + কথাবার্তার সারাংশ"}
                : new String[]{"ছবির লেখা পড়ুন", "ছবিতে কী আছে তার বর্ণনা দিন", "বর্ণনা আর লেখা দুটোই"};
        final int[] modes = video
                ? new int[]{MediaReader.VID_DESC, MediaReader.VID_BOTH}
                : new int[]{MediaReader.IMG_TEXT, MediaReader.IMG_DESC, MediaReader.IMG_BOTH};
        new AlertDialog.Builder(this)
                .setTitle(video ? "ভিডিওতে কী করতে চান?" : "ছবিতে কী করতে চান?")
                .setItems(names, (d, w) -> runMedia(uri, title, video, modes[w]))
                .setNegativeButton("বাতিল", null)
                .show();
    }

    private void runMedia(final Uri uri, final String title, final boolean video, final int mode) {
        final String[] progress = {"শুরু হচ্ছে…"};
        final boolean[] cancelled = {false};
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(video ? "ভিডিও বোঝা হচ্ছে" : "ছবি পড়া হচ্ছে")
                .setMessage(progress[0] + "\n\nঅ্যাপ খোলা রাখুন।")
                .setCancelable(false)
                .setNegativeButton("বন্ধ করুন", (d, w) -> cancelled[0] = true)
                .show();
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!dlg.isShowing()) return;
                dlg.setMessage(progress[0] + "\n\nঅ্যাপ খোলা রাখুন।");
                h.postDelayed(this, 1000);
            }
        };
        h.postDelayed(tick, 1000);
        final String key = prefs.getString("gemini_key", "");
        final String m1 = Gemini.cleanModel(prefs.getString("ocr_model", ""), Gemini.DEFAULT_M1);
        final String m2 = Gemini.cleanModel(prefs.getString("ocr_model2", ""), Gemini.DEFAULT_M2);
        new Thread(() -> {
            String out = null;
            String err = null;
            try {
                progress[0] = video ? "ভিডিও প্রস্তুত হচ্ছে…" : "ছবি Gemini-তে পাঠানো হচ্ছে…";
                out = video ? MediaReader.video(getApplicationContext(), uri, mode, key, m1, m2, progress)
                        : MediaReader.image(getApplicationContext(), uri, mode, key, m1, m2);
            } catch (Gemini.Failure f) {
                err = f.getMessage();
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final String fo = out, fe = err;
            runOnUiThread(() -> {
                try { dlg.dismiss(); } catch (Throwable ignored) { }
                if (cancelled[0]) return;
                if (fo == null) {
                    new AlertDialog.Builder(this).setTitle("হয়নি").setMessage(fe).setPositiveButton("ঠিক আছে", null).show();
                    return;
                }
                String t = customNamed ? title : (video ? "ভিডিও: " : "ছবি: ") + title;
                long id = saveDraft(t, video ? "vid" : "img", fo);
                Player.I.open(id, 1, 0);
                select(0);
                Toast.makeText(this, "লাইব্রেরিতে রাখা হয়েছে", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // ------------------------------------------------------------------ share
    private void shareDialog(final Store.Doc d, final int page) {
        final java.util.ArrayList<String> names = new java.util.ArrayList<>();
        final java.util.ArrayList<int[]> opts = new java.util.ArrayList<>();
        names.add("টেক্সট ফাইল (.txt)");
        opts.add(new int[]{ShareHelper.TXT, 0});
        names.add("PDF ফাইল");
        opts.add(new int[]{ShareHelper.PDF, 0});
        names.add("DOC ফাইল (Word-এ খোলে)");
        opts.add(new int[]{ShareHelper.DOC, 0});
        names.add("শুধু লেখা হিসেবে (মেসেজ বা নোটে)");
        opts.add(new int[]{ShareHelper.PLAIN, 0});
        if (page > 0) {
            names.add("শুধু এই পেজ (পেজ " + BanglaText.digits(page) + ") — লেখা হিসেবে");
            opts.add(new int[]{ShareHelper.PLAIN, page});
        }
        new AlertDialog.Builder(this)
                .setTitle("\"" + d.title + "\" কীভাবে শেয়ার করবেন?")
                .setItems(names.toArray(new String[0]), (x, w) -> {
                    try {
                        ShareHelper.share(this, d, opts.get(w)[0], opts.get(w)[1] > 0 ? opts.get(w)[1] : 1, opts.get(w)[1] > 0 ? opts.get(w)[1] : d.pages);
                    } catch (Throwable t) {
                        Toast.makeText(this, "শেয়ার করা যায়নি: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("বাতিল", null)
                .show();
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

    private Button colored(String s, int bg, int fg, String description) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setMinHeight(dp(58));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setContentDescription(description);
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
