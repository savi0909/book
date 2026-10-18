# ============================================================
# Mock Payment Gateway — Multi-stage Docker Build
# ============================================================
# Standalone Spring Boot app that mimics Razorpay APIs.
# Used alongside fitpro-customer-api for dev/staging/demo.
# ============================================================

# --- Stage 1: Build ---
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Package resolves project dependencies into the persistent Maven cache below.
# Avoid dependency:go-offline, which also resolves unrelated Maven plugin dependencies.
COPY pom.xml ./pom.xml

# Copy source
COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B \
    && mv target/mock-payment-gateway-*.jar target/app.jar

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="abhishek@aaztratech.com"
LABEL app="mock-payment-gateway"
LABEL description="Razorpay-compatible mock payment gateway for FitPro"

RUN apk add --no-cache curl

WORKDIR /app

COPY --from=builder /build/target/app.jar app.jar

ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseG1GC -XX:+UseContainerSupport"

EXPOSE 8095

HEALTHCHECK --interval=15s --timeout=5s --retries=3 --start-period=30s \
    CMD curl -f http://localhost:8095/mock-pg/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=8095 -Dserver.address=0.0.0.0 -jar app.jar"]
