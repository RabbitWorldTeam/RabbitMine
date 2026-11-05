package com.tr4nce.rabbitmine.service;

import com.tr4nce.rabbitmine.RabbitMine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = RabbitMine.MODID)
public final class RabbitMineAdvancements {
    private static final Map<String, String> PASSPORT = Map.of(
            "Rabbit", "rabbit", "Volcano", "volcano", "Sakura", "sakura",
            "Sea", "sea", "TheEnd", "the_end", "minecraft", "minecraft"
    );
    private static final Map<Block, String> FROGLIGHTS = Map.of(
            Blocks.PEARLESCENT_FROGLIGHT, "pearlescent",
            Blocks.VERDANT_FROGLIGHT, "verdant",
            Blocks.OCHRE_FROGLIGHT, "ochre"
    );

    // Accessed from the server thread. Entries describe blocks written by RabbitMine.
    private static final Map<ServerLevel, Map<BlockPos, GeneratedBlock>> generated = new HashMap<>();
    private static final Map<String, Set<UUID>> evacuating = new HashMap<>();
    private static final Map<String, Set<UUID>> readyToReturn = new HashMap<>();

    private RabbitMineAdvancements() {
    }

    static void beginRefresh(String site, Set<UUID> evacuated) {
        readyToReturn.remove(site);
        generated.values().forEach(blocks -> blocks.values().removeIf(block -> block.site().equals(site)));
        evacuating.put(site, evacuated);
    }

    static void finishRefresh(String site) {
        Set<UUID> evacuated = evacuating.remove(site);
        if (evacuated != null && !evacuated.isEmpty()) {
            readyToReturn.put(site, evacuated);
        }
    }

    static void cancelRefresh(String site) {
        evacuating.remove(site);
        readyToReturn.remove(site);
    }

    static void recordBlock(ServerLevel level, BlockPos pos, String site, BlockState state) {
        if (state.isAir()) {
            forgetBlock(level, pos);
        } else {
            generated.computeIfAbsent(level, key -> new HashMap<>())
                    .put(pos.immutable(), new GeneratedBlock(site, state.getBlock()));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockDrops(BlockDropsEvent event) {
        GeneratedBlock origin = forgetBlock(event.getLevel(), event.getPos());
        if (origin == null || origin.block() != event.getState().getBlock()
                || !(event.getBreaker() instanceof ServerPlayer player) || player.isCreative()) {
            return;
        }

        award(player, "root", "mine");
        String passportCriterion = PASSPORT.get(origin.site());
        if (passportCriterion != null) {
            award(player, "miners_passport", passportCriterion);
        }
        if ("Rabbit".equals(origin.site())) {
            String color = FROGLIGHTS.get(origin.block());
            if (color != null) {
                award(player, "rabbit_froglights", color);
            }
        } else if ("Volcano".equals(origin.site()) && origin.block() == Blocks.WET_SPONGE) {
            award(player, "volcano_sponge", "sponge");
        }

        Set<UUID> players = readyToReturn.get(origin.site());
        if (players != null && players.remove(player.getUUID())) {
            award(player, "next_round", "return");
            if (players.isEmpty()) {
                readyToReturn.remove(origin.site());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multiPlace) {
            multiPlace.getReplacedBlockSnapshots().forEach(snapshot -> forgetBlock(level, snapshot.getPos()));
        } else {
            forgetBlock(level, event.getPos());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPistonMove(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var structure = event.getStructureHelper();
        if (structure != null && structure.resolve()) {
            structure.getToPush().forEach(pos -> {
                forgetBlock(level, pos);
                forgetBlock(level, pos.relative(structure.getPushDirection()));
            });
            structure.getToDestroy().forEach(pos -> forgetBlock(level, pos));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            event.getAffectedBlocks().forEach(pos -> forgetBlock(level, pos));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        generated.clear();
        evacuating.clear();
        readyToReturn.clear();
    }

    private static GeneratedBlock forgetBlock(ServerLevel level, BlockPos pos) {
        Map<BlockPos, GeneratedBlock> blocks = generated.get(level);
        return blocks == null ? null : blocks.remove(pos);
    }

    private static void award(ServerPlayer player, String id, String criterion) {
        var advancement = player.server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(RabbitMine.MODID, id));
        if (advancement != null) {
            player.getAdvancements().award(advancement, criterion);
        }
    }

    private record GeneratedBlock(String site, Block block) {
    }
}
