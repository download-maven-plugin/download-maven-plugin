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

import java.io.File;
import java.io.IOException;

/**
 * Extracts a downloaded archive.
 */
public interface Unpacker {

    /**
     * Extracts the archive into the output directory.
     * @param source Archive to read, either the downloaded file or its cached copy
     * @param outputDirectory Directory to extract into
     * @param outputFileName Name of the downloaded file, determines the archive type
     * @throws IOException If extraction fails
     * @throws DownloadException If the archive type is not supported
     */
    void unpack(File source, File outputDirectory, String outputFileName) throws IOException, DownloadException;
}
