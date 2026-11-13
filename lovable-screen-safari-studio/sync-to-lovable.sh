#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="${1:-./screen-safari-studio}"

if [[ ! -d "$TARGET_DIR/.git" ]]; then
  echo "Cloning creatorAkshanshsahu/screen-safari-studio into $TARGET_DIR..."
  git clone https://github.com/creatorAkshanshsahu/screen-safari-studio.git "$TARGET_DIR"
fi

echo "Copying updated Live TV & OG Embed files into $TARGET_DIR..."
cp -R "$SCRIPT_DIR/files/"* "$TARGET_DIR/"

cd "$TARGET_DIR"
git status -s
echo ""
echo "Ready! Review and push to sync straight into Lovable:"
echo "  cd $TARGET_DIR && git add -A && git commit -m 'feat(live): integrate maxTV & StarShare Live TV + OG embed API' && git push origin main"
