package io.github.download.maven.plugin.internal;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import javax.net.ssl.SSLHandshakeException;
import org.apache.http.auth.AUTH;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import io.github.download.maven.plugin.internal.cache.Validators;
import org.apache.http.message.BasicHeader;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link HttpFileRequester}
 *
 * @author Andrzej Jarmoniuk
 */
public class HttpFileRequesterTest {
    @Rule
    public TemporaryFolder outputDirectory = new TemporaryFolder();
    @Rule
    public WireMockRule wireMock = new WireMockRule(options().dynamicPort().dynamicHttpsPort());
    private File outputFile;
    private final static Log LOG = new SystemStreamLog();
    private final static String OUTPUT_FILE_NAME = "output-file";

    @Before
    public void setUp() {
        this.outputFile = new File(this.outputDirectory.getRoot(), OUTPUT_FILE_NAME);
    }

    private HttpFileRequester.Builder createFileRequesterBuilder() throws Exception {

        return new HttpFileRequester.Builder()
                .withProgressReport(new LoggingProgressReport(LOG))
                .withConnectTimeout(3000)
                .withSocketTimeout(3000)
                .withUri(new URI("http://localhost:" + this.wireMock.port()))
                .withRedirectsEnabled(false)
                .withPreemptiveAuth(false)
                .withLog(LOG);
    }

    /**
     * Tests {@link HttpFileRequester#download(File, List)} with no authentication
     * @throws Exception thrown if {@link HttpFileRequester} creation fails
     */
    @Test
    public void testNoAuth()
            throws Exception {
        this.wireMock.stubFor(get(anyUrl())
                .willReturn(ok().withBody("Hello, world!")));

        createFileRequesterBuilder()
                .build()
                .download(this.outputFile, emptyList());

        assertThat(String.join("", Files.readAllLines(this.outputFile.toPath())),
                is("Hello, world!"));
    }

    @Test
    public void testInsecureSSL()
        throws Exception {
        this.wireMock.stubFor(get(anyUrl())
            .willReturn(ok().withBody("Feeling insecure, huh?")));

        createFileRequesterBuilder()
            .withInsecure(true)
            .withUri(new URI("https://localhost:" + wireMock.httpsPort()))
            .build()
            .download(this.outputFile, emptyList());

        assertThat(String.join("", Files.readAllLines(this.outputFile.toPath())),
            is("Feeling insecure, huh?"));
    }
    @Test
    public void testSecuredSSL() {
        this.wireMock.stubFor(get(anyUrl())
            .willReturn(ok().withBody("Feeling secured!")));

        assertThrows(SSLHandshakeException.class, ()->{
            createFileRequesterBuilder()
                .withInsecure(false)
                .withUri(new URI("https://localhost:" + wireMock.httpsPort()))
                .build()
                .download(this.outputFile, emptyList());
        });
    }

    /**
     * Tests {@link HttpFileRequester#download(File, List)} with basic authentication
     * @throws Exception thrown if {@link HttpFileRequester} creation fails
     */
    @Test
    public void testBasicAuth()
            throws Exception {
        this.wireMock.stubFor(get(anyUrl())
                .willReturn(unauthorized()
                        .withHeader(AUTH.WWW_AUTH,"Basic")));
        this.wireMock.stubFor(get(anyUrl())
                .withBasicAuth("billg", "hunter2")
                .willReturn(ok().withBody("Hello, world!")));

        createFileRequesterBuilder()
                .withCredentials(new UserCredentials("billg", "hunter2"))
                .build()
                .download(this.outputFile, emptyList());

        assertThat(String.join("", Files.readAllLines(this.outputFile.toPath())),
                is("Hello, world!"));
    }

    /**
     * Tests {@link HttpFileRequester#download(File, List)} with preemptive basic authentication
     * @throws Exception thrown if {@link HttpFileRequester} creation fails
     */
    @Test
    public void testBasicAuthPreempt()
            throws Exception {
        wireMock.stubFor(get(anyUrl())
                .withBasicAuth("billg", "hunter2")
                .willReturn(ok().withBody("Hello, world!")));

        createFileRequesterBuilder()
                .withCredentials(new UserCredentials("billg", "hunter2"))
                .withPreemptiveAuth(true)
                .build()
                .download(this.outputFile, emptyList());

        assertThat(String.join("", Files.readAllLines(this.outputFile.toPath())),
                is("Hello, world!"));
    }

    /**
     * Tests {@link HttpFileRequester#download(File, List)} should throw a {@link DownloadFailureException}
     * if the download fails
     */
    @Test
    public void testDownloadFailure() {
        this.wireMock.stubFor(get(anyUrl())
                .willReturn(forbidden()));

        try {
            createFileRequesterBuilder()
                    .build()
                    .download(this.outputFile, emptyList());
            fail("A DownloadFailureException should have been thrown");
        } catch (Exception e) {
            assertThat(e, is(instanceOf(DownloadFailureException.class)));
        }
    }

    /**
     * Requests are routed through the configured proxy.
     */
    @Test
    public void testDownloadThroughProxy() throws Exception {
        this.wireMock.stubFor(get(urlEqualTo("/file")).willReturn(ok().withBody("via proxy")));

        createFileRequesterBuilder()
                .withUri(new URI("http://target.invalid/file"))
                .withProxy(new ProxySettings("localhost", this.wireMock.port()))
                .build()
                .download(this.outputFile, emptyList());

        assertThat(String.join("", Files.readAllLines(this.outputFile.toPath())), is("via proxy"));
        this.wireMock.verify(getRequestedFor(urlEqualTo("/file")).withHeader("Host", equalTo("target.invalid")));
    }

    /**
     * Proxy credentials are sent after the proxy asks for them.
     */
    @Test
    public void testDownloadThroughAuthenticatingProxy() throws Exception {
        final String expected = "Basic " + Base64.getEncoder()
            .encodeToString("puser:ppass".getBytes(StandardCharsets.UTF_8));
        this.wireMock.stubFor(get(anyUrl())
                .willReturn(aResponse().withStatus(407).withHeader("Proxy-Authenticate", "Basic realm=\"proxy\"")));
        this.wireMock.stubFor(get(anyUrl())
                .withHeader("Proxy-Authorization", equalTo(expected))
                .willReturn(ok().withBody("via proxy")));

        createFileRequesterBuilder()
                .withUri(new URI("http://target.invalid/file"))
                .withProxy(new ProxySettings("localhost", this.wireMock.port(),
                    new UserCredentials("puser", "ppass")))
                .build()
                .download(this.outputFile, emptyList());

        assertThat(String.join("", Files.readAllLines(this.outputFile.toPath())), is("via proxy"));
    }

    /**
     * Proxy username without a password doesn't enable proxy authentication.
     */
    @Test
    public void testProxyWithoutPasswordDoesNotAuthenticate() {
        this.wireMock.stubFor(get(anyUrl())
                .willReturn(aResponse().withStatus(407).withHeader("Proxy-Authenticate", "Basic realm=\"proxy\"")));

        final DownloadFailureException error = assertThrows(
            DownloadFailureException.class,
            () -> createFileRequesterBuilder()
                .withUri(new URI("http://target.invalid/file"))
                .withProxy(new ProxySettings("localhost", this.wireMock.port(),
                    new UserCredentials("puser", null)))
                .build()
                .download(this.outputFile, emptyList())
        );
        assertThat(error.getHttpCode(), is(407));
        this.wireMock.verify(1, getRequestedFor(anyUrl()));
        this.wireMock.verify(0, getRequestedFor(anyUrl()).withHeader("Proxy-Authorization", matching(".*")));
    }

    /**
     * An I/O failure while streaming the body is reported to the progress report and rethrown.
     */
    @Test
    public void testStreamingErrorIsReported() {
        this.wireMock.stubFor(get(anyUrl())
                .willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
        final ProgressReport report = mock(ProgressReport.class);

        assertThrows(
            IOException.class,
            () -> createFileRequesterBuilder()
                .withProgressReport(report)
                .build()
                .download(this.outputFile, emptyList())
        );
        verify(report).error(any(IOException.class));
    }

    /**
     * A response without a body doesn't create the output file.
     */
    @Test
    public void testNoContentDoesNotCreateFile() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(noContent()));

        createFileRequesterBuilder()
                .build()
                .download(this.outputFile, emptyList());

        assertThat(this.outputFile.exists(), is(false));
    }

    /**
     * 3xx responses fail the download when redirects are disabled.
     */
    @Test
    public void testRedirectFailsWhenRedirectsDisabled() {
        this.wireMock.stubFor(get(anyUrl()).willReturn(temporaryRedirect("/elsewhere")
            .withStatus(302)));

        final DownloadFailureException error = assertThrows(
            DownloadFailureException.class,
            () -> createFileRequesterBuilder()
                .build()
                .download(this.outputFile, emptyList())
        );
        assertThat(error.getHttpCode(), is(302));
        assertThat(error.getMessage(), containsString("followRedirects is false"));
    }

    /**
     * A cookie with an RFC 1123 {@code Expires} date (as sent by GitHub) set on a redirect hop
     * is accepted and sent with the next request.
     * @see <a href="https://github.com/download-maven-plugin/download-maven-plugin/issues/309">#309</a>
     */
    @Test
    public void testCookieWithRfc1123ExpiresIsKeptAcrossRedirect() throws Exception {
        this.wireMock.stubFor(get(urlEqualTo("/start"))
            .willReturn(temporaryRedirect("/file")
                .withStatus(302)
                .withHeader("Set-Cookie",
                    "session=abc; Path=/; Expires=Wed, 24 Jun 2099 17:10:07 GMT; HttpOnly; SameSite=Lax")));
        this.wireMock.stubFor(get(urlEqualTo("/file")).willReturn(forbidden()));
        this.wireMock.stubFor(get(urlEqualTo("/file"))
            .withCookie("session", equalTo("abc"))
            .willReturn(ok().withBody("with cookie")));

        createFileRequesterBuilder()
            .withUri(new URI("http://localhost:" + this.wireMock.port() + "/start"))
            .withRedirectsEnabled(true)
            .build()
            .download(this.outputFile, emptyList());

        assertThat(String.join("", Files.readAllLines(this.outputFile.toPath())), is("with cookie"));
    }

    /**
     * Gzip-encoded responses are requested and stored decompressed.
     */
    @Test
    public void testGzipResponseIsDecompressed() throws Exception {
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes)) {
            gzip.write("Hello, gzip!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        this.wireMock.stubFor(get(anyUrl())
                .withHeader("Accept-Encoding", containing("gzip"))
                .willReturn(ok().withHeader("Content-Encoding", "gzip").withBody(bytes.toByteArray())));

        createFileRequesterBuilder()
                .build()
                .download(this.outputFile, emptyList());

        assertThat(String.join("", Files.readAllLines(this.outputFile.toPath())), is("Hello, gzip!"));
    }

    /**
     * The query string of the resource is sent unchanged.
     */
    @Test
    public void testQueryIsSent() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok().withBody("Hello, query!")));

        createFileRequesterBuilder()
                .withUri(new URI("http://localhost:" + this.wireMock.port() + "/?query=value"))
                .build()
                .download(this.outputFile, emptyList());

        this.wireMock.verify(getRequestedFor(anyUrl()).withQueryParam("query", equalTo("value")));
    }

    /**
     * All redirect codes are followed when redirects are enabled.
     */
    @Test
    public void testRedirectsAreFollowedWhenEnabled() throws Exception {
        this.wireMock.stubFor(get(urlEqualTo("/new")).willReturn(ok().withBody("Hello, redirect!")));
        for (final int code : new int[]{301, 302, 303, 307, 308}) {
            Files.deleteIfExists(this.outputFile.toPath());
            this.wireMock.stubFor(get(urlEqualTo("/" + code))
                    .willReturn(aResponse().withStatus(code).withHeader("Location", "/new")));

            createFileRequesterBuilder()
                    .withUri(new URI("http://localhost:" + this.wireMock.port() + "/" + code))
                    .withRedirectsEnabled(true)
                    .build()
                    .download(this.outputFile, emptyList());

            assertThat("redirect " + code, String.join("", Files.readAllLines(this.outputFile.toPath())),
                    is("Hello, redirect!"));
        }
    }

    @Test
    public void testValidatorsOfDownloadedFileAreReturned() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(
            ok().withBody("Hello, world!")
                .withHeader("ETag", "\"abc\"")
                .withHeader("Last-Modified", "Sat, 01 Jan 2000 00:00:00 GMT")));

        final Fetched fetched = createFileRequesterBuilder().build().download(this.outputFile, emptyList());

        assertThat(fetched.isNotModified(), is(false));
        // the server marks the ETag of a compressed response, e.g. "abc--gzip": it is opaque to the client
        assertThat(fetched.validators().etag().get(), startsWith("\"abc"));
        assertThat(fetched.validators().lastModified().get(), is("Sat, 01 Jan 2000 00:00:00 GMT"));
    }

    @Test
    public void testNoValidatorsIfServerSendsNone() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok().withBody("Hello, world!")));

        final Fetched fetched = createFileRequesterBuilder().build().download(this.outputFile, emptyList());

        assertThat(fetched.validators(), is(Validators.NONE));
    }

    @Test
    public void testNotModifiedIsReportedAndWritesNoFile() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok().withBody("Hello, world!")));
        this.wireMock.stubFor(get(anyUrl())
            .withHeader("If-None-Match", equalTo("\"abc\""))
            .willReturn(aResponse().withStatus(304)));

        final Fetched fetched = createFileRequesterBuilder().build().download(
            this.outputFile, singletonList(new BasicHeader("If-None-Match", "\"abc\""))
        );

        assertThat(fetched.isNotModified(), is(true));
        assertThat(this.outputFile.exists(), is(false));
    }

    /**
     * A "latest" URL redirects to the current file: the conditional headers must reach
     * the server that knows the file, and the validators must be the ones of the file.
     */
    @Test
    public void testConditionalHeadersAndValidatorsCrossRedirects() throws Exception {
        this.wireMock.stubFor(get(urlEqualTo("/latest")).willReturn(temporaryRedirect("/v2")));
        this.wireMock.stubFor(get(urlEqualTo("/v2")).willReturn(ok().withBody("v2").withHeader("ETag", "\"v2\"")));
        this.wireMock.stubFor(get(urlEqualTo("/v2"))
            .withHeader("If-None-Match", matching("\"v2.*"))
            .willReturn(aResponse().withStatus(304)));
        final HttpFileRequester requester = createFileRequesterBuilder()
            .withUri(new URI("http://localhost:" + this.wireMock.port() + "/latest"))
            .withRedirectsEnabled(true)
            .build();

        final Validators current = requester
            .download(this.outputFile, singletonList(new BasicHeader("If-None-Match", "\"v1\"")))
            .validators();
        assertThat(current.etag().get(), startsWith("\"v2"));
        assertThat(
            requester
                .download(this.outputFile, singletonList(new BasicHeader("If-None-Match", current.etag().get())))
                .isNotModified(),
            is(true)
        );
    }
}
