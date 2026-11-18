package io.github.download.maven.plugin.internal;

import io.github.download.maven.plugin.internal.cache.DownloadCache;
import io.github.download.maven.plugin.internal.cache.InUseCaches;
import io.github.download.maven.plugin.internal.cache.Validators;
import io.github.download.maven.plugin.internal.checksum.ChecksumMismatchException;
import io.github.download.maven.plugin.internal.checksum.Checksums;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.download.maven.plugin.internal.TestFiles.newFolder;
import static io.github.download.maven.plugin.internal.TestFiles.read;
import static io.github.download.maven.plugin.internal.TestFiles.write;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @TempDir
    public Path tempDir;

    private File outputDirectory;

    private DownloadCache cache;

    private FakeFetcher fetcher;

    private FakeUnpacker unpacker;

    private List<File> changed;

    private FileLocks locks;

    private Log log;

    @BeforeEach
    public void setUp() throws Exception {
        this.outputDirectory = newFolder(this.tempDir, "out");
        this.log = mock(Log.class);
        this.cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
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
            return Fetched.content(Validators.NONE);
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

    /**
     * Issue #331: a transfer cut off mid-way must not leave a broken file that the next build
     * takes as already downloaded.
     */
    @Test
    public void cutOffDownloadLeavesNoFile() throws Exception {
        this.fetcher.respond(URI_UNDER_TEST, DownloaderTest::cutOff);
        this.fetcher.serve(URI_UNDER_TEST, "complete");
        assertThrows(DownloadException.class, () -> download(r -> { }));
        assertThat(this.outputDirectory.list(), is(emptyArray()));

        download(r -> { });
        assertThat(read(output()), is("complete"));
    }

    @Test
    public void ignoredCutOffDownloadLeavesNoFile() throws Exception {
        this.fetcher.respond(URI_UNDER_TEST, DownloaderTest::cutOff);
        download(r -> r.failOnError(false));
        assertThat(this.outputDirectory.list(), is(emptyArray()));
        assertThat(this.changed, is(empty()));
    }

    @Test
    public void downloadFailingItsChecksumLeavesNoFile() {
        this.fetcher.serve(URI_UNDER_TEST, "corrupted");
        assertThrows(ChecksumMismatchException.class, () -> download(r -> r.checksums(md5("expected"))));
        assertThat(this.outputDirectory.list(), is(emptyArray()));
    }

    /**
     * A build killed during the transfer can't clean up: the output file must only appear
     * once the download is complete.
     */
    @Test
    public void outputFileAppearsOnlyWhenDownloadIsComplete() throws Exception {
        final List<Boolean> existedDuringTransfer = new ArrayList<>();
        this.fetcher.respond(
            URI_UNDER_TEST,
            target -> {
                write(target, "content");
                existedDuringTransfer.add(output().exists());
                return Fetched.content(Validators.NONE);
            }
        );
        download(r -> { });
        assertThat(existedDuringTransfer, contains(false));
        assertThat(read(output()), is("content"));
    }

    @Test
    public void cutOffRevalidationLeavesNoFile() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.respond(URI_UNDER_TEST, DownloaderTest::cutOff);
        download(r -> r.cache(this.cache).unpack(true));

        download(r -> r.cache(this.cache).unpack(true).revalidate(true));
        assertThat(this.outputDirectory.list(), is(emptyArray()));
        assertThat(this.unpacker.contents, contains("v1", "v1"));
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

    // ---------------------------------------------------------------- revalidation of cached files

    @Test
    public void revalidationSendsKnownValidatorsAndUsesCachedFileWhenNotModified() throws Exception {
        final Validators validators = new Validators("\"v1\"", "Sat, 01 Jan 2000 00:00:00 GMT");
        this.fetcher.serve(URI_UNDER_TEST, "v1", validators);
        this.fetcher.notModified(URI_UNDER_TEST);
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).revalidate(true));
        assertThat(this.fetcher.known, contains(Validators.NONE, validators));
        assertThat(read(output()), is("v1"));
        verify(this.log).info("Cached file is up to date: not modified on the server");
    }

    /**
     * Issue #315: a "latest" URL that starts pointing at a new release must not be served
     * from the cache forever. Redirects don't matter here: the validators come from the final response.
     */
    @Test
    public void revalidationReplacesOutdatedCachedFile() throws Exception {
        final Validators second = new Validators("\"v2\"", null);
        this.fetcher.serve(URI_UNDER_TEST, "v1", new Validators("\"v1\"", null));
        this.fetcher.serve(URI_UNDER_TEST, "v2", second);
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).revalidate(true));
        assertThat(read(output()), is("v2"));
        assertThat(read(this.cache.getArtifact(URI_UNDER_TEST, md5("v2"))), is("v2"));
        assertThat(this.cache.validators(URI_UNDER_TEST), is(second));
        assertThat(this.changed, contains(output(), output()));
    }

    @Test
    public void revalidationAppliesToOverwrittenOutputFile() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.serve(URI_UNDER_TEST, "v2");
        download(r -> r.cache(this.cache));

        download(r -> r.cache(this.cache).overwrite(true).revalidate(true));
        assertThat(read(output()), is("v2"));
    }

    @Test
    public void sameContentFromServerWithoutValidatorsCountsAsUnchanged() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "archive");
        download(r -> r.cache(this.cache).unpackWhenChanged(true));
        this.unpacker.sources.clear();

        download(r -> r.cache(this.cache).unpackWhenChanged(true).revalidate(true));
        assertThat(this.fetcher.known, contains(Validators.NONE, Validators.NONE));
        assertThat(this.unpacker.sources, is(empty()));
        assertThat(output().exists(), is(false));
        verify(this.log).info("Cached file is up to date: the server sent the same content");
    }

    @Test
    public void newVersionIsUnpackedWhenChanged() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "archive 1");
        this.fetcher.serve(URI_UNDER_TEST, "archive 2");
        download(r -> r.cache(this.cache).unpackWhenChanged(true));

        download(r -> r.cache(this.cache).unpackWhenChanged(true).revalidate(true));
        assertThat(this.unpacker.sources.size(), is(2));
        assertThat(read(this.cache.getArtifact(URI_UNDER_TEST, md5("archive 2"))), is("archive 2"));
    }

    /**
     * On Windows, another build reading the cached file prevents replacing it.
     * The new version must still be unpacked, not the stale cached copy.
     */
    @Test
    public void downloadedFileIsUnpackedWhenCachedFileIsInUse() throws Exception {
        final DownloadCache cache = InUseCaches.create(newFolder(this.tempDir, "in-use"), this.log);
        this.fetcher.serve(URI_UNDER_TEST, "archive 1");
        this.fetcher.serve(URI_UNDER_TEST, "archive 2");
        download(r -> r.cache(cache).unpackWhenChanged(true));

        download(r -> r.cache(cache).unpackWhenChanged(true).revalidate(true));
        assertThat(this.unpacker.contents, contains("archive 1", "archive 2"));
        assertThat(this.unpacker.sources.get(1), is(output()));
        assertThat(output().exists(), is(false));
        assertThat(read(cache.getArtifact(URI_UNDER_TEST, md5("archive 1"))), is("archive 1"));
        verify(this.log).warn(startsWith("Could not update the download cache, using the downloaded file: "));
    }

    @Test
    public void downloadedFileIsCopiedWhenCachedFileIsInUse() throws Exception {
        final DownloadCache cache = InUseCaches.create(newFolder(this.tempDir, "in-use"), this.log);
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.serve(URI_UNDER_TEST, "v2");
        download(r -> r.cache(cache));
        Files.delete(output().toPath());

        download(r -> r.cache(cache).revalidate(true));
        assertThat(read(output()), is("v2"));
        assertThat(this.changed, contains(output(), output()));
    }

    @Test
    public void cachedFileIsUsedWhenServerCannotBeAsked() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.respond(
            URI_UNDER_TEST,
            target -> {
                write(target, "partial");
                throw new SocketTimeoutException("Read timed out");
            }
        );
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).revalidate(true));
        assertThat(read(output()), is("v1"));
        assertThat(read(this.cache.getArtifact(URI_UNDER_TEST, md5("v1"))), is("v1"));
        verify(this.log).warn(
            "Could not check " + URI_UNDER_TEST + " for a newer version, using the cached file: Read timed out"
        );
    }

    @Test
    public void cachedFileIsUsedWhenServerSendsNoContent() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.respond(URI_UNDER_TEST, target -> Fetched.content(Validators.NONE));
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).revalidate(true));
        assertThat(read(output()), is("v1"));
        assertThat(read(this.cache.getArtifact(URI_UNDER_TEST, md5("v1"))), is("v1"));
        verify(this.log).warn(
            "Could not check " + URI_UNDER_TEST + " for a newer version, using the cached file: the server sent no content"
        );
    }

    @Test
    public void cachedFileIsUsedWhenServerSendsEmptyFile() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.serve(URI_UNDER_TEST, "");
        download(r -> r.cache(this.cache).unpackWhenChanged(true));
        this.unpacker.sources.clear();

        download(r -> r.cache(this.cache).unpackWhenChanged(true).revalidate(true));
        assertThat(this.unpacker.sources, is(empty()));
        assertThat(output().exists(), is(false));
        assertThat(read(this.cache.getArtifact(URI_UNDER_TEST, md5("v1"))), is("v1"));
    }

    @Test
    public void cachedFileIsUsedWhenServerAnswersWithServerError() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.fail(URI_UNDER_TEST, new DownloadFailureException(503, "Unavailable"));
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).revalidate(true));
        assertThat(read(output()), is("v1"));
    }

    @Test
    public void cachedFileIsUsedWhenServerAnswersTooManyRequests() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.fail(URI_UNDER_TEST, new DownloadFailureException(429, "Too Many Requests"));
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).revalidate(true));
        assertThat(read(output()), is("v1"));
    }

    @Test
    public void refusalByServerFailsRevalidation() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.respond(
            URI_UNDER_TEST,
            target -> {
                write(target, "partial");
                throw new DownloadFailureException(404, "Not Found");
            }
        );
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        final DownloadException error = assertThrows(
            DownloadException.class, () -> download(r -> r.cache(this.cache).revalidate(true))
        );
        assertThat(error.getCause(), instanceOf(DownloadFailureException.class));
        assertThat(output().exists(), is(false));
        assertThat(read(this.cache.getArtifact(URI_UNDER_TEST, md5("v1"))), is("v1"));
    }

    @Test
    public void cachedFileIsUsedWhenServerRefusesWithoutFailOnError() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.fail(URI_UNDER_TEST, new DownloadFailureException(404, "Not Found"));
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).failOnError(false).revalidate(true));
        assertThat(read(output()), is("v1"));
        verify(this.log).warn(
            "Could not check " + URI_UNDER_TEST
                + " for a newer version, using the cached file: Download failed with code 404: Not Found"
        );
    }

    @Test
    public void revalidationIsRetried() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        this.fetcher.fail(URI_UNDER_TEST, new DownloadFailureException(503, "Unavailable"));
        this.fetcher.serve(URI_UNDER_TEST, "v2");
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).revalidate(true).retryPolicy(retries(2, null)));
        assertThat(read(output()), is("v2"));
    }

    @Test
    public void checksumPinsContentSoServerIsNotAsked() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).checksums(md5("v1")).revalidate(true));
        assertThat(this.fetcher.requests.size(), is(1));
        assertThat(read(output()), is("v1"));
    }

    @Test
    public void serverIsNotAskedOffline() throws Exception {
        this.fetcher.serve(URI_UNDER_TEST, "v1");
        download(r -> r.cache(this.cache));
        Files.delete(output().toPath());

        download(r -> r.cache(this.cache).offline(true).revalidate(true));
        assertThat(this.fetcher.requests.size(), is(1));
        assertThat(read(output()), is("v1"));
    }

    @Test
    public void validatorsAreRecordedEvenIfRevalidationIsOff() throws Exception {
        final Validators validators = new Validators("\"v1\"", null);
        this.fetcher.serve(URI_UNDER_TEST, "v1", validators);
        download(r -> r.cache(this.cache));
        assertThat(this.cache.validators(URI_UNDER_TEST), is(validators));
    }

    @Test
    public void notModifiedWithoutCachedFileFailsDownload() {
        this.fetcher.notModified(URI_UNDER_TEST);
        final DownloadException error = assertThrows(DownloadException.class, () -> download(r -> { }));
        assertThat(
            error.getMessage(), is("Server answered 304 Not Modified, but there is no cached copy to use")
        );
    }

    // ---------------------------------------------------------------- helpers

    private static Fetched cutOff(final File target) throws IOException {
        write(target, "partial");
        throw new IOException("Premature end of Content-Length delimited message body (expected: 100; received: 7)");
    }

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
        Fetched writeTo(File target) throws IOException;
    }

    /**
     * Serves queued responses per URI; the last response is repeated. Records requested URIs.
     */
    private static final class FakeFetcher implements Fetcher {

        private final Map<URI, List<Object>> responses = new ConcurrentHashMap<>();

        private final List<URI> requests = new CopyOnWriteArrayList<>();

        /** Validators received with each request. */
        private final List<Validators> known = new CopyOnWriteArrayList<>();

        void serve(final URI uri, final String content) {
            serve(uri, content, Validators.NONE);
        }

        void respond(final URI uri, final Response response) {
            this.responses.computeIfAbsent(uri, ignored -> new CopyOnWriteArrayList<>()).add(response);
        }

        void serve(final URI uri, final String content, final Validators validators) {
            respond(
                uri,
                target -> {
                    write(target, content);
                    return Fetched.content(validators);
                }
            );
        }

        void notModified(final URI uri) {
            this.responses.computeIfAbsent(uri, ignored -> new CopyOnWriteArrayList<>()).add((Response) target -> Fetched.notModified());
        }

        void fail(final URI uri, final Exception failure) {
            this.responses.computeIfAbsent(uri, ignored -> new CopyOnWriteArrayList<>()).add(failure);
        }

        @Override
        public Fetched fetch(final URI uri, final File target, final Validators validators) throws IOException {
            this.requests.add(uri);
            this.known.add(validators);
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
            return ((Response) next).writeTo(target);
        }
    }

    /**
     * Records unpacked sources.
     */
    private static final class FakeUnpacker implements Unpacker {

        private final List<File> sources = new ArrayList<>();

        private final List<String> contents = new ArrayList<>();

        @Override
        public void unpack(final File source, final File outputDirectory, final String outputFileName)
            throws IOException {
            this.sources.add(source);
            this.contents.add(read(source));
        }
    }
}
