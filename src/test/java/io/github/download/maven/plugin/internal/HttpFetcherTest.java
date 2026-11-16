package io.github.download.maven.plugin.internal;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.Optional;
import org.apache.http.message.BasicHeader;
import org.apache.maven.plugin.logging.Log;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link HttpFetcher}: how it combines plugin settings with the {@link Environment}.
 */
public class HttpFetcherTest {

    @Rule
    public WireMockRule wireMock = new WireMockRule(options().dynamicPort());

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private FakeEnvironment environment;

    private Log log;

    private File target;

    @Before
    public void setUp() throws Exception {
        this.environment = new FakeEnvironment();
        this.log = mock(Log.class);
        this.target = new File(this.temporaryFolder.getRoot(), "target");
    }

    @Test
    public void explicitCredentialsTakePrecedenceOverEnvironment() throws Exception {
        requireBasicAuth("explicit", "secret");
        this.environment.credentials = new UserCredentials("environment", "other");
        fetcher(new UserCredentials("explicit", "secret"), null).fetch(uri(), this.target);
        assertThat(read(this.target), is("secured"));
    }

    @Test
    public void environmentCredentialsAreUsedWithoutExplicitOnes() throws Exception {
        requireBasicAuth("environment", "secret");
        this.environment.credentials = new UserCredentials("environment", "secret");
        fetcher(null, "my-server").fetch(uri(), this.target);
        assertThat(read(this.target), is("secured"));
        assertThat(this.environment.serverId, is("my-server"));
    }

    @Test
    public void environmentFailureIsReported() {
        this.environment.failure = new EnvironmentException("Server my-server not found");
        final EnvironmentException error = assertThrows(
            EnvironmentException.class, () -> fetcher(null, "my-server").fetch(uri(), this.target)
        );
        assertThat(error.getMessage(), is("Server my-server not found"));
        this.wireMock.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    public void environmentIsResolvedOnceAcrossFetches() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        final HttpFetcher fetcher = fetcher(null, null);
        fetcher.fetch(uri(), this.target);
        fetcher.fetch(uri(), this.target);
        assertThat(this.environment.resolutions, is(1));
        this.wireMock.verify(2, getRequestedFor(anyUrl()));
    }

    @Test
    public void usesProxyFromEnvironment() throws Exception {
        this.wireMock.stubFor(get(urlEqualTo("/file")).willReturn(ok("via proxy")));
        this.environment.proxy = new ProxySettings("localhost", this.wireMock.port());
        fetcher(null, null).fetch(URI.create("http://target.invalid/file"), this.target);
        assertThat(read(this.target), is("via proxy"));
    }

    @Test
    public void sendsAdditionalHeaders() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        new HttpFetcher(
            this.environment, new HttpFileRequester.Builder(), null, null,
            Collections.singletonList(new BasicHeader("X-Custom", "value")), this.log
        ).fetch(uri(), this.target);
        this.wireMock.verify(getRequestedFor(anyUrl()).withHeader("X-Custom", equalTo("value")));
    }

    @Test
    public void reportsProgressIfEnvironmentAsksForIt() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        this.environment.progress = true;
        fetcher(null, null).fetch(uri(), this.target);
        verify(this.log).info("Downloading: " + uri());
    }

    @Test
    public void doesNotReportProgressOtherwise() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        fetcher(null, null).fetch(uri(), this.target);
        verify(this.log, never()).info(startsWith("Downloading: "));
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
