# FabWatch 백엔드 이미지 (다단계: JDK 17 빌드 → JRE 17 런타임)
# 빌드: docker build -t fabwatch-backend ./backend      (컨텍스트 = backend/)
# 실행: 프로파일·DB·시크릿은 전부 환경변수로 주입한다 (SPRING_PROFILES_ACTIVE=prod, DB_URL, JWT_SECRET, ... docs/13 §3)

# ---------- 1단계: 빌드 ----------
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# 소형 인스턴스에서 빌드해도 메모리를 과하게 쓰지 않도록 Gradle 데몬 JVM 상한을 둔다
ENV GRADLE_OPTS="-Xmx768m -Dorg.gradle.daemon=false"

# 의존성 레이어 캐시: 소스가 바뀌어도 build.gradle이 같으면 의존성 다운로드를 재사용한다
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
# 테스트는 CI/로컬에서 이미 통과시킨다(./gradlew test). 이미지 빌드는 jar 생성만 한다.
# bootJar는 application-local.yml(로컬 DB 비밀번호·개발용 JWT 키)을 jar에서 제외한다(build.gradle).
RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/fabwatch-*-SNAPSHOT.jar /workspace/app.jar

# ---------- 2단계: 런타임 ----------
FROM eclipse-temurin:17-jre-jammy

# 비루트 실행 사용자 (고정 uid — 볼륨 권한 맞추기 쉽게)
RUN groupadd --system --gid 10001 fabwatch \
    && useradd --system --uid 10001 --gid fabwatch --no-create-home --shell /usr/sbin/nologin fabwatch

WORKDIR /app
COPY --from=build --chown=fabwatch:fabwatch /workspace/app.jar /app/app.jar

# JVM 메모리: 컨테이너 한도(compose의 mem_limit)를 인식해 힙을 그 65%로 제한한다. 한도를 안 주면 호스트 전체 RAM 기준이 되므로
# compose에서 백엔드에 mem_limit를 반드시 지정할 것(소형 EC2에서 MySQL과 함께 쓰는 경우 특히).
# SerialGC: 코어 1~2개 소형 인스턴스에서 스레드·메모리 오버헤드가 가장 작다. OOM 시 컨테이너가 재시작되도록 즉시 종료.
# 환경변수 JAVA_TOOL_OPTIONS를 덮어써서 튜닝할 수 있다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

# 프로파일을 이미지에 박지 않는다 — 실수로 local/기본 프로파일로 뜨지 않도록 실행 환경에서 SPRING_PROFILES_ACTIVE=prod 명시.
ENV PORT=8080
EXPOSE 8080

USER fabwatch:fabwatch

# /actuator/health 만 공개 엔드포인트다. 기동(Flyway 마이그레이션 포함)에 시간이 걸릴 수 있어 start-period를 넉넉히 둔다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS "http://localhost:${PORT}/actuator/health" | grep -q '"status":"UP"' || exit 1

# exec 형식: SIGTERM이 JVM에 직접 전달돼 graceful shutdown(server.shutdown=graceful)이 동작한다
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
