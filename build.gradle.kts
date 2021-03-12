plugins {
    java
    kotlin("jvm") version "1.4.31"
    kotlin("plugin.serialization") version "1.4.31"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

application{
    mainClass.set("MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.mariadb.jdbc", "mariadb-java-client", "2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.3")
}

val run by tasks.getting(JavaExec::class) {
    standardInput = System.`in`
}