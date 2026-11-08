package com.sushilbaidyaindia.smartreader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** The offline library: every saved text and its pages live here, on the phone. */
final class Store extends SQLiteOpenHelper {

    static final class Doc {
        long id;
        String title;
        String source;     // paste, txt, pdf
        int pages;         // pages planned
        int done;          // pages ready (a PDF is still being read when done < pages)
        int firstPage;     // first page of the PDF that was read (1 for other sources)
        int lastPage;      // where reading stopped
        int lastSent;
        long created;
        long opened;
    }

    private static Store inst;

    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private Store(Context c) {
        super(c, "reader.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE docs (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, source TEXT, pages INTEGER, done INTEGER, "
                + "first_page INTEGER, last_page INTEGER, last_sent INTEGER, created INTEGER, opened INTEGER)");
        db.execSQL("CREATE TABLE pages (doc INTEGER, page INTEGER, body TEXT, PRIMARY KEY (doc, page))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    private static Doc read(Cursor c) {
        Doc d = new Doc();
        d.id = c.getLong(0);
        d.title = c.getString(1);
        d.source = c.getString(2);
        d.pages = c.getInt(3);
        d.done = c.getInt(4);
        d.firstPage = c.getInt(5);
        d.lastPage = c.getInt(6);
        d.lastSent = c.getInt(7);
        d.created = c.getLong(8);
        d.opened = c.getLong(9);
        return d;
    }

    private static final String COLS = "id, title, source, pages, done, first_page, last_page, last_sent, created, opened";

    synchronized long createDoc(String title, String source, int pages, int firstPage) {
        ContentValues v = new ContentValues();
        long now = System.currentTimeMillis();
        v.put("title", title);
        v.put("source", source);
        v.put("pages", pages);
        v.put("done", 0);
        v.put("first_page", firstPage);
        v.put("last_page", 1);
        v.put("last_sent", 0);
        v.put("created", now);
        v.put("opened", now);
        return getWritableDatabase().insert("docs", null, v);
    }

    synchronized void putPage(long doc, int page, String body) {
        ContentValues v = new ContentValues();
        v.put("doc", doc);
        v.put("page", page);
        v.put("body", body);
        getWritableDatabase().insertWithOnConflict("pages", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized void setDone(long doc, int done) {
        ContentValues v = new ContentValues();
        v.put("done", done);
        getWritableDatabase().update("docs", v, "id=?", new String[]{String.valueOf(doc)});
    }

    synchronized void setPages(long doc, int pages) {
        ContentValues v = new ContentValues();
        v.put("pages", pages);
        getWritableDatabase().update("docs", v, "id=?", new String[]{String.valueOf(doc)});
    }

    synchronized List<Doc> docs() {
        List<Doc> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM docs ORDER BY opened DESC", null);
        try {
            while (c.moveToNext()) out.add(read(c));
        } finally {
            c.close();
        }
        return out;
    }

    synchronized Doc doc(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM docs WHERE id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? read(c) : null;
        } finally {
            c.close();
        }
    }

    /** the text of a page, or null when that page is not ready yet */
    synchronized String page(long doc, int page) {
        Cursor c = getReadableDatabase().rawQuery("SELECT body FROM pages WHERE doc=? AND page=?",
                new String[]{String.valueOf(doc), String.valueOf(page)});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    synchronized void savePos(long doc, int page, int sent) {
        ContentValues v = new ContentValues();
        v.put("last_page", page);
        v.put("last_sent", sent);
        getWritableDatabase().update("docs", v, "id=?", new String[]{String.valueOf(doc)});
    }

    synchronized void touch(long doc) {
        ContentValues v = new ContentValues();
        v.put("opened", System.currentTimeMillis());
        getWritableDatabase().update("docs", v, "id=?", new String[]{String.valueOf(doc)});
    }

    synchronized void rename(long doc, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("docs", v, "id=?", new String[]{String.valueOf(doc)});
    }

    synchronized void delete(long doc) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("pages", "doc=?", new String[]{String.valueOf(doc)});
        db.delete("docs", "id=?", new String[]{String.valueOf(doc)});
    }
}
