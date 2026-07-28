FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package -DskipTests

FROM build AS extract
WORKDIR /extract
RUN java -Djarmode=tools -jar /build/target/evmap-service-*.jar extract --layers --launcher --destination .

# Alpine/musl JRE: ~180 MB smaller than the glibc `25-jre` image. No HTTP client is installed —
# the Compose healthcheck uses the busybox `wget` that is already part of the base image. (The check
# lives in Compose because podman builds OCI images, which ignore HEALTHCHECK.)
#
# Everything from here down is kept byte-identical to Dockerfile.sync so both images share the same
# layers on a host or registry that holds them together.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S spring && adduser -S -G spring spring
WORKDIR /app
COPY --from=extract --chown=spring:spring /extract/dependencies/ ./
COPY --from=extract --chown=spring:spring /extract/spring-boot-loader/ ./
COPY --from=extract --chown=spring:spring /extract/snapshot-dependencies/ ./
COPY --from=extract --chown=spring:spring /extract/application/ ./
USER spring
EXPOSE 8080
# Structured (ECS JSON) logging on stdout; override LOG_FORMAT to get plain text in a container.
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
