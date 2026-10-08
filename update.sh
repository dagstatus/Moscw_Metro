#!/bin/sh
# Метро Москвы — обновление приложения (macOS / Linux)
cd "$(dirname "$0")"
JAVA=java
for j in "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" "$HOME/android-studio/jbr/bin/java" "/opt/android-studio/jbr/bin/java"; do
  [ -x "$j" ] && JAVA="$j"
done
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] && JAVA="$JAVA_HOME/bin/java"

if [ -n "$1" ]; then exec "$JAVA" tools/Build.java "$@"; fi
while true; do
  echo
  echo "  1  Проверить данные (data/*.csv)"
  echo "  2  Открыть схему в браузере"
  echo "  3  Собрать тестовый APK (версия не меняется)"
  echo "  4  Выпустить обновление (новая версия + подписанный APK)"
  echo "  0  Выход"
  printf "Выберите действие: "; read c
  case "$c" in
    1) "$JAVA" tools/Build.java check ;;
    2) "$JAVA" tools/Build.java preview ;;
    3) "$JAVA" tools/Build.java apk ;;
    4) "$JAVA" tools/Build.java release ;;
    0) exit 0 ;;
  esac
done
