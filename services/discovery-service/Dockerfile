# See services/auth-service/Dockerfile for the rationale on this layout.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
COPY services/discovery-service/target/discovery-service-1.0.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8761
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost:8761/actuator/health/liveness | grep -q UP || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
