## Why

When a file URL contains a content hash, the server sets `Cache-Control: immutable, max-age=31536000, public` — telling browsers the resource will never change and should never be revalidated. However, the current `ETagMiddleware` does not strip `ETag` or `Last-Modified` headers that the underlying http4s static file middleware adds, meaning immutable responses still carry validators that contradict the caching intent and add unnecessary bytes to every response.

## What Changes

- When a requested URI is detected as content-hashed (via `uriIsHashed`), the response MUST have any `ETag` and `Last-Modified` headers **removed** before being sent to the client.
- `ETagMiddleware` will be updated to strip these headers in the immutable-cache branch of `respondWithEtag`.
- No change to behaviour for non-hashed files (they continue to use ETag-based revalidation as before).

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `sjsls`: Serving a content-hashed (immutable) file must not include ETag or Last-Modified response headers.

## Impact

- `sjsls/src/middleware/ETagMiddleware.scala` — immutable branch of `respondWithEtag` must remove `ETag` and `Last-Modified` headers in addition to setting the cache-control headers.
- Existing tests for `ETagMiddleware` / caching middleware will need updating to assert no ETag/Last-Modified on hashed paths.
