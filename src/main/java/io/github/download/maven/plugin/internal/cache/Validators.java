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

import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.maven.shared.utils.StringUtils;

/**
 * What a server said about the version of a file it served: its {@code ETag} and
 * {@code Last-Modified} response headers.
 *
 * <p>Sent back as {@code If-None-Match} and {@code If-Modified-Since}, they let the server
 * answer "not modified" instead of sending the file again. The values are opaque: they are
 * stored and sent back exactly as received.</p>
 */
public final class Validators {

    /**
     * No validators: the server sent none, or nothing is known about the file.
     */
    public static final Validators NONE = new Validators(null, null);

    /**
     * Value of the {@code ETag} header, may be {@literal null}.
     */
    private final String tag;

    /**
     * Value of the {@code Last-Modified} header, may be {@literal null}.
     */
    private final String modified;

    /**
     * Constructor.
     * @param etag Value of the {@code ETag} header, may be {@literal null}
     * @param lastModified Value of the {@code Last-Modified} header, may be {@literal null}
     */
    public Validators(@Nullable final String etag, @Nullable final String lastModified) {
        this.tag = StringUtils.isBlank(etag) ? null : etag;
        this.modified = StringUtils.isBlank(lastModified) ? null : lastModified;
    }

    /**
     * Entity tag.
     * @return Value of the {@code ETag} header, if the server sent one
     */
    public Optional<String> etag() {
        return Optional.ofNullable(this.tag);
    }

    /**
     * Modification time.
     * @return Value of the {@code Last-Modified} header, if the server sent one
     */
    public Optional<String> lastModified() {
        return Optional.ofNullable(this.modified);
    }

    /**
     * Whether there is nothing to send back to the server.
     * @return True if neither header is known
     */
    public boolean isEmpty() {
        return this.tag == null && this.modified == null;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof Validators
            && Objects.equals(this.tag, ((Validators) other).tag)
            && Objects.equals(this.modified, ((Validators) other).modified);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.tag, this.modified);
    }

    @Override
    public String toString() {
        return String.format("ETag: %s, Last-Modified: %s", this.tag, this.modified);
    }
}
