plugins {
    java
}

group = "net.newminecraftservers"
version = "0.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.compileJava {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("new-minecraft-servers-companion")
    archiveVersion.set(project.version.toString())
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest.attributes["Implementation-Version"] = project.version
}
