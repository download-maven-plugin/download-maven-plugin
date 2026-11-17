package io.github.download.maven.plugin.internal.cache;

import java.io.File;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.apache.maven.plugin.logging.Log;

/**
 * Download caches whose files are all in use, as on Windows while other builds read them.
 */
public final class InUseCaches {

    private InUseCaches() {
    }

    /**
     * Creates a cache in which new files can be created, but existing ones can't be replaced.
     * @param directory Cache directory
     * @param log Logger
     * @return Cache
     */
    public static DownloadCache create(final File directory, final Log log) {
        return new DownloadCache(
            directory, log,
            (source, target) -> {
                if (Files.exists(target)) {
                    throw new FileSystemException(
                        source.toString(), target.toString(),
                        "The process cannot access the file because it is being used by another process"
                    );
                }
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        );
    }
}
