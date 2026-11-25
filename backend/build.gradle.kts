plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.medimate"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        // 설치된 JDK가 무엇이든(Android Studio JBR 25 등) 그것으로 Java 21 바이트코드를 만든다
        languageVersion = JavaLanguageVersion.of(JavaVersion.current().majorVersion.toInt())
    }
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // 복약 일정 저장: 기본은 설치 없는 파일 DB(H2), 운영은 PostgreSQL
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    // Firebase 로그인 토큰(JWT) 검증
    implementation("com.nimbusds:nimbus-jose-jwt:9.37.3")
    // EXIF 회전값 읽기
    implementation("com.drewnoakes:metadata-extractor:2.22.0")
    // /docs (Swagger UI)
    implementation("org.webjars:swagger-ui:5.34.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.atlassian.oai:swagger-request-validator-mockmvc:3.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 테스트 런처 JVM은 파일 인코딩을 OS 기본값으로 잡는다. 한글 문장 비교가 깨지지 않게 고정한다
    systemProperty("file.encoding", "UTF-8")
    jvmArgs("-Duser.timezone=UTC", "-XX:+EnableDynamicAgentLoading")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
        showStandardStreams = true
    }
}
