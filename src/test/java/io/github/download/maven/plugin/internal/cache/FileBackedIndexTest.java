package io.github.download.maven.plugin.internal.cache;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.download.maven.plugin.internal.TestFiles.newFolder;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link FileBackedIndex}.
 */
public class FileBackedIndexTest {

    private static final URI FIRST = URI.create("http://example.invalid/first");

    private static final URI SECOND = URI.create("http://example.invalid/second");

    @TempDir
    public Path tempDir;

    private File dir;

    private Log log;

    @BeforeEach
    public void setUp() throws Exception {
        this.dir = newFolder(this.tempDir, "cache");
        this.log = mock(Log.class);
    }

    @Test
    public void emptyIndexReturnsNull() {
        assertThat(new FileBackedIndex(this.dir, this.log).get(FIRST), is(nullValue()));
    }

    @Test
    public void entriesWrittenByOneInstanceAreMergedIntoAnother() {
        final FileBackedIndex first = new FileBackedIndex(this.dir, this.log);
        final FileBackedIndex second = new FileBackedIndex(this.dir, this.log);
        first.put(FIRST, "first-file");
        second.put(SECOND, "second-file");
        assertThat(first.get(SECOND), is("second-file"));
        assertThat(second.get(FIRST), is("first-file"));
    }

    @Test
    public void corruptedIndexIsReportedOnRead() throws Exception {
        corruptIndex();
        assertThat(new FileBackedIndex(this.dir, this.log).get(FIRST), is(nullValue()));
        verify(this.log).warn(startsWith("Error while reading from cache"));
    }

    @Test
    public void corruptedIndexIsRewrittenOnWrite() throws Exception {
        corruptIndex();
        new FileBackedIndex(this.dir, this.log).put(FIRST, "first-file");
        verify(this.log).warn("Could not load index cache index file, it will be rewritten.");
        assertThat(new FileBackedIndex(this.dir, mock(Log.class)).get(FIRST), is("first-file"));
    }

    private void corruptIndex() throws Exception {
        Files.write(
            new File(this.dir, "index.ser").toPath(),
            "definitely not a serialized map".getBytes(StandardCharsets.UTF_8)
        );
    }
}
