#!/bin/sh

set -e

# Install Mill
curl -L -o /usr/local/bin/mill https://raw.githubusercontent.com/lefou/millw/0.4.11/millw && chmod +x /usr/local/bin/mill
curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > /usr/local/bin/cs && chmod +x /usr/local/bin/cs

cs install metals