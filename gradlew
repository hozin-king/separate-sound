#!/bin/sh
# Minimal Gradle bootstrap (pengganti gradle wrapper jar).
# Mengunduh distribusi Gradle resmi bila belum ada, lalu menjalankannya.
set -e
GRADLE_VERSION="9.7.1"
DIST_DIR="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
GRADLE_BIN="$DIST_DIR/gradle-${GRADLE_VERSION}/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  echo "[gradlew] mengunduh Gradle ${GRADLE_VERSION}..."
  mkdir -p "$DIST_DIR"
  cd "$DIST_DIR"
  curl -sSL -o gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  unzip -q -o gradle.zip
  rm -f gradle.zip
  cd - > /dev/null
fi
exec "$GRADLE_BIN" "$@"
