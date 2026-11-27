package com.sushilbaidyaindia.smartreader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Sends a saved text out of the app as a text file, a PDF, a Word-readable .doc, or plain text for a message. */
final class ShareHelper {
    private ShareHelper() { }

    static final int TXT = 0, PDF = 1, DOC = 2, PLAIN = 3;

    /** the pages from..to joined into one text */
    static String gather(Store st, Store.Doc d, int from, int to) {
        StringBuilder all = new StringBuilder();
        for (int p = from; p <= to; p++) {
            String t = st.page(d.id, p);
            if (t == null || t.trim().isEmpty()) continue;
            if (all.length() > 0) all.append("\n\n");
            all.append(t.trim());
        }
        return all.toString();
    }

    private static String safeName(Store.Doc d, int from, int to) {
        String safe = d.title.replaceAll("[\\\\/:*?\"<>|\\n\\r]", " ").trim();
        if (safe.isEmpty()) safe = "smart-reader";
        if (safe.length() > 60) safe = safe.substring(0, 60).trim();
        if (!(from <= 1 && to >= d.pages)) safe += " (পেজ " + from + "-" + to + ")";
        return safe;
    }

    /** builds the file in the app's cache */
    static File build(Activity a, Store.Doc d, int fmt, int from, int to) throws Exception {
        Store st = Store.get(a);
        String body = gather(st, d, from, to);
        if (body.isEmpty()) throw new Exception("এই পেজগুলোতে কোনো লেখা নেই");
        File dir = new File(a.getCacheDir(), "share");
        if (!dir.exists()) dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File o : old) o.delete();
        String ext = fmt == PDF ? ".pdf" : fmt == DOC ? ".doc" : ".txt";
        File f = new File(dir, safeName(d, from, to) + ext);
        if (fmt == PDF) writePdf(f, d, from, to, st);
        else if (fmt == DOC) writeDoc(f, d.title, body);
        else {
            Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            w.write(body);
            w.close();
        }
        return f;
    }

    static void share(Activity a, Store.Doc d, int fmt, int from, int to) throws Exception {
        Intent send = new Intent(Intent.ACTION_SEND);
        if (fmt == PLAIN) {
            String body = gather(Store.get(a), d, from, to);
            if (body.isEmpty()) throw new Exception("এই পেজগুলোতে কোনো লেখা নেই");
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, d.title);
            send.putExtra(Intent.EXTRA_TEXT, body.length() > 100000 ? body.substring(0, 100000) : body);
        } else {
            File f = build(a, d, fmt, from, to);
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", f);
            send.setType(fmt == PDF ? "application/pdf" : fmt == DOC ? "application/msword" : "text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, d.title);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        a.startActivity(Intent.createChooser(send, "শেয়ার করুন"));
    }

    /** saves into the phone's Downloads folder; returns a sentence saying where */
    static String download(Activity a, Store.Doc d, int fmt, int from, int to) throws Exception {
        File f = build(a, d, fmt, from, to);
        String mime = fmt == PDF ? "application/pdf" : fmt == DOC ? "application/msword" : "text/plain";
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Smart Reader");
            Uri u = a.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("Downloads-এ ফাইল বানানো যায়নি");
            java.io.OutputStream os = a.getContentResolver().openOutputStream(u);
            copy(f, os);
            return "Downloads ফোল্ডারের 'Smart Reader' এ রাখা হয়েছে: " + f.getName();
        }
        File dir = a.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new Exception("সংরক্ষণের জায়গা পাওয়া যায়নি");
        dir.mkdirs();
        File out = new File(dir, f.getName());
        copy(f, new FileOutputStream(out));
        return "ফোনে রাখা হয়েছে: " + out.getAbsolutePath();
    }

    private static void copy(File f, java.io.OutputStream os) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        in.close();
        os.close();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void writeDoc(File f, String title, String body) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:w=\"urn:schemas-microsoft-com:office:word\" xmlns=\"http://www.w3.org/TR/REC-html40\">");
        sb.append("<head><meta charset=\"utf-8\"><title>").append(esc(title)).append("</title></head><body>");
        sb.append("<h2>").append(esc(title)).append("</h2>");
        for (String para : body.split("\\n\\s*\\n")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            sb.append("<p>").append(esc(p).replace("\n", "<br>")).append("</p>");
        }
        sb.append("</body></html>");
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        w.write(sb.toString());
        w.close();
    }

    private static void writePdf(File f, Store.Doc d, int from, int to, Store st) throws Exception {
        final int W = 595, H = 842, M = 42;
        final int usable = H - 2 * M;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(14f);
        tp.setColor(0xFF000000);
        PdfDocument pdf = new PdfDocument();
        int pageNo = 0;
        try {
            for (int p = from; p <= to; p++) {
                String t = st.page(d.id, p);
                if (t == null || t.trim().isEmpty()) continue;
                if (p == from) t = d.title + "\n\n" + t;
                StaticLayout lay = StaticLayout.Builder.obtain(t, 0, t.length(), tp, W - 2 * M)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build();
                int line = 0;
                int lines = lay.getLineCount();
                while (line < lines) {
                    int startTop = lay.getLineTop(line);
                    int end = line;
                    while (end < lines && lay.getLineBottom(end) - startTop <= usable) end++;
                    if (end == line) end = line + 1;
                    int endBottom = lay.getLineBottom(end - 1);
                    pageNo++;
                    PdfDocument.Page pg = pdf.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
                    Canvas cv = pg.getCanvas();
                    cv.save();
                    cv.translate(M, M - startTop);
                    cv.clipRect(0, startTop, W - 2 * M, endBottom);
                    lay.draw(cv);
                    cv.restore();
                    pdf.finishPage(pg);
                    line = end;
                }
            }
            if (pageNo == 0) throw new Exception("শেয়ার করার মতো লেখা নেই");
            FileOutputStream os = new FileOutputStream(f);
            pdf.writeTo(os);
            os.close();
        } finally {
            pdf.close();
        }
    }
}
