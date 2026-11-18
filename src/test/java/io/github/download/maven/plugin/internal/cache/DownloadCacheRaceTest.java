package io.github.download.maven.plugin.internal.cache;

import io.github.download.maven.plugin.internal.checksum.Checksums;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.download.maven.plugin.internal.TestFiles.newFile;
import static io.github.download.maven.plugin.internal.TestFiles.newFolder;
import static io.github.download.maven.plugin.internal.TestFiles.read;
import static io.github.download.maven.plugin.internal.TestFiles.write;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isOneOf;
import static org.mockito.Mockito.mock;

/**
 * Two builds in separate JVMs share the cache directory, but not the in-memory index lock.
 * Two {@link DownloadCache} instances on the same directory behave the same way.
 */
public class DownloadCacheRaceTest {

    private static final URI URI_UNDER_TEST = URI.create("http://example.invalid/latest/tool.zip");

    @TempDir
    public Path tempDir;

    /**
     * Build A downloaded the new version v2, build B the old version v1 (it started earlier,
     * before the server switched, but finished later). B stores entirely between A's content
     * move and A's {@code .meta} write: the cache ends up with v1's content and v2's ETag.
     * Every later revalidation sends {@code If-None-Match: "v2"}, the server answers 304,
     * and the stale v1 is served forever. The cache must rather drop the validators,
     * which only costs one download.
     */
    @Test
    public void validatorsOfInterleavedBuildOfAnotherSizeAreDropped() throws Exception {
        assertValidatorsBelongToCachedContent("v1, a version of another size");
    }

    @Test
    public void validatorsOfInterleavedBuildOfSameSizeAreDropped() throws Exception {
        assertValidatorsBelongToCachedContent("v1");
    }

    /**
     * Lets build B store its version in the middle of build A storing "v2".
     * @param versionOfB Content of B's version, starting with "v1"
     * @throws Exception If the cache fails
     */
    private void assertValidatorsBelongToCachedContent(final String versionOfB) throws Exception {
        final Log log = mock(Log.class);
        final Checksums none = new Checksums(null, null, null, null, log);
        final File dir = newFolder(this.tempDir, "cache");
        final File old = newFile(this.tempDir, "old");
        write(old, "v0");
        new DownloadCache(dir, log).store(URI_UNDER_TEST, old, none, new Validators("\"v0\"", null));

        final DownloadCache buildB = new DownloadCache(dir, log);
        final File fromB = newFile(this.tempDir, "from-b");
        write(fromB, versionOfB);
        final AtomicBoolean interleaved = new AtomicBoolean();
        final DownloadCache buildA = new DownloadCache(
            dir, log,
            (source, target) -> {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                if (!target.toString().endsWith(".meta") && interleaved.compareAndSet(false, true)) {
                    buildB.store(URI_UNDER_TEST, fromB, none, new Validators("\"v1\"", null));
                }
            }
        );
        final File fromA = newFile(this.tempDir, "from-a");
        write(fromA, "v2");
        buildA.store(URI_UNDER_TEST, fromA, none, new Validators("\"v2\"", null));

        final DownloadCache later = new DownloadCache(dir, log);
        final String content = read(later.getArtifact(URI_UNDER_TEST, none));
        final Validators validators = later.validators(URI_UNDER_TEST);
        assertThat(content, is(versionOfB));
        assertThat(
            String.format("cached content '%s' is described by %s", content, validators),
            validators.etag().orElse("none"),
            isOneOf("\"v1\"", "none")
        );
    }
}
