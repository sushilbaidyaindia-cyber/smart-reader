package com.sushilbaidyaindia.smartreader;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Texts that were opened but not saved to the library yet. They are listed here, kept out of the library,
 * and thrown away when the person leaves without saving (or when the app starts fresh).
 */
final class Drafts {
    private Drafts() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("reader", Context.MODE_PRIVATE);
    }

    static synchronized Set<Long> all(Context c) {
        Set<Long> out = new HashSet<>();
        String raw = sp(c).getString("drafts", "");
        for (String s : raw.split(",")) {
            if (s.isEmpty()) continue;
            try { out.add(Long.parseLong(s)); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static void put(Context c, Set<Long> set) {
        StringBuilder sb = new StringBuilder();
        for (Long l : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(l);
        }
        sp(c).edit().putString("drafts", sb.toString()).apply();
    }

    static synchronized void add(Context c, long id) {
        Set<Long> s = all(c);
        s.add(id);
        put(c, s);
    }

    static synchronized void remove(Context c, long id) {
        Set<Long> s = all(c);
        if (s.remove(id)) put(c, s);
    }

    static boolean has(Context c, long id) {
        return all(c).contains(id);
    }

    /** removes every unsaved text from the phone (called when the app starts fresh) */
    static synchronized void purge(Context c) {
        Set<Long> s = all(c);
        if (s.isEmpty()) return;
        Store st = Store.get(c);
        for (Long id : s) {
            if (id == Importer.activeDoc && Importer.running) continue;
            try { st.delete(id); } catch (Throwable ignored) { }
        }
        put(c, new HashSet<Long>());
    }
}
