#!/usr/bin/env bash

set -euo pipefail

ZSH_DIR="${ZSH:-$HOME/.oh-my-zsh}"
ZSH_CUSTOM_DIR="${ZSH_CUSTOM:-$ZSH_DIR/custom}"

if [ ! -d "$ZSH_DIR" ]; then
    echo "Oh My Zsh not found at $ZSH_DIR"
    echo "The devcontainer common-utils feature probably did not install it."
    exit 1
fi

mkdir -p \
    "$ZSH_CUSTOM_DIR/plugins" \
    "$ZSH_CUSTOM_DIR/themes"

clone_or_update() {
    local repo="$1"
    local target="$2"

    if [ -d "$target/.git" ]; then
        git -C "$target" pull --ff-only
    elif [ -e "$target" ]; then
        echo "Target exists but is not a git repo: $target"
        exit 1
    else
        git clone --depth=1 "$repo" "$target"
    fi
}

clone_or_update \
    "https://github.com/zsh-users/zsh-autosuggestions.git" \
    "$ZSH_CUSTOM_DIR/plugins/zsh-autosuggestions"

clone_or_update \
    "https://github.com/zsh-users/zsh-syntax-highlighting.git" \
    "$ZSH_CUSTOM_DIR/plugins/zsh-syntax-highlighting"

clone_or_update \
    "https://github.com/frederik-hoeft/oh-my-zsh-themes.git" \
    "$HOME/.oh-my-zsh-themes"

ln -sf \
    "$HOME/.oh-my-zsh-themes/themes/fred.zsh-theme" \
    "$ZSH_CUSTOM_DIR/themes/fred.zsh-theme"

wget \
    "https://raw.githubusercontent.com/frederik-hoeft/dot-files/refs/heads/main/default.zshrc" \
    -O "$HOME/.zshrc"

wget \
    "https://raw.githubusercontent.com/frederik-hoeft/dot-files/refs/heads/main/default.vimrc" \
    -O "$HOME/.vimrc"

# default .zshrc is configured for system-wide install, so fix it to point to the local install in the devcontainer
sed -i "s|/opt/shared/oh-my-zsh/oh-my-zsh|${ZSH_DIR}|g" "$HOME/.zshrc"

# Preserve a pre-volume history file without overwriting existing volume history.
# The volume is a directory: HISTFILE itself must point inside it, not at a
# symlink in $HOME, because Zsh's HIST_SAVE_BY_COPY can replace that symlink.
history_file="$HOME/.local/share/zsh-history/.zsh_history"
legacy_history="$HOME/.zsh_history"
if [ -f "$legacy_history" ] && [ ! -L "$legacy_history" ]; then
    if [ -f "$history_file" ]; then
        cat "$legacy_history" >> "$history_file"
        rm "$legacy_history"
    else
        mv "$legacy_history" "$history_file"
    fi
fi
# Clean up the link installed by earlier revisions of this setup script.
if [ -L "$legacy_history" ] && [ "$(readlink "$legacy_history")" = "$history_file" ]; then
    rm "$legacy_history"
fi

# Set this before Oh My Zsh initializes (it otherwise defaults to ~/.zsh_history).
# The .zshrc was just downloaded, so prepending the setting is idempotent.
sed -i '1i HISTFILE="$HOME/.local/share/zsh-history/.zsh_history"' "$HOME/.zshrc"

# WSL volume mounts cause git to report dubious ownership
git config --global --add safe.directory '*'
