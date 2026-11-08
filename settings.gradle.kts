pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "IptvPlayer"
include(":app", ":core:domain", ":core:data", ":core:ui")
include(":feature:recordings", ":feature:search", ":feature:livetv", ":feature:vod", ":feature:series", ":feature:epg", ":feature:player", ":feature:settings")
