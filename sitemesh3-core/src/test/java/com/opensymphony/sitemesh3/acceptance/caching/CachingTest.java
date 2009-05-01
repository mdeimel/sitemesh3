package com.opensymphony.sitemesh3.acceptance.caching;

import com.opensymphony.sitemesh3.webapp.WebEnvironment;
import com.opensymphony.sitemesh3.webapp.WebAppContext;
import com.opensymphony.sitemesh3.simple.SimpleConfig;
import com.opensymphony.sitemesh3.simple.SimpleSiteMeshFilter;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Calendar;
import java.util.TimeZone;

import junit.framework.TestCase;
import org.mortbay.jetty.HttpFields;

/**
 * Tests that HTTP cacheable Servlets behave correctly when decorated.
 *
 * @author Joe Walnes
 */
public class CachingTest extends TestCase {

    // HTTP header name constants.
    private static final String IF_MODIFIED_SINCE = "If-Modified-Since";
    private static final String LAST_MODIFIED = "Last-Modified";

    // Two dates we use in the tests.
    private static final LastModifiedDate OLDER_DATE = new LastModifiedDate(1980);
    private static final LastModifiedDate NEWER_DATE = new LastModifiedDate(1990);

    // Two Servlets, one that serves the content for the request, and one that serves the decorator.
    private CachingServlet contentServlet;
    private CachingServlet decoratorServlet;

    // The web container.
    private WebEnvironment web;

    /**
     * Test setup:
     * Serve some content on /content, a decorator on /decorator,
     * and a SiteMesh mapping that applies /decorator to /content.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        contentServlet = new CachingServlet("<html><body>Content</body></html>");
        decoratorServlet = new CachingServlet("<html><body>Decorated: <sitemesh:write property='body'/></body></html>");
        web = new WebEnvironment.Builder()
                .addServlet("/content", contentServlet)
                .addServlet("/decorator", decoratorServlet)
                .addFilter("/*", new SimpleSiteMeshFilter(new SimpleConfig<WebAppContext>()
                        .addDecoratorPath("/content", "/decorator")))
                .create();
    }

    public void testServesFreshPageIfContentModified() throws Exception {
        contentServlet.setLastModified(NEWER_DATE);
        decoratorServlet.setLastModified(OLDER_DATE);

        getIfModifiedSince(OLDER_DATE);
        assertReturnedFreshPageModifiedOn(NEWER_DATE);
    }

    public void testServesFreshPageIfDecoratorModified() throws Exception {
        contentServlet.setLastModified(OLDER_DATE);
        decoratorServlet.setLastModified(NEWER_DATE);

        getIfModifiedSince(OLDER_DATE);
        assertReturnedFreshPageModifiedOn(NEWER_DATE);
    }

    public void testServesFreshPageIfContentAndDecoratorModified() throws Exception {
        contentServlet.setLastModified(NEWER_DATE);
        decoratorServlet.setLastModified(NEWER_DATE);

        getIfModifiedSince(OLDER_DATE);
        assertReturnedFreshPageModifiedOn(NEWER_DATE);
    }

    public void testServesNotModifiedPageIfBothContentAndDecoratorNotModified() throws Exception {
        contentServlet.setLastModified(OLDER_DATE);
        decoratorServlet.setLastModified(OLDER_DATE);

        getIfModifiedSince(OLDER_DATE);
        assertReturnedNotModified();
    }

    public void testServesFreshPageIfClientCacheTimeNotKnown() throws Exception {
        contentServlet.setLastModified(NEWER_DATE);
        decoratorServlet.setLastModified(OLDER_DATE);

        getFresh();
        assertReturnedFreshPageModifiedOn(NEWER_DATE);
    }

    // ------- Test helpers -------

    /**
     * Make the HTTP request to the (decorated) content, passing an If-Modified-Since HTTP header.
     */
    private void getIfModifiedSince(LastModifiedDate ifModifiedSinceDate) throws Exception {
        web.doGet("/content", IF_MODIFIED_SINCE, ifModifiedSinceDate.toHttpHeaderFormat());
    }

    /**
     * Make the HTTP request to the (decorated) content, as if the browser was requesting
     * it for the first time (i.e. without an If-Modified-Since HTTP header).
     */
    private void getFresh() throws Exception {
        web.doGet("/content");
    }

    /**
     * Assert the response of the last request returned fresh content, with a Last-Modified header.
     */
    private void assertReturnedFreshPageModifiedOn(LastModifiedDate expectedLastModifiedDate) {
        assertEquals("Expected request to return OK (200) status.",
                HttpServletResponse.SC_OK,  web.getStatus());
        assertEquals("Incorrect Last-Modified header returned",
                expectedLastModifiedDate.toHttpHeaderFormat(), web.getHeader(LAST_MODIFIED));
        assertEquals("Expected content to be decorated",
                "<html><body>Decorated: Content</body></html>", web.getBody());

        assertTrue(contentServlet.handledGetRequest());
        assertTrue(decoratorServlet.handledGetRequest());
    }

    /**
     * Assert the response of the last request returned a NOT MODIFIED response.
     */
    private void assertReturnedNotModified() {
        assertEquals("Expected request to return NOT MODIFIED (304) status.",
                HttpServletResponse.SC_NOT_MODIFIED,  web.getStatus());

        assertFalse(contentServlet.handledGetRequest());
        assertFalse(decoratorServlet.handledGetRequest());
    }

    /**
     * A simple Servlet that returns some static HTML content.
     *
     * <p>If the If-Modified-Header in the request is older than the date passed
     * into {@link #setLastModified(LastModifiedDate)}, then the content
     * will be returned as normal (this can be tested with
     * {@link #handledGetRequest()}. Otherwise, a NOT_MODIFIED response
     * will be returned.</p>
     *
     * <p>It is actually {@link javax.servlet.http.HttpServlet} that implements
     * most of this logic - we just override it's {@link #getLastModified(HttpServletRequest)}
     * method.</p>
     */
    private static class CachingServlet extends HttpServlet {

        private LastModifiedDate lastModifiedDate;
        private boolean handledGetRequest;
        private final String content;

        /**
         * @param content HTML content that Servlet should serve.
         */
        public CachingServlet(String content) {
            this.content = content;
        }

        public void setLastModified(LastModifiedDate lastModifiedDate) {
            this.lastModifiedDate = lastModifiedDate;
        }

        /**
         * Standard Servlet method.
         *
         * Returns value of date set by {@link #setLastModified(LastModifiedDate)}, or -1 (as per spec)
         * if not set.
         */
        @Override
        protected long getLastModified(HttpServletRequest request) {
            return lastModifiedDate == null ? -1 : lastModifiedDate.getMillis();
        }

        /**
         * Return content passed in constructor.
         */
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            response.setContentType("text/html");
            response.getWriter().print(content);
            handledGetRequest = true;
        }

        /**
         * Returns whether doGet() was called.
         */
        public boolean handledGetRequest() {
            return handledGetRequest;
        }

    }

    /**
     * Simple wrapper around a last modified date.
     *
     * This has a fairly coarse grained precision - years ;). Keeps things simple.
     */
    private static class LastModifiedDate {

        private final Calendar calendar;

        public LastModifiedDate(int year) {
            calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
            calendar.set(Calendar.YEAR, year);
        }

        public long getMillis() {
            return calendar.getTimeInMillis();
        }

        public String toHttpHeaderFormat() {
            return HttpFields.formatDate(calendar, false);
        }

    }

}
