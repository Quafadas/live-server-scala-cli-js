#!/bin/sh

set -e

# Put millw on the path, so we can call 'mill' from the command line.
curl -L -o /usr/local/bin/mill https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.0.3/mill-dist-1.0.3-mill.sh && chmod +x /usr/local/bin/mill

# Install Coursier - can call cs from command line
curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > /usr/local/bin/cs && chmod +x /usr/local/bin/cs

# Install metals - should prevent dependancy downloads on container start
cs install metals
cs launch com.microsoft.playwright:playwright:1.51.0 -M "com.microsoft.playwright.CLI" -- install --with-deps