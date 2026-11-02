FROM gcr.io/distroless/java25:nonroot@sha256:a90a5fadd78dc37b643da72dca2f744ef67eba625eca7227a5b375b548e6b1c2
ENV TZ="Europe/Oslo"
ENV JAVA_TOOL_OPTIONS=-XX:+ExitOnOutOfMemoryError
WORKDIR /app
COPY app/build/libs/*.jar ./app.jar
EXPOSE 8080
CMD ["app.jar"]
