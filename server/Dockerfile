# 서버 안에서 직접 빌드한다 (PC에 Java가 없어도 됨)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 medimate
COPY --from=build /src/build/libs/medimate-server.jar app.jar
USER medimate
EXPOSE 8080
# 1GB 서버에서도 돌도록 메모리 상한을 둔다
ENV JAVA_OPTS="-Xmx384m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
