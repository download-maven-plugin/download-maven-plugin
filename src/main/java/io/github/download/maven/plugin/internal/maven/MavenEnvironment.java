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
package io.github.download.maven.plugin.internal.maven;

import io.github.download.maven.plugin.internal.Environment;
import io.github.download.maven.plugin.internal.EnvironmentException;
import io.github.download.maven.plugin.internal.ProxySettings;
import io.github.download.maven.plugin.internal.UserCredentials;
import java.io.File;
import java.net.URI;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.settings.Server;
import org.apache.maven.shared.utils.StringUtils;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.transfer.TransferListener;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;

/**
 * {@link Environment} backed by the current Maven session and settings.
 */
public final class MavenEnvironment implements Environment {

    /**
     * Simple class name of Maven's transfer listener used in quiet mode.
     */
    private static final String QUIET_LISTENER = "QuietMavenTransferListener";

    /**
     * Current Maven session.
     */
    private final MavenSession session;

    /**
     * Decrypts passwords from settings.xml.
     */
    private final SecDispatcher dispatcher;

    /**
     * Logger.
     */
    private final Log log;

    /**
     * Constructor.
     * @param session Current Maven session
     * @param dispatcher Decrypts passwords from settings.xml
     * @param log Logger
     */
    public MavenEnvironment(final MavenSession session, final SecDispatcher dispatcher, final Log log) {
        this.session = session;
        this.dispatcher = dispatcher;
        this.log = log;
    }

    @Override
    public boolean isOffline() {
        return this.session.getRepositorySession().isOffline();
    }

    @Override
    public boolean showsProgress() {
        final boolean result;
        if (this.session.getSettings().isInteractiveMode()) {
            final TransferListener listener = this.session.getRequest().getTransferListener();
            result = listener == null || !QUIET_LISTENER.equals(listener.getClass().getSimpleName());
        } else {
            result = false;
        }
        return result;
    }

    @Override
    public File localRepository() {
        return new File(this.session.getLocalRepository().getBasedir());
    }

    @Override
    public Optional<ProxySettings> proxyFor(final URI uri, @Nullable final String serverId) {
        final RemoteRepository repository = MavenEnvironment.remoteRepository(serverId, uri);
        return Optional.ofNullable(this.repositorySession().getProxySelector())
            .map(selector -> selector.getProxy(repository))
            .map(
                proxy -> new ProxySettings(
                    proxy.getHost(), proxy.getPort(), this.proxyCredentials(repository, proxy).orElse(null)
                )
            );
    }

    @Override
    public Optional<UserCredentials> credentialsFor(final URI uri, @Nullable final String serverId)
        throws EnvironmentException {
        final Optional<UserCredentials> result;
        if (StringUtils.isNotBlank(serverId)) {
            result = Optional.of(this.serverCredentials(serverId));
        } else {
            final RemoteRepository repository = MavenEnvironment.remoteRepository(null, uri);
            result = Optional.ofNullable(this.repositorySession().getAuthenticationSelector())
                .map(selector -> selector.getAuthentication(repository))
                .flatMap(auth -> this.repositoryCredentials(repository, auth));
        }
        return result;
    }

    /**
     * Credentials of a server from settings.xml, with the password decrypted.
     * @param serverId Server id
     * @return Credentials
     * @throws EnvironmentException If the server is not configured
     */
    private UserCredentials serverCredentials(final String serverId) throws EnvironmentException {
        final Server server = this.session.getSettings().getServer(serverId);
        if (server == null) {
            throw new EnvironmentException(String.format("Server %s not found", serverId));
        }
        this.log.debug(
            String.format(
                "serverId %s supplies username: %s and password: ***", serverId, server.getUsername()
            )
        );
        return new UserCredentials(server.getUsername(), this.decrypt(server.getPassword(), serverId));
    }

    /**
     * Credentials provided by the repository session for a repository.
     * @param repository Repository the resource belongs to
     * @param authentication Authentication selected for the repository
     * @return Credentials if a username is provided
     */
    private Optional<UserCredentials> repositoryCredentials(
        final RemoteRepository repository, final Authentication authentication
    ) {
        final RemoteRepository authRepo = new RemoteRepository.Builder(repository)
            .setAuthentication(authentication)
            .build();
        try (
            AuthenticationContext ctx = AuthenticationContext.forRepository(this.repositorySession(), authRepo)
        ) {
            final Optional<UserCredentials> result = MavenEnvironment.credentials(ctx);
            result.ifPresent(
                creds -> this.log.debug(String.format("username: %s and password: ***", creds.username()))
            );
            return result;
        }
    }

    /**
     * Credentials provided by the repository session for a proxy.
     * @param repository Repository the resource belongs to
     * @param proxy Proxy selected for the repository
     * @return Credentials if a username is provided
     */
    private Optional<UserCredentials> proxyCredentials(final RemoteRepository repository, final Proxy proxy) {
        final RemoteRepository proxyRepo = new RemoteRepository.Builder(repository)
            .setProxy(proxy)
            .build();
        try (AuthenticationContext ctx = AuthenticationContext.forProxy(this.repositorySession(), proxyRepo)) {
            return MavenEnvironment.credentials(ctx);
        }
    }

    /**
     * Decrypt given password using maven security dispatcher.
     * @param password Encrypted password/passphrase.
     * @param serverId Server ID from settings.xml the password came from.
     * @return Decrypted password or input string if failed to decrypt.
     */
    private String decrypt(final String password, final String serverId) {
        String result;
        try {
            result = this.dispatcher.decrypt(password);
        } catch (final SecDispatcherException ex) {
            this.log.warn(
                String.format(
                    "Failed to decrypt password/passphrase for server %s, %s",
                    serverId, "using auth token as is"
                ), ex
            );
            result = password;
        }
        return result;
    }

    /**
     * Repository system session.
     * @return Repository session
     */
    private RepositorySystemSession repositorySession() {
        return this.session.getRepositorySession();
    }

    /**
     * Reads credentials from an authentication context.
     * @param ctx Authentication context, may be {@literal null}
     * @return Credentials if a username is provided
     */
    private static Optional<UserCredentials> credentials(@Nullable final AuthenticationContext ctx) {
        return Optional.ofNullable(ctx)
            .filter(context -> StringUtils.isNotBlank(context.get(AuthenticationContext.USERNAME)))
            .map(
                context -> new UserCredentials(
                    context.get(AuthenticationContext.USERNAME),
                    context.get(AuthenticationContext.PASSWORD),
                    context.get(AuthenticationContext.NTLM_DOMAIN),
                    context.get(AuthenticationContext.NTLM_WORKSTATION)
                )
            );
    }

    /**
     * Creates a remote repository describing the resource, used to select proxy and authentication.
     * @param serverId Server id from settings, may be {@literal null}
     * @param uri Resource to download
     * @return Remote repository
     */
    private static RemoteRepository remoteRepository(@Nullable final String serverId, final URI uri) {
        return new RemoteRepository.Builder(
            StringUtils.isBlank(serverId) ? null : serverId,
            StringUtils.isBlank(serverId) ? uri.getScheme() : null,
            StringUtils.isBlank(serverId) ? String.format("%s://%s", uri.getScheme(), uri.getHost()) : null
        ).build();
    }
}
