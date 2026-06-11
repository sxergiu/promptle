#!/usr/bin/env bash
#
# Render a sample chain straight to an export GIF (no game flow / DB / server) and
# open it, so you can eyeball the export styling/avatars/transitions quickly.
#
# Runs the ExportPreviewTest dev tool, which writes:
#   promptle/target/export-preview/output.gif   (the animated GIF)
#   promptle/target/export-preview/frame-0NN.png (source frames)
#
# Edit the chain in
#   promptle/src/test/java/com/app/promptle/export/service/ExportPreviewTest.java
# to try different text / avatars / lengths.
#
# Requires: ffmpeg on PATH, a JDK 21+, and the export assets
# (run scripts/gen-export-assets.sh once if they're missing).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GIF="$REPO_ROOT/promptle/target/export-preview/output.gif"

# Pick a JDK 21+ if the default java is older (macOS).
if ! java -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null)"
    export JAVA_HOME
    echo "Using JAVA_HOME=$JAVA_HOME"
  fi
fi

cd "$REPO_ROOT/promptle"
./mvnw test -Dtest=ExportPreviewTest

echo "GIF: $GIF"
# Open in a browser so it actually animates (Preview shows static frames).
if command -v open >/dev/null 2>&1; then
  open -a "Google Chrome" "$GIF" 2>/dev/null || open "$GIF"
fi
