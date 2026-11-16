FROM gcr.io/distroless/java25:nonroot@sha256:1c4ced761d17961c900cba60c33c8c3c46edbdadaa9d5ef9806eb5a6824fd406
ENV TZ="Europe/Oslo"
ENV JAVA_TOOL_OPTIONS=-XX:+ExitOnOutOfMemoryError
WORKDIR /app
COPY app/build/libs/*.jar ./app.jar
EXPOSE 8080
CMD ["app.jar"]
