package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import dev.agentcraft.mp.server.StudioRange;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class StudioRangeTest {
    private static Plot plot(int index,int x,int z) { return new Plot(index,StudioId.of(UUID.randomUUID()),new BlockPos(x,0,z)); }
    @Test void distance_is_to_the_inclusive_edge_chunks_and_uses_chebyshev_corners() {
        Plot p=plot(0,0,0); // x chunks -3..2, z chunks -3..3
        assertEquals(0,StudioRange.chunkDistance(p,0,0));
        for(int x=-3;x<=2;x++) { assertEquals(0,StudioRange.chunkDistance(p,x,-3)); assertEquals(0,StudioRange.chunkDistance(p,x,3)); }
        for(int z=-3;z<=3;z++) { assertEquals(0,StudioRange.chunkDistance(p,-3,z)); assertEquals(0,StudioRange.chunkDistance(p,2,z)); }
        for(int[] c:List.of(new int[]{-4,0},new int[]{3,0},new int[]{0,-4},new int[]{0,4},new int[]{3,4},new int[]{-4,-4}))
            assertEquals(1,StudioRange.chunkDistance(p,c[0],c[1]));
        assertEquals(3,StudioRange.chunkDistance(p,5,5));
    }
    @Test void negative_plot_coordinates_floor_to_chunks_and_ignore_height() {
        Plot p=plot(2,-128,-128); // x chunks -11..-6, z chunks -11..-5
        assertEquals(0,StudioRange.chunkDistance(p,-11,-11));
        assertEquals(0,StudioRange.chunkDistance(p,-6,-5));
        assertEquals(1,StudioRange.chunkDistance(p,-12,-12));
        assertEquals(1,StudioRange.chunkDistance(p,-5,-4));
        Plot raised=new Plot(p.index(),p.owner(),new BlockPos(-128,1000,-128));
        assertEquals(StudioRange.chunkDistance(p,0,0),StudioRange.chunkDistance(raised,0,0));
    }
    @Test void own_is_always_visible_and_remote_visibility_requires_overworld_and_radius() {
        Plot remote=plot(0,0,0), own=plot(1,1024,1024);
        List<Plot> all=List.of(remote,own);
        assertEquals(all,StudioRange.visiblePlots(all,own.owner(),true,2,3,0));
        assertEquals(List.of(own),StudioRange.visiblePlots(all,own.owner(),true,3,4,0));
        assertEquals(all,StudioRange.visiblePlots(all,own.owner(),true,3,4,1));
        assertEquals(List.of(own),StudioRange.visiblePlots(all,own.owner(),false,0,0,100));
        assertTrue(StudioRange.visiblePlots(List.of(remote),own.owner(),false,0,0,100).isEmpty());
        assertThrows(IllegalArgumentException.class,()->StudioRange.visiblePlots(all,own.owner(),true,0,0,-1));
    }
    @Test void diff_preserves_order_and_changed_index_or_origin_leaves_then_enters() {
        Plot a=plot(0,0,0), b=plot(1,128,0), c=plot(2,128,128);
        assertEquals(new StudioRange.Change(List.of(a),List.of(c)),StudioRange.diff(List.of(a,b),List.of(b,c)));
        assertEquals(new StudioRange.Change(List.of(),List.of()),StudioRange.diff(List.of(a,b),List.of(b,a)));
        for(Plot changed:List.of(new Plot(3,a.owner(),a.origin()),new Plot(a.index(),a.owner(),new BlockPos(-128,0,0)))) {
            var diff=StudioRange.diff(List.of(a,b),List.of(changed,c));
            assertEquals(List.of(a,b),diff.left()); assertEquals(List.of(changed,c),diff.entered());
            assertThrows(UnsupportedOperationException.class,()->diff.entered().clear());
        }
    }
}
