#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELATIVE_PATH="third_party/whisper.cpp"
TARGET_DIR="$ROOT_DIR/$RELATIVE_PATH"
REPOSITORY_URL="https://github.com/ggml-org/whisper.cpp.git"
VERSION="v1.9.3"

if ! command -v git >/dev/null 2>&1; then
    echo "ERROR: git is required." >&2
    exit 1
fi

cd "$ROOT_DIR"

if [ -f "$TARGET_DIR/include/whisper.h" ]; then
    echo "whisper.cpp already exists: $TARGET_DIR"
    echo "Current revision: $(git -C "$TARGET_DIR" describe --tags --always 2>/dev/null || echo unknown)"
    exit 0
fi

mkdir -p "$ROOT_DIR/third_party"

echo "Installing whisper.cpp $VERSION"
echo "Target: $TARGET_DIR"

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    if git ls-files --stage "$RELATIVE_PATH" | grep -q '^160000 '; then
        git submodule update --init --recursive "$RELATIVE_PATH"
    else
        git submodule add "$REPOSITORY_URL" "$RELATIVE_PATH"
    fi

    git -C "$TARGET_DIR" fetch --tags origin
    git -C "$TARGET_DIR" checkout "$VERSION"

    # Record the pinned release as the submodule gitlink in the parent repo.
    git add .gitmodules "$RELATIVE_PATH"
else
    git clone \
        --branch "$VERSION" \
        --depth 1 \
        "$REPOSITORY_URL" \
        "$TARGET_DIR"
fi

echo "whisper.cpp ready: $(git -C "$TARGET_DIR" describe --tags --always)"
