package com.sushilbaidyaindia.smartreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Reads a PDF into the library, page by page. Two ways:
 * "direct" takes the text that is inside the PDF (offline, quick, but Bangla order can come out wrong),
 * "ocr" looks at each page as a picture with Gemini (needs internet and the person's key).
 */
final class Importer {
    private Importer() { }

    static final class Info {
        File file;
        int pages;
        String name;
    }

    static final class Job {
        File file;
        String title;
        boolean ocr;
        int from;
        int to;
    }

    // what the screen shows
    static volatile boolean running = false;
    static volatile long activeDoc = -1;
    static volatile int done = 0;
    static volatile int total = 0;
    static volatile String status = "";
    static volatile boolean cancel = false;
    static volatile boolean finishedOk = false;
    static Job job;

    /** copies the picked PDF into the app's cache and counts its pages */
    static Info prepare(Context c, Uri uri, String name) throws Exception {
        File f = new File(c.getCacheDir(), "import_" + System.currentTimeMillis() + ".pdf");
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("ফাইল খোলা যায়নি");
        OutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
        Info info = new Info();
        info.file = f;
        info.name = name;
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            PdfRenderer r = new PdfRenderer(pfd);
            info.pages = r.getPageCount();
            r.close();
        } finally {
            pfd.close();
        }
        return info;
    }

    /** runs on the import service's own thread */
    static void run(Context c) {
        Job j = job;
        if (j == null) return;
        Store st = Store.get(c);
        SharedPreferences sp = c.getSharedPreferences("reader", Context.MODE_PRIVATE);
        String key = sp.getString("gemini_key", "");
        String m1 = sp.getString("ocr_model", "gemini-3.5-flash-lite");
        String m2 = sp.getString("ocr_model2", "gemini-2.5-flash");

        total = j.to - j.from + 1;
        done = 0;
        cancel = false;
        finishedOk = false;
        status = "শুরু হচ্ছে…";
        long id = st.createDoc(j.title, "pdf", total, j.from);
        activeDoc = id;
        running = true;

        PdfRenderer renderer = null;
        ParcelFileDescriptor pfd = null;
        PDDocument pd = null;
        PDFTextStripper stripper = null;
        try {
            if (j.ocr) {
                pfd = ParcelFileDescriptor.open(j.file, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(pfd);
            } else {
                PDFBoxResourceLoader.init(c.getApplicationContext());
                pd = PDDocument.load(j.file, MemoryUsageSetting.setupTempFileOnly());
                stripper = new PDFTextStripper();
            }
            String lastError = "";
            int failed = 0;
            for (int p = j.from; p <= j.to; p++) {
                if (cancel) break;
                int local = p - j.from + 1;
                status = "পেজ " + BanglaText.digits(p) + " পড়া হচ্ছে (" + BanglaText.digits(done) + "/" + BanglaText.digits(total) + ")";
                String text;
                try {
                    if (j.ocr) text = ocrPage(renderer, p - 1, key, m1, m2);
                    else {
                        stripper.setStartPage(p);
                        stripper.setEndPage(p);
                        text = stripper.getText(pd);
                    }
                    text = Splitter.tidy(Gemini.clean(text));
                    if (text.isEmpty()) text = "";
                } catch (Gemini.Failure f) {
                    lastError = f.getMessage();
                    failed++;
                    text = "(পেজ " + BanglaText.digits(p) + "-এর লেখা পড়া যায়নি)";
                    if (!f.retryable && failed >= 2) {
                        // a wrong key or a blocked request will fail on every page, so stop early
                        st.putPage(id, local, text);
                        done++;
                        st.setDone(id, done);
                        status = "থেমে গেছে: " + lastError;
                        break;
                    }
                } catch (Throwable t) {
                    lastError = String.valueOf(t.getMessage());
                    text = "(পেজ " + BanglaText.digits(p) + "-এর লেখা পড়া যায়নি)";
                }
                st.putPage(id, local, text);
                done++;
                st.setDone(id, done);
                if (j.ocr && p < j.to) sleep(total > 20 ? 1500 : 800);
            }
            if (cancel) {
                status = "বন্ধ করা হয়েছে। এ পর্যন্ত " + BanglaText.digits(done) + " পেজ সংরক্ষিত।";
                st.setPages(id, Math.max(1, done));
            } else if (done >= total) {
                status = "শেষ হয়েছে: " + BanglaText.digits(done) + " পেজ লাইব্রেরিতে সংরক্ষিত।"
                        + (failed > 0 ? " " + BanglaText.digits(failed) + "টি পেজ পড়া যায়নি (" + lastError + ")" : "");
                finishedOk = failed == 0;
            } else {
                st.setPages(id, Math.max(1, done));
            }
        } catch (Throwable t) {
            status = "ব্যর্থ: " + t.getMessage();
            st.setPages(id, Math.max(1, done));
        } finally {
            try { if (renderer != null) renderer.close(); } catch (Throwable ignored) { }
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
            try { if (pd != null) pd.close(); } catch (Throwable ignored) { }
            try { j.file.delete(); } catch (Throwable ignored) { }
            running = false;
            activeDoc = -1;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static String ocrPage(PdfRenderer r, int index, String key, String m1, String m2) throws Gemini.Failure {
        if (key == null || key.trim().isEmpty()) throw new Gemini.Failure("সেটিংসে Gemini API কী দেওয়া নেই", false);
        String b64;
        PdfRenderer.Page pg = r.openPage(index);
        try {
            int w = pg.getWidth();
            int h = pg.getHeight();
            float scale = 1600f / Math.max(w, h);
            if (scale < 1f) scale = 1f;
            int bw = Math.max(1, Math.round(w * scale));
            int bh = Math.max(1, Math.round(h * scale));
            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            bmp.recycle();
            b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } finally {
            pg.close();
        }
        Gemini.Failure last = null;
        String[] models = m2 == null || m2.trim().isEmpty() || m2.trim().equals(m1.trim()) ? new String[]{m1} : new String[]{m1, m2};
        for (String model : models) {
            for (int attempt = 0; attempt < 4; attempt++) {
                if (cancel) throw new Gemini.Failure("বন্ধ করা হয়েছে", false);
                try {
                    return Gemini.ocr(key, model, b64);
                } catch (Gemini.Failure f) {
                    last = f;
                    if (!f.retryable) break;
                    sleep(3000L * (attempt + 1));
                }
            }
        }
        throw last == null ? new Gemini.Failure("OCR ব্যর্থ", true) : last;
    }
}
