package io.github.download.maven.plugin.internal;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedList;
import java.util.function.Consumer;
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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonatype.plexus.build.incremental.BuildContext;

import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.github.download.maven.plugin.internal.Fields.set;
import static io.github.download.maven.plugin.internal.TestFiles.read;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WGetMojo#execute()} branches not covered by {@link WGetMojoTest}:
 * parameter validation, locking, offline mode, retry configuration, unpacking and
 * network settings coming from the Maven session.
 */
public class WGetMojoExecutionTest {

    private static final String OUTPUT_FILE_NAME = "output-file";

    @Rule
    public WireMockRule wireMock = new WireMockRule(options().dynamicPort());

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path cacheDirectory;

    private Path outputDirectory;

    private Log log;

    private BuildContext buildContext;

    private ArchiverManager archiverManager;

    private MavenExecutionRequest request;

    private DefaultRepositorySystemSession repositorySession;

    @Before
    public void setUp() throws Exception {
        this.cacheDirectory = this.temporaryFolder.newFolder("cache").toPath();
        this.outputDirectory = this.temporaryFolder.newFolder("out").toPath();
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
        final File localRepo = this.temporaryFolder.newFolder("repo");
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
        final File file = this.temporaryFolder.newFile("not-a-dir");
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
        final File blocker = this.temporaryFolder.newFile("blocker");
        final MojoExecutionException error = assertThrows(
            MojoExecutionException.class,
            () -> createMojo(m -> set(m, "cacheDirectory", new File(blocker, "cache"))).execute()
        );
        assertThat(error.getMessage(), is("IO Error: "));
        assertThat(error.getCause().getMessage(), containsString("Could not create cache directory"));
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

    private void stubSlowThenFast() {
        this.wireMock.stubFor(get(anyUrl()).inScenario("slow")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(ok("slow").withFixedDelay(1000))
            .willSetStateTo("fast"));
        this.wireMock.stubFor(get(anyUrl()).inScenario("slow")
            .whenScenarioStateIs("fast")
            .willReturn(ok("fast")));
    }

    private void cleanOutputDirectory() throws Exception {
        org.codehaus.plexus.util.FileUtils.cleanDirectory(this.outputDirectory.toFile());
    }
}
