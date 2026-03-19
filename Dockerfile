# Multi-stage build for backend
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN microdnf install --nodocs git && \
    ./mvnw clean package -DskipTests && \
    microdnf remove git

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:9000/health || exit 1

EXPOSE 9000
ENTRYPOINT ["java", "-jar", "app.jar"]
