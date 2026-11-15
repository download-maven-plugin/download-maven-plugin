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
 * Username/password pair, optionally with NTLM domain and workstation.
 * Values are already resolved: decrypted and ready to send.
 */
public final class UserCredentials {

    /**
     * User name.
     */
    private final String username;

    /**
     * Password, may be {@literal null}.
     */
    private final String password;

    /**
     * NTLM domain, may be {@literal null}.
     */
    private final String ntlmDomain;

    /**
     * NTLM workstation, may be {@literal null}.
     */
    private final String ntlmHost;

    /**
     * Constructor.
     * @param username User name
     * @param password Password, may be {@literal null}
     * @param ntlmDomain NTLM domain, may be {@literal null}
     * @param ntlmHost NTLM workstation, may be {@literal null}
     */
    public UserCredentials(
        final String username, @Nullable final String password,
        @Nullable final String ntlmDomain, @Nullable final String ntlmHost
    ) {
        this.username = username;
        this.password = password;
        this.ntlmDomain = ntlmDomain;
        this.ntlmHost = ntlmHost;
    }

    /**
     * Constructor for credentials without NTLM settings.
     * @param username User name
     * @param password Password, may be {@literal null}
     */
    public UserCredentials(final String username, @Nullable final String password) {
        this(username, password, null, null);
    }

    /**
     * User name.
     * @return User name
     */
    public String username() {
        return this.username;
    }

    /**
     * Password.
     * @return Password, may be {@literal null}
     */
    @Nullable
    public String password() {
        return this.password;
    }

    /**
     * NTLM domain.
     * @return NTLM domain if set
     */
    public Optional<String> ntlmDomain() {
        return Optional.ofNullable(this.ntlmDomain);
    }

    /**
     * NTLM workstation.
     * @return NTLM workstation if set
     */
    public Optional<String> ntlmHost() {
        return Optional.ofNullable(this.ntlmHost);
    }

    @Override
    public String toString() {
        return String.format("UserCredentials{username=%s, password=***}", this.username);
    }
}
