FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .

RUN mvn dependency:go-offline -q

COPY src ./src

RUN mvn clean package -DskipTests -q


FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN useradd --system --create-home \
    --shell /usr/sbin/nologin appuser

COPY --from=build /app/target/*.jar app.jar

USER appuser

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=55"

ENTRYPOINT ["java", "-jar", "app.jar"]