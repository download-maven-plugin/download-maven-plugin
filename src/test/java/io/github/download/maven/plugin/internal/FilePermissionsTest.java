package io.github.download.maven.plugin.internal;

import java.io.File;
import java.nio.file.Path;
import java.io.IOException;
import org.apache.maven.plugin.logging.Log;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.github.download.maven.plugin.internal.TestFiles.newFile;

public class FilePermissionsTest {

    @TempDir
    public Path tempDir;

    @Test
    public void applyExecutable() throws IOException, DownloadException {
        final File outputFile = newFile(this.tempDir);
        new FilePermissions("+x", Mockito.mock(Log.class)).applyTo(outputFile);
        MatcherAssert.assertThat(
            outputFile.canExecute(),
            Matchers.is(true)
        );
    }

    @Test
    public void applyExecutableAgain() throws IOException, DownloadException {
        final File outputFile = newFile(this.tempDir);
        outputFile.setExecutable(true);
        new FilePermissions("+x", Mockito.mock(Log.class)).applyTo(outputFile);
        MatcherAssert.assertThat(
            outputFile.canExecute(),
            Matchers.is(true)
        );
    }

    @Test
    @DisabledOnOs(
        value = OS.WINDOWS,
        disabledReason = "On Windows File#setExecutable reports success even for a missing file"
    )
    public void failIfPermissionCannotBeApplied() {
        final File missing = new File(this.tempDir.toFile(), "missing");
        assertThrows(
            DownloadException.class,
            () -> new FilePermissions("+x", Mockito.mock(Log.class)).applyTo(missing)
        );
    }

    @Test
    @DisabledOnOs(
        value = OS.WINDOWS,
        disabledReason = "On Windows File#canExecute is always true, so not executable can't be observed"
    )
    public void noPermissionsIsNoop() throws IOException, DownloadException {
        final File outputFile = newFile(this.tempDir);
        outputFile.setExecutable(false);
        new FilePermissions(null, Mockito.mock(Log.class)).applyTo(outputFile);
        MatcherAssert.assertThat(outputFile.canExecute(), Matchers.is(false));
    }

    @Test
    public void failOnUnknownCommand() throws IOException {
        final File outputFile = newFile(this.tempDir);
        outputFile.setExecutable(true);
        assertThrows(
            DownloadException.class,
            () -> new FilePermissions("-x", Mockito.mock(Log.class)).applyTo(outputFile)
        );
    }
}
