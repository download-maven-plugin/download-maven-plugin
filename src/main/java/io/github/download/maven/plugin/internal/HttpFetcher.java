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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.message.BasicHeader;
import org.apache.maven.plugin.logging.Log;

/**
 * {@link Fetcher} downloading over HTTP(S) with {@link HttpFileRequester}.
 *
 * <p>Credentials, proxy and progress reporting come from the {@link Environment}. They are
 * resolved on the first fetch, so a misconfigured server is only reported when a download
 * is actually needed, and only once across retries.</p>
 *
 * <p>Known {@link Validators} are sent as {@code If-None-Match} and {@code If-Modified-Since}.
 * Both are sent because real servers honour only one of them, and not the same one. A header
 * of that name configured on the plugin wins.</p>
 */
public final class HttpFetcher implements Fetcher {

    /**
     * Source of credentials, proxy and progress settings.
     */
    private final Environment environment;

    /**
     * Requester builder with connection settings already applied.
     */
    private final HttpFileRequester.Builder requester;

    /**
     * Credentials configured on the plugin, take precedence over the environment.
     */
    private final UserCredentials explicit;

    /**
     * Server id from settings, may be {@literal null}.
     */
    private final String serverId;

    /**
     * Additional request headers.
     */
    private final List<Header> headers;

    /**
     * Logger.
     */
    private final Log log;

    /**
     * Whether credentials and proxy have been resolved.
     */
    private boolean resolved;

    /**
     * Resolved credentials, may be {@literal null}.
     */
    private UserCredentials credentials;

    /**
     * Resolved proxy, may be {@literal null}.
     */
    private ProxySettings proxy;

    /**
     * Whether progress is reported.
     */
    private boolean progress;

    /**
     * Constructor.
     * @param environment Source of credentials, proxy and progress settings
     * @param requester Requester builder with connection settings (timeouts, redirects, TLS) applied
     * @param explicit Credentials configured on the plugin, {@literal null} to use the environment's
     * @param serverId Server id from settings, may be {@literal null}
     * @param headers Additional request headers
     * @param log Logger
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public HttpFetcher(
        final Environment environment, final HttpFileRequester.Builder requester,
        @Nullable final UserCredentials explicit, @Nullable final String serverId,
        final List<Header> headers, final Log log
    ) {
        this.environment = environment;
        this.requester = requester;
        this.explicit = explicit;
        this.serverId = serverId;
        this.headers = Collections.unmodifiableList(new ArrayList<>(headers));
        this.log = log;
    }

    @Override
    public Fetched fetch(final URI uri, final File target, final Validators known)
        throws IOException, DownloadException {
        if (!this.resolved) {
            if (this.explicit == null) {
                this.credentials = this.environment.credentialsFor(uri, this.serverId).orElse(null);
            } else {
                this.credentials = this.explicit;
            }
            this.proxy = this.environment.proxyFor(uri, this.serverId).orElse(null);
            this.progress = this.environment.showsProgress();
            this.resolved = true;
        }
        final ProgressReport report;
        if (this.progress) {
            report = new LoggingProgressReport(this.log);
        } else {
            report = new SilentProgressReport(this.log);
        }
        return this.requester
            .withUri(uri)
            .withCredentials(this.credentials)
            .withProxy(this.proxy)
            .withProgressReport(report)
            .withLog(this.log)
            .build()
            .download(target, this.conditional(known));
    }

    /**
     * Request headers with the conditional ones added.
     * @param known Validators of the version the caller already has
     * @return Headers to send
     */
    private List<Header> conditional(final Validators known) {
        final List<Header> result = new ArrayList<>(this.headers);
        known.etag()
            .filter(ignored -> !this.configures(HttpHeaders.IF_NONE_MATCH))
            .ifPresent(etag -> result.add(new BasicHeader(HttpHeaders.IF_NONE_MATCH, etag)));
        known.lastModified()
            .filter(ignored -> !this.configures(HttpHeaders.IF_MODIFIED_SINCE))
            .ifPresent(date -> result.add(new BasicHeader(HttpHeaders.IF_MODIFIED_SINCE, date)));
        return result;
    }

    /**
     * Whether a header is configured on the plugin.
     * @param name Header name
     * @return True if the configured headers contain it
     */
    private boolean configures(final String name) {
        return this.headers.stream().anyMatch(header -> header.getName().equalsIgnoreCase(name));
    }
}
