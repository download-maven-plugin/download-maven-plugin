package io.github.download.maven.plugin.internal;

import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.logging.Log;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

public class FilePermissionsTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Test
    public void applyExecutable() throws IOException, DownloadException {
        final File outputFile = this.temporaryFolder.newFile();
        new FilePermissions("+x", Mockito.mock(Log.class)).applyTo(outputFile);
        MatcherAssert.assertThat(
            outputFile.canExecute(),
            Matchers.is(true)
        );
    }

    @Test
    public void applyExecutableAgain() throws IOException, DownloadException {
        final File outputFile = this.temporaryFolder.newFile();
        outputFile.setExecutable(true);
        new FilePermissions("+x", Mockito.mock(Log.class)).applyTo(outputFile);
        MatcherAssert.assertThat(
            outputFile.canExecute(),
            Matchers.is(true)
        );
    }

    @Test
    public void failIfPermissionCannotBeApplied() {
        // on Windows File#setExecutable reports success even for a missing file
        assumeNotWindows();
        final File missing = new File(this.temporaryFolder.getRoot(), "missing");
        Assert.assertThrows(
            DownloadException.class,
            () -> new FilePermissions("+x", Mockito.mock(Log.class)).applyTo(missing)
        );
    }

    @Test
    public void noPermissionsIsNoop() throws IOException, DownloadException {
        // on Windows File#canExecute is always true, so "not executable" can't be observed
        assumeNotWindows();
        final File outputFile = this.temporaryFolder.newFile();
        outputFile.setExecutable(false);
        new FilePermissions(null, Mockito.mock(Log.class)).applyTo(outputFile);
        MatcherAssert.assertThat(outputFile.canExecute(), Matchers.is(false));
    }

    @Test
    public void failOnUnknownCommand() throws IOException {
        final File outputFile = this.temporaryFolder.newFile();
        outputFile.setExecutable(true);
        Assert.assertThrows(
            DownloadException.class,
            () -> new FilePermissions("-x", Mockito.mock(Log.class)).applyTo(outputFile)
        );
    }

    private static void assumeNotWindows() {
        Assume.assumeFalse(
            "POSIX permissions are not observable on Windows",
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
        );
    }
}
