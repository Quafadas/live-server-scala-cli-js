## ADDED Requirements

### Requirement: No ETag or Last-Modified on immutable cached responses
When a response for a content-hashed URI is served with `Cache-Control: immutable`, the server SHALL NOT include `ETag` or `Last-Modified` headers in that response.

#### Scenario: Hashed URI strips ETag
- **WHEN** a client requests a URI whose path is detected as content-hashed
- **THEN** the response SHALL contain `Cache-Control: immutable`
- **AND** the response SHALL NOT contain an `ETag` header

#### Scenario: Hashed URI strips Last-Modified
- **WHEN** a client requests a URI whose path is detected as content-hashed
- **THEN** the response SHALL NOT contain a `Last-Modified` header

#### Scenario: Non-hashed URI retains ETag
- **WHEN** a client requests a URI that is NOT content-hashed but is known in the hash map
- **THEN** the response SHALL still contain an `ETag` header for revalidation
