## 1. Implementation

- [x] 1.1 In `ETagMiddleware.respondWithEtag`, in the `uriIsHashed` branch, chain `resp.removeHeader(ci"ETag").removeHeader(ci"Last-Modified")` before calling `putHeaders` with the immutable cache-control headers

## 2. Tests

- [x] 2.1 Add a test asserting that a request for a content-hashed URI returns no `ETag` header
- [x] 2.2 Add a test asserting that a request for a content-hashed URI returns no `Last-Modified` header
- [x] 2.3 Confirm existing test for non-hashed URIs still asserts `ETag` is present
