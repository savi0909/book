FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
WORKDIR /app
COPY target/book-my-show-load-tester-1.0.0.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-Xmx192m -XX:MaxRAMPercentage=50"
ENTRYPOINT ["java","-jar","app.jar"]
