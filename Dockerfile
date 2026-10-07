FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/sd-book-my-show-1.0.0.jar app.jar
USER 10001
# 512 MB container: ~300 MB heap leaves room for ~150 MB metaspace/code cache/threads.
# Serial GC is explicit so ergonomics cannot change with a different CPU/memory limit.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"
ENTRYPOINT ["java","-jar","app.jar"]
