package dev.agentcraft.mp;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

public record Plot(int index, StudioId owner, BlockPos origin) {
    public Plot {
        if (index < 0) throw new IllegalArgumentException("negative plot");
        Objects.requireNonNull(owner);
        origin = origin.immutable();
    }
    public AABB box() { return new AABB(-46, 60, -36, 47, 101, 55).move(origin); }
    // Site coordinates are inclusive, like the HQ plan and Anchors.Bounds.
    public boolean contains(BlockPos pos) {
        int x = pos.getX() - origin.getX(), y = pos.getY() - origin.getY(), z = pos.getZ() - origin.getZ();
        return x >= -46 && x <= 46 && y >= 60 && y <= 100 && z >= -36 && z <= 54;
    }
}
