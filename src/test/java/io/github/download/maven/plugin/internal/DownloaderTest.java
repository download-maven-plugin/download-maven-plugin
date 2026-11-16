package io.github.download.maven.plugin.internal;

import io.github.download.maven.plugin.internal.cache.DownloadCache;
import io.github.download.maven.plugin.internal.checksum.ChecksumMismatchException;
import io.github.download.maven.plugin.internal.checksum.Checksums;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.plugin.logging.Log;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static io.github.download.maven.plugin.internal.TestFiles.read;
import static io.github.download.maven.plugin.internal.TestFiles.write;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link Downloader}: download orchestration with in-memory fakes,
 * no network and no Maven.
 */
public class DownloaderTest {

    private static final URI URI_UNDER_TEST = URI.create("http://example.invalid/file.txt");

    private static final String OUTPUT_FILE_NAME = "file.txt";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File outputDirectory;

    private DownloadCache cache;

    private FakeFetcher fetcher;

    private FakeUnpacker unpacker;

    private List<File> changed;

    private FileLocks locks;

    private Log log;

    @Before
    public void setUp() throws Exception {
        this.outputDirectory = this.temporaryFolder.newFolder("out");
        this.log = mock(Log.class);
        this.cache = new DownloadCache(this.temporaryFolder.newFolder("cache"), this.log);
        this.fetcher = new FakeFetcher();
        this.unpacker = new FakeUnpacker();
        this.changed = new CopyOnWriteArrayList<>();
        this.locks = new FileLocks();
    }

    // ---------------------------------------------------------------- download and existing files

    @Test
    public void downloadsAndNotifiesListener() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "content");
        download(r -> { });
        assertThat(read(output()), is("content"));
        assertThat(this.fetcher.requests, contains(URI_UNDER_TEST));
        assertThat(this.changed, contains(output()));
    }

    @Test
    public void existingFileIsKeptWithoutOverwrite() throws Exception {
        write(output(), "local");
        download(r -> { });
        assertThat(read(output()), is("local"));
        assertThat(this.fetcher.requests, is(empty()));
        assertThat(this.changed, contains(output()));
        verify(this.log).info("File already exists, skipping");
    }

    @Test
    public void existingFileIsReplacedWithOverwrite() throws Exception {
        write(output(), "local");
        this.fetcher.serve(URI_UNDER_TEST, "remote");
        download(r -> r.overwrite(true));
        assertThat(read(output()), is("remote"));
    }

    @Test
    public void existingFileNotMatchingChecksumIsReplaced() throws Exception {
        write(output(), "corrupted");
        this.fetcher.serve(URI_UNDER_TEST, "remote");
        download(r -> r.verifyExisting(true).checksums(md5("remote")));
        assertThat(read(output()), is("remote"));
        verify(this.log).warn(startsWith("The local version of file file.txt doesn't match"));
    }

    @Test
    public void existingFileMatchingChecksumIsKept() throws Exception {
        write(output(), "local");
        download(r -> r.verifyExisting(true).checksums(md5("local")));
        assertThat(read(output()), is("local"));
        assertThat(this.fetcher.requests, is(empty()));
        assertThat(this.changed, contains(output()));
    }

    // ---------------------------------------------------------------- cache

    @Test
    public void cachedFileIsServedWithoutFetching() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "Hello, world!");
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());
        this.fetcher.serve(URI_UNDER_TEST, "Goodbye!");

        download(r -> r.cache(this.cache));
        assertThat(read(output()), is("Hello, world!"));
        assertThat(this.fetcher.requests, contains(URI_UNDER_TEST));
    }

    @Test
    public void queryIsPartOfCacheKey() throws Exception {
        final URI first = URI.create("http://example.invalid/?query=one");
        final URI second = URI.create("http://example.invalid/?query=two");
        this.fetcher.serve(first, "one");
        this.fetcher.serve(second, "two");
        download(r -> r.uri(first).cache(this.cache));
        download(r -> r.uri(first).cache(this.cache).overwrite(true));
        download(r -> r.uri(second).cache(this.cache).overwrite(true));
        assertThat(this.fetcher.requests, contains(first, second));
        assertThat(read(output()), is("two"));
    }

    @Test
    public void permissionsAreAppliedToFileFromCache() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "#!/bin/sh");
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).permissions("+x"));
        assertThat(this.fetcher.requests.size(), is(1));
        assertThat(output().canExecute(), is(true));
    }

    /**
     * Two downloads into the same cache, the first one finishing last, must both end up in the cache index.
     */
    @Test
    public void cacheRetainsEntriesFromConcurrentDownloads() throws Exception {
        final URI foo = URI.create("http://example.invalid/foo");
        final URI bar = URI.create("http://example.invalid/bar");
        final CountDownLatch fooStarted = new CountDownLatch(1);
        final CountDownLatch barFinished = new CountDownLatch(1);
        this.fetcher.respond(foo, target -> {
            fooStarted.countDown();
            await(barFinished);
            write(target, "foo");
        });
        this.fetcher.serve(bar, "bar");

        final CompletableFuture<Void> first = CompletableFuture.runAsync(
            () -> unchecked(() -> download(r -> r.uri(foo).outputFileName("foo").cache(this.cache)))
        );
        final CompletableFuture<Void> second = CompletableFuture.runAsync(
            () -> unchecked(() -> {
                await(fooStarted);
                download(r -> r.uri(bar).outputFileName("bar").cache(this.cache));
                barFinished.countDown();
            })
        );
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        final Checksums none = new Checksums(null, null, null, null, this.log);
        assertThat(read(this.cache.getArtifact(foo, none)), is("foo"));
        assertThat(read(this.cache.getArtifact(bar, none)), is("bar"));
    }

    // ---------------------------------------------------------------- failures

    @Test
    public void failureFailsDownload() {
        this.fetcher.fail(URI_UNDER_TEST, new DownloadFailureException(403, "Forbidden"));
        final DownloadException error = assertThrows(DownloadException.class, () -> download(r -> { }));
        assertThat(error.getCause(), is(instanceOf(DownloadFailureException.class)));
    }

    @Test
    public void failedDownloadIsNotCached() {
        this.fetcher.fail(URI_UNDER_TEST, new DownloadFailureException(500, "Server Error"));
        assertThrows(DownloadException.class, () -> download(r -> r.cache(this.cache)));
        assertThat(this.cache.getArtifact(URI_UNDER_TEST, new Checksums(null, null, null, null, this.log)),
            is((File) null));
    }

    /**
     * An ignored failure must not leave anything behind: nothing cached, nothing unpacked, no notification.
     */
    @Test
    public void ignoredFailureSkipsPostProcessing() throws Exception {
        this.fetcher.fail(URI_UNDER_TEST, new DownloadFailureException(500, "Server Error"));
        download(r -> r.failOnError(false).cache(this.cache).unpack(true));
        verify(this.log).warn(startsWith("Download failed with code 500"));
        assertThat(output().exists(), is(false));
        assertThat(this.unpacker.sources, is(empty()));
        assertThat(this.changed, is(empty()));
        assertThat(this.cache.getArtifact(URI_UNDER_TEST, new Checksums(null, null, null, null, this.log)),
            is((File) null));
    }

    @Test
    public void checksumMismatchFailsEvenIfFailuresAreIgnored() {
        this.fetcher.serve(URI_UNDER_TEST, "content");
        assertThrows(
            ChecksumMismatchException.class,
            () -> download(r -> r.failOnError(false).checksums(md5("other")))
        );
    }

    @Test
    public void offlineWithoutCacheFailsWithoutFetching() {
        final DownloadException error = assertThrows(
            DownloadException.class, () -> download(r -> r.offline(true))
        );
        assertThat(error.getMessage(), is("No file in cache and maven is in offline mode"));
        assertThat(this.fetcher.requests, is(empty()));
    }

    @Test
    public void offlineFailureCanBeIgnored() throws Exception {
        download(r -> r.offline(true).failOnError(false));
        verify(this.log).warn("Ignoring download failure.");
        assertThat(this.fetcher.requests, is(empty()));
        assertThat(this.changed, is(empty()));
    }

    @Test
    public void offlineUsesCachedFile() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "content");
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).offline(true));
        assertThat(read(output()), is("content"));
        assertThat(this.fetcher.requests.size(), is(1));
    }

    @Test
    public void lockedFileFailsDownload() throws Exception {
        try (HeldLock ignored = HeldLock.on(this.locks.forFile(output()))) {
            final DownloadException error = assertThrows(
                DownloadException.class, () -> download(r -> r.lockWaitMillis(10L))
            );
            assertThat(error.getMessage(), org.hamcrest.Matchers.startsWith("Could not acquire lock for File"));
        }
        assertThat(this.fetcher.requests, is(empty()));
    }

    @Test
    public void lockedFileOnlyWarnsIfFailuresAreIgnored() throws Exception {
        try (HeldLock ignored = HeldLock.on(this.locks.forFile(output()))) {
            download(r -> r.lockWaitMillis(10L).failOnError(false));
        }
        verify(this.log).warn(startsWith("Could not acquire lock for File"));
        assertThat(this.fetcher.requests, is(empty()));
    }

    // ---------------------------------------------------------------- retries

    @Test
    public void retriesServerErrorAndSucceeds() throws Exception {
        this.fetcher.fail(URI_UNDER_TEST, new DownloadFailureException(503, "Unavailable"));
        this.fetcher.serve(URI_UNDER_TEST, "content");
        download(r -> r.retryPolicy(retries(3, null)));
        assertThat(read(output()), is("content"));
        assertThat(this.fetcher.requests.size(), is(2));
        verify(this.log).info("Retrying (2 more)");
    }

    @Test
    public void successfulDownloadIsNotRetried() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "content");
        download(r -> r.retryPolicy(retries(3, null)));
        assertThat(this.fetcher.requests.size(), is(1));
    }

    @Test
    public void clientErrorIsNotRetriedByDefault() {
        this.fetcher.fail(URI_UNDER_TEST, new DownloadFailureException(404, "Not Found"));
        assertThrows(DownloadException.class, () -> download(r -> r.retryPolicy(retries(3, null))));
        assertThat(this.fetcher.requests.size(), is(1));
    }

    @Test
    public void ioFailureIsNotRetriedByDefault() {
        this.fetcher.fail(URI_UNDER_TEST, new SocketTimeoutException("timed out"));
        this.fetcher.serve(URI_UNDER_TEST, "content");
        final DownloadException error = assertThrows(
            DownloadException.class, () -> download(r -> r.retryPolicy(retries(3, null)))
        );
        assertThat(error.getCause(), is(instanceOf(SocketTimeoutException.class)));
        assertThat(this.fetcher.requests.size(), is(1));
    }

    @Test
    public void doesNotRetryCodeOutsidePolicy() {
        this.fetcher.fail(URI_UNDER_TEST, new DownloadFailureException(503, "Unavailable"));
        this.fetcher.serve(URI_UNDER_TEST, "content");
        assertThrows(DownloadException.class, () -> download(r -> r.retryPolicy(retries(3, Collections.singletonList(404)))));
        assertThat(this.fetcher.requests.size(), is(1));
    }

    @Test
    public void retriesConfiguredException() throws Exception {
        this.fetcher.fail(URI_UNDER_TEST, new SocketTimeoutException("timed out"));
        this.fetcher.serve(URI_UNDER_TEST, "content");
        download(r -> r.retryPolicy(new RetryPolicy(
            2, null, Collections.singletonList(SocketTimeoutException.class),
            Duration.ZERO, 1.0d, Duration.ZERO
        )));
        assertThat(read(output()), is("content"));
    }

    // ---------------------------------------------------------------- unpacking

    @Test
    public void unpacksDownloadedFileAndRemovesIt() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "archive");
        download(r -> r.unpack(true));
        assertThat(this.unpacker.sources, contains(output()));
        assertThat(output().exists(), is(false));
        assertThat(this.changed, contains(this.outputDirectory));
    }

    @Test
    public void unpacksCachedCopyWithoutCopyingIt() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "archive");
        download(r -> r.cache(this.cache).unpack(true));
        this.unpacker.sources.clear();

        download(r -> r.cache(this.cache).unpack(true));
        assertThat(this.fetcher.requests.size(), is(1));
        assertThat(this.unpacker.sources.size(), is(1));
        assertThat(this.unpacker.sources.get(0).getParentFile(), is(this.cache.getArtifact(
            URI_UNDER_TEST, new Checksums(null, null, null, null, this.log)).getParentFile()));
        assertThat(output().exists(), is(false));
    }

    @Test
    public void unpackWhenChangedSkipsCachedCopy() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "archive");
        download(r -> r.cache(this.cache).unpackWhenChanged(true));
        this.unpacker.sources.clear();
        this.changed.clear();

        download(r -> r.cache(this.cache).unpackWhenChanged(true));
        assertThat(this.unpacker.sources, is(empty()));
        assertThat(this.changed, is(empty()));
        verify(this.log).info("Skipping unpacking as the file has not changed");
    }

    @Test
    public void unpackWinsOverUnpackWhenChanged() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "archive");
        download(r -> r.cache(this.cache).unpack(true));
        this.unpacker.sources.clear();

        download(r -> r.cache(this.cache).unpack(true).unpackWhenChanged(true));
        assertThat(this.unpacker.sources.size(), is(1));
        verify(this.log).info("Unpacking even though unchanged cache file exists because unpack = true");
    }

    // ---------------------------------------------------------------- helpers

    private void download(final Consumer<DownloadRequest.Builder> customizer) throws Exception {
        final DownloadRequest.Builder builder = new DownloadRequest.Builder()
            .uri(URI_UNDER_TEST)
            .outputDirectory(this.outputDirectory)
            .outputFileName(OUTPUT_FILE_NAME)
            .checksums(new Checksums(null, null, null, null, this.log));
        customizer.accept(builder);
        new Downloader(this.fetcher, this.unpacker, this.changed::add, this.locks, this.log)
            .download(builder.build());
    }

    private File output() {
        return new File(this.outputDirectory, OUTPUT_FILE_NAME);
    }

    private Checksums md5(final String content) {
        return new Checksums(DigestUtils.md5Hex(content), null, null, null, this.log);
    }

    private static RetryPolicy retries(final int attempts, final List<Integer> codes) {
        return new RetryPolicy(attempts, codes, Collections.emptyList(), Duration.ZERO, 1.0d, Duration.ZERO);
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat("timed out waiting", latch.await(5, TimeUnit.SECONDS), is(true));
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void unchecked(final ThrowingRunnable action) {
        try {
            action.run();
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Response {
        void writeTo(File target) throws IOException;
    }

    /**
     * Serves queued responses per URI; the last response is repeated. Records requested URIs.
     */
    private static final class FakeFetcher implements Fetcher {

        private final Map<URI, List<Object>> responses = new ConcurrentHashMap<>();

        private final List<URI> requests = new CopyOnWriteArrayList<>();

        void serve(final URI uri, final String content) {
            respond(uri, target -> write(target, content));
        }

        void respond(final URI uri, final Response response) {
            this.responses.computeIfAbsent(uri, ignored -> new CopyOnWriteArrayList<>()).add(response);
        }

        void fail(final URI uri, final Exception failure) {
            this.responses.computeIfAbsent(uri, ignored -> new CopyOnWriteArrayList<>()).add(failure);
        }

        @Override
        public void fetch(final URI uri, final File target) throws IOException {
            this.requests.add(uri);
            final List<Object> queue = this.responses.get(uri);
            if (queue == null || queue.isEmpty()) {
                throw new DownloadFailureException(404, "Not Found");
            }
            final Object next = queue.size() > 1 ? queue.remove(0) : queue.get(0);
            if (next instanceof IOException) {
                throw (IOException) next;
            }
            if (next instanceof RuntimeException) {
                throw (RuntimeException) next;
            }
            ((Response) next).writeTo(target);
        }
    }

    /**
     * Records unpacked sources.
     */
    private static final class FakeUnpacker implements Unpacker {

        private final List<File> sources = new ArrayList<>();

        @Override
        public void unpack(final File source, final File outputDirectory, final String outputFileName) {
            this.sources.add(source);
        }
    }
}
