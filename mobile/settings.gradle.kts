pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    // Kotlin/Wasm downloads Node.js, Yarn and Binaryen at build time and adds those repositories itself, so project
    // repositories must be allowed to be ignored (the settings ones below are the ones that count).
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google(); mavenCentral()
        exclusiveContent {
            forRepository { ivy("https://nodejs.org/dist") { patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }; metadataSources { artifact() } } }
            filter { includeModule("org.nodejs", "node") }
        }
        exclusiveContent {
            forRepository { ivy("https://github.com/yarnpkg/yarn/releases/download") { patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }; metadataSources { artifact() } } }
            filter { includeModule("com.yarnpkg", "yarn") }
        }
        exclusiveContent {
            forRepository { ivy("https://github.com/WebAssembly/binaryen/releases/download") { patternLayout { artifact("version_[revision]/[artifact]-version_[revision]-[classifier].[ext]") }; metadataSources { artifact() } } }
            filter { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}
rootProject.name = "odyssey-mobile"

include(":core:domain", ":core:data", ":core:ui")
include(":feature:auth", ":feature:discovery", ":feature:booking", ":feature:vendorops", ":feature:adminops", ":feature:customerapp")
include(":vertical:carwash")
include(":app:customer", ":app:vendor", ":app:admin", ":app:customer-web")
