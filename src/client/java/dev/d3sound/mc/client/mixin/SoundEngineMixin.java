package dev.d3sound.mc.client.mixin;

import dev.d3sound.mc.client.D3SoundEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Перехват звуковой системы игры.
 *
 * Все звуки проходят через один метод, поэтому здесь мы решаем, отдать звук
 * нашему движку или оставить ванильному. Забранные звуки игра не начинает
 * играть сама — иначе они звучали бы дважды.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

	@Shadow @Final private SoundManager soundManager;

	@Inject(
		method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void d3sound$play(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		D3SoundEngine engine = D3SoundEngine.get();
		if (!engine.enabled) return;
		if (!instance.canPlaySound()) return;

		WeighedSoundEvents events = instance.resolve(this.soundManager);
		if (events == null) return;

		Sound sound = instance.getSound();
		if (sound == null || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND || sound == SoundManager.EMPTY_SOUND) {
			return;
		}

		SoundEngineAccessor accessor = (SoundEngineAccessor) (Object) this;
		float volume = accessor.d3sound$calculateVolume(instance);
		if (volume <= 0f) return;
		float pitch = accessor.d3sound$calculatePitch(instance);
		boolean looping = instance.isLooping() && instance.getDelay() == 0;

		SoundEngine self = (SoundEngine) (Object) this;
		if (engine.takeOver(self, instance, sound, volume, pitch, looping)) {
			// ванильный play дальше не пойдёт, поэтому оповещаем слушателей сами:
			// без этого пропадают субтитры и чужие моды не видят наших звуков
			d3sound$notifyListeners(accessor, instance, events, sound);
			cir.setReturnValue(SoundEngine.PlayResult.STARTED_SILENTLY);
		}
	}

	/** Оповещение слушателей ровно так же, как это делает ванильный play. */
	@Unique
	private void d3sound$notifyListeners(SoundEngineAccessor accessor, SoundInstance instance,
	                                     WeighedSoundEvents events, Sound sound) {
		java.util.List<SoundEventListener> listeners = accessor.d3sound$listeners();
		if (listeners == null || listeners.isEmpty()) return;
		boolean everywhere = instance.isRelative()
			|| instance.getAttenuation() == SoundInstance.Attenuation.NONE;
		float range = everywhere
			? Float.POSITIVE_INFINITY
			: Math.max(instance.getVolume(), 1f) * (float) sound.getAttenuationDistance();
		for (SoundEventListener listener : listeners) listener.onPlaySound(instance, events, range);
	}

	@Inject(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"))
	private void d3sound$stop(SoundInstance instance, CallbackInfo ci) {
		D3SoundEngine.get().stop(instance);
	}

	@Inject(method = "stopAll()V", at = @At("HEAD"))
	private void d3sound$stopAll(CallbackInfo ci) {
		D3SoundEngine.get().stopAll();
	}

	@Inject(method = "updateSource(Lnet/minecraft/client/Camera;)V", at = @At("HEAD"))
	private void d3sound$updateSource(Camera camera, CallbackInfo ci) {
		Vec3 pos = camera.position();
		D3SoundEngine.get().updateListener(pos.x, pos.y, pos.z, camera.yRot(), camera.xRot());
	}

	@Inject(method = "destroy()V", at = @At("HEAD"))
	private void d3sound$destroy(CallbackInfo ci) {
		D3SoundEngine.get().shutdown();
	}
}
