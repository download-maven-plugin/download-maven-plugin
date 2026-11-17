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
import java.io.File;
import java.io.IOException;
import java.net.URI;

/**
 * Transfers a remote resource into a local file.
 */
public interface Fetcher {

    /**
     * Downloads the resource into the target file, replacing its content.
     *
     * <p>If {@code known} is not empty the download is conditional: when the server reports
     * that this version is still current, nothing is written and the outcome is
     * {@link Fetched#isNotModified() not modified}.</p>
     * @param uri Resource to download
     * @param target File to write the content to
     * @param known Validators of the version the caller already has, {@link Validators#NONE} if none
     * @return What happened, with the validators of the content that was written
     * @throws IOException If the transfer fails
     * @throws DownloadException If the transfer can't be set up, e.g. credentials are missing
     */
    Fetched fetch(URI uri, File target, Validators known) throws IOException, DownloadException;
}
