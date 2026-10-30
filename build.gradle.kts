plugins {
    java
}

group = "net.azisaba.airpen"
version = "1.0.0-1.21.11"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    val protocolLibJar = providers.gradleProperty("protocolLibJar").orNull
    if (protocolLibJar != null) {
        compileOnly(files(protocolLibJar))
    } else {
        compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    }
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

tasks.compileJava {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
