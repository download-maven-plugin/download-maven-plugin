package io.github.download.maven.plugin.internal;

import io.github.download.maven.plugin.internal.cache.DownloadCache;
import io.github.download.maven.plugin.internal.checksum.Checksums;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.maven.plugin.logging.Log;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;

/**
 * Issue #315: a stable "latest" URL that redirects to a per-version URL must not be
 * served from the cache forever once it starts pointing at a new version.
 *
 * <p>Uses an in-memory {@link Fetcher} (no sockets) that behaves like a server whose
 * "latest" URL redirects to the current release. Following the redirect itself is
 * {@link HttpFileRequester}'s job and is covered by {@code WGetMojoTest#testShouldFollowRedirects}.</p>
 */
public class DownloaderRedirectCacheTest {

    private static final URI LATEST = URI.create("http://api.example/latest/binary");
    private static final URI V1 = URI.create("http://cdn.example/bin/app-1.0.zip");
    private static final URI V2 = URI.create("http://cdn.example/bin/app-2.0.zip");
    private static final String OUTPUT_FILE_NAME = "app.zip";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File cacheDirectory;
    private File outputDirectory;
    private Log log;

    @Before
    public void setUp() throws Exception {
        cacheDirectory = temporaryFolder.newFolder("cache");
        outputDirectory = temporaryFolder.newFolder("out");
        log = mock(Log.class);
    }

    /**
     * Issue #315: once the "latest" URL redirects to a new release, the next build must
     * pick up that release instead of serving the previous one from the cache.
     */
    @Test
    @Ignore("Bug #315: the cache is keyed by the original URL, so build 2 makes no request "
        + "and gets the stale V1 content from the cache")
    public void newReleaseIsDownloadedAfterLatestRedirectMovesOn() throws Exception {
        final DownloadCache cache = new DownloadCache(cacheDirectory, log);
        final List<URI> build1 = new CopyOnWriteArrayList<>();
        runBuild(V1, build1, cache);
        assertThat(build1, contains(LATEST));
        assertThat(readOutput(), is("content of " + V1));

        // A new release is published: "latest" now redirects to V2.
        // Build 2 starts from a clean output directory (e.g. after `mvn clean`).
        final List<URI> build2 = new CopyOnWriteArrayList<>();
        runBuild(V2, build2, cache);

        assertThat("server must be asked where latest points now", build2, hasItem(LATEST));
        assertThat("new release must be used", readOutput(), is("content of " + V2));
    }

    /**
     * Without a cache every build asks the server again, so a new release
     * behind the "latest" URL is picked up.
     */
    @Test
    public void skipCacheFollowsLatestRedirectOnEveryBuild() throws Exception {
        final List<URI> build1 = new CopyOnWriteArrayList<>();
        runBuild(V1, build1, null);
        assertThat(build1, contains(LATEST));
        assertThat(readOutput(), is("content of " + V1));

        final List<URI> build2 = new CopyOnWriteArrayList<>();
        runBuild(V2, build2, null);
        assertThat(build2, contains(LATEST));
        assertThat(readOutput(), is("content of " + V2));
    }

    /**
     * Runs one build against a server where {@link #LATEST} redirects to {@code latestTarget}.
     */
    private void runBuild(final URI latestTarget, final List<URI> requests, final DownloadCache cache)
        throws Exception {
        Files.deleteIfExists(new File(outputDirectory, OUTPUT_FILE_NAME).toPath());
        final Fetcher server = (uri, target) -> {
            requests.add(uri);
            final URI resolved = LATEST.equals(uri) ? latestTarget : uri;
            TestFiles.write(target, "content of " + resolved);
        };
        new Downloader(server, (source, dir, name) -> { }, file -> { }, new FileLocks(), log)
            .download(
                new DownloadRequest.Builder()
                    .uri(LATEST)
                    .outputDirectory(outputDirectory)
                    .outputFileName(OUTPUT_FILE_NAME)
                    .checksums(new Checksums(null, null, null, null, log))
                    .cache(cache)
                    .build()
            );
    }

    private String readOutput() throws Exception {
        return TestFiles.read(new File(outputDirectory, OUTPUT_FILE_NAME));
    }
}
