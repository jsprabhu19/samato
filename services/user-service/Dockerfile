# See services/auth-service/Dockerfile for the rationale on this layout.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
COPY services/user-service/target/user-service-1.0.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost:8081/actuator/health/liveness | grep -q UP || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
