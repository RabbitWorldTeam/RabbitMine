package com.tr4nce.rabbitmine.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tr4nce.rabbitmine.RabbitMine;
import com.tr4nce.rabbitmine.item.MiningHammerItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

@EventBusSubscriber(modid = RabbitMine.MODID, value = Dist.CLIENT)
public final class MiningHammerOutline {
    private MiningHammerOutline() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void renderOutline(RenderHighlightEvent.Block event) {
        var client = Minecraft.getInstance();
        var player = client.player;
        var level = client.level;
        if (player == null || level == null || event.getCamera().getEntity() != player
                || player.isShiftKeyDown() || player.isCreative() || player.isSpectator()
                || !(player.getMainHandItem().getItem() instanceof MiningHammerItem)) {
            return;
        }

        var hit = event.getTarget();
        BlockPos center = hit.getBlockPos();
        if (!MiningHammerItem.canMine(level, center, player)) return;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack.Pose pose = event.getPoseStack().last();
        VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());
        CollisionContext context = CollisionContext.of(player);
        for (BlockPos pos : MiningHammerItem.miningPlane(center, hit.getDirection().getAxis())) {
            if (pos.equals(center) || !MiningHammerItem.canMine(level, pos, player)) continue;

            VoxelShape shape = level.getBlockState(pos).getShape(level, pos, context);
            double x = pos.getX() - camera.x;
            double y = pos.getY() - camera.y;
            double z = pos.getZ() - camera.z;
            shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
                float dx = (float) (x2 - x1);
                float dy = (float) (y2 - y1);
                float dz = (float) (z2 - z1);
                float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                lines.addVertex(pose, (float) (x + x1), (float) (y + y1), (float) (z + z1))
                        .setColor(0.0F, 0.0F, 0.0F, 0.4F)
                        .setNormal(pose, dx / length, dy / length, dz / length);
                lines.addVertex(pose, (float) (x + x2), (float) (y + y2), (float) (z + z2))
                        .setColor(0.0F, 0.0F, 0.0F, 0.4F)
                        .setNormal(pose, dx / length, dy / length, dz / length);
            });
        }
    }
}
