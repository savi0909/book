FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/ticket-booking-lab-1.0.0.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65"
ENTRYPOINT ["java","-jar","app.jar"]
