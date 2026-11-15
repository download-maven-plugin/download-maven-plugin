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
import io.github.download.maven.plugin.internal.checksum.Checksums;
import java.io.File;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * What to download and how. Immutable; create with {@link Builder}.
 */
@SuppressWarnings({"checkstyle:JavadocVariable", "checkstyle:EmptyLineSeparator"})
public final class DownloadRequest {

    private final URI uri;
    private final File outputDirectory;
    private final String outputFileName;
    private final Checksums checksums;
    private final DownloadCache cache;
    private final boolean overwrite;
    private final boolean verifyExisting;
    private final boolean unpack;
    private final boolean unpackWhenChanged;
    private final boolean failOnError;
    private final boolean offline;
    private final long lockWaitMillis;
    private final String permissions;
    private final RetryPolicy retryPolicy;

    /**
     * Constructor.
     * @param builder Builder holding the values
     */
    private DownloadRequest(final Builder builder) {
        this.uri = Objects.requireNonNull(builder.uri, "uri");
        this.outputDirectory = Objects.requireNonNull(builder.outputDirectory, "outputDirectory");
        this.outputFileName = Objects.requireNonNull(builder.outputFileName, "outputFileName");
        this.checksums = Objects.requireNonNull(builder.checksums, "checksums");
        this.cache = builder.cache;
        this.overwrite = builder.overwrite;
        this.verifyExisting = builder.verifyExisting;
        this.unpack = builder.unpack;
        this.unpackWhenChanged = builder.unpackWhenChanged;
        this.failOnError = builder.failOnError;
        this.offline = builder.offline;
        this.lockWaitMillis = builder.lockWaitMillis;
        this.permissions = builder.permissions;
        this.retryPolicy = builder.retryPolicy;
    }

    /**
     * Resource to download.
     * @return URI
     */
    public URI uri() {
        return this.uri;
    }

    /**
     * Directory to store the file in, and to unpack into.
     * @return Output directory
     */
    public File outputDirectory() {
        return this.outputDirectory;
    }

    /**
     * Name of the downloaded file.
     * @return File name
     */
    public String outputFileName() {
        return this.outputFileName;
    }

    /**
     * Downloaded file.
     * @return Output file
     */
    public File outputFile() {
        return new File(this.outputDirectory, this.outputFileName);
    }

    /**
     * Expected checksums of the file.
     * @return Checksums, possibly none
     */
    public Checksums checksums() {
        return this.checksums;
    }

    /**
     * Cache to read from and store into.
     * @return Cache, empty if caching is disabled
     */
    public Optional<DownloadCache> cache() {
        return Optional.ofNullable(this.cache);
    }

    /**
     * Whether an existing output file is replaced.
     * @return True to download even if the file exists
     */
    public boolean overwrite() {
        return this.overwrite;
    }

    /**
     * Whether an existing output file is checked against the checksums.
     * @return True to replace an existing file that doesn't match
     */
    public boolean verifyExisting() {
        return this.verifyExisting;
    }

    /**
     * Whether the file is unpacked.
     * @return True to always unpack
     */
    public boolean unpack() {
        return this.unpack;
    }

    /**
     * Whether the file is unpacked unless it came unchanged from the cache.
     * @return True to unpack changed files only
     */
    public boolean unpackWhenChanged() {
        return this.unpackWhenChanged;
    }

    /**
     * Whether a failed download fails the build.
     * @return False to only log a warning
     */
    public boolean failOnError() {
        return this.failOnError;
    }

    /**
     * Whether network access is forbidden.
     * @return True in offline mode
     */
    public boolean offline() {
        return this.offline;
    }

    /**
     * How long to wait for another download of the same file.
     * @return Milliseconds
     */
    public long lockWaitMillis() {
        return this.lockWaitMillis;
    }

    /**
     * Permissions to apply to the downloaded file.
     * @return Permissions, e.g. {@code +x}, or {@literal null} for none
     */
    @Nullable
    public String permissions() {
        return this.permissions;
    }

    /**
     * When to retry a failed download.
     * @return Retry policy
     */
    public RetryPolicy retryPolicy() {
        return this.retryPolicy;
    }

    /**
     * Builder of {@link DownloadRequest}. Defaults: no cache, fail on error,
     * online, no retries, 30s lock wait.
     */
    @SuppressWarnings(
        {
            "checkstyle:MissingJavadocMethod", "checkstyle:HiddenField",
            "checkstyle:MagicNumber", "checkstyle:EmptyLineSeparator"
        }
    )
    public static final class Builder {
        private URI uri;
        private File outputDirectory;
        private String outputFileName;
        private Checksums checksums;
        private DownloadCache cache;
        private boolean overwrite;
        private boolean verifyExisting;
        private boolean unpack;
        private boolean unpackWhenChanged;
        private boolean failOnError = true;
        private boolean offline;
        private long lockWaitMillis = 30_000L;
        private String permissions;
        private RetryPolicy retryPolicy = RetryPolicy.none();

        public Builder uri(final URI uri) {
            this.uri = uri;
            return this;
        }

        public Builder outputDirectory(final File outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        public Builder outputFileName(final String outputFileName) {
            this.outputFileName = outputFileName;
            return this;
        }

        public Builder checksums(final Checksums checksums) {
            this.checksums = checksums;
            return this;
        }

        public Builder cache(@Nullable final DownloadCache cache) {
            this.cache = cache;
            return this;
        }

        public Builder overwrite(final boolean overwrite) {
            this.overwrite = overwrite;
            return this;
        }

        public Builder verifyExisting(final boolean verifyExisting) {
            this.verifyExisting = verifyExisting;
            return this;
        }

        public Builder unpack(final boolean unpack) {
            this.unpack = unpack;
            return this;
        }

        public Builder unpackWhenChanged(final boolean unpackWhenChanged) {
            this.unpackWhenChanged = unpackWhenChanged;
            return this;
        }

        public Builder failOnError(final boolean failOnError) {
            this.failOnError = failOnError;
            return this;
        }

        public Builder offline(final boolean offline) {
            this.offline = offline;
            return this;
        }

        public Builder lockWaitMillis(final long lockWaitMillis) {
            this.lockWaitMillis = lockWaitMillis;
            return this;
        }

        public Builder permissions(@Nullable final String permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder retryPolicy(final RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy);
            return this;
        }

        public DownloadRequest build() {
            return new DownloadRequest(this);
        }
    }
}
