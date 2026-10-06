FROM gcr.io/distroless/java25:nonroot
ENV TZ="Europe/Oslo"
ENV JAVA_TOOL_OPTIONS=-XX:+ExitOnOutOfMemoryError
WORKDIR /app
COPY build/libs/*.jar ./app.jar
EXPOSE 8080
CMD ["app.jar"]
