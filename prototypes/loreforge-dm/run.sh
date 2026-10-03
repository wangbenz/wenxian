#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -x /usr/libexec/java_home ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21)
  export JAVA_HOME
fi
mvn -q -DskipTests package
mkdir -p .data/runtime
dm_runtime_jar=$(mktemp .data/runtime/server.XXXXXX)
cp target/loreforge-dm.jar "$dm_runtime_jar"
# 运行固定副本，后续构建不会破坏正在运行的类加载器。
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$dm_runtime_jar"
