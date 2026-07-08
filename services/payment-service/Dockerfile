FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
RUN apk add --no-cache maven
COPY pom.xml ./
COPY shared shared
COPY shared-kafka shared-kafka
# Copy the entire services tree so the parent reactor can find every
# module it lists. (Maven validates that all <module> entries exist
# even if -am only needs a subset.)
COPY services services
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl services/payment-service -am -DskipTests clean package

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /workspace/services/payment-service/target/payment-service-*.jar /app/app.jar
EXPOSE 8084
USER nonroot
ENTRYPOINT ["java","-jar","/app/app.jar"]
