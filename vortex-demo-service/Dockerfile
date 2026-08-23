# Packages the already-built demo-service jar as a container image — this file never compiles
# anything itself. Vortex is an experiment runner, not a build system: it consumes an existing
# image, it never runs `docker build`. Build with `make demo-image` (which builds the jar with
# Maven first, exactly as the fast JVM/macOS dev loop already does, then packages it here), never
# invoked by Vortex at runtime.
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY target/vortex-demo-service.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
