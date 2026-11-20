# 0006. Revalidate cached files with HTTP conditional requests (`updatePolicy`)

- Status: Accepted
- Date: 2026-11-18
- Issues: #233, #315, #332, #333

## Context

Once cached, a file was never downloaded again. That is right for versioned
URLs, but some URLs serve changing content under the same address
(`.../releases/latest/download/tool.zip`, metadata files, branch archives).
Builds kept using the first copy forever. The only workaround,
`skipCache`, downloads the file again on every build even when nothing changed.

Two approaches were proposed:

- #233: ask the server whether the cached copy is still current (ETag / Last-Modified);
- #315: key the cache on the final redirect target, since "latest" URLs
  often redirect to a stable, per-version URL.

## Decision

We will add `updatePolicy` (`download.cache.updatePolicy`): `never`
(the default, same behaviour as before) or `always`. With `always`, the plugin sends
a conditional request before using a cached file:

- it sends both `If-None-Match` and `If-Modified-Since`, because servers
  support only one or the other; redirects are followed, and validators
  come from the final response;
- `304 Not Modified` means the cached file is used;
- new content replaces the cached file; content identical to the cached copy
  counts as unchanged (covers servers that ignore conditional requests), so
  `unpackWhenChanged` does not unpack again;
- I/O errors, `5xx`, `429` and empty responses fall back to the cached copy
  with a warning; `4xx` obeys `failOnError`;
- no request is made offline or when a checksum is configured, because the
  checksum already pins the content.

`ETag` and `Last-Modified` are recorded on every download in a `.meta` file
next to the cached file, together with the size and modification time of
the file they belong to. Validators that don't match the cached file are
ignored, because another process may have replaced the file in between
(see [0005](0005-shared-download-cache-locking.md)). The index format does
not change.

## Alternatives considered

- **Cache key = final redirect target** (#315) - works for servers without
  validators, but needs an extra request anyway, only helps URLs that
  redirect to per-version URLs, and breaks when servers redirect dynamically
  (load balancers, signed CDN URLs). It can still be added later as a separate option.
- **Time-based expiry** (`daily`, `interval:N` like Maven repositories) -
  can serve outdated content within the interval and download again when
  nothing changed.
  `updatePolicy` leaves room to add such values later.
- **`skipCache`** - already exists; always downloads the whole file.

## Consequences

- Changing URLs work with the cache, at the cost of one request per build
  (no body when the server answers 304).
- Servers without validators, or that ignore conditional requests, send the
  full file on every build; content comparison still keeps the result correct.
- Files cached by plugin versions before 2.2.0 have no `.meta`, so they are
  downloaded once more.
- The `.meta` file is an on-disk format shared across plugin versions;
  changing it needs to stay backward compatible.
- A redirect or error page must never be stored as the cached file (#332),
  and an empty revalidation response must not replace it (#333).
