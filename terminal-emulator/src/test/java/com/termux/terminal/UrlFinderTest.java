package com.termux.terminal;

import junit.framework.TestCase;
import java.util.ArrayList;
import java.util.List;

public class UrlFinderTest extends TestCase {
    private static List<String> urls(String text) {
        List<String> r = new ArrayList<>();
        for (UrlFinder.Span s : UrlFinder.findAll(text)) r.add(s.url);
        return r;
    }

    private void assertUrls(String text, String... expected) {
        List<String> e = new ArrayList<>();
        for (String x : expected) e.add(x);
        assertEquals(text, e, urls(text));
    }

    public void testHttpAndHttps() {
        assertUrls("hello http://example.com world", "http://example.com");
        assertUrls("see http://a.com/x and https://b.org/y?z=1#f", "http://a.com/x", "https://b.org/y?z=1#f");
        assertUrls("hello https://example.com/#bar https://example.com/foo#bar", "https://example.com/#bar", "https://example.com/foo#bar");
        assertUrls("http://example.com\nhttp://another.com", "http://example.com", "http://another.com");
    }

    public void testTrailingPunctuation() {
        assertUrls("see https://example.com/a/b.", "https://example.com/a/b");
        assertUrls("a http://x.io/p, b", "http://x.io/p");
        assertUrls("(https://en.wikipedia.org/wiki/Foo_(bar))", "https://en.wikipedia.org/wiki/Foo_(bar)");
        assertUrls("\"https://a.com/b\"", "https://a.com/b");
        assertUrls("<http://a.com/b>", "http://a.com/b");
        assertUrls("http://a.com/b!?", "http://a.com/b");
    }

    public void testOtherSchemes() {
        assertUrls("ssh://git@host:22/repo.git", "ssh://git@host:22/repo.git");
        assertUrls("s3://bucket/key/file.txt next", "s3://bucket/key/file.txt");
        assertUrls("postgres://u:p@localhost:5432/db", "postgres://u:p@localhost:5432/db");
        assertUrls("ws://localhost:8080/socket", "ws://localhost:8080/socket");
        assertUrls("mail mailto:me@example.com, tel:+380501234567.", "mailto:me@example.com", "tel:+380501234567");
        assertUrls("magnet:?xt=urn:btih:abc123&dn=file", "magnet:?xt=urn:btih:abc123&dn=file");
    }

    public void testBareAddresses() {
        assertUrls("go to www.example.org/page now", "https://www.example.org/page");
        assertUrls("repo github.com/user/repo.git.", "https://github.com/user/repo.git");
        assertUrls("server at localhost:8080/api", "http://localhost:8080/api");
        assertUrls("ping 192.168.0.1:3000/x", "http://192.168.0.1:3000/x");
        assertUrls("see termux.dev, thanks", "https://termux.dev");
    }

    public void testNotLinks() {
        assertUrls("run install.sh then main.py and README.md");
        assertUrls("user@example.com wrote");                     // e-mail addresses are not links
        assertUrls("version 1.2.3 and lib.so and file.txt");
        assertUrls("http:// alone");
    }

    public void testAtIndex() {
        String t = "abc https://x.com/y, def";
        UrlFinder.Span s = UrlFinder.at(t, 10);
        assertNotNull(s);
        assertEquals("https://x.com/y", t.substring(s.start, s.end));
        assertNull(UrlFinder.at(t, 1));
    }
}
