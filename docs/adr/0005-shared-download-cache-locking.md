# 0005. Share the download cache safely between processes with file locks

- Status: Accepted
- Date: 2026-11-15
- Issues: #188, #320, #326

## Context

The download cache (by default under the local Maven repository) is shared
by every build on the machine: parallel modules in one JVM, parallel Maven
processes and CI jobs on the same agent, and different plugin versions.
The cache index maps URIs to cached files and is one serialized file.

The original code only guarded the index with an in-JVM lock, and other
processes could interfere:

- a reader could see a half-written index and fail with `EOFException` (#188);
- two processes could each read the index, add an entry and write it back,
  so the last writer silently dropped the other's entry;
- on Windows file locks are mandatory, so writing the index before taking
  the lock failed when another process held it (#320);
- creating the cache directory with `exists()`/`mkdirs()` raced with other
  processes doing the same (#326).

## Decision

We will coordinate processes through OS file locks on the index file and
make every update a single locked read-merge-write:

- reading takes a shared `FileChannel` lock;
- `put` takes an exclusive lock, re-reads the index from disk, merges the
  in-memory entries into it, and truncates and rewrites the file while
  still holding the same lock;
- the index is serialized in memory first, and the file is only touched
  while the lock is held (required on Windows);
- an unreadable or incompatible index is logged and rewritten, not fatal;
- directories are created with `Files.createDirectories`, which succeeds if
  the directory already exists;
- the index format stays the same, so older plugin versions can keep
  sharing the cache.

## Alternatives considered

- **In-JVM lock only** - does not protect against other Maven processes.
- **Separate lock file** (`.lock` next to the index) - one more file to
  clean up after crashed processes, and older plugin versions would not
  respect it.
- **Write to a temp file and atomically rename** - avoids torn reads, but
  still loses concurrent entries without a lock around the read-merge-write,
  and rename over an open file fails on Windows.
- **One index file per entry** - avoids contention but changes the on-disk
  format, which breaks sharing with older plugin versions.

## Consequences

- Concurrent builds no longer lose index entries or fail on a partially written index.
- Index writes are serialized across processes; this is cheap because the index is small.
- File locks are advisory on Linux/macOS: older plugin versions that write
  without the lock can still race. That gets fixed as users upgrade.
- Cache code must be tested for multiple processes and Windows file
  semantics (see `FileBackedIndexMultiProcessTest`, `DownloadCacheRaceTest`).
  Windows has coarse modification times and mandatory locks.
- Later cache changes (for example [0006](0006-revalidate-cache-with-conditional-requests.md))
  must stay safe when other processes modify the cache concurrently.
