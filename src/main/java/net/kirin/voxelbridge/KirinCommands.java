package net.kirin.voxelbridge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

public final class KirinCommands {
    private KirinCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("kirinvx")
            .executes(ctx -> stats(ctx.getSource()))
            .then(ClientCommandManager.literal("stats").executes(ctx -> stats(ctx.getSource())))
            .then(ClientCommandManager.literal("rescan").executes(ctx -> {
                KirinVoxelBridge.WORLD.rescan();
                ctx.getSource().sendFeedback(Component.literal("[kirinvx] pemindaian diulang dari awal."));
                return 1;
            }))
            .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                boolean on = !KirinVoxelBridge.WORLD.enabled;
                KirinVoxelBridge.WORLD.enabled = on;
                ctx.getSource().sendFeedback(Component.literal("[kirinvx] " + (on ? "aktif" : "dimatikan")));
                return 1;
            }))
            .then(ClientCommandManager.literal("speed")
                .then(ClientCommandManager.argument("unit_per_tick", IntegerArgumentType.integer(1, VoxelWorld.UNITS_TOTAL))
                    .executes(ctx -> {
                        int n = IntegerArgumentType.getInteger(ctx, "unit_per_tick");
                        KirinVoxelBridge.WORLD.unitsPerTick = n;
                        ctx.getSource().sendFeedback(Component.literal("[kirinvx] " + n + " unit 16^3 per tick."));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("bridge")
                .executes(ctx -> bridgeStatus(ctx.getSource()))
                .then(ClientCommandManager.literal("find").executes(ctx -> {
                    for (String line : KirinVoxelBridge.BRIDGE.scanNow()) {
                        ctx.getSource().sendFeedback(Component.literal("[kirinvx] " + line));
                    }
                    return 1;
                }))
                .then(ClientCommandManager.literal("test").executes(ctx -> {
                    for (String line : KirinVoxelBridge.BRIDGE.selfTest(KirinVoxelBridge.WORLD)) {
                        ctx.getSource().sendFeedback(Component.literal("[kirinvx] " + line));
                    }
                    return 1;
                }))
                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    boolean on = !KirinVoxelBridge.BRIDGE.enabled;
                    KirinVoxelBridge.BRIDGE.enabled = on;
                    ctx.getSource().sendFeedback(Component.literal("[kirinvx] jembatan " + (on ? "aktif" : "dimatikan")));
                    return 1;
                })))
            .then(ClientCommandManager.literal("dump").executes(ctx -> {
                try {
                    Path p = FabricLoader.getInstance().getConfigDir().resolve("kirinvx-shapes.txt");
                    Files.write(p, KirinVoxelBridge.WORLD.dumpShapes());
                    ctx.getSource().sendFeedback(Component.literal("[kirinvx] tabel bentuk ditulis ke " + p));
                } catch (Exception e) {
                    KirinVoxelBridge.LOG.error("dump gagal", e);
                    ctx.getSource().sendFeedback(Component.literal("[kirinvx] dump gagal: " + e));
                }
                return 1;
            })));
    }

    private static int bridgeStatus(FabricClientCommandSource src) {
        for (String line : KirinVoxelBridge.BRIDGE.status()) {
            src.sendFeedback(Component.literal("[kirinvx] " + line));
        }
        return 1;
    }

    private static int stats(FabricClientCommandSource src) {
        for (String line : KirinVoxelBridge.WORLD.report()) {
            src.sendFeedback(Component.literal("[kirinvx] " + line));
        }
        return 1;
    }
}
