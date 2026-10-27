FROM mcr.microsoft.com/devcontainers/java:25-trixie

# Needed by setup-shell.sh when fetching the shared shell dotfiles.
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

# Docker initializes named volumes from these directories on first use.
# Make sure the non-root workspace user owns the Maven and npm caches.
RUN mkdir -p /home/vscode/.m2 /home/vscode/.npm \
    && chown -R vscode:vscode /home/vscode/.m2 /home/vscode/.npm
