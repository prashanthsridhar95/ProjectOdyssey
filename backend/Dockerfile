# Build stage: compile the backend and produce a runnable distribution.
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY core ./core
COPY pack-carwash ./pack-carwash
COPY server ./server
RUN gradle :server:installDist --no-daemon -q -x test

# Runtime stage: just a JRE and the distribution.
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 1001 odyssey
WORKDIR /app
COPY --from=build /src/server/build/install/server /app
USER odyssey
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=10 CMD curl -fs http://localhost:8080/health || exit 1
CMD ["/app/bin/server"]
