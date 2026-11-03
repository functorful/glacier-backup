plugins { `java-library` }
dependencies {
    api(project(":application"))
    implementation(libs.awssdk.s3)
    implementation(libs.awssdk.s3transfermanager)
    implementation(libs.awssdk.netty.nio.client)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.test {
    useJUnitPlatform { excludeTags("integration", "memory") }
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs LocalStack-backed integration tests tagged \"integration\"."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

// Heap-capped regression guard for the scan-merge memory footprint (see
// NioLocalVolumeMemoryRegressionTest: a scan of ~1M files previously got the
// whole process OOM-killed on a small NAS). Runs in its own forked JVM so the
// cap applies only to this test, not the whole test task. 320m is a *measured,
// non-vacuous* cap (from a sweep of heap sizes): the fixed implementation
// reliably passes at 320m (and down to ~280m), while feeding the same
// 1,000,000-entry workload through the pre-fix
// ScanEntry/mergeBatches/ScanAccumulator combination reliably fails with
// OutOfMemoryError at 350m and below, only passing at 400m+. Do not raise
// this back toward 384m+ without re-verifying against pre-fix code that it
// still fails — a cap that passes on both fixed and pre-fix code is vacuous.
tasks.register<Test>("memoryTest") {
    group = "verification"
    description = "Runs the heap-capped scan-merge memory regression test tagged \"memory\"."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("memory") }
    maxHeapSize = "320m"
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(tasks.named("memoryTest"))
}
