FROM gcr.io/distroless/java25:nonroot@sha256:59bbb349c9752fc57b75744b41b1614c9dabe8bb4869523aabd5a4288f6c09a0
ENV TZ="Europe/Oslo"
ENV JAVA_TOOL_OPTIONS=-XX:+ExitOnOutOfMemoryError
WORKDIR /app
COPY app/build/libs/*.jar ./app.jar
EXPOSE 8080
CMD ["app.jar"]
