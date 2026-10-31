pluginManagement {
    repositories {
        google()
        providers.gradleProperty("junshi.mavenMirror").orNull?.let { address ->
            require(address.startsWith("https://"))
            maven { url = uri(address) }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        providers.gradleProperty("junshi.mavenMirror").orNull?.let { address ->
            require(address.startsWith("https://"))
            maven { url = uri(address) }
        }
        mavenCentral()
    }
}
rootProject.name = "Goutoujunshi"
include(":app", ":core:designsystem", ":core:domain", ":core:data", ":core:knowledge")
