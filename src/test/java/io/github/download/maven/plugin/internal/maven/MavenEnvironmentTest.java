package io.github.download.maven.plugin.internal.maven;

import io.github.download.maven.plugin.internal.EnvironmentException;
import io.github.download.maven.plugin.internal.ProxySettings;
import io.github.download.maven.plugin.internal.UserCredentials;
import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.settings.Server;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MavenEnvironment}.
 */
public class MavenEnvironmentTest {

    private static final URI TARGET = URI.create("http://target.invalid/file");

    private MavenExecutionRequest request;

    private DefaultRepositorySystemSession repositorySession;

    private SecDispatcher dispatcher;

    private Log log;

    @Before
    public void setUp() {
        this.request = mock(MavenExecutionRequest.class);
        this.repositorySession = new DefaultRepositorySystemSession();
        this.dispatcher = mock(SecDispatcher.class);
        this.log = mock(Log.class);
    }

    // ---------------------------------------------------------------- credentials

    @Test
    public void serverCredentialsAreDecrypted() throws Exception {
        servers(server("my-server", "billg", "{encrypted}"));
        when(this.dispatcher.decrypt("{encrypted}")).thenReturn("hunter2");
        final UserCredentials creds = environment().credentialsFor(TARGET, "my-server").get();
        assertThat(creds.username(), is("billg"));
        assertThat(creds.password(), is("hunter2"));
    }

    @Test
    public void rawPasswordIsUsedIfDecryptionFails() throws Exception {
        servers(server("my-server", "billg", "plain"));
        when(this.dispatcher.decrypt(anyString())).thenThrow(new SecDispatcherException("no master password"));
        final UserCredentials creds = environment().credentialsFor(TARGET, "my-server").get();
        assertThat(creds.password(), is("plain"));
        verify(this.log).warn(startsWith("Failed to decrypt password/passphrase for server my-server"),
            any(SecDispatcherException.class));
    }

    @Test
    public void unknownServerFails() {
        servers(server("other", "billg", "secret"));
        final EnvironmentException error = assertThrows(
            EnvironmentException.class,
            () -> environment().credentialsFor(TARGET, "unknown")
        );
        assertThat(error.getMessage(), is("Server unknown not found"));
    }

    @Test
    public void credentialsFromAuthenticationSelector() throws Exception {
        this.repositorySession.setAuthenticationSelector(
            repository -> new AuthenticationBuilder().addUsername("user").addPassword("secret").build()
        );
        final UserCredentials creds = environment().credentialsFor(TARGET, null).get();
        assertThat(creds.username(), is("user"));
        assertThat(creds.password(), is("secret"));
    }

    @Test
    public void noCredentialsWithoutAuthenticationSelector() throws Exception {
        assertThat(environment().credentialsFor(TARGET, null), is(Optional.empty()));
    }

    @Test
    public void noCredentialsIfSelectorHasNoneForResource() throws Exception {
        this.repositorySession.setAuthenticationSelector(repository -> null);
        assertThat(environment().credentialsFor(TARGET, ""), is(Optional.empty()));
    }

    @Test
    public void noCredentialsIfSelectorProvidesNoUsername() throws Exception {
        this.repositorySession.setAuthenticationSelector(
            repository -> new AuthenticationBuilder().addPassword("secret").build()
        );
        assertThat(environment().credentialsFor(TARGET, null), is(Optional.empty()));
    }

    // ---------------------------------------------------------------- proxy

    @Test
    public void noProxyWithoutProxySelector() {
        assertThat(environment().proxyFor(TARGET, null), is(Optional.empty()));
    }

    @Test
    public void proxyWithoutAuthentication() {
        this.repositorySession.setProxySelector(
            new DefaultProxySelector().add(new Proxy("http", "proxy.invalid", 3128), null)
        );
        final ProxySettings proxy = environment().proxyFor(TARGET, null).get();
        assertThat(proxy.host(), is("proxy.invalid"));
        assertThat(proxy.port(), is(3128));
        assertThat(proxy.credentials(), is(Optional.empty()));
    }

    @Test
    public void proxyWithNtlmCredentials() {
        this.repositorySession.setProxySelector(
            new DefaultProxySelector().add(
                new Proxy("http", "proxy.invalid", 3128,
                    new AuthenticationBuilder()
                        .addUsername("puser").addPassword("ppass").addNtlm("workstation", "domain")
                        .build()),
                null
            )
        );
        final UserCredentials creds = environment().proxyFor(TARGET, null).get().credentials().get();
        assertThat(creds.username(), is("puser"));
        assertThat(creds.password(), is("ppass"));
        assertThat(creds.ntlmHost(), is(Optional.of("workstation")));
        assertThat(creds.ntlmDomain(), is(Optional.of("domain")));
    }

    @Test
    public void noProxyForNonProxyHost() {
        this.repositorySession.setProxySelector(
            new DefaultProxySelector().add(new Proxy("http", "proxy.invalid", 3128), "target.invalid")
        );
        assertThat(environment().proxyFor(TARGET, null), is(Optional.empty()));
    }

    // ---------------------------------------------------------------- session state

    @Test
    public void noProgressInBatchMode() {
        assertThat(environment().showsProgress(), is(false));
    }

    @Test
    public void progressInInteractiveMode() {
        when(this.request.isInteractiveMode()).thenReturn(true);
        assertThat(environment().showsProgress(), is(true));
    }

    @Test
    public void progressWithRegularTransferListener() {
        when(this.request.isInteractiveMode()).thenReturn(true);
        when(this.request.getTransferListener()).thenReturn(new AbstractTransferListener() { });
        assertThat(environment().showsProgress(), is(true));
    }

    @Test
    public void noProgressWithQuietTransferListener() {
        when(this.request.isInteractiveMode()).thenReturn(true);
        when(this.request.getTransferListener()).thenReturn(new QuietMavenTransferListener());
        assertThat(environment().showsProgress(), is(false));
    }

    @Test
    public void offlineComesFromRepositorySession() {
        assertThat(environment().isOffline(), is(false));
        this.repositorySession.setOffline(true);
        assertThat(environment().isOffline(), is(true));
    }

    @Test
    public void localRepositoryComesFromSession() {
        final ArtifactRepository repository = mock(ArtifactRepository.class);
        when(repository.getBasedir()).thenReturn("/repo");
        when(this.request.getLocalRepository()).thenReturn(repository);
        assertThat(environment().localRepository(), is(new File("/repo")));
    }

    /**
     * Mimics Maven's quiet transfer listener, which is detected by its simple class name.
     */
    private static final class QuietMavenTransferListener extends AbstractTransferListener {
    }

    // ---------------------------------------------------------------- helpers

    private MavenEnvironment environment() {
        return new MavenEnvironment(
            new MavenSession(null, this.repositorySession, this.request, new DefaultMavenExecutionResult()),
            this.dispatcher,
            this.log
        );
    }

    private void servers(final Server... servers) {
        when(this.request.getServers()).thenReturn(Arrays.asList(servers));
    }

    private static Server server(final String id, final String username, final String password) {
        final Server server = new Server();
        server.setId(id);
        server.setUsername(username);
        server.setPassword(password);
        return server;
    }
}
