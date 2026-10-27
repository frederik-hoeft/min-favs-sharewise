FROM eclipse-temurin:25-jre-resolute
WORKDIR /app
COPY target/*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
