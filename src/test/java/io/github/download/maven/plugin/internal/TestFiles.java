package io.github.download.maven.plugin.internal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * File helpers for tests. All text is UTF-8.
 */
public final class TestFiles {

    private TestFiles() {
    }

    /**
     * Creates an empty file with a unique name in the directory.
     */
    public static File newFile(final Path dir) throws IOException {
        return Files.createTempFile(dir, "junit", null).toFile();
    }

    /**
     * Creates an empty file in the directory, failing if it already exists.
     */
    public static File newFile(final Path dir, final String name) throws IOException {
        return Files.createFile(dir.resolve(name)).toFile();
    }

    /**
     * Creates a subdirectory, failing if it already exists.
     */
    public static File newFolder(final Path dir, final String name) throws IOException {
        return Files.createDirectory(dir.resolve(name)).toFile();
    }

    /**
     * Reads the whole file as text, line breaks included.
     */
    public static String read(final File file) throws IOException {
        if (file == null) {
            throw new AssertionError("expected a file, got null");
        }
        return read(file.toPath());
    }

    /**
     * Reads the whole file as text, line breaks included.
     */
    public static String read(final Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * Replaces the file content with the text.
     */
    public static void write(final File file, final String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a zip archive.
     * @param entries Alternating entry names and contents, e.g. {@code "a.txt", "a", "dir/b.txt", "b"}
     * @return Archive bytes
     */
    public static byte[] zip(final String... entries) throws IOException {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("expected name/content pairs");
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int idx = 0; idx < entries.length; idx += 2) {
                zip.putNextEntry(new ZipEntry(entries[idx]));
                zip.write(entries[idx + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
