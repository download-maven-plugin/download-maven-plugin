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

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;

/**
 * Thrown when a file in the download cache can't be replaced, typically because another build
 * reads it: Windows doesn't allow replacing an open file.
 *
 * <p>The cache stays usable: the replaced file keeps its previous content. Its validators
 * may already belong to the new content, which only costs a download on the next update check.
 * The freshly downloaded file is complete and can be used instead of the cached one.</p>
 */
public final class CachedFileInUseException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     * @param target Cached file that couldn't be replaced
     * @param cause Failure of the replacement
     */
    CachedFileInUseException(final Path target, final FileSystemException cause) {
        super(
            String.format(
                "Could not replace cached file %s, it is probably in use by another build: %s",
                target, cause.getMessage()
            ),
            cause
        );
    }
}
