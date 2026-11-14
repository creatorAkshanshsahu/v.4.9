#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="${1:-./screen-safari-studio}"

if [[ ! -d "$TARGET_DIR/.git" ]]; then
  echo "Cloning creatorAkshanshsahu/screen-safari-studio into $TARGET_DIR..."
  git clone https://github.com/creatorAkshanshsahu/screen-safari-studio.git "$TARGET_DIR"
else
  echo "Updating $TARGET_DIR to latest origin/main..."
  git -C "$TARGET_DIR" pull --ff-only origin main
fi

echo "Copying verified Live TV playback fixes into $TARGET_DIR..."
cp -R "$SCRIPT_DIR/files/"* "$TARGET_DIR/"

cd "$TARGET_DIR"
git status -s
echo ""
echo "Ready! Commit and push to deploy automatically to https://screen-safari-studio.lovable.app:"
echo "  cd $TARGET_DIR && git add -A && git commit -m 'fix(live): auto-switch direct CDN token/server on slow frames + prune expired tokens' && git push origin main"
