package pepjebs.mapatlases.lifecycle;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.MathHelper;
import pepjebs.mapatlases.MapAtlasesMod;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.networking.MapAtlasesInitAtlasS2CPacket;
import pepjebs.mapatlases.networking.MapAtlasesOpenGUIC2SPacket;
import pepjebs.mapatlases.utils.MapAtlasesAccessUtils;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

public class MapAtlasesServerLifecycleEvents {

    public static final Identifier MAP_ATLAS_ACTIVE_STATE_CHANGE = new Identifier(
            MapAtlasesMod.MOD_ID, "active_state_change");

    // Used to prevent Map creation spam consuming all Empty Maps on auto-create
    private static final Semaphore mutex = new Semaphore(1);

    // Holds the current MapState ID for each player
    private static final Map<String, String> playerToActiveMapId = new HashMap<>();

    public static void openGuiEvent(
            MinecraftServer server,
            ServerPlayerEntity player,
            ServerPlayNetworkHandler _handler,
            PacketByteBuf buf,
            PacketSender _responseSender) {
        MapAtlasesOpenGUIC2SPacket p = new MapAtlasesOpenGUIC2SPacket();
        p.read(buf);
        server.execute(() -> {
            ItemStack atlas = p.atlas;
            player.openHandledScreen((MapAtlasItem) atlas.getItem());
            player.getWorld().playSound(null, player.getBlockPos(),
                    MapAtlasesMod.ATLAS_OPEN_SOUND_EVENT, SoundCategory.PLAYERS, 1.0F, 1.0F);
        });
    }

    public static void mapAtlasPlayerJoin(
            ServerPlayNetworkHandler serverPlayNetworkHandler,
            PacketSender _responseSender,
            MinecraftServer _server
    ) {
        ServerPlayerEntity player = serverPlayNetworkHandler.player;
        ItemStack atlas = MapAtlasesAccessUtils.getAtlasFromPlayerByConfig(player);
        if (atlas.isEmpty()) return;
        Map<String, MapState> mapInfos = MapAtlasesAccessUtils.getAllMapInfoFromAtlas(player.world, atlas);
        for (Map.Entry<String, MapState> info : mapInfos.entrySet()) {
            String mapId = info.getKey();
            MapState state = info.getValue();
            state.update(player, atlas);
            state.getPlayerSyncData(player);
            PacketByteBuf packetByteBuf = new PacketByteBuf(Unpooled.buffer());
            (new MapAtlasesInitAtlasS2CPacket(mapId, state)).write(packetByteBuf);
            player.networkHandler.sendPacket(new CustomPayloadS2CPacket(
                    MapAtlasesInitAtlasS2CPacket.MAP_ATLAS_INIT,
                    packetByteBuf));
        }
        MapAtlasesMod.LOGGER.info("Server initialized "+mapInfos.size()+" MapStates for "+player.getName().getString());
    }

    public static void mapAtlasServerTick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.isRemoved() || player.isInTeleportationState()) continue;
            ItemStack atlas = MapAtlasesAccessUtils.getAtlasFromPlayerByConfig(player);
            if (!atlas.isEmpty()) {
                Map.Entry<String, MapState> activeInfo =
                        MapAtlasesAccessUtils.getActiveAtlasMapStateServer(
                                player.getWorld(), atlas, player);
                String changedMapState = null;
                if (activeInfo != null) {
                    String playerName = player.getName().getString();
                    if (!playerToActiveMapId.containsKey(playerName)
                            || playerToActiveMapId.get(playerName).compareTo(activeInfo.getKey()) != 0) {
                        changedMapState = playerToActiveMapId.get(playerName);
                        playerToActiveMapId.put(playerName, activeInfo.getKey());
                        PacketByteBuf packetByteBuf = new PacketByteBuf(Unpooled.buffer());
                        packetByteBuf.writeString(activeInfo.getKey());
                        player.networkHandler.sendPacket(new CustomPayloadS2CPacket(
                                MAP_ATLAS_ACTIVE_STATE_CHANGE, packetByteBuf));
                    }
                } else {
                    PacketByteBuf packetByteBuf = new PacketByteBuf(Unpooled.buffer());
                    packetByteBuf.writeString("null");
                    player.networkHandler.sendPacket(new CustomPayloadS2CPacket(
                            MAP_ATLAS_ACTIVE_STATE_CHANGE, packetByteBuf));
                }

                // Maps are 128x128
                int playX = player.getBlockPos().getX();
                int playZ = player.getBlockPos().getZ();
                boolean isPlayerOutsideAllMapRegions = true;
                int scale = 0;
                ArrayList<Pair<Integer, Integer>> discoveringEdges = new ArrayList<>();
                if (activeInfo != null) {
                    discoveringEdges = getPlayerDiscoveringMapEdges(
                            activeInfo.getValue().centerX,
                            activeInfo.getValue().centerZ,
                            (1 << activeInfo.getValue().scale) * 128,
                            playX,
                            playZ
                    );
                }

                Map<String, MapState> mapInfos =
                        MapAtlasesAccessUtils.getCurrentDimMapInfoFromAtlas(player.world, atlas);
                for (Map.Entry<String, MapState> info : mapInfos.entrySet()) {
                    MapState state = info.getValue();
                    boolean isDiscoveringEdgeMap =
                            discoveringEdges.stream().anyMatch(p -> p.getLeft()==state.centerX && p.getRight() == state.centerZ);
                    boolean isActiveMap = activeInfo != null
                            && activeInfo.getValue().centerX==state.centerX && activeInfo.getValue().centerZ==state.centerZ;
                    // Only update active (based on discovery radius) map states
                    if (!isDiscoveringEdgeMap && !isActiveMap && changedMapState == null)
                        continue;
                    state.update(player, atlas);
                    ((FilledMapItem) Items.FILLED_MAP).updateColors(player.getWorld(), player, state);
                    int mapId = MapAtlasesAccessUtils.getMapIntFromString(info.getKey());
                    Packet<?> p = null;
                    int tries = 0;
                    while (p == null && tries < 10) {
                        p = state.getPlayerMarkerPacket(mapId, player);
                        tries++;
                    }
                    if (p != null) {
                        PacketByteBuf packetByteBuf = new PacketByteBuf(Unpooled.buffer());
                        p.write(packetByteBuf);
                        player.networkHandler.sendPacket(new CustomPayloadS2CPacket(
                                MapAtlasesInitAtlasS2CPacket.MAP_ATLAS_SYNC,
                                packetByteBuf));
                    }

                    isPlayerOutsideAllMapRegions = isPlayerOutsideAllMapRegions &&
                            isPlayerOutsideSquareRegion(
                                    state.centerX,
                                    state.centerZ,
                                    (1 << state.scale) * 128,
                                    playX,
                                    playZ
                            );
                    scale = state.scale;
                }

                if (MapAtlasesMod.CONFIG != null && !MapAtlasesMod.CONFIG.enableEmptyMapEntryAndFill) continue;
                if (isPlayerOutsideAllMapRegions) {
                    maybeCreateNewMapEntry(player, atlas, scale, MathHelper.floor(player.getX()),
                            MathHelper.floor(player.getZ()));
                }
                discoveringEdges.removeIf(p -> mapInfos.values().stream().anyMatch(i -> p.getLeft() == i.centerX
                        && p.getRight() == i.centerZ));
                for (var p : discoveringEdges) {
                    maybeCreateNewMapEntry(player, atlas, scale, p.getLeft(), p.getRight());
                }
            }
        }
    }

    private static void maybeCreateNewMapEntry(
            ServerPlayerEntity player,
            ItemStack atlas,
            int scale,
            int destX,
            int destZ
    ) {
        List<Integer> mapIds = new ArrayList<>();
        if (atlas.getNbt() != null) {
            mapIds = Arrays.stream(
                    atlas.getNbt().getIntArray(MapAtlasItem.MAP_LIST_NBT)).boxed().collect(Collectors.toList());
        } else {
            // If the Atlas is "inactive", give it a pity 1 Empty Map
            NbtCompound defaultAtlasNbt = new NbtCompound();
            defaultAtlasNbt.putInt(MapAtlasItem.EMPTY_MAP_NBT, 1);
            atlas.setNbt(defaultAtlasNbt);
        }
        int emptyCount = MapAtlasesAccessUtils.getEmptyMapCountFromItemStack(atlas);
        if (mutex.availablePermits() > 0
                && (emptyCount > 0 || player.isCreative())) {
            try {
                mutex.acquire();

                // Make the new map
                if (!player.isCreative()) {
                    atlas.getNbt().putInt(
                            MapAtlasItem.EMPTY_MAP_NBT,
                            atlas.getNbt().getInt(MapAtlasItem.EMPTY_MAP_NBT) - 1
                    );
                }
                ItemStack newMap = FilledMapItem.createMap(
                        player.getWorld(),
                        destX,
                        destZ,
                        (byte) scale,
                        true,
                        false);
                mapIds.add(FilledMapItem.getMapId(newMap));
                atlas.getNbt().putIntArray(MapAtlasItem.MAP_LIST_NBT, mapIds);

                // Play the sound
                player.getWorld().playSound(null, player.getBlockPos(),
                        MapAtlasesMod.ATLAS_CREATE_MAP_SOUND_EVENT,
                        SoundCategory.PLAYERS, 1.0F, 1.0F);
            } catch (InterruptedException e) {
                MapAtlasesMod.LOGGER.warn(e);
            } finally {
                mutex.release();
            }
        }
    }

    private static boolean isPlayerOutsideSquareRegion(
            int xCenter,
            int zCenter,
            int width,
            int xPlayer,
            int zPlayer) {
        int halfWidth = width / 2;
        return xPlayer < xCenter - halfWidth ||
                xPlayer > xCenter + halfWidth ||
                zPlayer < zCenter - halfWidth ||
                zPlayer > zCenter + halfWidth;
    }

    private static ArrayList<Pair<Integer, Integer>> getPlayerDiscoveringMapEdges(
            int xCenter,
            int zCenter,
            int width,
            int xPlayer,
            int zPlayer) {
        int halfWidth = width / 2;
        ArrayList<Pair<Integer, Integer>> results = new ArrayList<>();
        for (int i = -1; i < 2; i++) {
            for (int j = -1; j < 2; j++) {
                if (i != 0 || j != 0) {
                    int qI = xCenter;
                    int qJ = zCenter;
                    if (i == -1 && xPlayer - 128 < xCenter - halfWidth) {
                        qI -= width;
                    } else if (i == 1 && xPlayer + 128 > xCenter + halfWidth) {
                        qI += width;
                    }
                    if (j == -1 && zPlayer - 128 < zCenter - halfWidth) {
                        qJ -= width;
                    } else if (j == 1 && zPlayer + 128 > zCenter + halfWidth) {
                        qJ += width;
                    }
                    // Some lambda bullshit
                    int finalQI = qI;
                    int finalQJ = qJ;
                    if ((qI != xCenter || qJ != zCenter) && results.stream()
                            .noneMatch(p -> p.getLeft() == finalQI && p.getRight() == finalQJ)) {
                        results.add(new Pair<>(qI, qJ));
                    }
                }
            }
        }
        return results;
    }
}