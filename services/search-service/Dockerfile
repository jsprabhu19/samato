FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY pom.xml ../
COPY shared shared
COPY shared-kafka shared-kafka
COPY services/search-service services/search-service
RUN --mount=type=cache,target=/root/.m2 \
    cd services/search-service && \
    mvn -B -pl . -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /workspace/services/search-service/target/*.jar /app/app.jar
EXPOSE 8087
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost:8087/actuator/health/liveness | grep -q UP || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
