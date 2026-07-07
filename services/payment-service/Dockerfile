FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY shared ../shared
COPY shared-kafka ../shared-kafka
COPY services/payment-service ./services/payment-service
RUN ./mvnw -pl services/payment-service -am clean package -DskipTests -B || \
    mvn -pl services/payment-service -am clean package -DskipTests -B

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /app/services/payment-service/target/payment-service-*.jar /app/app.jar
EXPOSE 8084
USER nonroot
ENTRYPOINT ["java","-jar","/app/app.jar"]
