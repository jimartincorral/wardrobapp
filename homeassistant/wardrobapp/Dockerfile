# The Home Assistant app's image: the server and the browser app, on a Java
# runtime.
#
# Nothing is compiled here. The workflow that builds this image builds the
# server's distribution and the browser app's bundle first, with Gradle, and
# this copies them in -- both are bytecode or WebAssembly, the same files for
# every architecture, so only the runtime underneath differs between images,
# and the slow part (the Wasm optimiser) runs once rather than once per
# architecture under emulation.
#
# Built from the repository's root:
#
#     ./gradlew :server:installDist :web:wasmJsBrowserDistribution
#     docker build -f homeassistant/wardrobapp/Dockerfile .

# Temurin's Java 17 runtime, which is the version every module is compiled for,
# on Ubuntu: one tag that is published for both amd64 and arm64, so the same
# line serves both images.
FROM eclipse-temurin:17-jre-jammy

ARG BUILD_VERSION=development
ARG BUILD_NUMBER=0
ARG BUILD_ARCH=amd64

COPY server/build/install/wardrobapp-server /opt/wardrobapp
COPY web/build/dist/wasmJs/productionExecutable /opt/wardrobapp/web

# See ServerSettings for what each of these does.
#
# The allowed client is the address Home Assistant's ingress forwards from.
# Other apps on the same internal network can reach this container too, and
# they have not been through Home Assistant's sign-in; refusing everything but
# ingress is what Home Assistant asks of an app that uses it.
#
# Serial GC and a quarter of the memory: a household's wardrobe on a machine
# that is also running Home Assistant, which is a reason to be small rather
# than fast.
ENV WARDROBAPP_DATA=/data \
    WARDROBAPP_PORT=8099 \
    WARDROBAPP_SYNC_PORT=8100 \
    WARDROBAPP_WEB=/opt/wardrobapp/web \
    WARDROBAPP_ALLOWED_CLIENTS=172.30.32.2 \
    WARDROBAPP_VERSION=${BUILD_VERSION} \
    WARDROBAPP_BUILD=${BUILD_NUMBER} \
    JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=25"

LABEL io.hass.type="addon" \
      io.hass.version="${BUILD_VERSION}" \
      io.hass.arch="${BUILD_ARCH}" \
      org.opencontainers.image.title="Wardrobapp" \
      org.opencontainers.image.source="https://github.com/jimartincorral/wardrobapp"

CMD ["/opt/wardrobapp/bin/wardrobapp-server"]
