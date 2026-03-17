pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://central.sonatype.com/repository/maven-snapshots")
    }
}

rootProject.name = "Mycelium"
include(":app")
includeBuild("cybin") {
    dependencySubstitution {
        substitute(module("com.github.TekkadanPlays:cybin")).using(project(":cybin"))
    }
}

// Benchmark module disabled for now - plugin version conflict with AGP 8.13
// To enable: update AGP or use compatible baseline profile plugin version
// include(":benchmark")