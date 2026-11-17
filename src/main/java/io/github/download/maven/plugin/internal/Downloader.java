/*
 * Copyright 2009-2018 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.github.download.maven.plugin.internal;

import io.github.download.maven.plugin.internal.cache.DownloadCache;
import io.github.download.maven.plugin.internal.cache.Validators;
import io.github.download.maven.plugin.internal.checksum.ChecksumMismatchException;
import io.github.download.maven.plugin.internal.checksum.Checksums;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.apache.maven.plugin.logging.Log;

/**
 * Downloads a file: guards it against concurrent downloads, reuses an existing or
 * cached copy, fetches it with retries, verifies checksums, populates the cache,
 * applies permissions and optionally unpacks it.
 *
 * <p>A cached copy is used as is, unless the request asks to {@link DownloadRequest#revalidate()
 * revalidate} it. Then the server is asked whether the copy is still current, and a newer
 * version replaces it. If the server can't be asked, e.g. it is unreachable or answers with an
 * error, the cached copy is used.</p>
 *
 * <p>Talks to the outside world only through {@link Fetcher}, {@link Unpacker} and
 * {@link OutputListener}, so it can be tested without network or Maven.</p>
 */
public final class Downloader {

    /**
     * Transfers remote resources.
     */
    private final Fetcher fetcher;

    /**
     * Extracts archives.
     */
    private final Unpacker unpacker;

    /**
     * Notified about produced files.
     */
    private final OutputListener listener;

    /**
     * Locks guarding output files.
     */
    private final FileLocks locks;

    /**
     * Logger.
     */
    private final Log log;

    /**
     * Constructor.
     * @param fetcher Transfers remote resources
     * @param unpacker Extracts archives
     * @param listener Notified about produced files
     * @param locks Locks guarding output files; share one instance between concurrent downloads
     * @param log Logger
     */
    public Downloader(
        final Fetcher fetcher, final Unpacker unpacker, final OutputListener listener,
        final FileLocks locks, final Log log
    ) {
        this.fetcher = fetcher;
        this.unpacker = unpacker;
        this.listener = listener;
        this.locks = locks;
        this.log = log;
    }

    /**
     * Performs the download.
     * @param request What to download and how
     * @throws DownloadException If the download fails and {@code failOnError} is set,
     *  or regardless of it on checksum mismatch or misconfiguration
     * @throws IOException If local file operations fail
     */
    public void download(final DownloadRequest request) throws DownloadException, IOException {
        final File outputFile = request.outputFile();
        final Lock lock = this.locks.forFile(outputFile);
        final boolean acquired;
        try {
            acquired = lock.tryLock(request.lockWaitMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DownloadException(String.format("Interrupted while waiting for lock on %s", outputFile), ex);
        }
        if (acquired) {
            try {
                this.downloadLocked(request);
            } finally {
                lock.unlock();
            }
        } else {
            this.fail(
                request,
                String.format("Could not acquire lock for File: %s in %dms", outputFile, request.lockWaitMillis())
            );
        }
    }

    /**
     * Performs the download while holding the file lock.
     * @param request What to download and how
     * @throws DownloadException If the download fails
     * @throws IOException If local file operations fail
     */
    private void downloadLocked(final DownloadRequest request) throws DownloadException, IOException {
        final File outputFile = request.outputFile();
        Optional<File> cachedFile = Optional.empty();
        Optional<Validators> fetched = Optional.empty();
        boolean fileWasCached = false;
        boolean available = true;
        if (!this.keepExisting(request)) {
            cachedFile = request.cache().map(cache -> cache.getArtifact(request.uri(), request.checksums()));
            fileWasCached = cachedFile.map(File::exists).orElse(false);
            if (fileWasCached) {
                fetched = this.revalidate(request);
            }
            if (fileWasCached && !fetched.isPresent()) {
                this.useCached(request, cachedFile.get());
            } else if (!fileWasCached) {
                fetched = this.fetch(request);
                available = fetched.isPresent();
            }
        }
        if (available) {
            this.complete(request, cachedFile, fileWasCached, fetched);
        }
    }

    /**
     * Uses the cached copy of the file. Unpacking reads the cached copy directly,
     * so it's only copied to the output file if the file won't be unpacked.
     * @param request What to download and how
     * @param cached Cached copy of the file
     * @throws IOException If the cached file can't be copied
     */
    private void useCached(final DownloadRequest request, final File cached) throws IOException {
        this.log.debug("File was cached: " + cached.getAbsolutePath());
        if (!request.unpack() && !request.unpackWhenChanged()) {
            final File outputFile = request.outputFile();
            this.log.debug("Copying cached file to " + outputFile.getAbsolutePath());
            Files.copy(cached.toPath(), outputFile.toPath());
        }
    }

    /**
     * Decides whether an existing output file is kept, deleting it otherwise.
     * @param request What to download and how
     * @return True if the existing file is used as is
     * @throws IOException If the existing file can't be read
     */
    private boolean keepExisting(final DownloadRequest request) throws IOException {
        final File outputFile = request.outputFile();
        boolean keep = false;
        if (outputFile.exists()) {
            boolean checksumMatch = true;
            if (request.verifyExisting()) {
                try {
                    request.checksums().validate(outputFile);
                } catch (final ChecksumMismatchException ex) {
                    this.log.warn(
                        String.format(
                            "The local version of file %s %s %s", outputFile.getName(),
                            "doesn't match the expected checksum.",
                            "You should consider checking the specified checksum is correctly set."
                        )
                    );
                    checksumMatch = false;
                }
            }
            if (!checksumMatch || request.overwrite()) {
                outputFile.delete();
            } else {
                this.log.info("File already exists, skipping");
                keep = true;
            }
        }
        return keep;
    }

    /**
     * Fetches the file with retries and verifies its checksums.
     * @param request What to download and how
     * @return Validators of the downloaded file, empty if a failure was ignored
     * @throws DownloadException If the download fails and {@code failOnError} is set,
     *  or regardless of it on checksum mismatch or misconfiguration
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private Optional<Validators> fetch(final DownloadRequest request) throws DownloadException {
        Optional<Validators> fetched = Optional.empty();
        if (request.offline()) {
            this.fail(request, "No file in cache and maven is in offline mode");
            this.log.warn("Ignoring download failure.");
        } else {
            final File outputFile = request.outputFile();
            final Checksums checksums = request.checksums();
            try {
                fetched = Optional.of(
                    this.retry(request.retryPolicy()).executeCheckedSupplier(
                        () -> {
                            final Fetched result = this.fetcher.fetch(request.uri(), outputFile, Validators.NONE);
                            if (result.isNotModified()) {
                                // only possible with conditional headers configured on the plugin
                                throw new IOException(
                                    "Server answered 304 Not Modified, but there is no cached copy to use"
                                );
                            }
                            checksums.validate(outputFile);
                            return result.validators();
                        }
                    )
                );
            } catch (final DownloadException ex) {
                throw ex;
            } catch (final Throwable ex) {
                if (request.failOnError()) {
                    throw new DownloadException(ex.getMessage(), ex);
                }
                this.log.warn(ex.getMessage());
            }
        }
        return fetched;
    }

    /**
     * Asks the server whether the cached copy is still current, if the request wants that.
     *
     * <p>Nothing is asked when offline. Nothing is asked either when checksums are supplied:
     * they pin the content, so a newer version could only fail the build. A server that can't
     * answer "not modified" sends the file again; whether its content changed is decided when
     * it is stored in the cache, see {@link #complete}.</p>
     * @param request What to download and how
     * @return Validators of the file the server sent into the output file; empty if the cached
     *  copy is to be used: the server reported it as current, or couldn't be asked
     * @throws DownloadException On misconfiguration, e.g. credentials that can't be resolved
     * @throws IOException If a partially downloaded file can't be removed
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private Optional<Validators> revalidate(final DownloadRequest request) throws DownloadException, IOException {
        Optional<Validators> fetched = Optional.empty();
        if (request.revalidate() && !request.offline() && request.checksums().isEmpty()) {
            final File outputFile = request.outputFile();
            try {
                final Validators known = request.cache().get().validators(request.uri());
                final Fetched result = this.retry(request.retryPolicy()).executeCheckedSupplier(
                    () -> this.fetcher.fetch(request.uri(), outputFile, known)
                );
                if (result.isNotModified()) {
                    this.log.info("Cached file is up to date: not modified on the server");
                } else {
                    fetched = Optional.of(result.validators());
                }
            } catch (final DownloadException ex) {
                throw ex;
            } catch (final Throwable ex) {
                this.log.warn(
                    String.format(
                        "Could not check %s for a newer version, using the cached file: %s",
                        request.uri(), ex.getMessage()
                    )
                );
                Files.deleteIfExists(outputFile.toPath());
            }
        }
        return fetched;
    }

    /**
     * Post-processes an available file: caches it, applies permissions, unpacks it
     * and notifies the listener.
     *
     * <p>The file counts as changed if it wasn't cached before, or if a revalidation brought
     * content that differs from the cached copy. Only a changed file is unpacked with
     * {@code unpackWhenChanged}.</p>
     * @param request What to download and how
     * @param cachedFile Cached copy of the file, if it was found in the cache
     * @param fileWasCached Whether the file was in the cache before
     * @param fetched Validators of the file if it was just downloaded
     * @throws DownloadException If permissions can't be applied or the archive type is unknown
     * @throws IOException If caching or unpacking fails
     */
    private void complete(
        final DownloadRequest request, final Optional<File> cachedFile, final boolean fileWasCached,
        final Optional<Validators> fetched
    ) throws DownloadException, IOException {
        final File outputFile = request.outputFile();
        boolean changed = !fileWasCached;
        if (request.cache().isPresent() && fetched.isPresent()) {
            changed = request.cache().get().store(request.uri(), outputFile, request.checksums(), fetched.get());
        } else if (request.cache().isPresent() && changed) {
            request.cache().get().install(request.uri(), outputFile, request.checksums());
        }
        if (fileWasCached && fetched.isPresent()) {
            this.log.info(
                changed ? "Cached file was outdated: downloaded the current version"
                    : "Cached file is up to date: the server sent the same content"
            );
        }
        new FilePermissions(request.permissions(), this.log).applyTo(outputFile);
        if (request.unpack() || request.unpackWhenChanged()) {
            if (!request.unpack() && !changed) {
                this.log.info("Skipping unpacking as the file has not changed");
            } else {
                this.unpack(request, cachedFile, changed);
                this.listener.changed(request.outputDirectory());
            }
            Files.deleteIfExists(outputFile.toPath());
        } else {
            this.listener.changed(outputFile);
        }
    }

    /**
     * Unpacks the cached copy if present, the downloaded file otherwise.
     * @param request What to download and how
     * @param cachedFile Cached copy of the file
     * @param changed Whether the file is new or has changed, only used for logging
     * @throws DownloadException If the archive type is unknown
     * @throws IOException If unpacking fails
     */
    private void unpack(final DownloadRequest request, final Optional<File> cachedFile, final boolean changed)
        throws DownloadException, IOException {
        if (request.unpackWhenChanged() && !changed) {
            this.log.info("Unpacking even though unchanged cache file exists because unpack = true");
        }
        final File outputFile = request.outputFile();
        final File source;
        if (cachedFile.isPresent() && cachedFile.get().exists()) {
            source = cachedFile.get();
        } else if (outputFile.exists()) {
            source = outputFile;
        } else {
            throw new IllegalStateException("No file to unpack");
        }
        this.unpacker.unpack(source, request.outputDirectory(), request.outputFileName());
    }

    /**
     * Fails the download, or only logs a warning if {@code failOnError} is not set.
     * @param request What to download and how
     * @param message Failure description
     * @throws DownloadException If {@code failOnError} is set
     */
    private void fail(final DownloadRequest request, final String message) throws DownloadException {
        if (request.failOnError()) {
            throw new DownloadException(message);
        }
        this.log.warn(message);
    }

    /**
     * Creates the retry mechanism for the policy.
     * @param policy Retry policy
     * @return Retry
     */
    @SuppressWarnings("unchecked")
    private Retry retry(final RetryPolicy policy) {
        final RetryConfig.Builder<?> config = RetryConfig.custom()
            .maxAttempts(policy.attempts())
            .failAfterMaxAttempts(true)
            .retryOnException(
                ex -> ex instanceof DownloadFailureException
                    && policy.retriesHttpCode(((DownloadFailureException) ex).getHttpCode())
            );
        if (!policy.exceptions().isEmpty()) {
            config.retryExceptions(policy.exceptions().toArray(new Class[0]));
        }
        if (policy.initialInterval().isZero()) {
            config.intervalFunction(attempt -> 0L);
        } else {
            config.intervalFunction(
                IntervalFunction.ofExponentialRandomBackoff(
                    policy.initialInterval(), policy.multiplier(),
                    IntervalFunction.DEFAULT_RANDOMIZATION_FACTOR, policy.maxInterval()
                )
            );
        }
        final Retry retry = RetryRegistry.of(config.build()).retry("WGet");
        retry.getEventPublisher().onRetry(
            event -> {
                this.log.info(
                    String.format("Retrying (%d more)", policy.attempts() - event.getNumberOfRetryAttempts())
                );
                if (event.getLastThrowable() != null) {
                    this.log.warn(event.getLastThrowable().getMessage());
                }
            }
        );
        return retry;
    }
}
