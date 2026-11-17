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

import io.github.download.maven.plugin.internal.cache.Validators;

/**
 * Outcome of a {@link Fetcher#fetch fetch}.
 */
public final class Fetched {

    /**
     * Whether the server reported that the known version is still current.
     */
    private final boolean unmodified;

    /**
     * Validators of the content that was written.
     */
    private final Validators current;

    /**
     * Constructor.
     * @param unmodified Whether the server reported that the known version is still current
     * @param current Validators of the content that was written
     */
    private Fetched(final boolean unmodified, final Validators current) {
        this.unmodified = unmodified;
        this.current = current;
    }

    /**
     * The server reported that the version the caller knows is still current; nothing was written.
     * @return Outcome
     */
    public static Fetched notModified() {
        return new Fetched(true, Validators.NONE);
    }

    /**
     * The content was written to the target file.
     * @param validators Validators the server sent with the content
     * @return Outcome
     */
    public static Fetched content(final Validators validators) {
        return new Fetched(false, validators);
    }

    /**
     * Whether the known version is still current.
     * @return True if the server answered "not modified" and the target file was not written
     */
    public boolean isNotModified() {
        return this.unmodified;
    }

    /**
     * Validators of the written content.
     * @return Validators, {@link Validators#NONE} if the server sent none or nothing was written
     */
    public Validators validators() {
        return this.current;
    }
}
