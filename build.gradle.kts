plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
}

base {
    archivesName = properties["archives_base_name"] as String
    group = properties["maven_group"] as String
    version = properties["mod_version"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:${properties["minecraft_version"] as String}")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")

    // Meteor Client
    modImplementation("meteordevelopment:meteor-client:${properties["meteor_version"] as String}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    processResources {
        val propertyMap = mapOf("version" to project.version)
        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
