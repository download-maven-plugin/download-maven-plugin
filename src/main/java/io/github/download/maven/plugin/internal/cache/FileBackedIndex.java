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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.concurrent.NotThreadSafe;
import org.apache.maven.plugin.logging.Log;

/**
 * Binary file backed index.
 * The implementation is <b>NOT</b> thread safe and should be synchronized
 * by the lock.
 * @author Paul Polishchuk
 * @since 1.3.1
 */
@NotThreadSafe
final class FileBackedIndex implements FileIndex {

    /**
     * File name where cache index is stored.
     */
    private static final String CACHE_FILENAME = "index.ser";

    /**
     * Cache index loaded from the index file.
     */
    private final Map<URI, String> index = new HashMap<>();

    /**
     * Cache location dir.
     */
    private final File storage;

    /**
     * A reentrant lock to ensure thread-safe access to the file-backed index data structure.
     *
     * This lock is used to synchronize access to the in-memory index and guarantee consistency
     * during read/write operations such as loading the index from the file storage and saving
     * the current state of the index back to the file.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Logger.
     */
    private final Log log;

    /**
     * Creates index backed by file "index.ser" in baseDir.
     * Throws runtime exceptions if baseDir does not exist, not a directory
     * or file can't be created in it.
     * @param baseDir Directory where the index file should be stored.
     * @param log Logger.
     */
    FileBackedIndex(final File baseDir, final Log log) {
        this.log = log;
        this.storage = new File(baseDir, CACHE_FILENAME);
    }

    /**
     * Adds an entry and persists the index.
     * The index file is read, merged and rewritten under a single exclusive lock,
     * so concurrent processes sharing the cache cannot overwrite each other's
     * entries. The index is serialized in memory before the file is truncated.
     * File locks are mandatory on Windows, so the file is only written while
     * the lock is held.
     * @param uri Entry URI.
     * @param path Entry path.
     */
    @Override
    public void put(final URI uri, final String path) {
        this.index.put(uri, path);
        try (
            RandomAccessFile file = new RandomAccessFile(this.storage, "rw");
            FileChannel channel = file.getChannel();
            FileLock ignored = channel.lock()
        ) {
            try {
                this.merge(FileBackedIndex.readFully(channel));
            } catch (final IncompatibleIndexException | IOException exc) {
                this.log.warn("Could not load index cache index file, it will be rewritten.");
            }
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream res = new ObjectOutputStream(bytes)) {
                res.writeObject(this.index);
            }
            channel.truncate(0L);
            final ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public String get(final URI uri) {
        try {
            this.load(this.storage);
        } catch (final IncompatibleIndexException | IOException exc) {
            this.log.warn(
                String.format("Error while reading from cache %s", this.storage.getAbsolutePath())
            );
        }
        return this.index.get(uri);
    }

    @Override
    public ReentrantLock getLock() {
        return this.lock;
    }

    /**
     * Loads index from the file storage replacing absent in-memory entries.
     * @param store File where index is persisted.
     * @throws IncompatibleIndexException If the store cannot be read due to a deserialization issue
     * @throws IOException If any operation with index file is failed.
     */
    private void load(final File store) throws IncompatibleIndexException, IOException {
        if (!store.exists()) {
            return;
        }
        try (
            RandomAccessFile file = new RandomAccessFile(store, "r");
            FileChannel channel = file.getChannel();
            FileLock ignored = channel.lock(0L, Long.MAX_VALUE, true)
        ) {
            this.merge(FileBackedIndex.readFully(channel));
        }
    }

    /**
     * Merges serialized index into the in-memory one, keeping in-memory entries.
     * @param content Serialized index, may be empty.
     * @throws IncompatibleIndexException If the content cannot be deserialized
     * @throws IOException If the content is corrupted.
     */
    @SuppressWarnings("unchecked")
    private void merge(final byte[] content) throws IncompatibleIndexException, IOException {
        if (content.length != 0) {
            try (
                ObjectInputStream deserialize = new ObjectInputStream(
                    new ByteArrayInputStream(content)
                )
            ) {
                final Map<URI, String> newEntries = (Map<URI, String>) deserialize.readObject();
                newEntries.forEach(this.index::putIfAbsent);
            } catch (final ClassNotFoundException | InvalidClassException exc) {
                throw new IncompatibleIndexException(exc);
            }
        }
    }

    /**
     * Reads the whole content of the channel from its beginning.
     * @param channel Channel to read.
     * @return Channel content.
     * @throws IOException If reading fails.
     */
    private static byte[] readFully(final FileChannel channel) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(channel.size()));
        channel.position(0L);
        int read = 0;
        while (buffer.hasRemaining() && read >= 0) {
            read = channel.read(buffer);
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }
}
