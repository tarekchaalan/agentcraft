package dev.agentcraft.mp;

import java.util.OptionalInt;
import net.minecraft.core.BlockPos;

/** Clockwise square spiral: 0=(0,0), 1=(1,0), 2=(1,1), in stride units. */
public final class PlotGrid {
    public static final int DEFAULT_STRIDE = 128;
    private PlotGrid() {}
    private static void stride(int stride) {
        if (stride < 96 || stride % 16 != 0) throw new IllegalArgumentException("stride must be 16-aligned and at least 96");
    }
    public static BlockPos originOf(int index, int stride) {
        stride(stride);
        if (index < 0) throw new IllegalArgumentException("negative index");
        if (index == 0) return BlockPos.ZERO;
        long k = (long) Math.ceil((Math.sqrt((long) index + 1) - 1) / 2);
        long d = (2*k+1)*(2*k+1)-1-index, side = 2*k;
        long x, z;
        if (d < side) { x=k-d; z=-k; }
        else if (d < 2*side) { x=-k; z=-k+(d-side); }
        else if (d < 3*side) { x=-k+(d-2*side); z=k; }
        else { x=k; z=k-(d-3*side); }
        long bx=x*stride, bz=z*stride;
        if(Math.abs(bx)>30000000 || Math.abs(bz)>30000000) throw new IllegalArgumentException("plot origin out of range");
        return new BlockPos((int)bx, 0, (int)bz);
    }
    /** Resolve the site footprint; roads between sites return empty. */
    public static OptionalInt indexAt(int x, int z, int stride) {
        stride(stride);
        long gx = Math.floorDiv((long)x + 46, stride), gz = Math.floorDiv((long)z + 36, stride);
        long dx = (long)x-gx*stride, dz = (long)z-gz*stride;
        if (dx < -46 || dx > 46 || dz < -36 || dz > 54) return OptionalInt.empty();
        long k=Math.max(Math.abs(gx),Math.abs(gz));
        if (k==0) return OptionalInt.of(0);
        long side=2*k, d;
        if (gz==-k) d=k-gx;
        else if (gx==-k) d=side+gz+k;
        else if (gz==k) d=2*side+gx+k;
        else d=3*side+k-gz;
        long i=(2*k+1)*(2*k+1)-1-d;
        return i <= Integer.MAX_VALUE ? OptionalInt.of((int)i) : OptionalInt.empty();
    }
}
