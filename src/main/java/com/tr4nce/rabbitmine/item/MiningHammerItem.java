package com.tr4nce.rabbitmine.item;

import com.tr4nce.rabbitmine.RabbitMine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

@EventBusSubscriber(modid = RabbitMine.MODID)
public final class MiningHammerItem extends PickaxeItem {
    private static final ThreadLocal<Boolean> MINING_AREA = new ThreadLocal<>();

    public MiningHammerItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockDrops(BlockDropsEvent event) {
        if (MINING_AREA.get() != null
                || !(event.getBreaker() instanceof ServerPlayer player)
                || player.isShiftKeyDown()
                || !(event.getTool().getItem() instanceof MiningHammerItem hammer)
                || !event.getState().is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return;
        }
        ItemStack tool = player.getMainHandItem();
        if (!tool.is(hammer)) {
            return;
        }

        ServerLevel level = event.getLevel();
        BlockPos center = event.getPos();
        Direction.Axis axis = miningAxis(player, level, center, event.getState());
        MINING_AREA.set(true);
        try {
            for (BlockPos pos : miningPlane(center, axis)) {
                if (pos.equals(center)) continue;
                if (tool.isEmpty() || player.getMainHandItem() != tool) return;
                if (canMine(level, pos, player)) {
                    player.gameMode.destroyBlock(pos.immutable());
                }
            }
        } finally {
            MINING_AREA.remove();
        }
    }

    public static Iterable<BlockPos> miningPlane(BlockPos center, Direction.Axis axis) {
        int x = axis == Direction.Axis.X ? 0 : 1;
        int y = axis == Direction.Axis.Y ? 0 : 1;
        int z = axis == Direction.Axis.Z ? 0 : 1;
        return BlockPos.betweenClosed(center.offset(-x, -y, -z), center.offset(x, y, z));
    }

    public static boolean canMine(Level level, BlockPos pos, Player player) {
        if (!level.isLoaded(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                && state.getDestroySpeed(level, pos) >= 0
                && state.canHarvestBlock(level, pos, player);
    }

    private static Direction.Axis miningAxis(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(player.blockInteractionRange() + 1.0));
        BlockHitResult hit = state.getShape(level, pos, CollisionContext.of(player)).clip(eye, end, pos);
        return hit == null ? Direction.getNearest(look.x, look.y, look.z).getAxis() : hit.getDirection().getAxis();
    }
}
