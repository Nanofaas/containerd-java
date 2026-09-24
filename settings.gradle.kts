plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "containerd-java"

// libcni-java is the published artifact from GitHub Packages, the one consumers resolve. To work
// on both at once, name a checkout explicitly: ./gradlew build -PlibcniDir=../libcni-java
// Never implicit: a build that swapped in whatever happened to sit next to it would pass against a
// libcni no consumer gets.
providers.gradleProperty("libcniDir").orNull?.let { includeBuild(it) }
