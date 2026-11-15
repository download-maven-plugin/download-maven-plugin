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
import java.net.URI;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Everything the download needs from the build tool, as plain values.
 * Decouples download logic from the Maven API so it can be tested with simple fakes.
 */
public interface Environment {

    /**
     * Whether the build runs in offline mode.
     * @return True if network access is not allowed
     */
    boolean isOffline();

    /**
     * Whether download progress should be reported.
     * @return True if progress should be logged
     */
    boolean showsProgress();

    /**
     * Local repository directory.
     * @return Base directory of the local repository
     */
    File localRepository();

    /**
     * Proxy to use for the given resource.
     * @param uri Resource to download
     * @param serverId Server id from settings, may be {@literal null}
     * @return Proxy if one is configured for the resource
     */
    Optional<ProxySettings> proxyFor(URI uri, @Nullable String serverId);

    /**
     * Credentials to use for the given resource.
     * @param uri Resource to download
     * @param serverId Server id from settings, may be {@literal null}
     * @return Credentials if the environment provides any
     * @throws EnvironmentException If the server id is not configured
     */
    Optional<UserCredentials> credentialsFor(URI uri, @Nullable String serverId) throws EnvironmentException;
}
