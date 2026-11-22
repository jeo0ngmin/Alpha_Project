// 메디메이트 서버: Claude 키 보관·호출 중계 (server/README.md)
plugins {
    java
    id("org.springframework.boot") version "4.1.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.medimate"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("com.google.firebase:firebase-admin:9.11.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.bootJar {
    archiveFileName = "medimate-server.jar"
}
