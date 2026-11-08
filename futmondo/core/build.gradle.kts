plugins {
    kotlin("jvm")
}

dependencies {
    // En Android org.json ya viene en el sistema; aquí solo se usa para compilar y probar.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
