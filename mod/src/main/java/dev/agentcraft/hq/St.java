package dev.agentcraft.hq;

import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBedBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Short block-state constructors for the HQ builders (vanilla properties, 26.3 names). */
final class St {
	private St() {
	}

	static BlockState of(Block b) {
		return b.defaultBlockState();
	}

	static BlockState stairs(Block b, Direction facing, boolean top) {
		return b.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
	}

	static BlockState slab(Block b, boolean top) {
		return b.defaultBlockState().setValue(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
	}

	static BlockState doubleSlab(Block b) {
		return b.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
	}

	static BlockState log(Block b, Direction.Axis axis) {
		return b.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
	}

	static BlockState facing(Block b, Direction facing) {
		return b.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
	}

	/** A trapdoor; open = standing against the side opposite to {@code facing}. */
	static BlockState trapdoor(Block b, Direction facing, boolean open, boolean top) {
		return b.defaultBlockState().setValue(TrapDoorBlock.FACING, facing).setValue(TrapDoorBlock.OPEN, open)
			.setValue(TrapDoorBlock.HALF, top ? Half.TOP : Half.BOTTOM);
	}

	static BlockState lantern(Block b, boolean hanging) {
		return b.defaultBlockState().setValue(LanternBlock.HANGING, hanging);
	}

	static BlockState leaves(Block b) {
		return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
	}

	static BlockState carpet(DyeColor c) {
		return Blocks.CARPET.pick(c).defaultBlockState();
	}

	static BlockState wool(DyeColor c) {
		return Blocks.WOOL.pick(c).defaultBlockState();
	}

	static BlockState concrete(DyeColor c) {
		return Blocks.CONCRETE.pick(c).defaultBlockState();
	}

	static BlockState terracotta(DyeColor c) {
		return Blocks.DYED_TERRACOTTA.pick(c).defaultBlockState();
	}

	static BlockState light(int level) {
		return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level);
	}

	/**
	 * A water source that never freezes: a waterlogged light block. It looks and behaves as water
	 * (hydrates farmland, flows nowhere) but is no {@code LiquidBlock} and is lit, and
	 * {@code Biome.shouldFreeze} wants both for ice.
	 */
	static BlockState unfrozenWater(int level) {
		return light(level).setValue(LightBlock.WATERLOGGED, true);
	}

	static BlockState candle(Block b, int n, boolean lit) {
		return b.defaultBlockState().setValue(CandleBlock.CANDLES, n).setValue(CandleBlock.LIT, lit);
	}

	static BlockState bell(Direction facing, BellAttachType attach) {
		return Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING, facing).setValue(BellBlock.ATTACHMENT, attach);
	}

	static BlockState flowerBed(Block b, Direction facing, int amount) {
		return b.defaultBlockState().setValue(FlowerBedBlock.FACING, facing).setValue(FlowerBedBlock.AMOUNT, amount);
	}

	static BlockState leafLitter(Direction facing, int amount) {
		return Blocks.LEAF_LITTER.defaultBlockState().setValue(FlowerBedBlock.FACING, facing)
			.setValue(BlockStateProperties.SEGMENT_AMOUNT, amount);
	}

	static BlockState lower(Block tallPlant) {
		return tallPlant.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER);
	}

	static BlockState upper(Block tallPlant) {
		return tallPlant.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
	}

	static BlockState chain(Block b, Direction.Axis axis) {
		return b.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
	}
}
