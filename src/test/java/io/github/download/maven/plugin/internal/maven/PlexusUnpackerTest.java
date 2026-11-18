package io.github.download.maven.plugin.internal.maven;

import io.github.download.maven.plugin.internal.DownloadException;
import io.github.download.maven.plugin.internal.TestFiles;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.codehaus.plexus.archiver.gzip.GZipUnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.download.maven.plugin.internal.TestFiles.newFile;
import static io.github.download.maven.plugin.internal.TestFiles.newFolder;
import static io.github.download.maven.plugin.internal.TestFiles.read;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlexusUnpacker} with real Plexus unarchivers.
 */
public class PlexusUnpackerTest {

    @TempDir
    public Path tempDir;

    private ArchiverManager archivers;

    private File outputDirectory;

    @BeforeEach
    public void setUp() throws Exception {
        this.archivers = mock(ArchiverManager.class);
        this.outputDirectory = newFolder(this.tempDir, "out");
    }

    @Test
    public void extractsArchiveEntries() throws Exception {
        final File zip = zip();
        new PlexusUnpacker(this.archivers, null, null, null).unpack(zip, this.outputDirectory, "archive.zip");
        assertThat(read(new File(this.outputDirectory, "a.txt")), is("a"));
        assertThat(read(new File(this.outputDirectory, "dir/b.txt")), is("b"));
    }

    @Test
    public void extractsOnlyIncludedAndNotExcludedEntries() throws Exception {
        final File zip = zip();
        new PlexusUnpacker(this.archivers, null, new String[]{"**/*.txt"}, new String[]{"dir/**"})
            .unpack(zip, this.outputDirectory, "archive.zip");
        assertThat(new File(this.outputDirectory, "a.txt").exists(), is(true));
        assertThat(new File(this.outputDirectory, "dir/b.txt").exists(), is(false));
    }

    @Test
    public void decompressesSingleFileArchiveNextToIt() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write("plain".getBytes(StandardCharsets.UTF_8));
        }
        final File source = newFile(this.tempDir, "source.gz");
        Files.write(source.toPath(), bytes.toByteArray());
        when(this.archivers.getUnArchiver(any(File.class))).thenAnswer(inv -> new GZipUnArchiver());

        new PlexusUnpacker(this.archivers, null, new String[0], new String[0])
            .unpack(source, this.outputDirectory, "data.txt.gz");
        assertThat(read(new File(this.outputDirectory, "data.txt")), is("plain"));
    }

    @Test
    public void unknownArchiveTypeFails() throws Exception {
        when(this.archivers.getUnArchiver(any(File.class))).thenThrow(new NoSuchArchiverException("bin"));
        final DownloadException error = assertThrows(
            DownloadException.class,
            () -> new PlexusUnpacker(this.archivers, null, null, null)
                .unpack(newFile(this.tempDir), this.outputDirectory, "file.bin")
        );
        assertThat(error.getMessage(), startsWith("No such archiver"));
    }

    private File zip() throws Exception {
        final File zip = newFile(this.tempDir, "source.zip");
        Files.write(zip.toPath(), TestFiles.zip("a.txt", "a", "dir/b.txt", "b"));
        when(this.archivers.getUnArchiver(any(File.class))).thenAnswer(inv -> new ZipUnArchiver());
        return zip;
    }
}
