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

import java.util.Optional;
import javax.annotation.Nullable;

/**
 * HTTP proxy to route requests through.
 */
public final class ProxySettings {

    /**
     * Proxy host.
     */
    private final String host;

    /**
     * Proxy port.
     */
    private final int port;

    /**
     * Proxy credentials, may be {@literal null}.
     */
    private final UserCredentials credentials;

    /**
     * Constructor.
     * @param host Proxy host
     * @param port Proxy port
     * @param credentials Proxy credentials, may be {@literal null}
     */
    public ProxySettings(final String host, final int port, @Nullable final UserCredentials credentials) {
        this.host = host;
        this.port = port;
        this.credentials = credentials;
    }

    /**
     * Constructor for a proxy without authentication.
     * @param host Proxy host
     * @param port Proxy port
     */
    public ProxySettings(final String host, final int port) {
        this(host, port, null);
    }

    /**
     * Proxy host.
     * @return Host
     */
    public String host() {
        return this.host;
    }

    /**
     * Proxy port.
     * @return Port
     */
    public int port() {
        return this.port;
    }

    /**
     * Proxy credentials.
     * @return Credentials if the proxy requires authentication
     */
    public Optional<UserCredentials> credentials() {
        return Optional.ofNullable(this.credentials);
    }
}
