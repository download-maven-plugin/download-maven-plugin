package io.github.download.maven.plugin.internal.checksum;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.plugin.logging.Log;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link Checksums}.
 */
public class ChecksumsTest {

    private static final byte[] CONTENT = "Hello, world!".getBytes(StandardCharsets.UTF_8);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File file;

    private Log log;

    @Before
    public void setUp() throws Exception {
        this.file = this.temporaryFolder.newFile();
        Files.write(this.file.toPath(), CONTENT);
        this.log = mock(Log.class);
    }

    @Test
    public void noChecksumsAlwaysValid() throws Exception {
        final Checksums checksums = new Checksums(null, null, null, null, this.log);
        checksums.validate(this.file);
        assertThat(checksums.isValid(this.file), is(true));
        verify(this.log).debug("No checksums were supplied, skipping file validation");
    }

    @Test
    public void singleChecksumDoesNotWarn() {
        final Checksums checksums = new Checksums(DigestUtils.md5Hex(CONTENT), null, null, null, this.log);
        assertThat(checksums.isValid(this.file), is(true));
        verify(this.log, never()).warn(anyString());
    }

    @Test
    public void allMatchingChecksumsAreValid() {
        final Checksums checksums = new Checksums(
            DigestUtils.md5Hex(CONTENT), DigestUtils.sha1Hex(CONTENT),
            DigestUtils.sha256Hex(CONTENT), DigestUtils.sha512Hex(CONTENT), this.log
        );
        assertThat(checksums.isValid(this.file), is(true));
    }

    @Test
    public void warnsWhenMoreThanOneChecksumIsSupplied() {
        new Checksums(DigestUtils.md5Hex(CONTENT), DigestUtils.sha1Hex(CONTENT), null, null, this.log);
        verify(this.log).warn(
            "More than one checksum is supplied. This may be slow for big files. Consider using a single checksum."
        );
    }

    @Test
    public void oneMismatchingChecksumInvalidatesFile() {
        final Checksums checksums = new Checksums(
            DigestUtils.md5Hex(CONTENT), null, DigestUtils.sha256Hex("other"), null, this.log
        );
        assertThat(checksums.isValid(this.file), is(false));
        final ChecksumMismatchException error = assertThrows(
            ChecksumMismatchException.class, () -> checksums.validate(this.file)
        );
        assertThat(error.getMessage(), containsString(DigestUtils.sha256Hex(CONTENT)));
    }

    @Test
    public void missingFileIsInvalid() {
        final Checksums checksums = new Checksums(DigestUtils.md5Hex(CONTENT), null, null, null, this.log);
        assertThat(checksums.isValid(new File(this.temporaryFolder.getRoot(), "absent")), is(false));
    }
}
