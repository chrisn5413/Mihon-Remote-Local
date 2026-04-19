#!/bin/sh
#
# Gradle start up script for POSIX compatible shells (requires gradle-wrapper.jar).
# If gradle-wrapper.jar is missing, generate it by running:
#   gradle wrapper --gradle-version 8.6
# Or open the project in Android Studio which will handle the wrapper automatically.
#

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

SCRIPT_DIR=$(dirname "$0")
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

MAX_FD="maximum"

warn () { echo "$*"; }
die () { echo; echo "ERROR: $*"; echo; exit 1; }

OS_NAME=$(uname)
case "$OS_NAME" in
  CYGWIN* ) cygwin=true ;;
  Darwin* ) darwin=true ;;
  MSYS* | MINGW* ) msys=true ;;
  NONSTOP* ) nonstop=true ;;
esac

CLASSPATH="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$CLASSPATH" ]; then
  die "gradle-wrapper.jar not found at $CLASSPATH. Run 'gradle wrapper --gradle-version 8.6' or open in Android Studio."
fi

exec java $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
