package io.github.download.maven.plugin.internal;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.download.maven.plugin.internal.cache.Validators;
import java.io.File;
import java.nio.file.Path;
import java.net.URI;
import java.util.Collections;
import java.util.Optional;
import org.apache.http.message.BasicHeader;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.github.download.maven.plugin.internal.TestFiles.read;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link HttpFetcher}: how it combines plugin settings with the {@link Environment}.
 */
public class HttpFetcherTest {

    @RegisterExtension
    public WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(options().dynamicPort())
        .failOnUnmatchedRequests(true)
        .build();

    @TempDir
    public Path tempDir;

    private FakeEnvironment environment;

    private Log log;

    private File target;

    @BeforeEach
    public void setUp() throws Exception {
        this.environment = new FakeEnvironment();
        this.log = mock(Log.class);
        this.target = new File(this.tempDir.toFile(), "target");
    }

    @Test
    public void explicitCredentialsTakePrecedenceOverEnvironment() throws Exception {
        requireBasicAuth("explicit", "secret");
        this.environment.credentials = new UserCredentials("environment", "other");
        fetcher(new UserCredentials("explicit", "secret"), null).fetch(uri(), this.target, Validators.NONE);
        assertThat(read(this.target), is("secured"));
    }

    @Test
    public void environmentCredentialsAreUsedWithoutExplicitOnes() throws Exception {
        requireBasicAuth("environment", "secret");
        this.environment.credentials = new UserCredentials("environment", "secret");
        fetcher(null, "my-server").fetch(uri(), this.target, Validators.NONE);
        assertThat(read(this.target), is("secured"));
        assertThat(this.environment.serverId, is("my-server"));
    }

    @Test
    public void environmentFailureIsReported() {
        this.environment.failure = new EnvironmentException("Server my-server not found");
        final EnvironmentException error = assertThrows(
            EnvironmentException.class, () -> fetcher(null, "my-server").fetch(uri(), this.target, Validators.NONE)
        );
        assertThat(error.getMessage(), is("Server my-server not found"));
        this.wireMock.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    public void environmentIsResolvedOnceAcrossFetches() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        final HttpFetcher fetcher = fetcher(null, null);
        fetcher.fetch(uri(), this.target, Validators.NONE);
        fetcher.fetch(uri(), this.target, Validators.NONE);
        assertThat(this.environment.resolutions, is(1));
        this.wireMock.verify(2, getRequestedFor(anyUrl()));
    }

    @Test
    public void usesProxyFromEnvironment() throws Exception {
        this.wireMock.stubFor(get(urlEqualTo("/file")).willReturn(ok("via proxy")));
        this.environment.proxy = new ProxySettings("localhost", this.wireMock.getPort());
        fetcher(null, null).fetch(URI.create("http://target.invalid/file"), this.target, Validators.NONE);
        assertThat(read(this.target), is("via proxy"));
    }

    @Test
    public void sendsAdditionalHeaders() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        new HttpFetcher(
            this.environment, new HttpFileRequester.Builder(), null, null,
            Collections.singletonList(new BasicHeader("X-Custom", "value")), this.log
        ).fetch(uri(), this.target, Validators.NONE);
        this.wireMock.verify(getRequestedFor(anyUrl()).withHeader("X-Custom", equalTo("value")));
    }

    @Test
    public void reportsProgressIfEnvironmentAsksForIt() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        this.environment.progress = true;
        fetcher(null, null).fetch(uri(), this.target, Validators.NONE);
        verify(this.log).info("Downloading: " + uri());
    }

    @Test
    public void doesNotReportProgressOtherwise() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        fetcher(null, null).fetch(uri(), this.target, Validators.NONE);
        verify(this.log, never()).info(startsWith("Downloading: "));
    }

    @Test
    public void knownValidatorsAreSentAsConditionalHeaders() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        fetcher(null, null).fetch(uri(), this.target, new Validators("\"abc\"", "Sat, 01 Jan 2000 00:00:00 GMT"));
        this.wireMock.verify(
            getRequestedFor(urlEqualTo("/file"))
                .withHeader("If-None-Match", equalTo("\"abc\""))
                .withHeader("If-Modified-Since", equalTo("Sat, 01 Jan 2000 00:00:00 GMT"))
        );
    }

    @Test
    public void noConditionalHeadersWithoutValidators() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        fetcher(null, null).fetch(uri(), this.target, Validators.NONE);
        this.wireMock.verify(
            getRequestedFor(urlEqualTo("/file")).withoutHeader("If-None-Match").withoutHeader("If-Modified-Since")
        );
    }

    @Test
    public void conditionalHeaderConfiguredOnPluginWins() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        new HttpFetcher(
            this.environment, new HttpFileRequester.Builder(), null, null,
            Collections.singletonList(new BasicHeader("if-none-match", "\"mine\"")), this.log
        ).fetch(uri(), this.target, new Validators("\"abc\"", "Sat, 01 Jan 2000 00:00:00 GMT"));
        this.wireMock.verify(
            getRequestedFor(urlEqualTo("/file"))
                .withHeader("If-None-Match", equalTo("\"mine\""))
                .withHeader("If-Modified-Since", equalTo("Sat, 01 Jan 2000 00:00:00 GMT"))
        );
        assertThat(
            this.wireMock.findAll(getRequestedFor(urlEqualTo("/file"))).get(0).getHeaders()
                .getHeader("If-None-Match").values().size(),
            is(1)
        );
    }

    private HttpFetcher fetcher(final UserCredentials explicit, final String serverId) {
        return new HttpFetcher(
            this.environment, new HttpFileRequester.Builder(), explicit, serverId,
            Collections.emptyList(), this.log
        );
    }

    private void requireBasicAuth(final String username, final String password) {
        this.wireMock.stubFor(get(anyUrl()).willReturn(unauthorized().withHeader("WWW-Authenticate", "Basic")));
        this.wireMock.stubFor(get(anyUrl()).withBasicAuth(username, password).willReturn(ok("secured")));
    }

    private URI uri() {
        return URI.create(this.wireMock.baseUrl() + "/file");
    }

    /**
     * Environment returning configured values and counting credential resolutions.
     */
    private static final class FakeEnvironment implements Environment {

        private UserCredentials credentials;

        private ProxySettings proxy;

        private boolean progress;

        private EnvironmentException failure;

        private String serverId;

        private int resolutions;

        @Override
        public boolean isOffline() {
            return false;
        }

        @Override
        public boolean showsProgress() {
            return this.progress;
        }

        @Override
        public File localRepository() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ProxySettings> proxyFor(final URI uri, final String id) {
            return Optional.ofNullable(this.proxy);
        }

        @Override
        public Optional<UserCredentials> credentialsFor(final URI uri, final String id)
            throws EnvironmentException {
            this.resolutions += 1;
            this.serverId = id;
            if (this.failure != null) {
                throw this.failure;
            }
            return Optional.ofNullable(this.credentials);
        }
    }
}
