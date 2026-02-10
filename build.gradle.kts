plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("plugin.jpa") version "2.2.21"
    id("org.springframework.boot") version "4.0.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.khj"
version = "0.0.1-SNAPSHOT"
description = "rag-document"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Database
    runtimeOnly("org.postgresql:postgresql")

    // 문서 파싱
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("org.jsoup:jsoup:1.18.1")

    // Milvus (불필요한 Bulk Writer 의존성 제외 - Hadoop, Parquet 등 CVE 다수)
    implementation("io.milvus:milvus-sdk-java:2.6.13") {
        exclude(group = "org.apache.hadoop")
        exclude(group = "org.apache.parquet")
        exclude(group = "org.apache.avro")
        exclude(group = "io.minio")
        exclude(group = "io.airlift", module = "aircompressor")
        exclude(group = "com.azure", module = "azure-identity")
        exclude(group = "com.azure", module = "azure-storage-blob")
        exclude(group = "com.amazonaws")
        exclude(group = "com.aliyun.oss")
        exclude(group = "com.tencentcloudapi")
    }

    // Slack
    implementation("com.slack.api:bolt-jakarta-servlet:1.45.3")
    implementation("com.slack.api:slack-api-client:1.45.3")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
