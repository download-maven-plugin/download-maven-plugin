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
package io.github.download.maven.plugin.internal.cache;

import io.github.download.maven.plugin.internal.checksum.Checksums;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.utils.io.FileUtils;

/**
 * A class representing a download cache.
 * @author Mickael Istria (Red Hat Inc)
 */
public final class DownloadCache {

    /**
     * Suffix of the file holding the validators of a cached file.
     */
    private static final String META_SUFFIX = ".meta";

    /**
     * Key of the {@code ETag} value in a meta file.
     */
    private static final String META_ETAG = "etag";

    /**
     * Key of the {@code Last-Modified} value in a meta file.
     */
    private static final String META_LAST_MODIFIED = "last-modified";

    /**
     * Key of the size of the cached file the validators belong to.
     */
    private static final String META_SIZE = "size";

    /**
     * Key of the modification time, in nanoseconds, of the cached file the validators belong to.
     */
    private static final String META_MODIFIED = "modified";

    /**
     * Directory where the download cache is stored.
     */
    private final File basedir;

    /**
     * Index of cached files in the download cache, mapping URIs to local file paths.
     * This index enables fast retrieval of cached files and ensures thread-safe
     * access through locking mechanisms.
     */
    private final FileIndex index;

    /**
     * Moves a written temporary file over its target.
     */
    private final Mover mover;

    /**
     * Constructor.
     * @param cacheDirectory Directory where the download cache is stored.
     * @param log Logger.
     */
    public DownloadCache(final File cacheDirectory, final Log log) {
        this(cacheDirectory, log, DownloadCache::move);
    }

    /**
     * Constructor.
     * @param cacheDirectory Directory where the download cache is stored.
     * @param log Logger.
     * @param mover Moves a written temporary file over its target, replaced in tests to
     *  simulate a target that can't be replaced.
     */
    DownloadCache(final File cacheDirectory, final Log log, final Mover mover) {
        this.index = new FileBackedIndex(cacheDirectory, log);
        this.basedir = cacheDirectory;
        this.mover = mover;
    }

    /**
     * Get a File in the download cache. If no cache for this URL, or
     * if expected checksums don't match cached ones, returns null.
     * @param uri URL of the file
     * @param checksums Supplied checksums.
     * @return A File when cache is found, null if no available cache
     */
    public File getArtifact(final URI uri, final Checksums checksums) {
        final Optional<String> resource;
        this.index.getLock().lock();
        try {
            resource = this.getEntry(uri, checksums);
        } finally {
            this.index.getLock().unlock();
        }
        return resource.map(res -> new File(this.basedir, res)).orElse(null);
    }

    /**
     * Installs a file into the download cache.
     * If the cache directory does not exist, it is created.
     * The method checks if the file corresponding to the URI and checksums already exists in the
     * cache. If not, the file is copied to the cache directory and the index is updated.
     * Unlike {@link #store}, an existing entry is never replaced and no validators are recorded.
     *
     * @param uri The URI of the file to be installed.
     * @param outputFile The file to be installed into the cache.
     * @param checksums The checksums used to verify the integrity of the file.
     * @throws CachedFileInUseException If a cached file with other content can't be replaced
     * @throws IOException If the cache directory cannot be created or the file can't be copied.
     */
    public void install(final URI uri, final File outputFile, final Checksums checksums)
        throws IOException {
        this.index.getLock().lock();
        try {
            if (!this.getEntry(uri, checksums).isPresent()) {
                this.store(uri, outputFile, checksums, Validators.NONE);
            }
        } finally {
            this.index.getLock().unlock();
        }
    }

    /**
     * Validators the server sent with the cached file.
     *
     * <p>They are kept in a {@code .meta} file next to the cached file rather than in the
     * index, so that older plugin versions sharing the cache can still read the index.</p>
     * @param uri The URI of the cached file.
     * @return Validators, {@link Validators#NONE} if there are none, e.g. the file was cached by
     *  a plugin version that didn't record them, or they belong to different content.
     */
    public Validators validators(final URI uri) {
        Validators result = Validators.NONE;
        this.index.getLock().lock();
        try {
            final String resource = this.index.get(uri);
            if (resource != null) {
                result = this.readMeta(resource);
            }
        } finally {
            this.index.getLock().unlock();
        }
        return result;
    }

    /**
     * Stores a freshly downloaded file in the cache, replacing the cached file if its content
     * differs, and records the validators the server sent with it.
     *
     * @param uri The URI the file was downloaded from.
     * @param downloaded The downloaded file.
     * @param checksums The checksums a cached file must match to count as an existing entry.
     * @param validators Validators the server sent with the file.
     * @return True if the cache had no such content for the URI before, i.e. the file is new or
     *  has changed; false if the cached file already had the same content.
     * @throws CachedFileInUseException If the cached file or its validators can't be replaced,
     *  e.g. because another build reads them on Windows
     * @throws IOException If the cache directory cannot be created, or the file or its
     *  validators can't be written.
     */
    public boolean store(
        final URI uri, final File downloaded, final Checksums checksums, final Validators validators
    ) throws IOException {
        this.createBasedir();
        final boolean changed;
        this.index.getLock().lock();
        try {
            final Optional<String> entry = this.getEntry(uri, checksums);
            final String fileName = entry.orElseGet(() -> DownloadCache.fileName(uri, downloaded));
            final File cached = new File(this.basedir, fileName);
            // taken before comparing: if another build replaces the file meanwhile, it won't match
            Stamp stamp = Stamp.read(cached.toPath());
            changed = !entry.isPresent() || cached.length() != downloaded.length()
                || !FileUtils.contentEquals(cached, downloaded);
            if (changed) {
                stamp = this.replace(
                    cached.toPath(),
                    temporary -> Files.copy(downloaded.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING)
                );
            }
            if (!entry.isPresent()) {
                this.index.put(uri, fileName);
            }
            // the content goes first: old validators with new content only cost a download,
            // new validators with old content would keep the old content forever. The stamp
            // ties the validators to the file written here: a build in another JVM, which
            // doesn't share the lock, may have replaced it since.
            this.writeMeta(fileName, validators, stamp);
        } finally {
            this.index.getLock().unlock();
        }
        return changed;
    }

    /**
     * Retrieves an entry from the cache index based on the provided URI and verifies it
     * using the given checksums.
     * @param uri The URI of the resource to retrieve.
     * @param checksums The checksums used to validate the integrity of the resource.
     * @return An Optional containing the resource path if the resource is valid and found,
     *  otherwise an empty Optional.
     */
    private Optional<String> getEntry(final URI uri, final Checksums checksums) {
        final String resource = this.index.get(uri);
        return Optional.ofNullable(resource)
            .filter(
                res -> {
                    final File resFile = new File(this.basedir, resource);
                    return resFile.isFile() && checksums.isValid(resFile);
                }
            );
    }

    /**
     * Creates the cache directory if it doesn't exist.
     * @throws IOException If the cache directory cannot be created.
     */
    private void createBasedir() throws IOException {
        try {
            Files.createDirectories(this.basedir.toPath());
        } catch (final IOException ex) {
            throw new IOException(
                String.format(
                    "Could not create cache directory: %s", this.basedir.getAbsolutePath()
                ),
                ex
            );
        }
    }

    /**
     * Reads the validators recorded for a cached file.
     * @param resource Name of the cached file.
     * @return Validators, {@link Validators#NONE} if none are recorded, they can't be read or
     *  they describe another file: one of another size or modification time, e.g. written by
     *  another build or a plugin version that doesn't record validators.
     */
    private Validators readMeta(final String resource) {
        Validators result = Validators.NONE;
        final File meta = new File(this.basedir, resource + META_SUFFIX);
        if (meta.isFile()) {
            final Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(meta.toPath())) {
                properties.load(input);
                if (Stamp.read(new File(this.basedir, resource).toPath()).matches(properties)) {
                    result = new Validators(
                        properties.getProperty(META_ETAG), properties.getProperty(META_LAST_MODIFIED)
                    );
                }
            } catch (final IOException | IllegalArgumentException ex) {
                result = Validators.NONE;
            }
        }
        return result;
    }

    /**
     * Records the validators of a cached file, or removes the record if there are none.
     * @param resource Name of the cached file.
     * @param validators Validators the server sent with the file.
     * @param stamp Stamp of the file the validators belong to.
     * @throws IOException If the record can't be written.
     */
    private void writeMeta(final String resource, final Validators validators, final Stamp stamp)
        throws IOException {
        final Path meta = new File(this.basedir, resource + META_SUFFIX).toPath();
        if (validators.isEmpty()) {
            Files.deleteIfExists(meta);
        } else {
            final Properties properties = new Properties();
            validators.etag().ifPresent(value -> properties.setProperty(META_ETAG, value));
            validators.lastModified().ifPresent(value -> properties.setProperty(META_LAST_MODIFIED, value));
            stamp.writeTo(properties);
            this.replace(
                meta,
                temporary -> {
                    try (OutputStream output = Files.newOutputStream(temporary)) {
                        properties.store(output, "download-maven-plugin: validators of the cached file");
                    }
                }
            );
        }
    }

    /**
     * Replaces a file in the cache directory in one step, so that a concurrent build never
     * sees a partially written file.
     * @param target File to replace.
     * @param writer Writes the new content to the temporary file it is given.
     * @return Stamp of the written file, which the move keeps
     * @throws CachedFileInUseException If the existing file can't be replaced, e.g. on Windows
     *  while another build reads it
     * @throws IOException If the file can't be written.
     */
    private Stamp replace(final Path target, final Writer writer) throws IOException {
        final Path temporary = Files.createTempFile(this.basedir.toPath(), "tmp-", ".part");
        try {
            writer.writeTo(temporary);
            final Stamp stamp = Stamp.read(temporary);
            try {
                this.mover.move(temporary, target);
            } catch (final FileSystemException ex) {
                if (Files.exists(target)) {
                    throw new CachedFileInUseException(target, ex);
                }
                throw ex;
            }
            return stamp;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Moves a file over another one, atomically if the file system supports it.
     * @param source File to move.
     * @param target File to replace.
     * @throws IOException If the file can't be moved.
     */
    private static void move(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Name of the cache file for a download.
     * @param uri The URI of the file.
     * @param outputFile The downloaded file.
     * @return File name, unique for the URI.
     */
    private static String fileName(final URI uri, final File outputFile) {
        return String.format("%s_%s", outputFile.getName(), DigestUtils.md5Hex(uri.toString()));
    }

    /**
     * Size and modification time of a cached file: identifies the content its validators belong to,
     * without reading it. A move within the cache directory keeps both.
     */
    private static final class Stamp {

        /**
         * Size in bytes.
         */
        private final long size;

        /**
         * Modification time in nanoseconds, as precise as the file system keeps it.
         */
        private final long modified;

        /**
         * Constructor.
         * @param size Size in bytes.
         * @param modified Modification time in nanoseconds.
         */
        private Stamp(final long size, final long modified) {
            this.size = size;
            this.modified = modified;
        }

        /**
         * Stamp of a file.
         * @param file The file.
         * @return Stamp, of an empty file if it doesn't exist.
         * @throws IOException If the attributes can't be read.
         */
        static Stamp read(final Path file) throws IOException {
            final Stamp result;
            if (Files.exists(file)) {
                result = new Stamp(
                    Files.size(file), Files.getLastModifiedTime(file).to(TimeUnit.NANOSECONDS)
                );
            } else {
                result = new Stamp(0L, 0L);
            }
            return result;
        }

        /**
         * Records the stamp.
         * @param properties Where to record it.
         */
        void writeTo(final Properties properties) {
            properties.setProperty(META_SIZE, String.valueOf(this.size));
            properties.setProperty(META_MODIFIED, String.valueOf(this.modified));
        }

        /**
         * Whether the recorded stamp is this one.
         * @param properties Where it is recorded.
         * @return True if both size and modification time match.
         */
        boolean matches(final Properties properties) {
            return String.valueOf(this.size).equals(properties.getProperty(META_SIZE))
                && String.valueOf(this.modified).equals(properties.getProperty(META_MODIFIED));
        }
    }

    /**
     * Moves a file over another one.
     */
    @FunctionalInterface
    interface Mover {

        /**
         * Moves the file, replacing the target if it exists.
         * @param source File to move.
         * @param target File to replace.
         * @throws IOException If the file can't be moved.
         */
        void move(Path source, Path target) throws IOException;
    }

    /**
     * Writes a file.
     */
    @FunctionalInterface
    private interface Writer {

        /**
         * Writes the content.
         * @param file File to write, exists and is empty.
         * @throws IOException If the file can't be written.
         */
        void writeTo(Path file) throws IOException;
    }
}
