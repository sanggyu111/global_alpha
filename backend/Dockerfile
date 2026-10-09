# 백엔드 이미지 (Railway 배포용). 빌드 컨텍스트는 저장소 루트여야 한다:
#   docker build -f backend/Dockerfile .
# 백엔드 빌드가 저장소 루트의 db/migration · db/seed 를 jar 안에 넣기 때문 (build.gradle processResources, 설계 3.3).

# ---- 1단계: Gradle 로 실행 가능한 jar 생성 ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# 의존성만 먼저 받아 두면 소스만 바뀐 재빌드에서 이 단계가 캐시된다
COPY backend/gradlew backend/settings.gradle backend/build.gradle backend/
COPY backend/gradle backend/gradle
RUN chmod +x backend/gradlew && cd backend && ./gradlew --no-daemon -q dependencies > /dev/null

COPY backend/src backend/src
COPY db db
# 테스트는 Testcontainers(Docker)가 필요해 이미지 빌드 안에서는 돌리지 않는다 — 로컬에서 ./gradlew test 로 검증
RUN cd backend && ./gradlew --no-daemon bootJar -x test

# ---- 2단계: JRE 만 있는 실행 이미지 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/backend/build/libs/backend-0.0.1-SNAPSHOT.jar app.jar

# 컨테이너 메모리 한도의 75% 를 힙으로 (무료 플랜의 작은 메모리에서 OOM 으로 죽지 않게)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# 포트는 플랫폼이 PORT 환경변수로 준다 (application.yml: server.port=${PORT:8080})
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
