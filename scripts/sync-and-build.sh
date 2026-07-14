#!/usr/bin/env bash
# Sync project to a remote build host, build, and optionally install.
# Usage:
#   ./scripts/sync-and-build.sh                 # sync + build
#   ./scripts/sync-and-build.sh --install        # sync + build + install on emulator
#   ./scripts/sync-and-build.sh --install-device # sync + build + install on USB device
#   ./scripts/sync-and-build.sh --skip-build     # sync only
#
# Required environment variables (must be set before running):
#   SYNC_SSH_KEY       - path to the SSH private key for the build host
#   SYNC_SSH_HOST      - SSH destination (e.g. user@host)
#   SYNC_REMOTE_DIR    - remote directory to sync into
#
# Optional environment variables:
#   SYNC_ADB_DEVICE    - ADB device serial for --install / --install-device
#                          (defaults to emulator-5554 / <device-serial>)

set -euo pipefail

: "${SYNC_SSH_KEY:?Environment variable SYNC_SSH_KEY must be set (path to SSH key)}"
: "${SYNC_SSH_HOST:?Environment variable SYNC_SSH_HOST must be set (e.g. user@host)}"
: "${SYNC_REMOTE_DIR:?Environment variable SYNC_REMOTE_DIR must be set (remote path)}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SSH_KEY="$SYNC_SSH_KEY"
SSH_HOST="$SYNC_SSH_HOST"
REMOTE_DIR="$SYNC_REMOTE_DIR"
ADB="export PATH=\$PATH:\$HOME/Library/Android/sdk/platform-tools && adb"
TARGET=""
SKIP_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --install)        TARGET="${SYNC_ADB_DEVICE:-emulator-5554}" ;;
        --install-device) TARGET="${SYNC_ADB_DEVICE:-}" ;;
        --skip-build)     SKIP_BUILD=true ;;
    esac
done

echo "=== [1/3] Syncing to build host ==="
rsync -avz --delete \
    --exclude='.git' \
    --exclude='.gradle' \
    --exclude='build' \
    --exclude='app/build' \
    --exclude='local.properties' \
    --exclude='.idea' \
    -e "ssh -i $SSH_KEY" \
    "$PROJECT_DIR/" "$SSH_HOST:$REMOTE_DIR/"

if [[ "$SKIP_BUILD" == true ]]; then
    echo "=== Build skipped ==="
    exit 0
fi

echo ""
echo "=== [2/3] Building on build host ==="
ssh -i "$SSH_KEY" "$SSH_HOST" "cd $REMOTE_DIR && \
    export JAVA_HOME=\$(/usr/libexec/java_home -v 17 2>/dev/null || echo '/Applications/Android Studio.app/Contents/jbr/Contents/Home') && \
    ./gradlew assembleDebug --console=plain 2>&1" | tail -20

echo ""
echo "=== [3/3] Install ==="
if [[ -z "$TARGET" ]]; then
    echo "Skipped (use --install or --install-device)"
    exit 0
fi

if [[ -z "${SYNC_ADB_DEVICE:-}" ]]; then
    echo "Warning: SYNC_ADB_DEVICE not set; install target may be incorrect." >&2
fi

ssh -i "$SSH_KEY" "$SSH_HOST" "$ADB -s $TARGET install -r \$(find $REMOTE_DIR/app/build -name '*.apk' -path '*/debug/*' | head -1)"

echo ""
echo "=== DONE ==="
