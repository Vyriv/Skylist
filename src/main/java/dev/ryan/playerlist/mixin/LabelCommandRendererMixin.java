package dev.ryan.playerlist.mixin;

import dev.ryan.playerlist.NameStyler;
import dev.ryan.playerlist.SkylistPresenceManager;
import net.minecraft.client.render.command.LabelCommandRenderer;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LabelCommandRenderer.class)
public abstract class LabelCommandRendererMixin {
    @ModifyArg(
        method = "render(Lnet/minecraft/client/render/command/BatchingRenderCommandQueue;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/font/TextRenderer;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/font/TextRenderer;draw(Lnet/minecraft/text/Text;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V"
        ),
        index = 0
    )
    private Text playerlist$styleLabelAtDrawTime(Text text) {
        if (text == null) {
            return null;
        }

        Text styled = NameStyler.INSTANCE.applyNameplateDecorations(text);
        styled = SkylistPresenceManager.INSTANCE.applyIdentifier(styled, styled.getString());
        NameStyler.INSTANCE.debugRenderReceipt(
            "label-command",
            "LabelCommandRenderer.render",
            text.getString(),
            styled.getString(),
            System.identityHashCode(styled),
            styled == text
        );
        return styled;
    }
}
