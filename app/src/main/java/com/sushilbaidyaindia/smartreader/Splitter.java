package com.sushilbaidyaindia.smartreader;

import java.util.ArrayList;
import java.util.List;

/** Cuts text into pages and sentences. */
final class Splitter {
    private Splitter() { }

    private static final int MAX_SENTENCE = 300;
    private static final int SHORT_LINE = 45;

    private static final String ABBR = " dr mr mrs ms prof st vs etc e.g i.e no md ";

    /** "Dr." and the like do not end a sentence */
    private static boolean isAbbreviation(String t, int dot) {
        int a = dot;
        while (a > 0 && (Character.isLetter(t.charAt(a - 1)) || t.charAt(a - 1) == '.')) a--;
        String w = t.substring(a, dot).toLowerCase();
        return w.length() > 0 && w.length() <= 4 && ABBR.contains(" " + w + " ");
    }

    private static boolean hasLetter(String t, int a, int b) {
        for (int i = a; i < b; i++) if (Character.isLetterOrDigit(t.charAt(i))) return true;
        return false;
    }

    /**
     * Sentence spans [start, end) of the text.
     * A sentence ends at । ? ! or a full stop followed by a space, and at a line break only when the line is short
     * (a heading, a line of a poem) or the next line is empty. Long lines that just wrap are joined.
     */
    static List<int[]> sentences(String t) {
        List<int[]> raw = new ArrayList<>();
        int n = t.length();
        int start = 0;
        int lineStart = 0;
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            boolean end = false;
            if (c == '।' || c == '॥' || c == '?' || c == '!' || c == '‽') {
                end = true;
            } else if (c == '.') {
                end = (i + 1 >= n || Character.isWhitespace(t.charAt(i + 1))) && !isAbbreviation(t, i);
            } else if (c == '\n') {
                int lineLen = i - lineStart;
                boolean blankNext = i + 1 < n && t.charAt(i + 1) == '\n';
                end = lineLen < SHORT_LINE || blankNext;
                lineStart = i + 1;
            }
            if (end) {
                // keep a closing quote or bracket with the sentence
                int e = i + 1;
                while (e < n && (t.charAt(e) == '"' || t.charAt(e) == '\'' || t.charAt(e) == '”' || t.charAt(e) == '’' || t.charAt(e) == ')')) e++;
                raw.add(new int[]{start, e});
                start = e;
                i = e - 1;
            }
        }
        if (start < n) raw.add(new int[]{start, n});

        List<int[]> out = new ArrayList<>();
        for (int[] s : raw) trimAndSplit(t, s[0], s[1], out);
        return out;
    }

    private static void trimAndSplit(String t, int a, int b, List<int[]> out) {
        while (a < b && Character.isWhitespace(t.charAt(a))) a++;
        while (b > a && Character.isWhitespace(t.charAt(b - 1))) b--;
        if (a >= b || !hasLetter(t, a, b)) return;
        while (b - a > MAX_SENTENCE) {
            int cut = -1;
            int limit = a + MAX_SENTENCE;
            for (int i = limit; i > a + MAX_SENTENCE / 2; i--) {
                char c = t.charAt(i);
                if (c == ',' || c == ';' || c == ':' || c == '،') { cut = i + 1; break; }
            }
            if (cut < 0) {
                for (int i = limit; i > a + MAX_SENTENCE / 2; i--) {
                    if (Character.isWhitespace(t.charAt(i))) { cut = i; break; }
                }
            }
            if (cut < 0) cut = limit;
            int e = cut;
            while (e > a && Character.isWhitespace(t.charAt(e - 1))) e--;
            if (e > a && hasLetter(t, a, e)) out.add(new int[]{a, e});
            a = cut;
            while (a < b && Character.isWhitespace(t.charAt(a))) a++;
        }
        if (a < b && hasLetter(t, a, b)) out.add(new int[]{a, b});
    }

    /** Pasted or plain text -> pages of about {@code target} characters, cut at a paragraph or sentence end. */
    static List<String> paginate(String text, int target) {
        List<String> pages = new ArrayList<>();
        String t = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        int i = 0;
        int n = t.length();
        while (n - i > target + target / 3) {
            int from = i + target / 2;
            int to = i + target;
            int cut = -1;
            int p = t.lastIndexOf("\n\n", to);
            if (p >= from) cut = p + 2;
            if (cut < 0) {
                for (int k = to; k > from; k--) {
                    char c = t.charAt(k);
                    if (c == '।' || c == '.' || c == '?' || c == '!' || c == '\n') { cut = k + 1; break; }
                }
            }
            if (cut < 0) {
                for (int k = to; k > from; k--) {
                    if (Character.isWhitespace(t.charAt(k))) { cut = k + 1; break; }
                }
            }
            if (cut < 0) cut = to;
            String chunk = t.substring(i, cut).trim();
            if (!chunk.isEmpty()) pages.add(chunk);
            i = cut;
        }
        String last = t.substring(i).trim();
        if (!last.isEmpty()) pages.add(last);
        if (pages.isEmpty()) pages.add("");
        return pages;
    }

    /** Tidies text from a PDF: joins hyphenated line ends, squeezes blanks. */
    static String tidy(String s) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ');
        t = t.replaceAll("[ \\t]+", " ");
        t = t.replaceAll(" ?\\n ?", "\n");
        t = t.replaceAll("([A-Za-z])-\\n([a-z])", "$1$2");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }
}
