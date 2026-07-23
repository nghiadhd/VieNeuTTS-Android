#!/bin/sh
#
# Gradle wrapper script — tự download Gradle 8.9 nếu chưa có.
#

# Resolve script location
PRG="$0"
while [ -h "$PRG" ]; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=$(dirname "$PRG")"/$link"
  fi
done
SAVED="$(pwd)"
cd "$(dirname "$PRG")" || exit 1
APP_HOME="$(pwd -P)"
cd "$SAVED" || exit 1

APP_NAME="KhoDocSach"
APP_BASE_NAME=$(basename "$0")

WRAPPER_PROPERTIES="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Download wrapper jar if missing
if [ ! -f "$JAR" ]; then
  echo "[gradlew] gradle-wrapper.jar not found — downloading..."
  mkdir -p "$APP_HOME/gradle/wrapper"
  # Try curl first, then wget
  if command -v curl > /dev/null 2>&1; then
    curl -fsSL \
      "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar" \
      -o "$JAR" 2>/dev/null || true
  fi
  # Fallback: bootstrap via system gradle
  if [ ! -f "$JAR" ] && command -v gradle > /dev/null 2>&1; then
    echo "[gradlew] Bootstrapping via system gradle..."
    (cd "$APP_HOME" && gradle wrapper --gradle-version 8.9 --distribution-type bin -q 2>/dev/null) || true
  fi
  if [ ! -f "$JAR" ]; then
    echo "[gradlew] ERROR: Could not obtain gradle-wrapper.jar."
    echo "  Run: gradle wrapper --gradle-version 8.9"
    exit 1
  fi
fi

# Determine java binary
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
  if [ ! -x "$JAVACMD" ]; then
    echo "ERROR: JAVA_HOME is set but java binary not found at $JAVACMD"
    exit 1
  fi
else
  JAVACMD="java"
  if ! command -v java > /dev/null 2>&1; then
    echo "ERROR: JAVA_HOME not set and 'java' not found in PATH."
    exit 1
  fi
fi

exec "$JAVACMD" \
  -Xmx2g \
  -Xms512m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -Dfile.encoding=UTF-8 \
  $JAVA_OPTS \
  $GRADLE_OPTS \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$JAR" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
