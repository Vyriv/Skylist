package dev.ryan.throwerlist

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.AssetInfo
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.imageio.ImageReader

object CapeTextureManager {
    private const val ASSET_ROOT = "assets/"
    private const val REMOTE_CAPE_MAX_BYTES = 4 * 1024 * 1024
    private const val REMOTE_RETRY_DELAY_MILLIS = 30_000L

    private val loadedCapes = ConcurrentHashMap<String, CachedCape>()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .build()

    fun initialize() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.isPaused) {
                return@register
            }

            loadedCapes.values.forEach { it.tick() }
        }
    }

    fun getCapeTexture(resourcePath: String?, capeUrl: String?): AssetInfo.TextureAssetInfo? {
        val normalizedUrl = capeUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedUrl != null) {
            return getRemoteCapeTexture(normalizedUrl)
        }

        val normalizedPath = resourcePath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return getBundledCapeTexture(normalizedPath)
    }

    fun invalidateRemoteCapes() {
        loadedCapes.entries.removeIf { it.key.startsWith("url:") }
    }

    private fun getBundledCapeTexture(resourcePath: String): AssetInfo.TextureAssetInfo? {
        val cacheKey = "asset:$resourcePath"
        val cached = loadedCapes.computeIfAbsent(cacheKey) {
            CachedCape(isRemote = false, loadedCape = loadBundledCape(resourcePath))
        }
        return cached.loadedCape?.textureAsset
    }

    private fun getRemoteCapeTexture(capeUrl: String): AssetInfo.TextureAssetInfo? {
        val cacheKey = "url:$capeUrl"
        val cached = loadedCapes.computeIfAbsent(cacheKey) { CachedCape(isRemote = true) }
        if (cached.loadedCape != null) {
            return cached.loadedCape?.textureAsset
        }
        if (cached.shouldRetry()) {
            requestRemoteCape(cacheKey, capeUrl, cached)
        }
        return null
    }

    private fun requestRemoteCape(cacheKey: String, capeUrl: String, cache: CachedCape) {
        cache.loading = true
        CompletableFuture.supplyAsync {
            downloadRemoteCape(capeUrl)
        }.whenComplete { bytes, throwable ->
            if (throwable != null || bytes == null) {
                cache.loading = false
                cache.lastFailureAt = System.currentTimeMillis()
                ThrowerListMod.logger.warn("Failed to fetch remote cape '{}'", capeUrl, throwable)
                return@whenComplete
            }

            ThrowerListMod.client.execute {
                val loadedCape = runCatching {
                    loadCapeBytes(textureIdForRemote(capeUrl), bytes, capeUrl)
                }.getOrElse { error ->
                    cache.loading = false
                    cache.lastFailureAt = System.currentTimeMillis()
                    ThrowerListMod.logger.warn("Failed to decode remote cape '{}'", capeUrl, error)
                    return@execute
                }

                cache.loadedCape = loadedCape
                cache.loading = false
                cache.lastFailureAt = 0L
                loadedCapes[cacheKey] = cache
                ThrowerListMod.logger.info("Loaded remote cape '{}'", capeUrl)
            }
        }
    }

    private fun loadBundledCape(resourcePath: String): LoadedCape {
        val textureId = Identifier.of("throwerlist", "dynamic_capes/${sanitizePath(resourcePath)}")
        val stream = openResource(resourcePath) ?: return LoadedCape(null, null)

        return stream.use { input ->
            loadCapeStream(textureId, input, resourcePath)
        }
    }

    private fun loadCapeBytes(textureId: Identifier, bytes: ByteArray, sourceName: String): LoadedCape =
        ByteArrayInputStream(bytes).use { input ->
            loadCapeStream(textureId, input, sourceName)
        }

    private fun loadCapeStream(textureId: Identifier, input: InputStream, sourceName: String): LoadedCape =
        if (sourceName.lowercase(Locale.ROOT).endsWith(".gif")) {
            loadAnimatedCape(textureId, input)
        } else {
            loadStaticCape(textureId, input)
        }

    private fun loadStaticCape(textureId: Identifier, input: InputStream): LoadedCape {
        val image = ImageIO.read(input) ?: return LoadedCape(null, null)
        val nativeImage = bufferedImageToNativeImage(image)
        val texture = NativeImageBackedTexture({ textureId.toString() }, nativeImage)
        ThrowerListMod.client.textureManager.registerTexture(textureId, texture)
        return LoadedCape(AssetInfo.TextureAssetInfo(textureId, textureId), null)
    }

    private fun loadAnimatedCape(textureId: Identifier, input: InputStream): LoadedCape {
        val reader = ImageIO.getImageReadersByFormatName("gif").asSequence().firstOrNull()
            ?: return LoadedCape(null, null)
        val imageInput = ImageIO.createImageInputStream(input) ?: return LoadedCape(null, null)

        imageInput.use { stream ->
            reader.input = stream
            val frames = buildList {
                val frameCount = reader.getNumImages(true)
                for (index in 0 until frameCount) {
                    val frame = reader.read(index) ?: continue
                    add(
                        AnimatedFrame(
                            pngBytes = bufferedImageToPngBytes(frame),
                            delayMillis = reader.readDelayMillis(index),
                        ),
                    )
                }
            }
            reader.dispose()

            if (frames.isEmpty()) {
                return LoadedCape(null, null)
            }

            val initialImage = frames.first().toNativeImage()
            val texture = NativeImageBackedTexture({ textureId.toString() }, initialImage)
            ThrowerListMod.client.textureManager.registerTexture(textureId, texture)
            return LoadedCape(
                textureAsset = AssetInfo.TextureAssetInfo(textureId, textureId),
                animatedTexture = AnimatedCapeTexture(texture, frames),
            )
        }
    }

    private fun bufferedImageToNativeImage(image: java.awt.image.BufferedImage): NativeImage {
        return NativeImage.read(ByteArrayInputStream(bufferedImageToPngBytes(image)))
    }

    private fun bufferedImageToPngBytes(image: java.awt.image.BufferedImage): ByteArray {
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }

    private fun openResource(resourcePath: String): InputStream? {
        val normalized = resourcePath.trimStart('/')
        return CapeTextureManager::class.java.classLoader.getResourceAsStream("$ASSET_ROOT$normalized")
    }

    private fun downloadRemoteCape(capeUrl: String): ByteArray {
        val uri = URI.create(capeUrl)
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw IOException("Remote cape URL must use https: $capeUrl")
        }

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(12))
            .header("accept", "image/png,image/gif,image/*,*/*")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) {
            throw IOException("Unexpected response ${response.statusCode()} from $capeUrl")
        }

        val contentType = response.headers().firstValue("content-type").orElse("").lowercase(Locale.ROOT)
        val looksLikeImage = contentType.startsWith("image/png") || contentType.startsWith("image/gif")
        val hasSupportedSuffix = capeUrl.lowercase(Locale.ROOT).endsWith(".png") || capeUrl.lowercase(Locale.ROOT).endsWith(".gif")
        if (!looksLikeImage && !hasSupportedSuffix) {
            throw IOException("Unsupported cape content type '$contentType' from $capeUrl")
        }

        val bytes = response.body()
        if (bytes.isEmpty()) {
            throw IOException("Remote cape '$capeUrl' returned an empty body")
        }
        if (bytes.size > REMOTE_CAPE_MAX_BYTES) {
            throw IOException("Remote cape '$capeUrl' exceeded ${REMOTE_CAPE_MAX_BYTES / 1024 / 1024} MB")
        }
        return bytes
    }

    private fun sanitizePath(resourcePath: String): String {
        return resourcePath.lowercase(Locale.ROOT)
            .replace('\\', '/')
            .replace(':', '_')
            .replace('?', '_')
            .replace('&', '_')
            .replace('=', '_')
            .replace('.', '_')
    }

    private fun textureIdForRemote(capeUrl: String): Identifier =
        Identifier.of(
            "throwerlist",
            "dynamic_capes/remote_${UUID.nameUUIDFromBytes(capeUrl.toByteArray()).toString().replace("-", "")}",
        )

    private fun ImageReader.readDelayMillis(index: Int): Long {
        val metadata = getImageMetadata(index)
        val root = metadata.getAsTree(metadata.nativeMetadataFormatName)
        val graphicControl = root.childNodes.asSequence()
            .firstOrNull { it.nodeName == "GraphicControlExtension" }
        val delay = graphicControl?.attributes?.getNamedItem("delayTime")?.nodeValue?.toLongOrNull() ?: 1L
        return (delay * 10L).coerceAtLeast(50L)
    }

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (index in 0 until length) {
            val node = item(index)
            if (node != null) {
                yield(node)
            }
        }
    }

    private data class LoadedCape(
        val textureAsset: AssetInfo.TextureAssetInfo?,
        val animatedTexture: AnimatedCapeTexture?,
    ) {
        fun tick() {
            animatedTexture?.tick()
        }
    }

    private data class CachedCape(
        val isRemote: Boolean,
        @Volatile var loadedCape: LoadedCape? = null,
        @Volatile var loading: Boolean = false,
        @Volatile var lastFailureAt: Long = 0L,
    ) {
        fun shouldRetry(): Boolean =
            loadedCape == null &&
                !loading &&
                (lastFailureAt == 0L || System.currentTimeMillis() - lastFailureAt >= REMOTE_RETRY_DELAY_MILLIS)

        fun tick() {
            loadedCape?.tick()
        }
    }

    private data class AnimatedFrame(
        val pngBytes: ByteArray,
        val delayMillis: Long,
    ) {
        fun toNativeImage(): NativeImage = NativeImage.read(ByteArrayInputStream(pngBytes))
    }

    private class AnimatedCapeTexture(
        private val texture: NativeImageBackedTexture,
        private val frames: List<AnimatedFrame>,
    ) {
        private var frameIndex = 0
        private var lastFrameChange = System.currentTimeMillis()

        fun tick() {
            if (frames.size <= 1) {
                return
            }

            val now = System.currentTimeMillis()
            if (now - lastFrameChange < frames[frameIndex].delayMillis) {
                return
            }

            frameIndex = (frameIndex + 1) % frames.size
            texture.setImage(frames[frameIndex].toNativeImage())
            texture.upload()
            lastFrameChange = now
        }
    }
}
