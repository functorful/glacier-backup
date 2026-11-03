plugins { java }

subprojects {
    apply(plugin = "java")
    repositories { mavenCentral() }
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    tasks.withType<Test> { useJUnitPlatform() }
    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj)
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
