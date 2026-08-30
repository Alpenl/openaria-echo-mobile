package com.openaria.openaria_echo_mobile.body.api

import com.openaria.openaria_echo_mobile.security.EndpointPolicy
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ArtifactDownloadStoreTest {
    @Test
    fun `successful download atomically publishes verified file and removes temporary file`() {
        withStore { directory, store, server ->
            val descriptor = artifactDescriptor()
            server.enqueue(headResponse(descriptor))
            server.enqueue(downloadResponse(descriptor, "hello"))

            val result = store.download(
                client = DeviceHttpClient(),
                connection = connection(server),
                sessionId = SESSION_ID,
                artifact = descriptor,
            )

            val saved = assertIs<ArtifactFileResult.Saved>(result)
            val published = File(saved.path)
            assertTrue(published.isFile)
            assertContentEquals("hello".toByteArray(), published.readBytes())
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
            assertEquals("HEAD", server.takeRequestOrFail().method)
            val get = server.takeRequestOrFail()
            assertEquals("GET", get.method)
            assertNull(get.getHeader("Range"))
        }
    }

    @Test
    fun `digest mismatch cleans temporary file and explicit retry restarts from byte zero`() {
        withStore { directory, store, server ->
            val descriptor = artifactDescriptor()
            server.enqueue(headResponse(descriptor))
            server.enqueue(downloadResponse(descriptor, "HELLO"))
            server.enqueue(headResponse(descriptor))
            server.enqueue(downloadResponse(descriptor, "hello"))

            val first = store.download(
                client = DeviceHttpClient(),
                connection = connection(server),
                sessionId = SESSION_ID,
                artifact = descriptor,
            )

            val rejected = assertIs<ArtifactFileResult.DownloadRejected>(first)
            assertIs<ArtifactDownloadResult.IntegrityFailure>(rejected.reason)
            assertTrue(directory.listFiles().orEmpty().isEmpty(), "failed transfer must leave no target or .part")

            val retried = store.download(
                client = DeviceHttpClient(),
                connection = connection(server),
                sessionId = SESSION_ID,
                artifact = descriptor,
            )

            assertIs<ArtifactFileResult.Saved>(retried)
            val requests = List(4) { server.takeRequestOrFail() }
            assertEquals(listOf("HEAD", "GET", "HEAD", "GET"), requests.map { it.method })
            assertNull(requests[1].getHeader("Range"))
            assertNull(requests[3].getHeader("Range"))
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
        }
    }

    @Test
    fun `cancelled transfer removes temporary file and publishes nothing`() {
        withStore { directory, store, server ->
            val descriptor = artifactDescriptor()
            server.enqueue(headResponse(descriptor))
            server.enqueue(downloadResponse(descriptor, "hello"))

            val result = store.download(
                client = DeviceHttpClient(),
                connection = connection(server),
                sessionId = SESSION_ID,
                artifact = descriptor,
                shouldCancel = { true },
            )

            val rejected = assertIs<ArtifactFileResult.DownloadRejected>(result)
            assertIs<ArtifactDownloadResult.Cancelled>(rejected.reason)
            assertTrue(directory.listFiles().orEmpty().isEmpty(), "cancelled transfer must remove its .part")
        }
    }

    private fun withStore(block: (File, ArtifactDownloadStore, MockWebServer) -> Unit) {
        val directory = Files.createTempDirectory("artifact-store-test-").toFile()
        val server = MockWebServer()
        try {
            block(directory, ArtifactDownloadStore(directory), server)
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    private fun headResponse(descriptor: ArtifactDescriptor): MockResponse {
        return representationHeaders(MockResponse().setResponseCode(200), descriptor)
    }

    private fun downloadResponse(descriptor: ArtifactDescriptor, body: String): MockResponse {
        return representationHeaders(
            MockResponse()
                .setResponseCode(200)
                .setBody(body),
            descriptor,
        )
    }

    private fun representationHeaders(
        response: MockResponse,
        descriptor: ArtifactDescriptor,
    ): MockResponse {
        return response
            .setHeader("Content-Type", descriptor.mediaType)
            .setHeader("Content-Length", descriptor.bytes)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("ETag", "\"${descriptor.artifactId}\"")
    }

    private fun connection(server: MockWebServer): DeviceConnection {
        val origin = server.url("/").toString().removeSuffix("/")
        val target = assertIs<EndpointPolicy.Decision.Allowed>(EndpointPolicy.validate(origin)).target
        return DeviceConnection(
            target = target,
            descriptor = DeviceDescriptor(
                deviceId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                deviceLabel = "YLX-00ABCDEF",
                hardwareFingerprint = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                packageVersion = "0.1.8",
                commit = "77f24f3777777777777777777777777777777777",
                buildId = "test",
                securityProfile = "customer",
                captureCapable = true,
                previewCapable = true,
                rangeDownloadCapable = true,
                networkMutationCapable = false,
                sessionListCapable = true,
                sessionDetailCapable = true,
                artifactDownloadCapable = true,
                captureStatusCapable = true,
                sessionDeletionCapable = false,
                volumeId = "56005c52-31f1-4dac-91cd-d8eafd737d1c",
                totalBytes = 1_024L,
                availableBytes = 512L,
                writable = true,
                runtime = DeviceRuntime(
                    observedAt = "2026-08-31T10:00:00Z",
                    connectionMethod = "wifi_client",
                    temperatureCelsius = 45.0,
                ),
            ),
            bearerToken = null,
        )
    }

    private fun artifactDescriptor(): ArtifactDescriptor {
        val sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        return ArtifactDescriptor(
            artifactId = sha256,
            role = "frames.index",
            path = "frames.ndjson",
            mediaType = "application/x-ndjson",
            bytes = 5L,
            sha256 = sha256,
        )
    }

    private fun MockWebServer.takeRequestOrFail() =
        takeRequest(2, TimeUnit.SECONDS) ?: error("expected artifact request")

    private companion object {
        const val SESSION_ID = "01991b70-7c88-7123-9234-123456789abc"
    }
}
