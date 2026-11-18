package io.github.download.maven.plugin.internal.cache;

import io.github.download.maven.plugin.internal.checksum.Checksums;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.codec.digest.DigestUtils;
import java.io.IOException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.download.maven.plugin.internal.TestFiles.newFile;
import static io.github.download.maven.plugin.internal.TestFiles.newFolder;
import static io.github.download.maven.plugin.internal.TestFiles.read;
import static io.github.download.maven.plugin.internal.TestFiles.write;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link DownloadCache}.
 */
public class DownloadCacheTest {

    private static final URI URI_UNDER_TEST = URI.create("http://example.invalid/file.txt");

    @TempDir
    public Path tempDir;

    private Log log;

    private Checksums noChecksums;

    private File source;

    @BeforeEach
    public void setUp() throws Exception {
        this.log = mock(Log.class);
        this.noChecksums = new Checksums(null, null, null, null, this.log);
        this.source = newFile(this.tempDir, "file.txt");
        write(this.source, "v1");
    }

    @Test
    public void unknownUriIsNotCached() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        assertThat(cache.getArtifact(URI_UNDER_TEST, this.noChecksums), is(nullValue()));
    }

    @Test
    public void installCreatesMissingCacheDirectory() throws Exception {
        final File dir = new File(this.tempDir.toFile(), "a/b/cache");
        final DownloadCache cache = new DownloadCache(dir, this.log);
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        final File cached = cache.getArtifact(URI_UNDER_TEST, this.noChecksums);
        assertThat(cached, is(notNullValue()));
        assertThat(read(cached), is("v1"));
    }

    @Test
    public void installFailsIfCacheDirectoryCannotBeCreated() throws Exception {
        final File blocker = newFile(this.tempDir, "blocker");
        final DownloadCache cache = new DownloadCache(new File(blocker, "cache"), this.log);
        final IOException error = assertThrows(
            IOException.class,
            () -> cache.install(URI_UNDER_TEST, this.source, this.noChecksums)
        );
        assertThat(error.getMessage(), containsString("Could not create cache directory"));
    }

    @Test
    public void cachedEntryNotMatchingChecksumIsIgnored() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        final Checksums other = new Checksums(DigestUtils.md5Hex("v2"), null, null, null, this.log);
        assertThat(cache.getArtifact(URI_UNDER_TEST, other), is(nullValue()));
    }

    @Test
    public void installReplacesEntryWhoseFileWasDeleted() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        Files.delete(cache.getArtifact(URI_UNDER_TEST, this.noChecksums).toPath());
        assertThat(cache.getArtifact(URI_UNDER_TEST, this.noChecksums), is(nullValue()));
        write(this.source, "v2");
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        assertThat(read(cache.getArtifact(URI_UNDER_TEST, this.noChecksums)), is("v2"));
    }

    @Test
    public void installKeepsExistingValidEntry() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        write(this.source, "v2");
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        assertThat(read(cache.getArtifact(URI_UNDER_TEST, this.noChecksums)), is("v1"));
    }

    @Test
    public void entriesArePersistedAcrossInstances() throws Exception {
        final File dir = newFolder(this.tempDir, "cache");
        new DownloadCache(dir, this.log).install(URI_UNDER_TEST, this.source, this.noChecksums);
        final File cached = new DownloadCache(dir, this.log).getArtifact(URI_UNDER_TEST, this.noChecksums);
        assertThat(read(cached), is("v1"));
    }

    // ---------------------------------------------------------------- store and validators

    @Test
    public void storeCachesNewFileWithValidators() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        final Validators validators = new Validators("\"v1\"", "Sat, 01 Jan 2000 00:00:00 GMT");
        assertThat(cache.store(URI_UNDER_TEST, this.source, this.noChecksums, validators), is(true));
        assertThat(read(cache.getArtifact(URI_UNDER_TEST, this.noChecksums)), is("v1"));
        assertThat(cache.validators(URI_UNDER_TEST), is(validators));
    }

    @Test
    public void storeReplacesChangedContentAndValidators() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        cache.store(URI_UNDER_TEST, this.source, this.noChecksums, new Validators("\"v1\"", null));
        write(this.source, "v2");
        final Validators second = new Validators("\"v2\"", null);
        assertThat(cache.store(URI_UNDER_TEST, this.source, this.noChecksums, second), is(true));
        assertThat(read(cache.getArtifact(URI_UNDER_TEST, this.noChecksums)), is("v2"));
        assertThat(cache.validators(URI_UNDER_TEST), is(second));
    }

    @Test
    public void storeReportsSameContentAsUnchangedButRecordsNewValidators() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        final Validators validators = new Validators(null, "Sat, 01 Jan 2000 00:00:00 GMT");
        assertThat(cache.store(URI_UNDER_TEST, this.source, this.noChecksums, validators), is(false));
        assertThat(cache.validators(URI_UNDER_TEST), is(validators));
    }

    @Test
    public void storeForgetsValidatorsTheServerNoLongerSends() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        cache.store(URI_UNDER_TEST, this.source, this.noChecksums, new Validators("\"v1\"", null));
        write(this.source, "v2");
        cache.store(URI_UNDER_TEST, this.source, this.noChecksums, Validators.NONE);
        assertThat(cache.validators(URI_UNDER_TEST), is(Validators.NONE));
    }

    @Test
    public void noValidatorsForUnknownUriOrFileCachedWithoutThem() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        assertThat(cache.validators(URI_UNDER_TEST), is(Validators.NONE));
        cache.install(URI_UNDER_TEST, this.source, this.noChecksums);
        assertThat(cache.validators(URI_UNDER_TEST), is(Validators.NONE));
    }

    @Test
    public void validatorsArePersistedAcrossInstances() throws Exception {
        final File dir = newFolder(this.tempDir, "cache");
        final Validators validators = new Validators("\"v1\"", null);
        new DownloadCache(dir, this.log).store(URI_UNDER_TEST, this.source, this.noChecksums, validators);
        assertThat(new DownloadCache(dir, this.log).validators(URI_UNDER_TEST), is(validators));
    }

    /**
     * A plugin version that doesn't know about validators may rewrite the cached file.
     * Validators of the previous content must not be sent for it.
     */
    @Test
    public void validatorsOfReplacedFileAreIgnored() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        cache.store(URI_UNDER_TEST, this.source, this.noChecksums, new Validators("\"v1\"", null));
        write(cache.getArtifact(URI_UNDER_TEST, this.noChecksums), "written by an older plugin version");
        assertThat(cache.validators(URI_UNDER_TEST), is(Validators.NONE));
    }

    @Test
    public void unreadableValidatorsAreIgnored() throws Exception {
        final DownloadCache cache = new DownloadCache(newFolder(this.tempDir, "cache"), this.log);
        cache.store(URI_UNDER_TEST, this.source, this.noChecksums, new Validators("\"v1\"", null));
        final File cached = cache.getArtifact(URI_UNDER_TEST, this.noChecksums);
        write(new File(cached.getPath() + ".meta"), "etag=\\u00zz");
        assertThat(cache.validators(URI_UNDER_TEST), is(Validators.NONE));
    }

    /**
     * On Windows, a cached file can't be replaced while another build reads it.
     */
    @Test
    public void cachedFileInUseKeepsItsContent() throws Exception {
        final File dir = newFolder(this.tempDir, "cache");
        final DownloadCache cache = InUseCaches.create(dir, this.log);
        final Validators validators = new Validators("\"v1\"", null);
        cache.store(URI_UNDER_TEST, this.source, this.noChecksums, validators);
        write(this.source, "v2");

        final CachedFileInUseException error = assertThrows(
            CachedFileInUseException.class,
            () -> cache.store(URI_UNDER_TEST, this.source, this.noChecksums, new Validators("\"v2\"", null))
        );
        assertThat(error.getMessage(), containsString("it is probably in use by another build"));
        assertThat(read(cache.getArtifact(URI_UNDER_TEST, this.noChecksums)), is("v1"));
        assertThat(cache.validators(URI_UNDER_TEST), is(validators));
        assertThat(dir.list((parent, name) -> name.endsWith(".part")).length, is(0));
    }

    /**
     * The index format is unchanged, so plugin versions that don't know about validators
     * can share the cache: validators live in a separate file.
     */
    @Test
    public void validatorsAreKeptOutOfTheIndex() throws Exception {
        final File dir = newFolder(this.tempDir, "cache");
        new DownloadCache(dir, this.log)
            .store(URI_UNDER_TEST, this.source, this.noChecksums, new Validators("\"v1\"", null));
        final FileBackedIndex index = new FileBackedIndex(dir, this.log);
        final String fileName = index.get(URI_UNDER_TEST);
        assertThat(read(new File(dir, fileName)), is("v1"));
        assertThat(new File(dir, fileName + ".meta").isFile(), is(true));
    }
}
