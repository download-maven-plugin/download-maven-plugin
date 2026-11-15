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
package io.github.download.maven.plugin.internal.cache;

import java.io.File;
import java.io.ObjectInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Reproduces issue #188: several Maven processes sharing one download cache
 * concurrently update {@code index.ser}. Every process must be able to update the
 * index without failures and no entry may be lost.
 */
public class FileBackedIndexMultiProcessTest {

    private static final int PROCESSES = 6;

    private static final int ENTRIES = 50;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void concurrentProcessesDoNotCorruptOrLoseIndexEntries() throws Exception {
        final File dir = this.temporaryFolder.newFolder("cache");
        final String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        final List<Process> processes = new ArrayList<>();
        for (int id = 0; id < PROCESSES; ++id) {
            processes.add(
                new ProcessBuilder(
                    java, "-cp", System.getProperty("java.class.path"),
                    Worker.class.getName(), dir.getAbsolutePath(), String.valueOf(id)
                ).redirectErrorStream(true).redirectOutput(this.log(id)).start()
            );
        }
        final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1L);
        final List<String> failures = new ArrayList<>();
        for (int id = 0; id < PROCESSES; ++id) {
            final Process process = processes.get(id);
            if (!process.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                failures.add("worker " + id + " timed out");
            } else if (process.exitValue() != 0) {
                failures.add(
                    "worker " + id + " failed:\n"
                        + new String(Files.readAllBytes(this.log(id).toPath()), StandardCharsets.UTF_8)
                );
            }
        }
        assertThat(String.join("\n", failures), failures, is(empty()));
        final Map<URI, String> index;
        try (ObjectInputStream stream = new ObjectInputStream(
            Files.newInputStream(new File(dir, "index.ser").toPath())
        )) {
            @SuppressWarnings("unchecked")
            final Map<URI, String> read = (Map<URI, String>) stream.readObject();
            index = read;
        }
        final List<URI> missing = new ArrayList<>();
        for (int id = 0; id < PROCESSES; ++id) {
            for (int entry = 0; entry < ENTRIES; ++entry) {
                final URI uri = Worker.uri(id, entry);
                if (!index.containsKey(uri)) {
                    missing.add(uri);
                }
            }
        }
        assertThat(
            String.format("%d of %d entries lost", missing.size(), PROCESSES * ENTRIES),
            missing, is(empty())
        );
    }

    private File log(final int id) {
        return new File(this.temporaryFolder.getRoot(), "worker-" + id + ".log");
    }

    /**
     * Child process: emulates one Maven build putting entries into the shared cache.
     */
    public static final class Worker {

        public static void main(final String... args) {
            final int id = Integer.parseInt(args[1]);
            final FileBackedIndex index = new FileBackedIndex(new File(args[0]), new SystemStreamLog());
            for (int entry = 0; entry < ENTRIES; ++entry) {
                index.put(uri(id, entry), "file-" + id + "-" + entry);
            }
        }

        static URI uri(final int id, final int entry) {
            return URI.create(String.format("http://example.invalid/%d/%d", id, entry));
        }
    }
}
