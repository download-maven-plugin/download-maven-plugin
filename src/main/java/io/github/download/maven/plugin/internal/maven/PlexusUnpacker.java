/*
 * Copyright 2009-2018 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.github.download.maven.plugin.internal.maven;

import io.github.download.maven.plugin.internal.DownloadException;
import io.github.download.maven.plugin.internal.Unpacker;
import java.io.File;
import javax.annotation.Nullable;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.bzip2.BZip2UnArchiver;
import org.codehaus.plexus.archiver.gzip.GZipUnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.archiver.snappy.SnappyUnArchiver;
import org.codehaus.plexus.archiver.xz.XZUnArchiver;
import org.codehaus.plexus.components.io.filemappers.FileMapper;
import org.codehaus.plexus.components.io.fileselectors.FileSelector;
import org.codehaus.plexus.components.io.fileselectors.IncludeExcludeFileSelector;

/**
 * {@link Unpacker} backed by Plexus Archiver.
 */
public final class PlexusUnpacker implements Unpacker {

    /**
     * Selects the unarchiver by file extension.
     */
    private final ArchiverManager archivers;

    /**
     * Renames extracted entries, may be {@literal null}.
     */
    private final FileMapper[] mappers;

    /**
     * Patterns of entries to extract.
     */
    private final String[] includes;

    /**
     * Patterns of entries to skip.
     */
    private final String[] excludes;

    /**
     * Constructor.
     * @param archivers Selects the unarchiver by file extension
     * @param mappers Renames extracted entries, may be {@literal null}
     * @param includes Patterns of entries to extract, {@literal null} or empty for all
     * @param excludes Patterns of entries to skip, {@literal null} or empty for none
     */
    public PlexusUnpacker(
        final ArchiverManager archivers, @Nullable final FileMapper[] mappers,
        @Nullable final String[] includes, @Nullable final String[] excludes
    ) {
        this.archivers = archivers;
        this.mappers = PlexusUnpacker.copy(mappers);
        this.includes = PlexusUnpacker.orEmpty(includes);
        this.excludes = PlexusUnpacker.orEmpty(excludes);
    }

    @Override
    public void unpack(final File source, final File outputDirectory, final String outputFileName)
        throws DownloadException {
        final UnArchiver unarchiver;
        try {
            unarchiver = this.archivers.getUnArchiver(new File(outputDirectory, outputFileName));
        } catch (final NoSuchArchiverException ex) {
            throw new DownloadException(String.format("No such archiver: %s)", ex.getMessage()));
        }
        unarchiver.setSourceFile(source);
        if (PlexusUnpacker.isFileUnArchiver(unarchiver)) {
            unarchiver.setDestFile(
                new File(outputDirectory, outputFileName.substring(0, outputFileName.lastIndexOf('.')))
            );
        } else {
            unarchiver.setDestDirectory(outputDirectory);
        }
        unarchiver.setFileMappers(this.mappers);
        if (this.includes.length != 0 || this.excludes.length != 0) {
            final IncludeExcludeFileSelector selector = new IncludeExcludeFileSelector();
            if (this.includes.length != 0) {
                selector.setIncludes(this.includes);
            }
            if (this.excludes.length != 0) {
                selector.setExcludes(this.excludes);
            }
            unarchiver.setFileSelectors(new FileSelector[]{selector});
        }
        unarchiver.extract();
    }

    /**
     * Whether the unarchiver decompresses a single file rather than extracting entries.
     * @param unarchiver Unarchiver
     * @return True for single-file formats (bzip2, gzip, snappy, xz)
     */
    private static boolean isFileUnArchiver(final UnArchiver unarchiver) {
        return unarchiver instanceof BZip2UnArchiver
            || unarchiver instanceof GZipUnArchiver
            || unarchiver instanceof SnappyUnArchiver
            || unarchiver instanceof XZUnArchiver;
    }

    /**
     * Copies an array of patterns.
     * @param patterns Patterns, may be {@literal null}
     * @return Copy, empty if {@literal null}
     */
    private static String[] orEmpty(@Nullable final String[] patterns) {
        final String[] result;
        if (patterns == null) {
            result = new String[0];
        } else {
            result = patterns.clone();
        }
        return result;
    }

    /**
     * Copies an array of file mappers.
     * @param mappers File mappers, may be {@literal null}
     * @return Copy, or {@literal null}
     */
    @Nullable
    private static FileMapper[] copy(@Nullable final FileMapper[] mappers) {
        final FileMapper[] result;
        if (mappers == null) {
            result = null;
        } else {
            result = mappers.clone();
        }
        return result;
    }
}
