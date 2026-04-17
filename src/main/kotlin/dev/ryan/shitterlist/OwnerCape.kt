package dev.ryan.throwerlist

import com.mojang.authlib.GameProfile
import net.minecraft.entity.player.SkinTextures
import java.util.Optional

object OwnerCape {
    fun applyCustomCape(profile: GameProfile?, skinTextures: SkinTextures?): SkinTextures? {
        if (skinTextures == null) {
            return null
        }

        val customization = PlayerCustomizationRegistry.find(profile) ?: return skinTextures
        val capeTexture = CapeTextureManager.getCapeTexture(customization.capeResourcePath, customization.capeUrl) ?: return skinTextures
        return skinTextures.withOverride(
            SkinTextures.SkinOverride.create(
                Optional.empty(),
                Optional.of(capeTexture),
                Optional.empty(),
                Optional.empty(),
            ),
        )
    }
}
