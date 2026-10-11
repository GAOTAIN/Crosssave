#!/bin/sh
# 使用固定的 Gradle 包装器，优先使用配置的 Java 安装目录。
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
if [ -n "$JAVA_HOME" ]; then
    CROSSSAVE_JAVA="$JAVA_HOME/bin/java"
else
    CROSSSAVE_JAVA=java
fi
exec "$CROSSSAVE_JAVA" $JAVA_OPTS $GRADLE_OPTS -Dorg.gradle.appname=gradlew -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
