package dev.clicksclient.mixin;

import dev.clicksclient.mc.FarPlane;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets distant flagged-chunk markers render by pushing the camera far plane out when a module asks for it. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getFarPlaneDistance", at = @At("RETURN"), cancellable = true)
    private void clicksclient$extendFarPlane(CallbackInfoReturnable<Float> cir) {
        float vanilla = cir.getReturnValue();
        float extended = FarPlane.extend(vanilla);
        if (extended > vanilla) cir.setReturnValue(extended);
    }
}
