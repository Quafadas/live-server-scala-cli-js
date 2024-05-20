#!/bin/sh

set -e

# Install Mill
curl -L -o /usr/local/bin/mill https://raw.githubusercontent.com/lefou/millw/0.4.11/millw && chmod +x /usr/local/bin/mill

# Verify installation
mill version