# Multi-stage removed: the local reactor build (`mvn -B -DskipTests package`)
# is the single source of truth for JARs. Building inside Docker was a
# maintenance trap (had to re-resolve deps, recreate the shared module
# install step, and was failing because the parent pom wasn't on the
# classpath of the inner mvn). This Dockerfile just copies the locally-
# built fat-jar into a minimal JRE image.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
COPY services/auth-service/target/auth-service-1.0.0-SNAPSHOT.jar /app/app.jar
EXPOSE 9000
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost:9000/actuator/health/liveness | grep -q UP || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
