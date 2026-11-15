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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * When and how often a failed download is retried.
 */
public final class RetryPolicy {

    /**
     * Lowest HTTP code retried by default.
     */
    private static final int SERVER_ERROR = HttpCodes.INTERNAL_SERVER_ERROR.getCode();

    /**
     * Total number of attempts, at least 1.
     */
    private final int attempts;

    /**
     * HTTP codes to retry, {@literal null} to retry server errors.
     */
    private final List<Integer> httpCodes;

    /**
     * Exception types to retry.
     */
    private final List<Class<? extends Throwable>> exceptions;

    /**
     * Wait before the first retry; zero disables waiting.
     */
    private final Duration initialInterval;

    /**
     * Growth factor of the wait between retries.
     */
    private final double multiplier;

    /**
     * Longest wait between retries.
     */
    private final Duration maxInterval;

    /**
     * Constructor.
     * @param attempts Total number of attempts, at least 1
     * @param httpCodes HTTP codes to retry, {@literal null} to retry server errors (5xx)
     * @param exceptions Exception types to retry
     * @param initialInterval Wait before the first retry; zero disables waiting
     * @param multiplier Growth factor of the wait between retries
     * @param maxInterval Longest wait between retries
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public RetryPolicy(
        final int attempts, @Nullable final List<Integer> httpCodes,
        final List<Class<? extends Throwable>> exceptions,
        final Duration initialInterval, final double multiplier, final Duration maxInterval
    ) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1");
        }
        this.attempts = attempts;
        this.httpCodes = httpCodes == null ? null : Collections.unmodifiableList(new ArrayList<>(httpCodes));
        this.exceptions = Collections.unmodifiableList(new ArrayList<>(exceptions));
        this.initialInterval = initialInterval;
        this.multiplier = multiplier;
        this.maxInterval = maxInterval;
    }

    /**
     * Policy making a single attempt.
     * @return Policy without retries
     */
    public static RetryPolicy none() {
        return new RetryPolicy(1, null, Collections.emptyList(), Duration.ZERO, 1.0d, Duration.ZERO);
    }

    /**
     * Total number of attempts.
     * @return Attempts, at least 1
     */
    public int attempts() {
        return this.attempts;
    }

    /**
     * Whether a response with the given HTTP code is retried.
     * @param code HTTP status code
     * @return True if the download should be retried
     */
    public boolean retriesHttpCode(final int code) {
        final boolean result;
        if (this.httpCodes == null) {
            result = code >= SERVER_ERROR;
        } else {
            result = this.httpCodes.contains(code);
        }
        return result;
    }

    /**
     * Exception types to retry.
     * @return Exception types
     */
    public List<Class<? extends Throwable>> exceptions() {
        return this.exceptions;
    }

    /**
     * Wait before the first retry.
     * @return Interval; zero disables waiting
     */
    public Duration initialInterval() {
        return this.initialInterval;
    }

    /**
     * Growth factor of the wait between retries.
     * @return Multiplier
     */
    public double multiplier() {
        return this.multiplier;
    }

    /**
     * Longest wait between retries.
     * @return Interval
     */
    public Duration maxInterval() {
        return this.maxInterval;
    }
}
