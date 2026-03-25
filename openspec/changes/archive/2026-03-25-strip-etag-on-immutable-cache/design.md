## Context

`ETagMiddleware` in `sjsls` is a http4s middleware that inspects a shared `Ref[IO, Map[String, String]]` mapping URI paths to their content hash. It has three branches:

1. **Hashed URI** (`uriIsHashed` is true) — sets `Cache-Control: immutable, max-age=31536000, public`.
2. **Known path, not hashed** — sets an `ETag` + `Cache-Control: no-cache, must-revalidate`.
3. **Unknown path** — sets revalidate-only headers.

The http4s `StaticFile` helper, which backs the static serving middleware, will automatically attach an `ETag` (file hash or inode/mtime based) and a `Last-Modified` header to every `200 OK` response before `ETagMiddleware` sees it. This means branch 1 currently sends semantically contradictory headers: `Cache-Control: immutable` says "never revalidate", while `ETag` and `Last-Modified` invite browsers and intermediaries to do exactly that.

## Goals / Non-Goals

**Goals:**
- Ensure responses for content-hashed URIs never include `ETag` or `Last-Modified` headers.
- Keep the rest of the middleware logic unchanged.

**Non-Goals:**
- Changing how non-hashed files are cached or validated.
- Altering how content hashes are computed or stored.
- Any changes outside `ETagMiddleware.scala`.

## Decisions

### Strip headers in `respondWithEtag`, not downstream

**Decision**: Remove `ETag` and `Last-Modified` in the `uriIsHashed` branch of `respondWithEtag`, using http4s's `resp.removeHeader[`ETag`].removeHeader[`Last-Modified`]` (or the `Header.Raw` ci-string variants).

**Alternatives considered**:
- A separate middleware layer after ETagMiddleware that strips headers for hashed paths. Rejected — it duplicates the `uriIsHashed` check and spreads the logic.
- Configuring http4s `StaticFile` to omit those headers. Rejected — the static-file helper doesn't expose that option cleanly without forking internal behaviour.

Stripping at the point of decision is minimal, localized, and obvious.

### Use `resp.removeHeader` API

**Decision**: Use `resp.removeHeader(ci"ETag").removeHeader(ci"Last-Modified")` chained before `putHeaders(...)` inside the immutable branch.

This is idiomatic http4s and makes the intent explicit.

## Risks / Trade-offs

- [Risk] Removing `Last-Modified` may affect CDN edge caches that use it for their internal freshness calculations → Mitigation: CDNs respect `Cache-Control: immutable` as authoritative; no practical impact.
- [Risk] Tests that currently assert the full header set for hashed responses may need updating → Mitigation: Tests should be updated as part of this change to assert absence of ETag/Last-Modified.

## Migration Plan

No user-facing API change. The only observable difference is the removal of two response headers for content-hashed URIs. Existing clients benefit immediately on next deploy; no rollback concern.

## Open Questions

None.
