FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B -q || true
COPY shared ../shared
COPY shared-kafka ../shared-kafka
COPY services/order-service ./services/order-service
RUN ./mvnw -pl services/order-service -am clean package -DskipTests -B

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /app/services/order-service/target/order-service-*.jar /app/app.jar
EXPOSE 8083
USER nonroot
ENTRYPOINT ["java","-jar","/app/app.jar"]
