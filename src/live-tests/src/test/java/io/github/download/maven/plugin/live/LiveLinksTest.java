package io.github.download.maven.plugin.live;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Runs a build of download-maven-plugin against the real-world links of {@code links.txt}.
 *
 * <p>Every link is one test. The plugin is executed the way users execute it - by Maven, from a
 * generated {@code pom.xml} - in up to four steps:</p>
 * <ol>
 *   <li><b>download</b>: online, empty cache; the file must arrive (and match {@code sha256} if
 *       the link has one);</li>
 *   <li><b>cache</b>: output deleted, Maven offline; the same file must be restored from the
 *       cache;</li>
 *   <li><b>unpack</b> (archives only): Maven offline; the archive must be unpacked.</li>
 *   <li><b>update</b>: output deleted, online, {@code updatePolicy=always}; the plugin asks the
 *       server whether the cached file is still current. Links marked {@code revalidates} must
 *       answer "not modified", so nothing is downloaded again.</li>
 * </ol>
 */
@RunWith(Parameterized.class)
public class LiveLinksTest {

    private static final String GROUP_ID = "io.github.download-maven-plugin";

    private static final String ARTIFACT_ID = "download-maven-plugin";

    private static final File BASE_DIR = new File(System.getProperty("live.baseDir", "."));

    private static final File WORK_DIR = new File(System.getProperty("live.workDir", "target/live"));

    private static final Path REPORT = WORK_DIR.toPath().resolve("report.txt");

    private static final String VERSION = pluginVersion();

    /**
     * Seconds between progress lines of a running build.
     */
    private static final long HEARTBEAT_SECONDS = 15L;

    /**
     * Number of links to test.
     */
    private static int TOTAL;

    /**
     * Number of links tested so far, including the current one.
     */
    private static final AtomicInteger STARTED = new AtomicInteger();

    private final Link link;

    public LiveLinksTest(final String name, final Link link) {
        this.link = link;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> links() throws IOException {
        final Pattern only = Pattern.compile(System.getProperty("live.only", ".*"), Pattern.CASE_INSENSITIVE);
        try (Stream<String> lines = Files.lines(new File(BASE_DIR, "links.txt").toPath(), StandardCharsets.UTF_8)) {
            final List<Object[]> result = lines.map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(Link::parse)
                .filter(link -> only.matcher(link.label()).find())
                .map(link -> new Object[] {link.label(), link})
                .collect(Collectors.toList());
            TOTAL = result.size();
            return result;
        }
    }

    @BeforeClass
    public static void startReport() throws IOException {
        Files.deleteIfExists(REPORT);
    }

    @Test
    public void downloadsCachesAndUnpacks() throws Exception {
        final int maxMb = Integer.getInteger("live.maxMb", Integer.MAX_VALUE);
        progress(
            String.format(
                "[%2d/%d] %s (~%d MB)%s", STARTED.incrementAndGet(), TOTAL, this.link.label(), this.link.approxMb,
                this.link.approxMb <= maxMb ? "" : " - skipped, above -Dlive.maxMb=" + maxMb
            )
        );
        Assume.assumeTrue(
            String.format("%s is ~%d MB, above -Dlive.maxMb=%d", this.link.name, this.link.approxMb, maxMb),
            this.link.approxMb <= maxMb
        );
        final Path dir = WORK_DIR.toPath().resolve(this.link.label().replaceAll("[^A-Za-z0-9.-]+", "_"));
        deleteRecursively(dir, false);
        Files.createDirectories(dir);
        boolean passed = false;
        final long begin = System.currentTimeMillis();
        try {
            final long start = System.currentTimeMillis();
            final Path downloaded = this.download(dir);
            final long size = Files.size(downloaded);
            final String sha256 = sha256(downloaded);
            final long millis = System.currentTimeMillis() - start;
            progress(String.format("        download  %s, %s", megabytes(size), seconds(start)));
            this.restoreFromCache(dir, downloaded, sha256);
            if (this.link.unpack) {
                this.unpack(dir);
            }
            String update = "skipped";
            if (!"false".equals(System.getProperty("live.update"))) {
                update = this.update(dir, downloaded);
            }
            report(
                String.format(
                    "%s | %d bytes | %d ms | sha256=%s | update: %s", this.link.label(), size, millis, sha256, update
                )
            );
            passed = true;
            progress(String.format("        PASSED in %s", seconds(begin)));
        } catch (final AssertionError | Exception ex) {
            progress(String.format("        FAILED in %s: %s", seconds(begin), firstLine(ex.getMessage())));
            throw ex;
        } finally {
            if (!Boolean.getBoolean("live.keep")) {
                deleteRecursively(dir, !passed);
            }
        }
    }

    /**
     * Step 1: online build with an empty cache.
     */
    private Path download(final Path dir) throws Exception {
        final Path output = dir.resolve("output");
        this.run(dir, "1-download", false, output, this.checksum());
        final List<Path> files = list(output);
        Assert.assertEquals("download: expected exactly one file in " + output + " but got " + files, 1, files.size());
        final Path file = files.get(0);
        if (this.link.outputFileName != null) {
            Assert.assertEquals("download: output file name", this.link.outputFileName, file.getFileName().toString());
        }
        Assert.assertTrue("download: " + file + " is empty", Files.size(file) > 0L);
        return file;
    }

    /**
     * Step 2: the output is gone and Maven is offline, so the file can only come from the cache.
     */
    private void restoreFromCache(final Path dir, final Path downloaded, final String sha256) throws Exception {
        final long start = System.currentTimeMillis();
        Files.delete(downloaded);
        this.run(dir, "2-cache", true, downloaded.getParent(), this.checksum());
        Assert.assertTrue("cache: " + downloaded + " was not restored from the cache", Files.isRegularFile(downloaded));
        Assert.assertEquals("cache: restored file differs from the downloaded one", sha256, sha256(downloaded));
        progress(String.format("        cache     restored offline, %s", seconds(start)));
    }

    /**
     * Step 3: unpack the cached archive, still offline.
     */
    private void unpack(final Path dir) throws Exception {
        final long start = System.currentTimeMillis();
        final Path unpacked = dir.resolve("unpacked");
        this.run(dir, "3-unpack", true, unpacked, this.checksum() + element("unpack", "true"));
        Assert.assertFalse("unpack: nothing was unpacked into " + unpacked, list(unpacked).isEmpty());
        progress(String.format("        unpack    %d entries, %s", list(unpacked).size(), seconds(start)));
    }

    /**
     * Step 4: the file is cached, the output is gone and the plugin has to ask the server
     * whether the cached file is still current.
     * @return What the plugin found out
     */
    private String update(final Path dir, final Path downloaded) throws Exception {
        final long start = System.currentTimeMillis();
        Files.delete(downloaded);
        // no checksum: it pins the content, so the plugin would not ask the server
        final Path log = this.run(dir, "4-update", false, downloaded.getParent(), element("updatePolicy", "always"));
        Assert.assertTrue("update: " + downloaded + " is missing", Files.isRegularFile(downloaded));
        Assert.assertTrue("update: " + downloaded + " is empty", Files.size(downloaded) > 0L);
        final String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        final String outcome;
        if (text.contains("Cached file is up to date: not modified on the server")) {
            outcome = "not modified";
        } else if (text.contains("Cached file is up to date: the server sent the same content")) {
            outcome = "downloaded again, same content";
        } else if (text.contains("Cached file was outdated: downloaded the current version")) {
            outcome = "downloaded again, new content";
        } else if (text.contains("for a newer version, using the cached file")) {
            outcome = "server could not be asked";
        } else {
            outcome = "not checked";
        }
        progress(String.format("        update    %s, %s", outcome, seconds(start)));
        if (this.link.revalidates) {
            Assert.assertEquals(
                "update: the server was expected to answer 304 Not Modified, see " + log, "not modified", outcome
            );
        } else {
            Assert.assertTrue(
                "update: the plugin did not ask the server, see " + log, outcome.startsWith("downloaded again")
                    || "not modified".equals(outcome)
            );
        }
        return outcome;
    }

    private Path run(
        final Path dir, final String step, final boolean offline, final Path outputDirectory, final String extra
    ) throws Exception {
        final Path pom = dir.resolve("pom.xml");
        Files.write(pom, this.pom(dir, outputDirectory, extra).getBytes(StandardCharsets.UTF_8));
        final Path log = dir.resolve(step + ".log");
        final InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pom.toFile());
        request.setBaseDirectory(dir.toFile());
        request.setGoals(Collections.singletonList(String.format("%s:%s:%s:wget", GROUP_ID, ARTIFACT_ID, VERSION)));
        request.setBatchMode(true);
        request.setOffline(offline);
        request.setTimeoutInSeconds(60 * Integer.getInteger("live.timeoutMinutes", 30));
        final Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(new File(System.getProperty("live.mavenHome", System.getProperty("maven.home", ""))));
        final String repository = System.getProperty("live.localRepository", "");
        if (!repository.isEmpty()) {
            invoker.setLocalRepositoryDirectory(new File(repository));
        }
        final InvocationResult result;
        final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        final long start = System.currentTimeMillis();
        heartbeat.scheduleAtFixedRate(
            () -> progress(
                String.format(
                    "        %-9s still running, %s, %s so far", step.substring(2), seconds(start),
                    megabytes(sizeOf(outputDirectory))
                )
            ),
            HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS
        );
        try (PrintStream out = new PrintStream(Files.newOutputStream(log), true, "UTF-8")) {
            request.setOutputHandler(out::println);
            request.setErrorHandler(out::println);
            result = invoker.execute(request);
        } finally {
            heartbeat.shutdownNow();
        }
        if (result.getExitCode() != 0 || result.getExecutionException() != null) {
            Assert.fail(
                String.format(
                    "%s: Maven build failed (exit code %d%s) for %s%nLog: %s%n%s",
                    step.substring(2), result.getExitCode(),
                    result.getExecutionException() == null ? "" : ", " + result.getExecutionException().getMessage(),
                    this.link.url, log, tail(log)
                )
            );
        }
        return log;
    }

    private String pom(final Path dir, final Path outputDirectory, final String extra) {
        final StringBuilder config = new StringBuilder();
        config.append(element("url", this.link.url));
        config.append(element("outputDirectory", outputDirectory.toAbsolutePath().toString()));
        config.append(element("cacheDirectory", dir.resolve("cache").toAbsolutePath().toString()));
        if (this.link.outputFileName != null) {
            config.append(element("outputFileName", this.link.outputFileName));
        }
        config.append(extra);
        if (!this.link.headers.isEmpty()) {
            config.append("<headers>");
            this.link.headers.forEach((name, value) -> config.append(element(name, value)));
            config.append("</headers>");
        }
        return String.join(
            "\n",
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">",
            "  <modelVersion>4.0.0</modelVersion>",
            "  <groupId>io.github.download-maven-plugin.live</groupId>",
            "  <artifactId>live-link</artifactId>",
            "  <version>1</version>",
            "  <packaging>pom</packaging>",
            "  <build><plugins><plugin>",
            "    <groupId>" + GROUP_ID + "</groupId>",
            "    <artifactId>" + ARTIFACT_ID + "</artifactId>",
            "    <version>" + VERSION + "</version>",
            "    <configuration>" + config + "</configuration>",
            "  </plugin></plugins></build>",
            "</project>",
            ""
        );
    }

    /**
     * Configuration of the checksum the link pins, if any.
     */
    private String checksum() {
        String result = "";
        if (this.link.sha256 != null) {
            result = element("sha256", this.link.sha256);
        }
        return result;
    }

    private static String element(final String name, final String value) {
        return String.format(
            "<%s>%s</%s>", name, value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"), name
        );
    }

    /**
     * Version of the plugin to test: {@code -Dplugin.version}, else the one of the plugin's pom.
     */
    private static String pluginVersion() {
        String version = System.getProperty("plugin.version");
        if (version == null || version.isEmpty()) {
            final File pom = new File(BASE_DIR, "../../pom.xml");
            try {
                version = XPathFactory.newInstance().newXPath().evaluate(
                    "/project/version", DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
                ).trim();
            } catch (final Exception ex) {
                throw new IllegalStateException("Can't read the plugin version from " + pom + "; pass -Dplugin.version", ex);
            }
        }
        System.out.println("Testing " + GROUP_ID + ":" + ARTIFACT_ID + ":" + version);
        return version;
    }

    /**
     * Prints a progress line right away, so a long run shows what it is doing.
     */
    private static synchronized void progress(final String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static String seconds(final long start) {
        return String.format("%.1f s", (System.currentTimeMillis() - start) / 1000.0d);
    }

    private static String megabytes(final long bytes) {
        return String.format("%.1f MB", bytes / 1_000_000.0d);
    }

    /**
     * Short failure description: the first line of the message, with the plugin's error if the build failed.
     */
    private static String firstLine(final String message) {
        final String text = String.valueOf(message).trim();
        String result = text.contains("\n") ? text.substring(0, text.indexOf('\n')).trim() : text;
        final java.util.regex.Matcher error = Pattern.compile("on project [^:]+: (.+?) -> \\[Help").matcher(text);
        if (error.find()) {
            result = result.replaceFirst(" for http.*", "") + ": " + error.group(1);
        }
        return result;
    }

    /**
     * Total size of the files in a directory, while a build may still be writing to it.
     */
    private static long sizeOf(final Path dir) {
        long size = 0L;
        try (Stream<Path> files = Files.walk(dir)) {
            size = files.filter(Files::isRegularFile).mapToLong(file -> file.toFile().length()).sum();
        } catch (final IOException | java.io.UncheckedIOException ex) {
            // the directory may not exist yet, or a file vanished while walking
        }
        return size;
    }

    private static synchronized void report(final String line) throws IOException {
        Files.createDirectories(WORK_DIR.toPath());
        Files.write(
            REPORT, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND
        );
    }

    private static List<Path> list(final Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.sorted().collect(Collectors.toList());
        }
    }

    private static String tail(final Path log) throws IOException {
        final List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
            .filter(line -> line.startsWith("[ERROR] ") || line.startsWith("[WARNING] "))
            .filter(line -> !line.contains("re-run Maven") && !line.contains("Re-run Maven"))
            .filter(line -> !line.contains("For more information") && !line.contains("[Help 1] http"))
            .collect(Collectors.toList());
        return String.join(System.lineSeparator(), lines.subList(0, Math.min(lines.size(), 15)));
    }

    private static String sha256(final Path file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] buffer = new byte[1 << 16];
        try (InputStream input = Files.newInputStream(file)) {
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        final StringBuilder hex = new StringBuilder();
        for (final byte value : digest.digest()) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    /**
     * Deletes a work directory; build logs and the generated pom survive when {@code keepLogs} is set.
     */
    private static void deleteRecursively(final Path dir, final boolean keepLogs) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        final List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (final Path path : paths) {
            final String name = path.getFileName().toString();
            final boolean log = dir.equals(path.getParent()) && (name.endsWith(".log") || "pom.xml".equals(name));
            if (keepLogs && (log || dir.equals(path))) {
                continue;
            }
            // unpacked archives may contain read-only directories
            path.toFile().setWritable(true);
            Files.deleteIfExists(path);
        }
    }

    /**
     * One line of {@code links.txt}.
     */
    static final class Link {

        private final String category;

        private final String name;

        private final String outputFileName;

        private final int approxMb;

        private final boolean unpack;

        private final boolean revalidates;

        private final String sha256;

        private final Map<String, String> headers;

        private final String url;

        private Link(
            final String category, final String name, final String outputFileName, final int approxMb,
            final boolean unpack, final boolean revalidates, final String sha256,
            final Map<String, String> headers, final String url
        ) {
            this.revalidates = revalidates;
            this.category = category;
            this.name = name;
            this.outputFileName = outputFileName;
            this.approxMb = approxMb;
            this.unpack = unpack;
            this.sha256 = sha256;
            this.headers = headers;
            this.url = url;
        }

        static Link parse(final String line) {
            final String[] parts = line.split("\\s+\\|\\s+", 6);
            if (parts.length != 6) {
                throw new IllegalArgumentException("links.txt: expected 6 columns but got: " + line);
            }
            boolean unpack = false;
            boolean revalidates = false;
            String sha256 = null;
            final Map<String, String> headers = new LinkedHashMap<>();
            final String[] options = "-".equals(parts[4]) ? new String[0] : parts[4].split(",");
            for (final String option : options) {
                if ("unpack".equals(option)) {
                    unpack = true;
                } else if ("revalidates".equals(option)) {
                    revalidates = true;
                } else if (option.startsWith("sha256=")) {
                    sha256 = option.substring("sha256=".length()).toLowerCase(Locale.ROOT);
                } else if (option.startsWith("header:") && option.contains("=")) {
                    final String header = option.substring("header:".length());
                    headers.put(header.substring(0, header.indexOf('=')), header.substring(header.indexOf('=') + 1));
                } else {
                    throw new IllegalArgumentException("links.txt: unknown option '" + option + "' in: " + line);
                }
            }
            return new Link(
                parts[0], parts[1], "-".equals(parts[2]) ? null : parts[2], Integer.parseInt(parts[3]),
                unpack, revalidates, sha256, headers, parts[5]
            );
        }

        String label() {
            return this.category + " " + this.name;
        }
    }
}
