package io.github.download.maven.plugin.internal.cache;

import io.github.download.maven.plugin.internal.checksum.Checksums;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import org.apache.commons.codec.digest.DigestUtils;
import java.io.IOException;
import org.apache.maven.plugin.logging.Log;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static io.github.download.maven.plugin.internal.TestFiles.read;
import static io.github.download.maven.plugin.internal.TestFiles.write;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link DownloadCache}.
 */
public class DownloadCacheTest {

    private static final URI URI_UNDER_TEST = URI.create("http://example.invalid/file.txt");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Log log;

    private Checksums noChecksums;

    private File source;

    @Before
    public void setUp() throws Exception {
        this.log = mock(Log.class);
        this.noChecksums = new Checksums(null, null, null, null, this.log);
        this.source = this.temporaryFolder.newFile("file.txt");
        write(this.source, "v1");
    }

    @Test
    public void unknownUriIsNotCached() throws Exception {
        final DownloadCache cache = new DownloadCache(this.temporaryFolder.newFolder("cache"), this.log);
        assertThat(cache.getArtifact(URI_UNDER_TEST, this.noChecksums), is(nullValue()));
    }

    @Test
    public void installCreatesMissingCacheDirectory() throws Exception {
        final File dir = new File(this.temporaryFolder.getRoot(), "a/b/cache");
        final DownloadCache cache = new DownloadCache(dir, this.log);
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        final File cached = cache.getArtifact(URI_UNDER_TEST, this.noChecksums);
        assertThat(cached, is(notNullValue()));
        assertThat(read(cached), is("v1"));
    }

    @Test
    public void installFailsIfCacheDirectoryCannotBeCreated() throws Exception {
        final File blocker = this.temporaryFolder.newFile("blocker");
        final DownloadCache cache = new DownloadCache(new File(blocker, "cache"), this.log);
        final IOException error = assertThrows(
            IOException.class,
            () -> cache.install(URI_UNDER_TEST, this.source, this.noChecksums)
        );
        assertThat(error.getMessage(), containsString("Could not create cache directory"));
    }

    @Test
    public void cachedEntryNotMatchingChecksumIsIgnored() throws Exception {
        final DownloadCache cache = new DownloadCache(this.temporaryFolder.newFolder("cache"), this.log);
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        final Checksums other = new Checksums(DigestUtils.md5Hex("v2"), null, null, null, this.log);
        assertThat(cache.getArtifact(URI_UNDER_TEST, other), is(nullValue()));
    }

    @Test
    public void installReplacesEntryWhoseFileWasDeleted() throws Exception {
        final DownloadCache cache = new DownloadCache(this.temporaryFolder.newFolder("cache"), this.log);
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        Files.delete(cache.getArtifact(URI_UNDER_TEST, this.noChecksums).toPath());
        assertThat(cache.getArtifact(URI_UNDER_TEST, this.noChecksums), is(nullValue()));
        write(this.source, "v2");
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        assertThat(read(cache.getArtifact(URI_UNDER_TEST, this.noChecksums)), is("v2"));
    }

    @Test
    public void installKeepsExistingValidEntry() throws Exception {
        final DownloadCache cache = new DownloadCache(this.temporaryFolder.newFolder("cache"), this.log);
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        write(this.source, "v2");
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        assertThat(read(cache.getArtifact(URI_UNDER_TEST, this.noChecksums)), is("v1"));
    }

    @Test
    public void entriesArePersistedAcrossInstances() throws Exception {
        final File dir = this.temporaryFolder.newFolder("cache");
        new DownloadCache(dir, this.log).install(URI_UNDER_TEST, this.source, this.noChecksums);
        final File cached = new DownloadCache(dir, this.log).getArtifact(URI_UNDER_TEST, this.noChecksums);
        assertThat(read(cached), is("v1"));
    }
}
