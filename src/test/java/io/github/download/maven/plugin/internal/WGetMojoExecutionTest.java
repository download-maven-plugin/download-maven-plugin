package io.github.download.maven.plugin.internal;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.plexus.build.incremental.BuildContext;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.forbidden;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.github.download.maven.plugin.internal.Fields.set;
import static io.github.download.maven.plugin.internal.TestFiles.newFile;
import static io.github.download.maven.plugin.internal.TestFiles.newFolder;
import static io.github.download.maven.plugin.internal.TestFiles.read;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WGetMojo}: parameter validation and how parameters and the Maven
 * session are passed on to the download. The download behaviour itself is tested in
 * {@link DownloaderTest}, {@link HttpFetcherTest} and {@link HttpFileRequesterTest}.
 */
public class WGetMojoExecutionTest {

    private static final String OUTPUT_FILE_NAME = "output-file";

    @RegisterExtension
    public WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(options().dynamicPort())
        .failOnUnmatchedRequests(true)
        .build();

    @TempDir
    public Path tempDir;

    private Path cacheDirectory;

    private Path outputDirectory;

    private Log log;

    private BuildContext buildContext;

    private ArchiverManager archiverManager;

    private MavenExecutionRequest request;

    private DefaultRepositorySystemSession repositorySession;

    @BeforeEach
    public void setUp() throws Exception {
        this.cacheDirectory = newFolder(this.tempDir, "cache").toPath();
        this.outputDirectory = newFolder(this.tempDir, "out").toPath();
        this.log = mock(Log.class);
        this.buildContext = mock(BuildContext.class);
        this.archiverManager = mock(ArchiverManager.class);
        this.request = mock(MavenExecutionRequest.class);
        this.repositorySession = new DefaultRepositorySystemSession();
        this.repositorySession.setOffline(false);
    }

    // ---------------------------------------------------------------- preconditions

    @Test
    public void skipDoesNothing() throws Exception {
        createMojo(m -> set(m, "skip", true)).execute();
        this.wireMock.verify(0, getRequestedFor(anyUrl()));
        assertThat(output().toFile().exists(), is(false));
        verify(this.log).info("download-maven-plugin:wget skipped");
    }

    @Test
    public void runOnlyAtRootSkipsNonRootProject() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        createMojo(m -> {
            set(m, "runOnlyAtRoot", true);
            currentProject(m, false);
        }).execute();
        this.wireMock.verify(0, getRequestedFor(anyUrl()));
        verify(this.log).info("download-maven-plugin:wget skipped (not project root)");
    }

    @Test
    public void runOnlyAtRootDownloadsInRootProject() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        createMojo(m -> {
            set(m, "runOnlyAtRoot", true);
            currentProject(m, true);
        }).execute();
        assertThat(read(output()), is("content"));
    }

    @Test
    public void serverIdAndUsernameAreMutuallyExclusive() {
        final MojoExecutionException error = assertThrows(
            MojoExecutionException.class,
            () -> createMojo(m -> {
                set(m, "serverId", "server");
                set(m, "username", "user");
            }).execute()
        );
        assertThat(error.getMessage(), is("Specify either serverId or username/password, not both"));
    }

    @Test
    public void serverIdAndPasswordAreMutuallyExclusive() {
        assertThrows(
            MojoExecutionException.class,
            () -> createMojo(m -> {
                set(m, "serverId", "server");
                set(m, "password", "secret");
            }).execute()
        );
    }

    @Test
    public void retriesMustBePositive() {
        final MojoFailureException error = assertThrows(
            MojoFailureException.class,
            () -> createMojo(m -> set(m, "retries", 0)).execute()
        );
        assertThat(error.getMessage(), is("retries must be at least 1"));
    }

    @Test
    public void cacheDefaultsToLocalRepository() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        final File localRepo = newFolder(this.tempDir, "repo");
        final ArtifactRepository repository = mock(ArtifactRepository.class);
        when(repository.getBasedir()).thenReturn(localRepo.getAbsolutePath());
        when(this.request.getLocalRepository()).thenReturn(repository);
        createMojo(m -> set(m, "cacheDirectory", null)).execute();
        assertThat(
            new File(localRepo, ".cache/download-maven-plugin/index.ser").isFile(),
            is(true)
        );
    }

    @Test
    public void outputDirectoryMustBeADirectory() throws Exception {
        final File file = newFile(this.tempDir, "not-a-dir");
        final MojoExecutionException error = assertThrows(
            MojoExecutionException.class,
            () -> createMojo(m -> set(m, "outputDirectory", file)).execute()
        );
        assertThat(error.getMessage(), org.hamcrest.Matchers.startsWith("outputDirectory is not a directory"));
    }

    @Test
    public void missingOutputDirectoryIsCreated() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        final File nested = this.outputDirectory.resolve("a/b").toFile();
        createMojo(m -> set(m, "outputDirectory", nested)).execute();
        assertThat(read(new File(nested, OUTPUT_FILE_NAME).toPath()), is("content"));
    }

    @Test
    public void outputFileNameIsDerivedFromUri() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        createMojo(m -> {
            set(m, "uri", URI.create(this.wireMock.baseUrl() + "/path/archive.tar.gz"));
            set(m, "outputFileName", null);
        }).execute();
        assertThat(read(this.outputDirectory.resolve("archive.tar.gz")), is("content"));
    }

    /**
     * An unresolved {@code ${...}} output directory (e.g. when running without a project)
     * falls back to the current working directory. The existing {@code target} directory
     * is used as output "file" so that nothing is written to the working directory.
     */
    @Test
    public void unresolvedOutputDirectoryFallsBackToWorkingDirectory() throws Exception {
        createMojo(m -> {
            set(m, "outputDirectory", new File("${project.build.directory}"));
            set(m, "outputFileName", "target");
            set(m, "overwrite", false);
            set(m, "skipCache", true);
        }).execute();
        verify(this.log).info(startsWith("Could not resolve outputDirectory '${project.build.directory}'"));
        verify(this.log).info("Adjusting outputDirectory to " + new File(".").getCanonicalPath());
        verify(this.log).info("File already exists, skipping");
        this.wireMock.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    public void cacheDirectoryMustBeADirectory() throws Exception {
        final File notADirectory = newFile(this.tempDir, "not-a-directory");
        final MojoFailureException error = assertThrows(
            MojoFailureException.class,
            () -> createMojo(m -> set(m, "cacheDirectory", notADirectory)).execute()
        );
        assertThat(error.getMessage(), containsString("cacheDirectory is not a directory"));
    }

    @Test
    public void skipCacheLeavesCacheDirectoryEmpty() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        createMojo(m -> set(m, "skipCache", true)).execute();
        assertThat(this.cacheDirectory.toFile().list(), is(emptyArray()));
    }

    @Test
    public void missingCacheDirectoryIsCreated() throws Exception {
        final File missing = this.cacheDirectory.resolve("a/b").toFile();
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        createMojo(m -> set(m, "cacheDirectory", missing)).execute();
        assertThat(read(output()), is("content"));
        assertThat(missing.isDirectory(), is(true));
    }

    // ---------------------------------------------------------------- caching

    @Test
    public void largeFileIsServedFromCache() throws Exception {
        final byte[] large = WGetMojoExecutionTest.randomBytes();
        assertServedFromCache(ok().withBody(large));
        assertThat(Files.readAllBytes(output()), is(large));
    }

    /**
     * The cache stores what the plugin downloaded, whatever the server says about caching it.
     */
    @Test
    public void responseNotMeantForCachingIsCached() throws Exception {
        assertServedFromCache(ok("content").withHeader("Cache-Control", "no-store").withHeader("Vary", "*"));
        assertThat(read(output()), is("content"));
    }

    /**
     * A new version of the same size differs only in its content, which must be compared in full.
     */
    @Test
    public void updatePolicyAlwaysReplacesLargeFileOfSameSize() throws Exception {
        final byte[] first = WGetMojoExecutionTest.randomBytes();
        final byte[] second = first.clone();
        second[second.length - 1] ^= 1;
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok().withBody(first)));
        createMojo(m -> set(m, "updatePolicy", "always")).execute();
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok().withBody(second)));
        cleanOutputDirectory();

        createMojo(m -> set(m, "updatePolicy", "always")).execute();
        assertThat(Files.readAllBytes(output()), is(second));
        cleanOutputDirectory();
        createMojo(m -> { }).execute();
        assertThat(Files.readAllBytes(output()), is(second));
        verify(this.log).info("Cached file was outdated: downloaded the current version");
    }

    /**
     * A server answering the update check without a body must not break a build that has
     * a cached copy, nor replace it with nothing.
     */
    @Test
    public void updatePolicyAlwaysKeepsCachedFileWhenServerAnswersNoContent() throws Exception {
        assertCachedFileSurvivesEmptyAnswer(aResponse().withStatus(204));
    }

    @Test
    public void updatePolicyAlwaysKeepsCachedFileWhenServerAnswersEmptyBody() throws Exception {
        assertCachedFileSurvivesEmptyAnswer(aResponse().withStatus(200).withBody(new byte[0]));
    }

    private void assertCachedFileSurvivesEmptyAnswer(final ResponseDefinitionBuilder answer) throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("v1").withHeader("ETag", "\"v1\"")));
        createMojo(m -> set(m, "updatePolicy", "always")).execute();
        this.wireMock.stubFor(get(anyUrl()).willReturn(answer));
        cleanOutputDirectory();

        createMojo(m -> set(m, "updatePolicy", "always")).execute();
        assertThat(read(output()), is("v1"));
        cleanOutputDirectory();
        createMojo(m -> { }).execute();
        assertThat("cached copy", read(output()), is("v1"));
    }

    @Test
    public void updatePolicyAlwaysKeepsUnchangedLargeFile() throws Exception {
        final byte[] large = WGetMojoExecutionTest.randomBytes();
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok().withBody(large)));
        createMojo(m -> set(m, "updatePolicy", "always")).execute();
        cleanOutputDirectory();

        createMojo(m -> set(m, "updatePolicy", "always")).execute();
        assertThat(Files.readAllBytes(output()), is(large));
        verify(this.log).info("Cached file is up to date: the server sent the same content");
    }

    // ---------------------------------------------------------------- request and output configuration

    @Test
    public void sendsConfiguredHeaders() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        final Map<String, String> headers = new HashMap<>();
        headers.put("X-Custom-1", "first custom header");
        headers.put("X-Custom-2", "second custom header");
        createMojo(m -> set(m, "headers", headers)).execute();
        this.wireMock.verify(
            getRequestedFor(anyUrl())
                .withHeader("X-Custom-1", equalTo("first custom header"))
                .withHeader("X-Custom-2", equalTo("second custom header"))
        );
    }

    @Test
    public void followsRedirectsByDefault() throws Exception {
        for (final int code : new int[] {301, 302, 303, 307, 308}) {
            stubRedirect(code);
            createMojo(m -> {
                set(m, "uri", URI.create(this.wireMock.baseUrl() + "/" + code));
                set(m, "overwrite", true);
            }).execute();
            assertThat(read(output()), is("redirected"));
        }
    }

    @Test
    public void redirectFailsIfFollowRedirectsIsOff() throws Exception {
        for (final int code : new int[] {301, 302, 303, 307, 308}) {
            stubRedirect(code);
            final MojoExecutionException error = assertThrows(
                MojoExecutionException.class,
                () -> createMojo(m -> {
                    set(m, "uri", URI.create(this.wireMock.baseUrl() + "/" + code));
                    set(m, "followRedirects", false);
                }).execute()
            );
            assertThat(error.getMessage(), containsString("Download failed with code " + code));
        }
    }

    @Test
    public void overwriteReplacesExistingFile() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("new")));
        TestFiles.write(output().toFile(), "old");
        createMojo(m -> set(m, "overwrite", true)).execute();
        assertThat(read(output()), is("new"));
    }

    @Test
    public void eachChecksumParameterIsVerified() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        for (final String parameter : new String[] {"md5", "sha1", "sha256", "sha512"}) {
            final MojoExecutionException error = assertThrows(
                MojoExecutionException.class,
                () -> createMojo(m -> set(m, parameter, "wrong")).execute(),
                parameter
            );
            assertThat(parameter, error.getCause().getMessage(), containsString("Not same digest as expected"));
            assertThat(parameter, this.outputDirectory.toFile().list(), is(emptyArray()));
        }
    }

    /**
     * Issue #331: the next build must download again, not skip the broken file.
     */
    @Test
    public void nextBuildDownloadsAgainAfterTransferWasCutOff() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
        assertThrows(MojoExecutionException.class, () -> createMojo(m -> { }).execute());
        assertThat(this.outputDirectory.toFile().list(), is(emptyArray()));

        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("complete")));
        createMojo(m -> { }).execute();
        assertThat(read(output()), is("complete"));
    }

    @Test
    public void alwaysVerifyChecksumReplacesExistingFileNotMatchingIt() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        TestFiles.write(output().toFile(), "wrong");
        createMojo(m -> {
            set(m, "alwaysVerifyChecksum", true);
            set(m, "md5", DigestUtils.md5Hex("content"));
        }).execute();
        assertThat(read(output()), is("content"));
        verify(this.log).warn(startsWith("The local version of file " + OUTPUT_FILE_NAME + " doesn't match"));
    }

    @Test
    public void setsOutputFilePermissions() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        createMojo(m -> set(m, "outputFilePermissions", "+x")).execute();
        assertThat(output().toFile().canExecute(), is(true));
    }

    // ---------------------------------------------------------------- locking

    @Test
    public void failsIfLockCannotBeAcquired() throws Exception {
        // the lock registry is shared by all executions in the JVM
        final FileLocks locks = Fields.getStatic(WGetMojo.class, "FILE_LOCKS", FileLocks.class);
        try (HeldLock ignored = HeldLock.on(locks.forFile(output().toFile()))) {
            final MojoExecutionException error = assertThrows(
                MojoExecutionException.class,
                () -> createMojo(m -> set(m, "maxLockWaitTime", 10L)).execute()
            );
            assertThat(error.getMessage(), org.hamcrest.Matchers.startsWith("Could not acquire lock for File"));
        }
        this.wireMock.verify(0, getRequestedFor(anyUrl()));
    }

    // ---------------------------------------------------------------- offline

    @Test
    public void offlineWithoutCachedFileFails() {
        this.repositorySession.setOffline(true);
        final MojoExecutionException error = assertThrows(
            MojoExecutionException.class,
            () -> createMojo(m -> { }).execute()
        );
        assertThat(error.getMessage(), is("No file in cache and maven is in offline mode"));
        this.wireMock.verify(0, getRequestedFor(anyUrl()));
    }

    /**
     * {@code failOnError=false} must also work with the cache enabled.
     */
    @Test
    public void ignoredDownloadFailureWithCacheEnabled() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(serverError()));
        createMojo(m -> set(m, "failOnError", false)).execute();
        verify(this.log).warn(startsWith("Download failed with code 500"));
        assertThat(output().toFile().exists(), is(false));
    }

    // ---------------------------------------------------------------- retries

    @Test
    public void unknownRetryExceptionClassFails() {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        final MojoExecutionException error = assertThrows(
            MojoExecutionException.class,
            () -> createMojo(m -> set(m, "retriesOnExceptions",
                Collections.singletonList("com.example.NoSuchException"))).execute()
        );
        assertThat(error.getMessage(), containsString("com.example.NoSuchException"));
    }

    @Test
    public void configuredExceptionIsRetried() throws Exception {
        stubSlowThenFast();
        createMojo(m -> {
            set(m, "readTimeOut", 200);
            set(m, "retries", 2);
            set(m, "retriesOnExceptions", Collections.singletonList("java.net.SocketTimeoutException"));
        }).execute();
        assertThat(read(output()), is("fast"));
        this.wireMock.verify(2, getRequestedFor(anyUrl()));
    }

    /**
     * Waits grow exponentially. With a 50% randomization factor the first wait is within
     * [initial/2, 1.5*initial] = [100, 300] ms and the second at least
     * initial*multiplier/2 = 400 ms, so the second wait is always longer than any possible
     * first one. A constant (non-exponential) interval could not produce that.
     */
    @Test
    public void retriesWithExponentialBackoffAndLogsEachRetry() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).inScenario("flaky")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(serverError())
            .willSetStateTo("failed once"));
        this.wireMock.stubFor(get(anyUrl()).inScenario("flaky")
            .whenScenarioStateIs("failed once")
            .willReturn(serverError())
            .willSetStateTo("recovered"));
        this.wireMock.stubFor(get(anyUrl()).inScenario("flaky")
            .whenScenarioStateIs("recovered")
            .willReturn(ok("content")));
        createMojo(m -> {
            set(m, "retries", 3);
            set(m, "retriesIntervalInitial", 200L);
            set(m, "retriesIntervalMultiplier", 4.0d);
            set(m, "retriesIntervalMax", 10000L);
        }).execute();
        assertThat(read(output()), is("content"));
        this.wireMock.verify(3, getRequestedFor(anyUrl()));
        final long[] arrivals = this.wireMock.getAllServeEvents().stream()
            .mapToLong(event -> event.getRequest().getLoggedDate().getTime())
            .sorted()
            .toArray();
        final long firstWait = arrivals[1] - arrivals[0];
        final long secondWait = arrivals[2] - arrivals[1];
        assertThat("first wait " + firstWait, firstWait >= 100L, is(true));
        assertThat("second wait " + secondWait, secondWait >= 400L, is(true));
        verify(this.log).info("Retrying (2 more)");
        verify(this.log).info("Retrying (1 more)");
        verify(this.log, org.mockito.Mockito.times(2)).warn(startsWith("Download failed with code 500"));
    }

    @Test
    public void retriesConfiguredHttpCodes() {
        this.wireMock.stubFor(get(anyUrl()).willReturn(notFound()));
        assertThrows(
            MojoExecutionException.class,
            () -> createMojo(m -> {
                set(m, "retries", 3);
                set(m, "retriesOnHttpCodes", "404,500");
                set(m, "retriesIntervalInitial", 0L);
            }).execute()
        );
        this.wireMock.verify(3, getRequestedFor(anyUrl()));
    }

    // ---------------------------------------------------------------- unpacking

    @Test
    public void unpacksArchiveAndRemovesIt() throws Exception {
        serveZip();
        createMojo(m -> set(m, "unpack", true)).execute();
        assertThat(read(this.outputDirectory.resolve("a.txt")), is("a"));
        assertThat(read(this.outputDirectory.resolve("dir/b.txt")), is("b"));
        assertThat(output().toFile().exists(), is(false));
        verify(this.buildContext).refresh(this.outputDirectory.toFile());
    }

    @Test
    public void unpackWhenChangedSkipsUnchangedCachedArchive() throws Exception {
        serveZip();
        createMojo(m -> set(m, "unpackWhenChanged", true)).execute();
        cleanOutputDirectory();

        createMojo(m -> set(m, "unpackWhenChanged", true)).execute();
        verify(this.log).info("Skipping unpacking as the file has not changed");
        assertThat(this.outputDirectory.resolve("a.txt").toFile().exists(), is(false));
    }

    // ---------------------------------------------------------------- update policy

    @Test
    public void unknownUpdatePolicyFails() {
        final MojoFailureException error = assertThrows(
            MojoFailureException.class,
            () -> createMojo(m -> set(m, "updatePolicy", "daily")).execute()
        );
        assertThat(error.getMessage(), is("updatePolicy must be 'never' or 'always', but was 'daily'"));
    }

    @Test
    public void cachedFileIsUsedWithoutAskingServerByDefault() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("v1")));
        createMojo(m -> { }).execute();
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("v2")));
        cleanOutputDirectory();

        createMojo(m -> { }).execute();
        assertThat(read(output()), is("v1"));
        this.wireMock.verify(1, getRequestedFor(anyUrl()));
    }

    @Test
    public void updatePolicyAlwaysDownloadsNewVersionOfCachedFile() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("v1").withHeader("ETag", "\"v1\"")));
        createMojo(m -> set(m, "updatePolicy", "always")).execute();
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("v2").withHeader("ETag", "\"v2\"")));
        cleanOutputDirectory();

        createMojo(m -> set(m, "updatePolicy", "Always")).execute();
        assertThat(read(output()), is("v2"));
        this.wireMock.verify(
            getRequestedFor(anyUrl()).withHeader("If-None-Match", matching("\"v1.*"))
        );
    }

    // ---------------------------------------------------------------- session credentials

    /**
     * Credentials provided by the repository session's authentication selector should be used.
     */
    @Test
    public void usesCredentialsFromRepositorySession() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(unauthorized().withHeader("WWW-Authenticate", "Basic")));
        this.wireMock.stubFor(get(anyUrl()).withBasicAuth("user", "secret").willReturn(ok("secured")));
        this.repositorySession.setAuthenticationSelector(
            repository -> new AuthenticationBuilder().addUsername("user").addPassword("secret").build()
        );
        createMojo(m -> { }).execute();
        assertThat(read(output()), is("secured"));
    }

    /**
     * The {@code username}/{@code password} parameters are used for authentication.
     */
    @Test
    public void usesConfiguredUsernameAndPassword() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(unauthorized().withHeader("WWW-Authenticate", "Basic")));
        this.wireMock.stubFor(get(anyUrl()).withBasicAuth("user", "secret").willReturn(ok("secured")));
        createMojo(m -> {
            set(m, "username", "user");
            set(m, "password", "secret");
        }).execute();
        assertThat(read(output()), is("secured"));
    }

    // ---------------------------------------------------------------- error reporting

    /**
     * Local I/O failures are reported as "IO Error" with the failure as cause.
     */
    @Test
    public void localIoFailureIsReportedAsIoError() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("content")));
        final File blocker = newFile(this.tempDir, "blocker");
        final MojoExecutionException error = assertThrows(
            MojoExecutionException.class,
            () -> createMojo(m -> set(m, "cacheDirectory", new File(blocker, "cache"))).execute()
        );
        assertThat(error.getMessage(), is("IO Error: "));
        assertThat(error.getCause().getMessage(), containsString("Could not create cache directory"));
    }

    @Test
    public void downloadFailureIsReportedWithItsCause() {
        this.wireMock.stubFor(get(anyUrl()).willReturn(forbidden()));
        final MojoExecutionException error = assertThrows(
            MojoExecutionException.class, () -> createMojo(m -> { }).execute()
        );
        assertThat(error.getCause(), is(instanceOf(DownloadFailureException.class)));
        assertThat(((DownloadFailureException) error.getCause()).getHttpCode(), is(403));
        this.wireMock.verify(1, getRequestedFor(anyUrl()));
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("deprecation")
    private WGetMojo createMojo(final Consumer<WGetMojo> initializer) {
        final WGetMojo mojo = new WGetMojo();
        mojo.setLog(this.log);
        set(mojo, "uri", URI.create(this.wireMock.baseUrl()));
        set(mojo, "outputFileName", OUTPUT_FILE_NAME);
        set(mojo, "outputDirectory", this.outputDirectory.toFile());
        set(mojo, "cacheDirectory", this.cacheDirectory.toFile());
        set(mojo, "retries", 1);
        set(mojo, "failOnError", true);
        set(mojo, "maxLockWaitTime", 30000L);
        set(mojo, "readTimeOut", 3000);
        set(mojo, "includes", new String[0]);
        set(mojo, "excludes", new String[0]);
        set(mojo, "buildContext", this.buildContext);
        set(mojo, "archiverManager", this.archiverManager);
        final MavenSession session = new MavenSession(null, this.request, null, new LinkedList<>());
        set(session, "repositorySession", this.repositorySession);
        set(mojo, "session", session);
        initializer.accept(mojo);
        return mojo;
    }

    private static void currentProject(final WGetMojo mojo, final boolean root) {
        final MavenProject project = new MavenProject();
        project.setExecutionRoot(root);
        Fields.get(mojo, "session", MavenSession.class).setCurrentProject(project);
    }

    private Path output() {
        return this.outputDirectory.resolve(OUTPUT_FILE_NAME);
    }

    private void serveZip() throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok().withBody(TestFiles.zip("a.txt", "a", "dir/b.txt", "b"))));
        when(this.archiverManager.getUnArchiver(any(File.class))).thenAnswer(inv -> new ZipUnArchiver());
    }

    private void stubRedirect(final int code) {
        this.wireMock.stubFor(get(urlEqualTo("/" + code))
            .willReturn(aResponse().withStatus(code).withHeader("Location", "/redirected")));
        this.wireMock.stubFor(get(urlEqualTo("/redirected")).willReturn(ok("redirected")));
    }

    private void stubSlowThenFast() {
        this.wireMock.stubFor(get(anyUrl()).inScenario("slow")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(ok("slow").withFixedDelay(1000))
            .willSetStateTo("fast"));
        this.wireMock.stubFor(get(anyUrl()).inScenario("slow")
            .whenScenarioStateIs("fast")
            .willReturn(ok("fast")));
    }

    /**
     * Downloads twice: the first answer is cached, so the second download is served from the cache
     * although the server would answer differently.
     * @param first First answer of the server
     * @throws Exception If a download fails
     */
    private void assertServedFromCache(final ResponseDefinitionBuilder first) throws Exception {
        this.wireMock.stubFor(get(anyUrl()).willReturn(first));
        createMojo(m -> { }).execute();
        this.wireMock.stubFor(get(anyUrl()).willReturn(ok("changed")));
        cleanOutputDirectory();

        createMojo(m -> { }).execute();
        this.wireMock.verify(1, getRequestedFor(anyUrl()));
    }

    private static byte[] randomBytes() {
        final byte[] bytes = new byte[8 * 1024 * 1024];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private void cleanOutputDirectory() throws Exception {
        org.codehaus.plexus.util.FileUtils.cleanDirectory(this.outputDirectory.toFile());
    }
}
