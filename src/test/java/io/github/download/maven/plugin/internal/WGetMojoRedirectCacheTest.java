package io.github.download.maven.plugin.internal;

import org.apache.http.HttpClientConnection;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.util.ReflectionUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.sonatype.plexus.build.incremental.BuildContext;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Issue #315: a stable "latest" URL that redirects to a per-version URL must not be
 * served from the cache forever once it starts pointing at a new version.
 *
 * <p>Uses an in-process HTTP stub (no sockets), so the real Apache HttpClient redirect
 * handling is exercised without binding a local port.</p>
 */
public class WGetMojoRedirectCacheTest {

    private static final String LATEST = "http://api.example/latest/binary";
    private static final String V1 = "http://cdn.example/bin/app-1.0.zip";
    private static final String V2 = "http://cdn.example/bin/app-2.0.zip";
    private static final String OUTPUT_FILE_NAME = "app.zip";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path cacheDirectory;
    private Path outputDirectory;

    @Before
    public void setUp() throws Exception {
        cacheDirectory = temporaryFolder.newFolder("cache").toPath();
        outputDirectory = temporaryFolder.newFolder("out").toPath();
    }

    /**
     * Issue #315: once the "latest" URL redirects to a new release, the next build must
     * pick up that release instead of serving the previous one from the cache.
     */
    @Test
    @Ignore("Bug #315: the cache is keyed by the original URL, so build 2 makes no request "
        + "and gets the stale V1 content from the cache")
    public void newReleaseIsDownloadedAfterLatestRedirectMovesOn() throws Exception {
        final List<String> build1 = new CopyOnWriteArrayList<>();
        runBuild(V1, build1, m -> { });
        assertThat(build1, contains("GET " + LATEST, "GET " + V1));
        assertThat(readOutput(), is("content of " + V1));

        // A new release is published: "latest" now redirects to V2.
        // Build 2 starts from a clean output directory (e.g. after `mvn clean`).
        final List<String> build2 = new CopyOnWriteArrayList<>();
        runBuild(V2, build2, m -> { });

        assertThat("server must be asked where latest points now", build2, hasItem("GET " + LATEST));
        assertThat("new release must be used", readOutput(), is("content of " + V2));
    }

    /**
     * With {@code skipCache=true} every build follows the redirect again, so a new release
     * behind the "latest" URL is picked up.
     */
    @Test
    public void skipCacheFollowsLatestRedirectOnEveryBuild() throws Exception {
        final Consumer<WGetMojo> skipCache = m -> set(m, "skipCache", true);

        final List<String> build1 = new CopyOnWriteArrayList<>();
        runBuild(V1, build1, skipCache);
        assertThat(build1, contains("GET " + LATEST, "GET " + V1));
        assertThat(readOutput(), is("content of " + V1));

        final List<String> build2 = new CopyOnWriteArrayList<>();
        runBuild(V2, build2, skipCache);
        assertThat(build2, contains("GET " + LATEST, "GET " + V2));
        assertThat(readOutput(), is("content of " + V2));
    }

    private void runBuild(final String latestTarget, final List<String> requests,
                          final Consumer<WGetMojo> initializer) throws Exception {
        Files.deleteIfExists(outputDirectory.resolve(OUTPUT_FILE_NAME));
        final HttpClientBuilder client = redirectingServer(latestTarget, requests);
        try (MockedStatic<HttpClientBuilder> builder = mockStatic(HttpClientBuilder.class)) {
            builder.when(HttpClientBuilder::create).thenReturn(client);
            createMojo(initializer).execute();
        }
    }

    private String readOutput() throws Exception {
        return new String(Files.readAllBytes(outputDirectory.resolve(OUTPUT_FILE_NAME)), StandardCharsets.UTF_8);
    }

    /**
     * Simulates a server where {@link #LATEST} answers 307 to {@code latestTarget},
     * and every other URL answers 200 with a body naming that URL.
     */
    private static HttpClientBuilder redirectingServer(final String latestTarget, final List<String> requests) {
        final HttpClientBuilder clientBuilder = HttpClientBuilder.create();
        clientBuilder.setConnectionManager(new BasicHttpClientConnectionManager() {
            @Override
            public void connect(HttpClientConnection conn, HttpRoute route, int connectTimeout, HttpContext context) {
            }
        });
        clientBuilder.setRequestExecutor(new HttpRequestExecutor() {
            @Override
            protected HttpResponse doSendRequest(HttpRequest request, HttpClientConnection conn, HttpContext context) {
                final String url = absoluteUrl(request, context);
                requests.add(request.getRequestLine().getMethod() + " " + url);
                if (LATEST.equals(url)) {
                    final BasicHttpResponse redirect = new BasicHttpResponse(
                        HttpVersion.HTTP_1_1, HttpStatus.SC_TEMPORARY_REDIRECT, "Temporary Redirect");
                    redirect.setHeader("Location", latestTarget);
                    return redirect;
                }
                final BasicHttpResponse ok = new BasicHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SC_OK, "OK");
                try {
                    ok.setEntity(new StringEntity("content of " + url));
                } catch (UnsupportedEncodingException e) {
                    throw new IllegalStateException(e);
                }
                return ok;
            }
        });
        return clientBuilder;
    }

    private static String absoluteUrl(final HttpRequest request, final HttpContext context) {
        final String uri = request.getRequestLine().getUri();
        if (uri.startsWith("http")) {
            return uri;
        }
        final HttpRoute route = (HttpRoute) context.getAttribute("http.route");
        return route.getTargetHost().getSchemeName() + "://" + route.getTargetHost().getHostName() + uri;
    }

    private WGetMojo createMojo(final Consumer<WGetMojo> initializer) {
        final WGetMojo mojo = new WGetMojo();
        final BuildContext buildContext = mock(BuildContext.class);
        doNothing().when(buildContext).refresh(any(File.class));
        set(mojo, "uri", URI.create(LATEST));
        set(mojo, "outputFileName", OUTPUT_FILE_NAME);
        set(mojo, "outputDirectory", outputDirectory.toFile());
        set(mojo, "cacheDirectory", cacheDirectory.toFile());
        set(mojo, "retries", 1);
        set(mojo, "buildContext", buildContext);
        set(mojo, "session", new MavenSessionStub());
        initializer.accept(mojo);
        return mojo;
    }

    private static void set(final Object object, final String field, final Object value) {
        try {
            final Field f = ReflectionUtils.getFieldByNameIncludingSuperclasses(field, object.getClass());
            f.setAccessible(true);
            f.set(object, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class MavenSessionStub extends MavenSession {
        @SuppressWarnings("deprecation")
        MavenSessionStub() {
            super(null, mock(MavenExecutionRequest.class), null, new LinkedList<>());
            final DefaultRepositorySystemSession repositorySession = new DefaultRepositorySystemSession();
            repositorySession.setOffline(false);
            set(this, "repositorySession", repositorySession);
        }
    }
}
