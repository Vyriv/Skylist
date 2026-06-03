package dev.ryan.playerlist

import com.mojang.authlib.GameProfile
import net.minecraft.entity.player.SkinTextures
import java.util.Locale
import java.util.Optional

object OwnerCape {
    private const val cacheLimit = 128

    private data class CapeCacheKey(
        val version: Long,
        val profileKey: String,
        val skinTextures: SkinTextures,
        val capeResourcePath: String?,
        val capeUrl: String?,
    )

    private val capeCache = NameStylerLruCache<CapeCacheKey, SkinTextures>(cacheLimit)

    @Volatile
    private var observedRegistryVersion = Long.MIN_VALUE

    fun applyCustomCape(profile: GameProfile?, skinTextures: SkinTextures?): SkinTextures? {
        if (skinTextures == null) {
            return null
        }
        if (!ConfigManager.isCustomCapesEnabled()) {
            return skinTextures
        }
        if (!PlayerCustomizationRegistry.hasCapeCustomizations()) {
            return skinTextures
        }

        val version = currentRegistryVersion()
        val customization = PlayerCustomizationRegistry.findWithCape(profile) ?: return skinTextures
        val capeTexture = CapeTextureManager.getCapeTexture(customization.capeResourcePath, customization.capeUrl) ?: return skinTextures

        val cacheKey = CapeCacheKey(
            version = version,
            profileKey = profileKey(profile),
            skinTextures = skinTextures,
            capeResourcePath = customization.capeResourcePath,
            capeUrl = customization.capeUrl,
        )
        capeCache.getCached(cacheKey)?.let { return it }

        val overridden = skinTextures.withOverride(
            SkinTextures.SkinOverride.create(
                Optional.empty(),
                Optional.of(capeTexture),
                Optional.empty(),
                Optional.empty(),
            ),
        )
        capeCache.putCached(cacheKey, overridden)
        return overridden
    }

    private fun currentRegistryVersion(): Long {
        val version = PlayerCustomizationRegistry.version
        if (observedRegistryVersion != version) {
            capeCache.clearCache()
            observedRegistryVersion = version
        }
        return version
    }

    private fun profileKey(profile: GameProfile?): String =
        profile?.id?.toString()
            ?: profile?.name?.lowercase(Locale.ROOT)
            ?: ""
}
