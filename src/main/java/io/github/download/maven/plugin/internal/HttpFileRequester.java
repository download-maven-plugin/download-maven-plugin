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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.file.Files;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.cache.HttpCacheContext;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.utils.StringUtils;

/**
 * File requester that can download resources over HTTP transport using Apache HttpClient 4.x.
 * Use {@link HttpFileRequester.Builder} to create an instance.
 */
@SuppressWarnings(
    {"checkstyle:JavadocVariable", "checkstyle:EmptyLineSeparator", "checkstyle:HiddenField"}
)
public final class HttpFileRequester {

    /**
     * Buffer size used for HTTP file download operations within {@link HttpFileRequester}.
     */
    private static final int BUFFER_SIZE = 8 * 11024;

    private ProgressReport progressReport;
    private int connectTimeout;
    private int socketTimeout;
    private HttpRoutePlanner routePlanner;
    private CredentialsProvider credentialsProvider;
    private boolean redirectsEnabled;
    private URI uri;
    private boolean preemptiveAuth;
    private boolean insecure;

    /**
     * Private constructor.
     */
    private HttpFileRequester() {
    }

    /**
     * Downloads the resource with the given URI to the specified local file system location.
     * Nothing is written if the server answers {@code 304 Not Modified} to conditional headers.
     * @param outputFile The output file.
     * @param headers List of headers.
     * @return Whether the server answered "not modified", else the validators of the saved content.
     * @throws IOException Thrown if I/O operations don't succeed.
     */
    public Fetched download(final File outputFile, final List<Header> headers) throws IOException {
        try (CloseableHttpClient httpClient = this.createHttpClientBuilder().build()) {
            final HttpCacheContext clientContext = HttpCacheContext.create();
            clientContext.setCredentialsProvider(this.credentialsProvider);
            if (this.preemptiveAuth) {
                final AuthCache authCache = new BasicAuthCache();
                authCache.put(
                    new HttpHost(this.uri.getHost(), this.uri.getPort()), new BasicScheme());
                clientContext.setAuthCache(authCache);
            }
            final HttpGet httpGet = new HttpGet(this.uri);
            headers.forEach(httpGet::setHeader);
            return httpClient.execute(
                httpGet,
                response -> this.handleResponse(this.uri, outputFile, response),
                clientContext
            );
        }
    }

    /**
     * Handles response from the server: fails on errors and unfollowed redirects, saves the body
     * otherwise, unless the server answered {@code 304 Not Modified}.
     * @param uri Request uri.
     * @param outputFile Output file for the download request.
     * @param response Response from the server.
     * @return Whether the server answered "not modified", else the validators of the saved content.
     * @throws IOException Thrown if I/O operations don't succeed
     */
    private Fetched handleResponse(final URI uri, final File outputFile, final HttpResponse response)
        throws IOException {
        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode >= HttpCodes.BAD_REQUEST.getCode()) {
            throw new DownloadFailureException(
                statusCode,
                response.getStatusLine().getReasonPhrase()
            );
        }
        if (statusCode >= HttpCodes.MOVED_PERMANENTLY.getCode() && statusCode <= HttpCodes.SEE_OTHER.getCode()) {
            throw new DownloadFailureException(
                statusCode,
                String.format(
                    "%s, Not downloading the resource because followRedirects is false.",
                    response.getStatusLine().getReasonPhrase()
                )
            );
        }
        final Fetched result;
        if (statusCode == HttpCodes.NOT_MODIFIED.getCode()) {
            result = Fetched.notModified();
        } else {
            this.save(uri, outputFile, response.getEntity());
            result = Fetched.content(
                new Validators(
                    HttpFileRequester.header(response, HttpHeaders.ETAG),
                    HttpFileRequester.header(response, HttpHeaders.LAST_MODIFIED)
                )
            );
        }
        return result;
    }

    /**
     * Writes the response body to the file.
     * @param uri Requested resource, for progress reporting.
     * @param outputFile File to write.
     * @param entity Response body, may be {@literal null}.
     * @throws IOException If the body can't be read or the file can't be written.
     */
    private void save(final URI uri, final File outputFile, @Nullable final HttpEntity entity)
        throws IOException {
        if (entity != null) {
            this.progressReport.initiate(uri, entity.getContentLength());
            try (
                InputStream in = entity.getContent(); OutputStream out =
                    Files.newOutputStream(outputFile.toPath())
            ) {
                final byte[] tmp = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(tmp)) != -1) {
                    out.write(tmp, 0, bytesRead);
                    this.progressReport.update(bytesRead);
                }
                out.flush();
                this.progressReport.completed();
            } catch (final IOException ex) {
                this.progressReport.error(ex);
                throw ex;
            }
        }
    }

    /**
     * Value of a response header.
     * @param response Response.
     * @param name Header name.
     * @return Header value, {@literal null} if the header is absent.
     */
    private static String header(final HttpResponse response, final String name) {
        return Optional.ofNullable(response.getFirstHeader(name)).map(Header::getValue).orElse(null);
    }

    /**
     * Creates and configures an instance of HttpClientBuilder.
     * @return A configured HttpClientBuilder instance
     */
    private HttpClientBuilder createHttpClientBuilder() {
        final HttpClientBuilder httpClientBuilder = HttpClients.custom()
            .setDefaultCredentialsProvider(this.credentialsProvider)
            .setRoutePlanner(this.routePlanner)
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectTimeout(this.connectTimeout)
                .setSocketTimeout(this.socketTimeout)
                .setRedirectsEnabled(this.redirectsEnabled)
                .setCookieSpec(CookieSpecs.STANDARD)
                .build());
        if (this.insecure) {
            try {
                httpClientBuilder.setSSLContext(
                        new SSLContextBuilder()
                            .loadTrustMaterial(null, TrustAllStrategy.INSTANCE)
                            .build())
                    .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            } catch (final NoSuchAlgorithmException | KeyStoreException
                           | KeyManagementException cantHappen) {
                throw new RuntimeException(cantHappen);
            }
        }
        return httpClientBuilder;
    }

    /**
     * Builder class for creating an instance of HttpFileRequester.
     */
    @SuppressWarnings({"checkstyle:MissingJavadocMethod", "checkstyle:MagicNumber"})
    public static final class Builder {
        private ProgressReport progressReport;
        private int connectTimeout = 3000;
        private int socketTimeout = 3000;
        private URI uri;
        private UserCredentials credentials;
        private ProxySettings proxy;
        private Log log;
        private boolean redirectsEnabled;
        private boolean preemptiveAuth;
        private boolean insecure;

        public HttpFileRequester.Builder withUri(final URI uri) {
            this.uri = uri;
            return this;
        }

        public HttpFileRequester.Builder withProgressReport(final ProgressReport progressReport) {
            this.progressReport = progressReport;
            return this;
        }

        public HttpFileRequester.Builder withConnectTimeout(final int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public HttpFileRequester.Builder withSocketTimeout(final int socketTimeout) {
            this.socketTimeout = socketTimeout;
            return this;
        }

        /**
         * Credentials for the server hosting the resource.
         * @param credentials Credentials, {@literal null} for anonymous access
         * @return This builder
         */
        public HttpFileRequester.Builder withCredentials(@Nullable final UserCredentials credentials) {
            this.credentials = credentials;
            return this;
        }

        /**
         * Proxy to route the request through.
         * @param proxy Proxy, {@literal null} to use the JVM default proxy selector
         * @return This builder
         */
        public HttpFileRequester.Builder withProxy(@Nullable final ProxySettings proxy) {
            this.proxy = proxy;
            return this;
        }

        public HttpFileRequester.Builder withLog(final Log log) {
            this.log = log;
            return this;
        }

        public HttpFileRequester.Builder withRedirectsEnabled(final boolean followRedirects) {
            this.redirectsEnabled = followRedirects;
            return this;
        }

        public HttpFileRequester.Builder withPreemptiveAuth(final boolean preemptiveAuth) {
            this.preemptiveAuth = preemptiveAuth;
            return this;
        }

        public HttpFileRequester.Builder withInsecure(final boolean insecure) {
            this.insecure = insecure;
            return this;
        }

        public HttpFileRequester build() {
            final HttpFileRequester instance = new HttpFileRequester();
            instance.uri = Objects.requireNonNull(this.uri);
            instance.progressReport = this.progressReport;
            instance.connectTimeout = this.connectTimeout;
            instance.socketTimeout = this.socketTimeout;
            instance.redirectsEnabled = this.redirectsEnabled;
            instance.preemptiveAuth = this.preemptiveAuth;
            instance.insecure = this.insecure;
            instance.credentialsProvider = new BasicCredentialsProvider();
            if (this.credentials != null && StringUtils.isNotBlank(this.credentials.username())) {
                if (this.log != null && this.log.isDebugEnabled()) {
                    this.log.debug(
                        String.format("providing authentication for user %s and password: ***",
                            this.credentials.username()
                        )
                    );
                }
                instance.credentialsProvider.setCredentials(
                    new AuthScope(this.uri.getHost(), this.uri.getPort()),
                    new UsernamePasswordCredentials(this.credentials.username(), this.credentials.password())
                );
            }
            if (this.proxy != null && StringUtils.isNotBlank(this.proxy.host())) {
                final HttpHost host = new HttpHost(this.proxy.host(), this.proxy.port());
                instance.routePlanner = new DefaultProxyRoutePlanner(host);
                this.proxy.credentials()
                    .filter(creds -> StringUtils.isNotBlank(creds.username()))
                    .filter(creds -> StringUtils.isNotBlank(creds.password()))
                    .ifPresent(
                        creds -> instance.credentialsProvider.setCredentials(
                            new AuthScope(host.getHostName(), host.getPort()),
                            Builder.proxyCredentials(creds)
                        )
                    );
            } else {
                instance.routePlanner = new SystemDefaultRoutePlanner(ProxySelector.getDefault());
            }
            return instance;
        }

        /**
         * Converts proxy credentials to HTTP client credentials, NTLM if domain and workstation are set.
         * @param creds Proxy credentials
         * @return HTTP client credentials
         */
        private static Credentials proxyCredentials(final UserCredentials creds) {
            final Credentials result;
            final boolean ntlm = creds.ntlmHost().filter(StringUtils::isNotBlank).isPresent()
                && creds.ntlmDomain().filter(StringUtils::isNotBlank).isPresent();
            if (ntlm) {
                result = new NTCredentials(
                    creds.username(), creds.password(), creds.ntlmHost().get(), creds.ntlmDomain().get()
                );
            } else {
                result = new UsernamePasswordCredentials(creds.username(), creds.password());
            }
            return result;
        }
    }
}
