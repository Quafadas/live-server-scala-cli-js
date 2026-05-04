#!/bin/sh
set -e

# Extract Mill version from build.mill
MILL_VERSION=1.1.6

# Extract Playwright version from playwrightVersion.mill
PLAYWRIGHT_VERSION=1.58.0

# Put millw on the path, so we can call 'mill' from the command line.
curl -L -o /usr/local/bin/mill https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/${MILL_VERSION}/mill-dist-${MILL_VERSION}-mill.sh && chmod +x /usr/local/bin/mill

# Install Coursier - can call cs from command line
curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > /usr/local/bin/cs && chmod +x /usr/local/bin/cs

# Install metals - should prevent dependancy downloads on container start
cs install metals