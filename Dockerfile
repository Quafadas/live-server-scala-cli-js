FROM mcr.microsoft.com/devcontainers/base:ubuntu

# Install OpenJDK 11
RUN apt-get update && \
    apt-get install -y openjdk-17-jdk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# https://get-coursier.io/docs/cli-installation
RUN curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs && chmod +x cs && mv cs /usr/local/bin/cs

COPY build.sc .
COPY mill .
COPY .mill-version .
COPY playwrightVersion.sc .

# Download mills dependancies. if build.sc hasn't changed, this _should_ hit the layer cache.
RUN ./mill __.prepareOffline

# We should have all the deps to run Metals here, saving time from a cold start.
RUN cs install metals
# Copy source into container
COPY . .

# Compile the project - anything that has hit "main" should (at least!) compile
# And setup mills BSP server for metals
RUN ./mill show __.compile && ./mill mill.bsp.BSP/install