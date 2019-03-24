FROM maven:3.6.0-jdk-11-slim as build-env
ADD . /build/tfbridge
WORKDIR /build/tfbridge
RUN mvn clean install
RUN mkdir -p /app
RUN mv target/tfbridge*.jar /app/tfbridge.jar

FROM gcr.io/distroless/java:11
COPY --from=build-env /app /app
WORKDIR /app
CMD ["tfbridge.jar"]