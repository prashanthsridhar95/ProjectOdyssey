pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "odyssey-mobile"

include(":core:domain", ":core:data", ":core:ui")
include(":feature:auth", ":feature:discovery", ":feature:booking", ":feature:vendorops", ":feature:adminops")
include(":vertical:carwash")
include(":app:customer", ":app:vendor", ":app:admin")
