import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: pinned sibling JAR (symlinked next to the worktree).
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.66.jar"))
    } else {
        // CI: downloaded by the shared release workflow.
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Compose Icons (FeatherIcons)
    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Parsing `docker ... --format {{json .}}` output
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

// The default `jar` writes a classes-only archive into the same build/libs the
// release workflow uploads wholesale. Our thin jar would be named
// `boss-plugin-docker-<ver>-thin.jar` — which matches BOTH the plugin store's
// "contains plugin/boss" asset pick and the host's GitHub-asset regex, so a
// classifier is not enough to keep it from being shipped as the real plugin.
// Disabled outright (the editor-tab precedent); buildPluginJar is canonical.
tasks.named<Jar>("jar") {
    enabled = false
}

// Task to build plugin JAR with compiled classes only (deps provided by host)
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-docker-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Docker Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.docker.DockerDynamicPlugin"
        )
    }

    from(sourceSets.main.get().output)
    from("src/main/resources")
}

// Sync version from build.gradle.kts into plugin.json (single source of truth).
// The inputs.property line is REQUIRED: without it a version-only bump leaves
// this task UP-TO-DATE and ships a stale plugin.json.
tasks.processResources {
    inputs.property("pluginVersion", version)
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$version"""")
        }
    }
}

tasks.build { dependsOn("buildPluginJar") }
