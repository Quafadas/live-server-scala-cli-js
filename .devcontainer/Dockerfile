FROM mcr.microsoft.com/devcontainers/base:ubuntu

# https://get-coursier.io/docs/cli-installation
RUN curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs && chmod +x cs && mv cs /usr/local/bin/cs

COPY build.sc .

# We should have all the deps to run Metals here, saving time from a cold start.
RUN cs install metals

# Download mills dependancies. if build.sc hasn't changed, this _should_ hit the layer cache.
RUN mill __.prepareOffline

# Copy source into container
COPY . .

# Compile the project - anything that has hit "main" should (at least!) compile
# And setup mills BSP server for metals
RUN ./mill show __.compile && ./mill mill.bsp.BSP/install