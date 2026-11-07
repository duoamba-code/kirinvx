package net.kirin.voxelbridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KirinVoxelBridge implements ClientModInitializer {
    public static final String MOD_ID = "kirinvx";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    public static final VoxelWorld WORLD = new VoxelWorld();
    public static final BridgeUploader BRIDGE = new BridgeUploader();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            WORLD.tick(mc);
            BRIDGE.tick(mc, WORLD);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> KirinCommands.register(dispatcher));
        LOG.info("Kirin Voxel Bridge (tahap 2) dimuat: grid {}^3, mengisi tekstur vxbridge bila shader pack memintanya.", VoxelWorld.SIZE);
    }
}
