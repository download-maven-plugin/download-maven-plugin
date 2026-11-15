package io.github.download.maven.plugin.internal;

import java.io.IOException;
import java.net.URI;
import org.apache.maven.plugin.logging.Log;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link LoggingProgressReport} and {@link SilentProgressReport}.
 */
public class ProgressReportTest {

    private static final URI URI_UNDER_TEST = URI.create("http://example.invalid/file");

    private Log log;

    @Before
    public void setUp() {
        this.log = mock(Log.class);
    }

    @Test
    public void reportsProgressInKilobytesForLargeResources() {
        final LoggingProgressReport report = new LoggingProgressReport(this.log);
        report.initiate(URI_UNDER_TEST, 4096L);
        report.update(1024L);
        report.update(3072L);
        report.completed();
        final InOrder order = inOrder(this.log);
        order.verify(this.log).info("Downloading: " + URI_UNDER_TEST);
        order.verify(this.log).info("1/4K");
        order.verify(this.log).info("4/4K");
        order.verify(this.log).info("4K downloaded");
    }

    @Test
    public void reportsProgressInBytesForSmallResources() {
        final LoggingProgressReport report = new LoggingProgressReport(this.log);
        report.initiate(URI_UNDER_TEST, 100L);
        report.update(40L);
        report.completed();
        verify(this.log).info("40/100");
        verify(this.log).info("40 downloaded");
    }

    @Test
    public void reportsUnknownTotalAsQuestionMark() {
        final LoggingProgressReport report = new LoggingProgressReport(this.log);
        report.initiate(URI_UNDER_TEST, -1L);
        report.update(10L);
        verify(this.log).info("10/?");
    }

    @Test
    public void loggingReportLogsErrors() {
        final IOException error = new IOException("boom");
        new LoggingProgressReport(this.log).error(error);
        verify(this.log).error(error);
    }

    @Test
    public void silentReportOnlyLogsErrors() {
        final SilentProgressReport report = new SilentProgressReport(this.log);
        report.initiate(URI_UNDER_TEST, 4096L);
        report.update(4096L);
        report.completed();
        verifyNoInteractions(this.log);
        final IOException error = new IOException("boom");
        report.error(error);
        verify(this.log).error(error);
    }
}
