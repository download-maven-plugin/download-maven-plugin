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

import io.github.download.maven.plugin.internal.cache.DownloadCache;
import io.github.download.maven.plugin.internal.checksum.Checksums;
import io.github.download.maven.plugin.internal.maven.MavenEnvironment;
import io.github.download.maven.plugin.internal.maven.PlexusUnpacker;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.apache.http.Header;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContexts;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.utils.StringUtils;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.components.io.filemappers.FileMapper;
import org.sonatype.plexus.build.incremental.BuildContext;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;

/**
 * Will download a file from a website using the standard HTTP protocol.
 * @author Marc-Andre Houle
 * @author Mickael Istria (Red Hat Inc)
 */
@Mojo(
    name = "wget", defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
    requiresProject = false, threadSafe = true
)
@SuppressWarnings("checkstyle:ClassFanOutComplexity")
public final class WGetMojo extends AbstractMojo {

    /**
     * Http connection pool.
     */
    private static final PoolingHttpClientConnectionManager CONN_POOL;

    /**
     * A map of file caches by their location paths.
     * Ensures one cache instance per path and enables safe execution in parallel
     * builds against the same cache.
     */
    private static final Map<String, DownloadCache> DOWNLOAD_CACHES = new ConcurrentHashMap<>();

    /**
     * Locks of files being downloaded, shared by all executions in this JVM.
     * Ensures exclusive access to a target file.
     */
    private static final FileLocks FILE_LOCKS = new FileLocks();

    /**
     * Represent the URL to fetch information from.
     */
    @Parameter(alias = "url", property = "download.url", required = true)
    private URI uri;

    /**
     * Flag to overwrite the file by redownloading it.
     * {@code overwrite=true} means that if the target file pre-exists
     * at the expected target-location for the current plugin execution,
     * then the pre-existing file will be overwritten and replaced anyway;
     * whereas default {@code overwrite=false} will entirely skip all the
     * execution if the target file pre-exists and matches specification
     * (name, signatures...).
     */
    @Parameter(property = "download.overwrite")
    private boolean overwrite;

    /**
     * Represent the file name to use as output value.
     * If not set, will use last segment of "url".
     */
    @Parameter(property = "download.outputFileName")
    private String outputFileName;

    /**
     * Represent the directory where the file should be downloaded.
     */
    @Parameter(
        property = "download.outputDirectory", defaultValue = "${project.build.directory}",
        required = true
    )
    private File outputDirectory;

    /**
     * The md5 of the file. If set, file checksum will be compared to this
     * checksum and plugin will fail.
     */
    @Parameter(property = "download.verify.md5")
    private String md5;

    /**
     * The sha1 of the file. If set, file checksum will be compared to this
     * checksum and plugin will fail.
     */
    @Parameter(property = "download.verify.sha1")
    private String sha1;

    /**
     * The sha256 of the file. If set, file checksum will be compared to this
     * checksum and plugin will fail.
     */
    @Parameter(property = "download.verify.sha256")
    private String sha256;

    /**
     * The sha512 of the file. If set, file checksum will be compared to this
     * checksum and plugin will fail.
     */
    @Parameter(property = "download.verify.sha512")
    private String sha512;

    /**
     * Whether to unpack the file in case it is an archive (.zip).
     */
    @Parameter(property = "download.unpack", defaultValue = "false")
    private boolean unpack;

    /**
     * Whether to unpack the artifact only when the downloaded file changes.
     */
    @Parameter(property = "download.unpackWhenChanged", defaultValue = "false")
    private boolean unpackWhenChanged;

    /**
     * Server Id from settings file to use for authentication.
     * Only one of serverId or (username/password) may be supplied
     */
    @Parameter(property = "download.auth.serverId")
    private String serverId;

    /**
     * Custom username for the download.
     */
    @Parameter(property = "download.auth.username")
    private String username;

    /**
     * Custom password for the download.
     */
    @Parameter(property = "download.auth.password")
    private String password;

    /**
     * How many retries for a download.
     */
    @Parameter(property = "download.retries", defaultValue = "2")
    private int retries;

    /**
     * Comma separated list of HTTP codes that will be retried.
     */
    @Parameter(property = "download.retries.httpCodes")
    private String retriesOnHttpCodes;

    /**
     * Configures a list of error classes that are recorded as a failure and thus are retried. Any exception matching
     * or inheriting from one of the list will be retried.
     */
    @Parameter(property = "download.retries.exceptions")
    private List<String> retriesOnExceptions;

    /**
     * Initial retries interval in milliseconds.
     */
    @Parameter(property = "download.retries.interval.initial", defaultValue = "500")
    private long retriesIntervalInitial;

    /**
     * Each subsequent retry interval will be multiplied by this value.
     */
    @Parameter(property = "download.retries.interval.multiplier", defaultValue = "1.5")
    private double retriesIntervalMultiplier;

    /**
     * Maximum value of interval between retries in milliseconds.
     */
    @Parameter(property = "download.retries.interval.max", defaultValue = "30000")
    private long retriesIntervalMax;

    /**
     * Read timeout for a download in milliseconds.
     */
    @Parameter(defaultValue = "3000")
    private int readTimeOut;

    /**
     * Download file without polling cache.
     * Means that the download operation will not look in the global cache
     * to resolve the file to download, and will directly proceed with
     * the download and won't store this download in the cache.
     * It's recommended for urls that have "volatile" content.
     */
    @Parameter(property = "download.cache.skip", defaultValue = "false")
    private boolean skipCache;

    /**
     * The directory to use as a cache. Default is ${local-repo}/.cache/download-maven-plugin
     */
    @Parameter(property = "download.cache.directory")
    private File cacheDirectory;

    /**
     * When to check the server for a newer version of a file that is in the download cache.
     * <ul>
     *   <li>{@code never}: a cached file is used as is, without contacting the server.</li>
     *   <li>{@code always}: the server is asked whether the cached file is still current, with
     *   a conditional request ({@code If-None-Match} / {@code If-Modified-Since}), and a newer
     *   version is downloaded. Use it for URLs whose content changes, e.g. "latest" links.
     *   A server that doesn't send {@code ETag} / {@code Last-Modified}, or ignores conditional
     *   requests, sends the whole file every time; it only counts as changed if its content
     *   differs from the cached copy. If the server can't be reached, answers with a server error
     *   or with {@code 429}, the cached file is used and a warning is logged. If it refuses the
     *   request ({@code 4xx}), the build fails unless {@code failOnError} is {@code false}.</li>
     * </ul>
     * The check is not made in offline mode, nor when a checksum ({@code md5}, {@code sha1},
     * {@code sha256}, {@code sha512}) is configured, as the checksum already pins the content.
     * An existing output file is left alone unless {@code overwrite} is set, whatever the policy.
     * @since 2.2.0
     */
    @Parameter(property = "download.cache.updatePolicy", defaultValue = "never")
    private String updatePolicy;

    /**
     * Flag to determine whether to fail on an unsuccessful download.
     */
    @Parameter(defaultValue = "true")
    private boolean failOnError;

    /**
     * Whether to skip execution of Mojo.
     */
    @Parameter(property = "download.plugin.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Whether to verify the checksum of an existing file.
     * <p>
     * By default, checksum verification only occurs after downloading a file. This option
     * additionally enforces
     * checksum verification for already existing, previously downloaded (or manually copied)
     * files. If the checksum
     * does not match, re-download the file.
     * <p>
     * Use this option in order to ensure that a new download attempt is made after a previously
     * interrupted build or
     * network connection or some other event corrupted a file.
     */
    @Parameter(property = "alwaysVerifyChecksum", defaultValue = "false")
    private boolean alwaysVerifyChecksum;

    /**
     * The option name is counter-intuitive and not related to signatures but to
     * checksums, in fact.
     * Please use {@link #alwaysVerifyChecksum} instead. This option might be removed in a future
     * release.
     * @deprecated
     */
    @Parameter(property = "checkSignature", defaultValue = "false")
    @Deprecated
    private boolean checkSignature;

    /**
     * Whether to follow redirects (301 Moved Permanently, 302 Found, 303 See Other,
     * 307 Temporary Redirect, 308 Permanent Redirect).
     * <p>If this option is disabled and the returned resource returns a redirect, the plugin
     * will report an error and exit unless {@link #failOnError} is {@code false}. The same
     * happens for a redirect that can't be followed, such as 300 Multiple Choices: the body
     * of a redirect is never saved as the file.</p>
     */
    @Parameter(property = "download.plugin.followRedirects", defaultValue = "true")
    private boolean followRedirects = true;

    /**
     * A list of additional HTTP headers to send with the request.
     */
    @Parameter(property = "download.plugin.headers")
    private Map<String, String> headers = new HashMap<>();

    /**
     * Maven session.
     */
    @Parameter(property = "session", readonly = true)
    private MavenSession session;

    /**
     * Maven Security Dispatcher.
     */
    @Component(hint = "mng-4384")
    private SecDispatcher securityDispatcher;

    /**
     * Maven Archiver Manager.
     */
    @Inject
    private ArchiverManager archiverManager;

    /**
     * Maven Build Context.
     */
    @Inject
    private BuildContext buildContext;

    /**
     * Runs the plugin only if the current project is the execution root.
     * This is helpful, if the plugin is defined in a profile and should only run once
     * to download a shared file.
     */
    @Parameter(property = "runOnlyAtRoot", defaultValue = "false")
    private boolean runOnlyAtRoot;

    /**
     * Maximum time (ms) to wait to acquire a file lock.
     * Customize the time when using the plugin to download the same file
     * from several submodules in parallel build.
     */
    @Parameter(property = "maxLockWaitTime", defaultValue = "30000")
    private long maxLockWaitTime;

    /**
     * {@link FileMapper}s to be used for rewriting each target path, or {@code null} if no rewriting shall happen.
     * @since 1.6.8
     */
    @Parameter(property = "download.fileMappers")
    private FileMapper[] fileMappers;

    /**
     * If {@code true}, preemptive authentication will be used.
     * @since 1.6.9
     */
    @Parameter(property = "preemptiveAuth", defaultValue = "false")
    private boolean preemptiveAuth;

    /**
     * Files to include when unpacking.
     *
     * <p>If left empty all files will be eligible to be unpacked.</p>
     *
     * <p>Entries are interpreted as Ant-style path patterns.</p>
     * @since 1.9.0
     */
    @Parameter(property = "download.unpack.includes")
    private String[] includes;

    /**
     * Files to ignore when unpacking.
     *
     * <p>If left empty no file will be excluded when unpacking.</p>
     *
     * <p>Entries are interpreted as Ant-style path patterns.</p>
     * @since 1.9.0
     */
    @Parameter(property = "download.unpack.excludes")
    private String[] excludes;

    /**
     * If {@code true}, SSL certificate verification is skipped.
     * @since 1.8.1
     */
    @Parameter(property = "insecure", defaultValue = "false")
    private boolean insecure;

    /**
     * Set specific permission to output file.
     * Currently, only {@code +x} option is supported to set executable permission.
     * @since 1.13.0
     */
    @Parameter(property = "download.outputFilePermissions")
    private String outputFilePermissions;

    static {
        CONN_POOL = new PoolingHttpClientConnectionManager(
            RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", new SSLConnectionSocketFactory(
                    SSLContexts.createSystemDefault(),
                    SSLProtocols.supported(),
                    null,
                    SSLConnectionSocketFactory.getDefaultHostnameVerifier()
                ))
                .build(),
            null,
            null,
            null,
            1,
            TimeUnit.MINUTES
        );
    }

    /**
     * Ensures that the output directory does not contain unresolved path variables, i.e. when
     * running without a pom.xml.
     * If unresolved path variables are detected, set the output directory to the current working
     * directory.
     * @throws MojoExecutionException If the current working directory could not be resolved.
     * This should never happen.
     * @since 1.7.2
     */
    private void adjustOutputDirectory() throws MojoExecutionException {
        if (this.outputDirectory.getPath().contains("${")) {
            this.getLog().info(
                String.format(
                    "Could not resolve outputDirectory '%s'.  Consider using -Ddownload.outputDirectory=.",
                    this.outputDirectory.getPath()
                )
            );
            this.outputDirectory = new File(".");
            try {
                this.getLog().info(
                    String.format(
                        "Adjusting outputDirectory to %s", this.outputDirectory.getCanonicalPath()
                    )
                );
            } catch (final IOException exc) {
                throw new MojoExecutionException(
                    "Current working directory could not be resolved. This should never happen.",
                    exc
                );
            }
        }
    }

    /**
     * Method call when the mojo is executed for the first time.
     * @throws MojoExecutionException if an error is occurring in this mojo.
     * @throws MojoFailureException if an error is occurring in this mojo.
     */
    @SuppressWarnings(
        {
            "checkstyle:ReturnCount", "checkstyle:NPathComplexity",
            "checkstyle:ExecutableStatementCount", "checkstyle:IllegalCatch"
        }
    )
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (this.skip) {
            this.getLog().info("download-maven-plugin:wget skipped");
            return;
        }
        if (this.runOnlyAtRoot && !this.session.getCurrentProject().isExecutionRoot()) {
            this.getLog().info("download-maven-plugin:wget skipped (not project root)");
            return;
        }
        if (
            StringUtils.isNotBlank(this.serverId)
                && (StringUtils.isNotBlank(this.username) || StringUtils.isNotBlank(this.password))
        ) {
            throw new MojoExecutionException("Specify either serverId or username/password, not both");
        }
        final Environment environment = new MavenEnvironment(
            this.session, this.securityDispatcher, this.getLog()
        );
        if (this.session.getSettings() == null) {
            this.getLog().warn("settings is null");
        }
        if (this.session.getSettings().isOffline()) {
            this.getLog().debug("download-maven-plugin:wget offline mode");
        }
        this.getLog().debug("Got settings");
        if (this.retries < 1) {
            throw new MojoFailureException("retries must be at least 1");
        }
        final boolean revalidate = this.revalidate();
        final DownloadCache cache = this.cache(environment);
        this.adjustOutputDirectory();
        if (this.outputDirectory.exists() && !this.outputDirectory.isDirectory()) {
            throw new MojoExecutionException(
                "outputDirectory is not a directory: " + this.outputDirectory.getAbsolutePath());
        } else {
            this.outputDirectory.mkdirs();
        }
        if (this.outputFileName == null) {
            this.outputFileName = FileNameUtils.getOutputFileName(this.uri);
        }
        final DownloadRequest request = new DownloadRequest.Builder()
            .uri(this.uri)
            .outputDirectory(this.outputDirectory)
            .outputFileName(this.outputFileName)
            .checksums(new Checksums(this.md5, this.sha1, this.sha256, this.sha512, this.getLog()))
            .cache(cache)
            .overwrite(this.overwrite)
            .verifyExisting(this.alwaysVerifyChecksum || this.checkSignature)
            .unpack(this.unpack)
            .unpackWhenChanged(this.unpackWhenChanged)
            .failOnError(this.failOnError)
            .offline(environment.isOffline())
            .revalidate(revalidate)
            .lockWaitMillis(this.maxLockWaitTime)
            .permissions(this.outputFilePermissions)
            .retryPolicy(this.retryPolicy())
            .build();
        final Downloader downloader = new Downloader(
            this.fetcher(environment),
            new PlexusUnpacker(this.archiverManager, this.fileMappers, this.includes, this.excludes),
            this.buildContext::refresh,
            FILE_LOCKS,
            this.getLog()
        );
        try {
            downloader.download(request);
        } catch (final DownloadException exc) {
            // report the original failure as cause, as the plugin did before the download logic was extracted
            throw new MojoExecutionException(exc.getMessage(), exc.getCause() == null ? exc : exc.getCause());
        } catch (final IOException exc) {
            throw new MojoExecutionException("IO Error: ", exc);
        } catch (final RuntimeException exc) {
            throw new MojoExecutionException("General error: ", exc);
        }
    }

    /**
     * Whether cached files are checked against the server, as configured by {@code updatePolicy}.
     * @return True for {@code always}, false for {@code never} or if not configured.
     * @throws MojoFailureException If {@code updatePolicy} has an unknown value.
     */
    private boolean revalidate() throws MojoFailureException {
        final String policy;
        if (this.updatePolicy == null) {
            policy = "never";
        } else {
            policy = this.updatePolicy.trim().toLowerCase(Locale.ROOT);
        }
        if (!"never".equals(policy) && !"always".equals(policy)) {
            throw new MojoFailureException(
                String.format("updatePolicy must be 'never' or 'always', but was '%s'", this.updatePolicy)
            );
        }
        return "always".equals(policy);
    }

    /**
     * Download cache for this execution.
     * @param environment Build environment.
     * @return Cache, or {@literal null} if caching is disabled.
     * @throws MojoFailureException If the cache directory is not a directory.
     */
    private DownloadCache cache(final Environment environment) throws MojoFailureException {
        final DownloadCache result;
        if (this.skipCache) {
            this.getLog().debug("Cache is skipped");
            result = null;
        } else {
            if (this.cacheDirectory == null) {
                this.cacheDirectory = new File(environment.localRepository(), ".cache/download-maven-plugin");
            } else if (this.cacheDirectory.exists() && !this.cacheDirectory.isDirectory()) {
                throw new MojoFailureException(
                    String.format("cacheDirectory is not a directory: %s", this.cacheDirectory.getAbsolutePath())
                );
            }
            this.getLog().debug(String.format("Cache is: %s", this.cacheDirectory.getAbsolutePath()));
            result = DOWNLOAD_CACHES.computeIfAbsent(
                this.cacheDirectory.getAbsolutePath(),
                directory -> new DownloadCache(this.cacheDirectory, this.getLog())
            );
        }
        return result;
    }

    /**
     * Retry policy from the plugin configuration.
     * @return Retry policy.
     * @throws MojoExecutionException If a retried exception class or HTTP code is invalid.
     */
    @SuppressWarnings("unchecked")
    private RetryPolicy retryPolicy() throws MojoExecutionException {
        final List<Integer> codes;
        final List<Class<? extends Throwable>> exceptions = new ArrayList<>(0);
        try {
            if (this.retriesOnHttpCodes == null) {
                codes = null;
            } else {
                codes = Arrays.stream(this.retriesOnHttpCodes.split(","))
                    .map(String::trim)
                    .map(Integer::valueOf)
                    .collect(Collectors.toList());
            }
            if (this.retriesOnExceptions != null) {
                for (final String name : this.retriesOnExceptions) {
                    exceptions.add((Class<? extends Throwable>) Class.forName(name));
                }
            }
        } catch (final ClassNotFoundException | NumberFormatException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
        return new RetryPolicy(
            this.retries, codes, exceptions,
            Duration.ofMillis(this.retriesIntervalInitial), this.retriesIntervalMultiplier,
            Duration.ofMillis(this.retriesIntervalMax)
        );
    }

    /**
     * HTTP fetcher configured from the plugin parameters.
     * @param environment Build environment providing credentials, proxy and progress settings.
     * @return Fetcher.
     */
    private Fetcher fetcher(final Environment environment) {
        final UserCredentials explicit;
        if (StringUtils.isNotBlank(this.username)) {
            explicit = new UserCredentials(this.username, this.password);
        } else {
            explicit = null;
        }
        return new HttpFetcher(
            environment,
            new HttpFileRequester.Builder()
                .withConnectTimeout(this.readTimeOut)
                .withSocketTimeout(this.readTimeOut)
                .withPreemptiveAuth(this.preemptiveAuth)
                .withRedirectsEnabled(this.followRedirects)
                .withInsecure(this.insecure),
            explicit,
            this.serverId,
            this.getAdditionalHeaders(),
            this.getLog()
        );
    }

    /**
     * Constructs additional HTTP headers for requests.
     * @return A list of headers derived from the internal headers map.
     */
    private List<Header> getAdditionalHeaders() {
        return this.headers.entrySet().stream()
            .map(pair -> new BasicHeader(pair.getKey(), pair.getValue()))
            .collect(Collectors.toList());
    }
}
