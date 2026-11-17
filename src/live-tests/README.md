# Live tests

Runs a build of download-maven-plugin against real-world download links
(GitHub, Maven Central, Nexus, Artifactory, S3, Azure, GCS, mirror networks, ...).

This is a standalone project. It is not part of the plugin build, it never runs in CI,
and it needs internet access. Run it on demand, for example before a release.

## Running

```sh
# 1. put the build you want to test into the local repository (from the repository root)
mvn install -DskipTests

# 2. run the suite
mvn -f src/live-tests/pom.xml test
```

A full run downloads about 6 GB and takes 10-15 minutes on a fast connection.

| Option | Meaning |
|---|---|
| `-Dplugin.version=2.1.1` | Plugin version to test. Default: the version in the root `pom.xml`. Any version Maven can resolve works, so a release can be compared with a snapshot. |
| `-Dlive.maxMb=50` | Skip links bigger than this. `-Dlive.maxMb=3` is a 4-minute smoke run of about half of the links. |
| `-Dlive.only=<regex>` | Run only links whose `<category> <name>` matches, case-insensitively: `-Dlive.only=github`, `-Dlive.only="^F "`. |
| `-Dlive.update=false` | Skip the update step, e.g. for plugin versions before 2.2.0, which have no `updatePolicy`. |
| `-Dlive.keep=true` | Keep downloaded files, caches and unpacked archives of passed links. |
| `-Dlive.timeoutMinutes=30` | Timeout of one nested Maven build. |

## What is verified

Each line of [`links.txt`](links.txt) is one test. The plugin is executed by Maven from a
generated `pom.xml`, with its default settings and a private, empty cache directory:

1. **download** - online. Exactly one file must arrive, with the expected name and not empty.
   If the link has a `sha256`, the plugin verifies it.
2. **cache** - the output file is deleted and Maven runs offline (`-o`). The plugin must
   restore an identical file from its cache.
3. **unpack** - only for links marked `unpack`. Maven runs offline and the plugin must
   unpack the cached archive.
4. **update** - the output file is deleted and the plugin runs online with
   `updatePolicy=always`, so it has to ask the server whether the cached file is still
   current. The file must be there afterwards. For links marked `revalidates` the server
   must answer `304 Not Modified`, i.e. nothing is downloaded again. The other links are
   known not to support that; they are downloaded again, which is also accepted.

## Results

- Console: progress as the run goes - one block per link with the outcome and duration of
  each step, and a line every 15 seconds with the bytes downloaded so far while a step is
  still running:

  ```
  [ 7/80] A Elastic artifacts (~574 MB)
          download  still running, 15.0 s, 89.4 MB so far
          download  574.1 MB, 72.8 s
          cache     restored offline, 6.9 s
          update    not modified, 3.1 s
          PASSED in 82.8 s
  ```
- `target/surefire-reports`: one test per link.
- `target/live/report.txt`: size, download time, SHA-256 and the outcome of the update step
  of every passed link.
- `target/live/<link>/`: for a failed link, the generated `pom.xml` and the Maven logs
  (`1-download.log`, `2-cache.log`, `3-unpack.log`, `4-update.log`).

A failure is not necessarily a plugin bug: links die and servers have bad days.
Check the log, and fix or replace the line in `links.txt` when the link itself is gone.

## Link categories

| | Hosting pattern | Example |
|---|---|---|
| A | Versioned URL, served directly | Maven Central artifact |
| B | Versioned URL behind a redirect | GitHub release asset of a tag |
| C | Stable URL whose content changes, no redirect | `maven-metadata.xml`, branch archive |
| D | "latest" URL redirecting to a stable versioned URL | `aka.ms`, Azul API |
| E | "latest" URL redirecting to a signed, expiring URL | GitHub `releases/latest/download` |
| F | Mirror redirector choosing a server per request | SourceForge, Eclipse, CTAN |

To add a link, append a line to `links.txt`; the format is described at the top of that file.
