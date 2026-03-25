## MODIFIED Requirements

### Requirement: Site generation with optional assets

The `siteGen` and `siteGenFull` tasks SHALL copy the assets directory into the task destination only when the assets directory exists on disk. When the assets directory does not exist, the tasks SHALL succeed without copying assets.

#### Scenario: Assets directory exists
- **WHEN** the `siteGen` or `siteGenFull` task is invoked
- **AND** the `assetsDir` path exists on disk
- **THEN** the assets SHALL be copied into the task destination with `mergeFolders = true`

#### Scenario: Assets directory does not exist
- **WHEN** the `siteGen` or `siteGenFull` task is invoked
- **AND** the `assetsDir` path does not exist on disk
- **THEN** the task SHALL succeed without attempting to copy assets
- **AND** the generated site SHALL still contain `index.html` and linked JS output
