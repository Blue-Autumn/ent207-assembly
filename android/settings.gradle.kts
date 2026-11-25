pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://developer.huawei.com/repo/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "ARAssemblyDemo"
include(":app")
