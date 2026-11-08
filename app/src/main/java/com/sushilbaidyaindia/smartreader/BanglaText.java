package com.sushilbaidyaindia.smartreader;

/** Bangla names and digits. */
final class BanglaText {
    static final String[] WEEKDAYS = {"রবিবার", "সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার"};
    static final String[] MONTHS = {"জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
            "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"};
    private static final char[] DIGITS = {'০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯'};

    private BanglaText() { }

    /** 2026 -> ২০২৬ */
    static String digits(long n) {
        return digits(String.valueOf(n));
    }

    /** ২০২৬ -> 2026 (so that dates typed with Bangla digits can be read) */
    static String asciiDigits(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '০' && c <= '৯') b.append((char) ('0' + (c - '০')));
            else b.append(c);
        }
        return b.toString();
    }

    static String digits(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= '0' && c <= '9' ? DIGITS[c - '0'] : c);
        }
        return b.toString();
    }
}
