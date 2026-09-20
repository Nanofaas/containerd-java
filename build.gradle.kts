plugins {
    `java-library`
    `maven-publish`
    application
    jacoco
    id("com.google.protobuf") version "0.9.5"
    // Analysis runs through this rather than the sonar-scanner-cli image: that image is published
    // for amd64 only, so it cannot run on an arm64 host. The plugin also knows the source sets,
    // the compiled classes and the test reports, which the CLI has to be told about by hand.
    id("org.sonarqube") version "7.5.0.8588"
    // Builds the example as a native image, which is how the GraalVM support is kept honest:
    // the metadata this library ships is only correct if a real image built from it runs.
    id("org.graalvm.buildtools.native") version "1.1.12"
}

group = "io.nanofaas"
version = "0.4.0-SNAPSHOT"

val containerdApiVersion = "v2.2.1" // pinned containerd API; bump together with vendored protos
val grpcVersion = "1.73.0"
val protobufVersion = "4.35.0"
val nettyVersion = "4.1.121.Final" // MUST match grpc-netty's resolved netty; see Step 5
val slf4jVersion = "2.0.17"
val javaRelease = 22 // java.lang.foreign is final from 22; nothing here needs more
val junitVersion = "5.11.4"
val junitPlatformVersion = "1.11.4"
val assertjVersion = "3.27.3"
val javaxAnnotationVersion = "1.3.2"

repositories {
    mavenLocal { content { includeGroup("io.libcni") } }
    mavenCentral()
    // libcni-java, for the optional CNI source set. GitHub Packages needs credentials even to
    // read a public package, unlike Maven Central: a token with read:packages. Declared for the
    // CNI configurations only, so nobody building the core has to have one.
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/Nanofaas/libcni-java")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
            password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.token").orNull
        }
        content { includeGroup("io.libcni") }
    }
}

java {
    toolchain {
        // Built with 25; the bytecode targets 22 (see options.release below), which is the
        // floor the Foreign Function and Memory API sets — mkfifo goes through it, because the
        // JNR binding it replaced generates classes at runtime and so cannot be compiled into a
        // GraalVM native image.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
    withJavadocJar()
}

// Javadoc documents the published API only: io.nanofaas.containerd and its spi package.
//
// The generated protobuf/gRPC stubs are excluded because the spec keeps generated types out of
// the public API, and they would bury the output under thousands of pages. The internal package
// and the runnable example are excluded because neither is API — internals carry their reasoning
// in ordinary comments, which is where it belongs for code nobody compiles against.
//
// What remains is checked strictly: Xdoclint:all fails the build on an undocumented public
// member, a missing @param or @return, a broken link or malformed HTML. Since withJavadocJar()
// puts this task on the assemble path, CI enforces it on every push.
tasks.javadoc {
    exclude("containerd/**", "runtimeoptions/**", "io/nanofaas/containerd/internal/**",
            "io/nanofaas/containerd/example/**")
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all", "-quiet")
        addStringOption("Xmaxwarns", "10000")
        // Without this the build passes on an undocumented public member: doclint reports it as
        // a warning, and javadoc warnings are not failures. -Werror is what makes it a gate.
        addBooleanOption("Werror", true)
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
    }
}

// Grouped by subject rather than by configuration: each dependency sits with the comment
// explaining why it has the scope it has, which regrouping would separate it from.
dependencies {
    api("io.grpc:grpc-netty:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    // gRPC codegen emits @javax.annotation.Generated (JSR-250), absent from JDK 9+; compile-time only
    compileOnly("javax.annotation:javax.annotation-api:$javaxAnnotationVersion")
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.google.protobuf:protobuf-java-util:$protobufVersion")
    api("org.slf4j:slf4j-api:$slf4jVersion")

    // Epoll native libs: both classifiers coexist on the runtime classpath; Netty's native
    // loader picks the one matching the host arch (x86_64 CI vs aarch64 dev host).
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-aarch_64")
    // io.netty.channel.epoll Java classes (Epoll, EpollDomainSocketChannel, EpollEventLoopGroup)
    // are not in the classifier jars (those carry only the .so); needed at compile time for
    // GrpcChannelFactory. Runtime provides them transitively via the native epoll artifacts.
    compileOnly("io.netty:netty-transport-classes-epoll:$nettyVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    // wiring test starts a real Netty epoll UDS server; epoll classes are compileOnly for main
    testImplementation("io.netty:netty-transport-classes-epoll:$nettyVersion")
    testRuntimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaRelease)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// Coverage exists only if something measures it: without this SonarQube reports 0% however many
// tests run, and its quality gate fails on new code that is in fact covered.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)   // what SonarQube reads
        html.required.set(true)  // what a human reads
    }
    classDirectories.setFrom(files(classDirectories.files.map {
        // The generated protobuf and gRPC stubs are not this project's code to cover.
        fileTree(it) { exclude("containerd/**", "runtimeoptions/**") }
    }))
}

// ---- CNI networking (optional, published separately) ----
// A source set rather than part of main: it pulls libcni-java and, through it, Gson, and this
// library's core has no JSON dependency by design. Consumers who do not want CNI never see either.
val cni = sourceSets.create("cni")

cni.compileClasspath += sourceSets.main.get().output
cni.runtimeClasspath += sourceSets.main.get().output

dependencies {
    "cniImplementation"("io.libcni:libcni-java:0.1.1-SNAPSHOT")
    "cniImplementation"("org.slf4j:slf4j-api:$slf4jVersion")
}

val cniJar = tasks.register<Jar>("cniJar") {
    description = "The CNI networking implementation, as its own artifact"
    group = "build"
    archiveBaseName.set("containerd-java-cni")
    from(cni.output)
}
tasks.named("assemble") { dependsOn(cniJar) }

val cniSourcesJar = tasks.register<Jar>("cniSourcesJar") {
    archiveBaseName.set("containerd-java-cni")
    archiveClassifier.set("sources")
    from(cni.allSource)
}
val cniJavadoc = tasks.register<Javadoc>("cniJavadoc") {
    setDestinationDir(layout.buildDirectory.dir("docs/cniJavadoc").get().asFile)
    source(cni.allJava)
    classpath = cni.compileClasspath
}
val cniJavadocJar = tasks.register<Jar>("cniJavadocJar") {
    archiveBaseName.set("containerd-java-cni")
    archiveClassifier.set("javadoc")
    from(cniJavadoc)
}
publishing {
    publications {
        create<MavenPublication>("core") {
            from(components["java"])
        }
        create<MavenPublication>("cni") {
            artifactId = "containerd-java-cni"
            artifact(cniJar)
            artifact(cniSourcesJar)
            artifact(cniJavadocJar)
            pom.withXml {
                val dependencies = asNode().appendNode("dependencies")
                for ((group, artifact, version) in listOf(
                    Triple("io.nanofaas", "containerd-java", project.version.toString()),
                    Triple("io.libcni", "libcni-java", "0.1.1-SNAPSHOT")
                )) {
                    dependencies.appendNode("dependency").apply {
                        appendNode("groupId", group)
                        appendNode("artifactId", artifact)
                        appendNode("version", version)
                        appendNode("scope", "compile")
                    }
                }
            }
        }
        withType<MavenPublication>().configureEach {
            pom {
                name.set(artifactId)
                description.set("Java containerd client with recoverable container lifecycle and optional CNI networking")
                url.set("https://github.com/Nanofaas/containerd-java")
                licenses { license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                } }
                scm {
                    connection.set("scm:git:https://github.com/Nanofaas/containerd-java.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Nanofaas/containerd-java.git")
                    url.set("https://github.com/Nanofaas/containerd-java")
                }
                developers { developer { id.set("miciav"); name.set("Michele") } }
            }
        }
    }
}

// ---- end-to-end demo application ----
// Exercises both libraries against a real containerd, and is what the Multipass scenario runs
// inside the VM. Its own source set so it never reaches a consumer, and shipped as a directory of
// jars rather than a fat one: gRPC finds its providers through META-INF/services files that a
// naive merge would silently drop, and the failure would look like a broken channel.
val e2e = sourceSets.create("e2e")

e2e.compileClasspath += sourceSets.main.get().output + cni.output +
        configurations["runtimeClasspath"] + configurations["cniRuntimeClasspath"]
e2e.runtimeClasspath += sourceSets.main.get().output + cni.output +
        configurations["runtimeClasspath"] + configurations["cniRuntimeClasspath"]

dependencies {
    "e2eRuntimeOnly"("org.slf4j:slf4j-simple:$slf4jVersion")
}

tasks.register<Sync>("e2eDistribution") {
    description = "Assembles the end-to-end demo and everything it needs to run"
    group = "build"
    into(layout.buildDirectory.dir("e2e-dist"))
    // The e2e and cni classpaths overlap; the same jar from either is the same file.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // The whole runtime classpath of the source set, not just its own configuration: a custom
    // source set inherits none of main's dependencies, so asking the configuration alone yields
    // a handful of jars and a NoClassDefFoundError at the first gRPC call.
    from(e2e.output) { into("classes") }
    from(sourceSets.main.get().output) { into("classes") }
    from(cni.output) { into("classes") }
    from(e2e.runtimeClasspath.filter { it.isFile && it.name.endsWith(".jar") }) { into("lib") }
    from(configurations["e2eRuntimeClasspath"].filter { it.name.endsWith(".jar") }) { into("lib") }
}

// ---- integration tests (require a real containerd; not part of `check`) ----
val integrationTest = sourceSets.create("integrationTest")

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
// main output is not on custom source set classpaths automatically (only the default `test`
// source set gets that wiring) — without this, integration tests cannot see the library classes
integrationTest.compileClasspath += files(sourceSets.main.get().output)
integrationTest.runtimeClasspath += files(sourceSets.main.get().output)
// The CNI integration tests drive the optional module, so they need it and its dependencies.
integrationTest.compileClasspath += sourceSets["cni"].output + configurations["cniRuntimeClasspath"]
integrationTest.runtimeClasspath += sourceSets["cni"].output + configurations["cniRuntimeClasspath"]

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against a real containerd on /run/containerd/containerd.sock"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    // The CNI tests need root and installed plugins, which the rest do not. Kept out so that a
    // skip in this task still means something is wrong.
    filter {
        excludeTestsMatching("io.nanofaas.containerd.Cni*IT")
        excludeTestsMatching("io.nanofaas.containerd.Rootless*IT")
    }
    systemProperty("io.nanofaas.containerd.socket", System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock"))
    testLogging { events("failed", "skipped") }
}

tasks.register<Test>("cniIntegrationTest") {
    description = "Runs the CNI networking tests; needs root and CNI plugins in /opt/cni/bin"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    // By name: every CNI test is Cni…IT, so a new one joins this task rather than silently
    // landing in the other and failing there for want of root.
    filter { includeTestsMatching("io.nanofaas.containerd.Cni*IT") }
    systemProperty("io.nanofaas.containerd.socket",
        System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock"))
    testLogging { events("failed", "skipped") }
}

tasks.register<Test>("rootlessIntegrationTest") {
    description = "Verifies delegated cgroup v2 limits against a rootless containerd"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("io.nanofaas.containerd.Rootless*IT") }
    System.getProperties().stringPropertyNames().filter { it.startsWith("io.nanofaas.containerd.") }
        .forEach { systemProperty(it, System.getProperty(it)) }
    testLogging { events("failed", "skipped", "passed") }
}

application {
    mainClass.set("io.nanofaas.containerd.example.Example")
}

// What to analyse. The server URL and the token come from the orchestrator at invocation time.
sonar {
    properties {
        property("sonar.projectKey", "io.nanofaas:containerd-java")
        property("sonar.projectName", "containerd-java")
        // Vendored containerd protos and everything generated from them are not this project's code.
        property("sonar.exclusions", "**/build/generated/**,**/src/main/proto/**")
        property("sonar.junit.reportPaths", "build/test-results/test")
        // Kotlin has no @SuppressWarnings for Sonar rules and the analyzer does not honour
        // NOSONAR here, so this rule is turned off for the build file by name. The dependency
        // block is grouped by subject, with each scope sitting next to the comment justifying it;
        // regrouping by configuration would separate every dependency from its reasoning.
        property("sonar.issue.ignore.multicriteria", "buildFileGrouping")
        property("sonar.issue.ignore.multicriteria.buildFileGrouping.ruleKey", "kotlin:S6629")
        property("sonar.issue.ignore.multicriteria.buildFileGrouping.resourceKey", "**/build.gradle.kts")
        property("sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path)
    }
}

// ---- SonarQube analysis driven by this library (not part of the published artifact) ----
// Its own source set so the orchestrator never reaches a consumer's classpath, the same reason
// the example's SLF4J backend is kept off it.
val sonar = sourceSets.create("sonar")

// A custom source set inherits none of main's dependencies, so give it main's own runtime
// classpath. Without it the epoll natives are missing and the channel factory fails with
// NoClassDefFoundError on io.netty.channel.epoll.Epoll.
sonar.compileClasspath += sourceSets.main.get().output + configurations["runtimeClasspath"]
sonar.runtimeClasspath += sourceSets.main.get().output + configurations["runtimeClasspath"]

dependencies {
    "sonarRuntimeOnly"("org.slf4j:slf4j-simple:$slf4jVersion")
}

tasks.register<JavaExec>("sonarAnalysis") {
    description = "Runs SonarQube in containers driven by this library, and analyses this project"
    group = "verification"
    mainClass.set("io.nanofaas.containerd.sonar.SonarAnalysis")
    classpath = sonar.runtimeClasspath
    // The scanner reads compiled classes and test results, so make sure they are there.
    dependsOn(tasks.named("build"))
    systemProperty("io.nanofaas.containerd.socket",
        System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock"))
    args = (findProperty("sonarArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}

// The runnable example gets an SLF4J backend on the `run` classpath only. Using `runtimeOnly`
// (as the plan suggested) would publish slf4j-simple to every consumer of this library, which
// the spec forbids ("slf4j-simple is test/example scope only") and which would collide with a
// consumer's own SLF4J binding. A dedicated configuration keeps it off the published classpath.
val exampleLogging = configurations.create("exampleLogging")
dependencies {
    exampleLogging("org.slf4j:slf4j-simple:$slf4jVersion")
}
tasks.named<JavaExec>("run") {
    classpath += exampleLogging
}

// ---- GraalVM native image ----
// The reachability metadata under src/main/resources/META-INF/native-image is what makes a
// consumer's native build work without their having to run the tracing agent themselves. It was
// recorded from a real run rather than written by hand, and covers what this library needs from
// gRPC, Netty and protobuf, plus the mkfifo downcall.
graalvmNative {
    binaries {
        named("test") { buildArgs.add("--enable-native-access=ALL-UNNAMED") }
        named("main") {
            imageName.set("containerd-java-example")
            mainClass.set("io.nanofaas.containerd.example.Example")
            // Explicit: applying java-library makes the plugin default to a shared library, and
            // what is wanted here is a runnable image — the point is to prove one starts and
            // drives containerd, which a .so cannot do.
            sharedLibrary.set(false)
            // Netty loads its epoll library through a restricted method; without this the image
            // prints a warning on every run that a future JDK will turn into a failure.
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
        }
    }
    // The agent is run by hand when the metadata needs refreshing, not on every build.
    agent { enabled.set(false) }
}

// ---- protobuf / gRPC stub generation ----
// Vendored protos under src/main/proto mirror the containerd API pinned above by
// $containerdApiVersion; the Task 2 download step read this same pin from this file.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc") {}
            }
        }
    }
}
