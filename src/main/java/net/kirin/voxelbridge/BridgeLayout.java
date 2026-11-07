package net.kirin.voxelbridge;

/** Tata letak tekstur jembatan. HARUS sama dengan shaders/lib/vx_bridge.glsl di shader pack. */
public final class BridgeLayout {
    private BridgeLayout() {}

    public static final int W = 1536;
    public static final int H = 640;
    public static final int GRID_ROWS = 576;                 // y 0..575: grid (unit 64x64, 24 unit per baris, 9 baris)
    public static final int UNIT_PX = 64;
    public static final int UNITS_PER_ROW = 24;
    public static final int DATA_Y = GRID_ROWS;              // y 576..639: data linier
    public static final int DATA_ROWS = H - GRID_ROWS;       // 64
    public static final int DATA_TEXELS = W * DATA_ROWS;     // 98304

    public static final int BOX_BASE = 8192;                 // id bentuk 1..8191 di alamat 1..8191
    public static final int HDR_BASE = 98040;
    public static final int ENT_BASE = 98048;
    public static final int MAX_ENTITY_TEXELS = 1 + 3 * VoxelWorld.MAX_ENTITIES;

    public static final int MAGIC_R = 75, MAGIC_G = 73, MAGIC_B = 82, MAGIC_A = 49;   // 'K','I','R','1'
}
