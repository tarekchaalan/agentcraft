package dev.agentcraft.client.hud;

import dev.agentcraft.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Combat for "hide in combat" ({@link HudVisibility#inCombat}): when the player was last hurt, and whether a hostile
 * mob ({@link Enemy}) is within {@link HudVisibility#HOSTILE_RANGE} blocks (scanned every half second, and only while
 * the setting is on). Client tick.
 */
public final class HudCombat {
	private static long lastHurtAt;
	private static boolean hostileNear;
	private static int scanIn;

	private HudCombat() {
	}

	static void tick(Minecraft mc) {
		var p = mc.player;
		if (p == null || mc.level == null) {
			hostileNear = false;
			return;
		}
		if (p.hurtTime > 0) {
			lastHurtAt = System.currentTimeMillis();
		}
		if (!HudConfig.get().hideInCombat()) {
			hostileNear = false;
			return;
		}
		if (--scanIn > 0) {
			return;
		}
		scanIn = 10;
		double r = HudVisibility.HOSTILE_RANGE;
		AABB box = p.getBoundingBox().inflate(r);
		hostileNear = !mc.level.getEntitiesOfClass(LivingEntity.class, box, e -> e instanceof Enemy && e.isAlive() && e.distanceToSqr(p) <= r * r)
			.isEmpty();
	}

	public static boolean inCombat(long now) {
		return HudVisibility.inCombat(now, lastHurtAt, hostileNear);
	}

	public static long lastHurtAt() {
		return lastHurtAt;
	}

	public static boolean hostileNear() {
		return hostileNear;
	}
}
