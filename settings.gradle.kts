// Gradle-проект для Android Studio (нужен, чтобы собрать AAB для Google Play).
// Для обычного обновления достаточно update.bat — Gradle там не используется.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "MetroMoscow"
include(":app")
