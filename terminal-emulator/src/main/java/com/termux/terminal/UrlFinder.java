package com.termux.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * god-termux: finds links in terminal text. Handles
 * <ul>
 *   <li>any {@code scheme://...} (http, https, ftp, ssh, git, ws, s3, postgres, vscode, ...)</li>
 *   <li>schemes without slashes: mailto:, tel:, sms:, magnet:, geo:, ...</li>
 *   <li>addresses without a scheme: www.site.org/path, github.com/user/repo, localhost:8080, 192.168.0.1:3000</li>
 * </ul>
 * Trailing punctuation and unbalanced closing brackets are not part of the link. Pure Java (unit-testable).
 */
public final class UrlFinder {
    private UrlFinder() {}

    /** A found link: [start, end) in the searched text; url is normalized for opening (https:// added to bare addresses). */
    public static final class Span {
        public final int start, end; public final String url;
        Span(int start, int end, String url) { this.start = start; this.end = end; this.url = url; }
        @Override public String toString() { return start + ".." + end + " " + url; }
    }

    private static final String REST = "[^\\s<>\"'`\\u2028\\u2029]";
    private static final String LABEL = "[a-z0-9\\u00a1-\\uffff](?:[a-z0-9\\u00a1-\\uffff\\-]*[a-z0-9\\u00a1-\\uffff])?";
    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)";
    private static final String TLDS = "com|org|net|edu|gov|mil|int|info|biz|name|pro|mobi|io|dev|app|co|me|tv|ly|gg|xyz|top|club|site|online|tech|cloud|shop|store|blog|wiki|page|link|live|news|fun|zone|host|space|network|email|games|art|onion"
        + "|ru|su|ua|by|kz|uz|ge|am|az|de|uk|fr|it|es|nl|be|ch|at|se|no|fi|dk|cz|sk|hu|ro|bg|gr|pt|ie|lt|lv|ee|us|ca|au|nz|jp|cn|kr|in|br|ar|mx|tr|il|ae|sa|ir|za|eu|asia|ws|tk|cf|ga";

    // any scheme followed by "://"
    private static final Pattern SCHEME = Pattern.compile(
        "(?<![A-Za-z0-9+.\\-])[A-Za-z][A-Za-z0-9+.\\-]{1,29}://" + REST + "+", Pattern.CASE_INSENSITIVE);

    // schemes that have no "//"
    private static final Pattern OTHER = Pattern.compile(
        "(?<![A-Za-z0-9+.\\-])(?:mailto|tel|sms|smsto|magnet|geo|sip|sips|xmpp|matrix|bitcoin|urn|news|nntp|callto|facetime):" + REST + "+", Pattern.CASE_INSENSITIVE);

    // no scheme at all
    private static final Pattern BARE = Pattern.compile(
        "(?<![A-Za-z0-9_@+.\\-/:%\\u00a1-\\uffff])"
        + "(?:"
        + "localhost"
        + "|" + OCTET + "(?:\\." + OCTET + "){3}"
        + "|www\\." + LABEL + "(?:\\." + LABEL + ")+"
        + "|(?:" + LABEL + "\\.)+(?:" + TLDS + ")(?![a-z0-9\\u00a1-\\uffff\\-])"
        + ")"
        + "(?::\\d{1,5})?(?:[/?#]" + REST + "*)?", Pattern.CASE_INSENSITIVE);

    /** All links in text, in order, without overlaps. */
    public static List<Span> findAll(String text) {
        List<Span> all = new ArrayList<>();
        collect(SCHEME, text, all, 0); collect(OTHER, text, all, 0); collect(BARE, text, all, 1);
        Collections.sort(all, new Comparator<Span>() {
            @Override public int compare(Span a, Span b) { return a.start != b.start ? Integer.compare(a.start, b.start) : Integer.compare(b.end, a.end); }
        });
        List<Span> out = new ArrayList<>(); int last = -1;
        for (Span s : all) if (s.start >= last) { out.add(s); last = s.end; }
        return out;
    }

    /** The link that contains the character at index, or null. */
    public static Span at(String text, int index) {
        for (Span s : findAll(text)) if (index >= s.start && index < s.end) return s;
        return null;
    }

    private static void collect(Pattern p, String text, List<Span> out, int bare) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String url = trimTrailing(m.group());
            if (url.length() < 3) continue;
            if (bare == 0 && url.endsWith("://")) continue;
            String open = url;
            if (bare == 1) open = (url.toLowerCase().startsWith("localhost") || Character.isDigit(url.charAt(0)) ? "http://" : "https://") + url;
            out.add(new Span(m.start(), m.start() + url.length(), open));
        }
    }

    /** Drops trailing punctuation and closing brackets that have no opening partner inside the link. */
    public static String trimTrailing(String url) {
        String t = url;
        while (t.length() > 1) {
            char c = t.charAt(t.length() - 1);
            boolean cut = ".,;:!?'\"`»…".indexOf(c) >= 0
                || (c == ')' && count(t, '(') < count(t, ')'))
                || (c == ']' && count(t, '[') < count(t, ']'))
                || (c == '}' && count(t, '{') < count(t, '}'));
            if (!cut) break;
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }
}
