package dev.agentcraft.world;

/**
 * Where the HQ stands in a world: the world position of the studio plan's origin, i.e. design
 * cell (0, {@code GROUND}, 0) is world cell ({@code x}, {@code y}, {@code z}). {@code y} is the
 * ground level (the grass top) the HQ was built on. The studio world always uses the origin at
 * ground 64; a natural world picks a site near its spawn once and keeps it in
 * {@code agentcraft-world.json}, so every rebuild lands on the same ground.
 */
public record HqSite(int x, int y, int z) {
}
