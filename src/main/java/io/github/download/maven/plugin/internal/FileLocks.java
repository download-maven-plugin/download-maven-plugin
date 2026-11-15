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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-process locks guarding output files against concurrent downloads.
 */
public final class FileLocks {

    /**
     * Locks by absolute file path.
     */
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();

    /**
     * Lock for the given file; the same file always gets the same lock.
     * @param file File to guard
     * @return Lock
     */
    public Lock forFile(final File file) {
        return this.locks.computeIfAbsent(file.getAbsolutePath(), ignored -> new ReentrantLock());
    }
}
