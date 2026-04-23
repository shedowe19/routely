#!/usr/bin/env sh
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
JAVACMD="${JAVA_HOME}/bin/java"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec "$JAVACMD" -Xmx64m -Xms64m \
    -Dorg.gradle.appname="gradlew" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
