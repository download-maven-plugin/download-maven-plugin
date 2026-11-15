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
        boolean fileWasCached = false;
        boolean available = true;
        if (!this.keepExisting(request)) {
            cachedFile = request.cache().map(cache -> cache.getArtifact(request.uri(), request.checksums()));
            fileWasCached = cachedFile.map(File::exists).orElse(false);
            if (fileWasCached) {
                this.useCached(request, cachedFile.get());
            } else {
                available = this.fetch(request);
            }
        }
        if (available) {
            this.complete(request, cachedFile, fileWasCached);
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
     * @return True if the file was downloaded, false if a failure was ignored
     * @throws DownloadException If the download fails and {@code failOnError} is set,
     *  or regardless of it on checksum mismatch or misconfiguration
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private boolean fetch(final DownloadRequest request) throws DownloadException {
        boolean fetched = false;
        if (request.offline()) {
            this.fail(request, "No file in cache and maven is in offline mode");
            this.log.warn("Ignoring download failure.");
        } else {
            final File outputFile = request.outputFile();
            final Checksums checksums = request.checksums();
            try {
                this.retry(request.retryPolicy()).executeCheckedSupplier(
                    () -> {
                        this.fetcher.fetch(request.uri(), outputFile);
                        checksums.validate(outputFile);
                        return null;
                    }
                );
                fetched = true;
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
     * Post-processes an available file: caches it, applies permissions, unpacks it
     * and notifies the listener.
     * @param request What to download and how
     * @param cachedFile Cached copy of the file, if it was found in the cache
     * @param fileWasCached Whether the file came from the cache
     * @throws DownloadException If permissions can't be applied or the archive type is unknown
     * @throws IOException If caching or unpacking fails
     */
    private void complete(
        final DownloadRequest request, final Optional<File> cachedFile, final boolean fileWasCached
    ) throws DownloadException, IOException {
        final File outputFile = request.outputFile();
        if (request.cache().isPresent()) {
            request.cache().get().install(request.uri(), outputFile, request.checksums());
        }
        new FilePermissions(request.permissions(), this.log).applyTo(outputFile);
        if (request.unpack() || request.unpackWhenChanged()) {
            if (!request.unpack() && fileWasCached) {
                this.log.info("Skipping unpacking as the file has not changed");
            } else {
                this.unpack(request, cachedFile, fileWasCached);
                this.listener.changed(request.outputDirectory());
            }
        } else {
            this.listener.changed(outputFile);
        }
    }

    /**
     * Unpacks the cached copy if present, the downloaded file otherwise, then deletes the downloaded file.
     * @param request What to download and how
     * @param cachedFile Cached copy of the file
     * @param fileWasCached Whether the file came from the cache
     * @throws DownloadException If the archive type is unknown
     * @throws IOException If unpacking fails
     */
    private void unpack(final DownloadRequest request, final Optional<File> cachedFile, final boolean fileWasCached)
        throws DownloadException, IOException {
        if (request.unpackWhenChanged() && fileWasCached) {
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
        if (outputFile.exists()) {
            outputFile.delete();
        }
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
