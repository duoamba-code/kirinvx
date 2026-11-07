package net.kirin.voxelbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Grid voxel 96^3 di sekitar pemain. Tiap sel = id bentuk (ShapeTable), 0 = udara / chunk belum dimuat.
 * Asal grid (ox, oy, oz) dipatok ke kelipatan 16 supaya tiap "unit" 16^3 sama persis dengan satu bagian chunk.
 * Pemindaian bergulir: tiap tick N unit dibaca ulang (pengganti sementara event perubahan blok, tahap 2).
 * Semuanya dikerjakan di thread klien (END_CLIENT_TICK).
 */
public final class VoxelWorld {
    public static final int SIZE = 96;
    public static final int UNIT = 16;
    public static final int UNITS = SIZE / UNIT;              // 6
    public static final int UNITS_TOTAL = UNITS * UNITS * UNITS;
    public static final int MAX_ENTITIES = 64;
    public static final double ENTITY_RADIUS = SIZE / 2.0;

    public final ShapeTable shapes = new ShapeTable();
    public volatile boolean enabled = true;
    public volatile int unitsPerTick = 4;

    private final int[] cells = new int[SIZE * SIZE * SIZE];
    private final int[] scratch = new int[SIZE * SIZE * SIZE];
    private final boolean[] unitLoaded = new boolean[UNITS_TOTAL];
    private final boolean[] dirty = new boolean[UNITS_TOTAL];
    private int epoch;               // naik tiap tabel bentuk dikosongkan (id bentuk berubah arti)
    private boolean fullDirty;       // semua unit harus diunggah ulang (asal geser / rescan)

    private boolean hasOrigin;
    private int ox, oy, oz;
    private int cursor;

    // statistik
    private long scanNanos;
    private long scanUnits;
    private long passChanged;
    private long lastPassChanged = -1;
    private long passesDone;

    /** kotak entity relatif asal grid: minx miny minz maxx maxy maxz (satuan blok) */
    private final List<float[]> entityBoxes = new ArrayList<>();

    public static int index(int x, int y, int z) { return x + SIZE * (z + SIZE * y); }

    public void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            if (hasOrigin) reset();
            return;
        }
        if (!enabled) return;

        int nx = Math.floorDiv(Mth.floor(player.getX()), UNIT) * UNIT - SIZE / 2;
        int ny = Math.floorDiv(Mth.floor(player.getY()), UNIT) * UNIT - SIZE / 2;
        int nz = Math.floorDiv(Mth.floor(player.getZ()), UNIT) * UNIT - SIZE / 2;
        if (!hasOrigin) {
            ox = nx; oy = ny; oz = nz;
            hasOrigin = true;
            cursor = 0;
        } else if (nx != ox || ny != oy || nz != oz) {
            shift(nx, ny, nz);
        }

        long t0 = System.nanoTime();
        int n = Math.max(1, unitsPerTick);
        for (int k = 0; k < n; k++) {
            scanUnit(level, cursor);
            scanUnits++;
            cursor++;
            if (cursor >= UNITS_TOTAL) {
                cursor = 0;
                lastPassChanged = passChanged;
                passChanged = 0;
                passesDone++;
            }
        }
        scanNanos += System.nanoTime() - t0;

        updateEntities(level, player);
    }

    /** Asal grid pindah (kelipatan 16): geser isi grid, sel baru di tepi diisi oleh pemindaian berikutnya. */
    private void shift(int nx, int ny, int nz) {
        int dx = nx - ox, dy = ny - oy, dz = nz - oz;
        for (int y = 0; y < SIZE; y++) {
            int sy = y + dy;
            for (int z = 0; z < SIZE; z++) {
                int sz = z + dz;
                for (int x = 0; x < SIZE; x++) {
                    int sx = x + dx;
                    boolean in = sx >= 0 && sx < SIZE && sy >= 0 && sy < SIZE && sz >= 0 && sz < SIZE;
                    scratch[index(x, y, z)] = in ? cells[index(sx, sy, sz)] : 0;
                }
            }
        }
        System.arraycopy(scratch, 0, cells, 0, cells.length);
        ox = nx; oy = ny; oz = nz;
        cursor = 0;
        passChanged = 0;
        markAllDirty();
    }

    private void scanUnit(ClientLevel level, int u) {
        int ux = u % UNITS;
        int uz = (u / UNITS) % UNITS;
        int uy = u / (UNITS * UNITS);
        int bx = ux * UNIT, by = uy * UNIT, bz = uz * UNIT;
        int wx0 = ox + bx, wy0 = oy + by, wz0 = oz + bz;

        boolean loaded = level.hasChunk(wx0 >> 4, wz0 >> 4);
        unitLoaded[u] = loaded;

        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        BlockState lastState = null;
        int lastId = 0;
        long changed = 0;

        for (int y = 0; y < UNIT; y++) {
            for (int z = 0; z < UNIT; z++) {
                int row = index(bx, by + y, bz + z);
                for (int x = 0; x < UNIT; x++) {
                    int id = 0;
                    if (loaded) {
                        BlockState s = level.getBlockState(mp.set(wx0 + x, wy0 + y, wz0 + z));
                        if (s == lastState) {
                            id = lastId;
                        } else {
                            id = s.isAir() ? 0 : shapes.idFor(s, level, mp);
                            lastState = s;
                            lastId = id;
                        }
                    }
                    if (cells[row + x] != id) {
                        cells[row + x] = id;
                        changed++;
                    }
                }
            }
        }
        if (changed > 0) dirty[u] = true;
        passChanged += changed;
    }

    private void updateEntities(ClientLevel level, LocalPlayer player) {
        entityBoxes.clear();
        double r = ENTITY_RADIUS;
        AABB area = new AABB(player.getX() - r, player.getY() - r, player.getZ() - r,
                             player.getX() + r, player.getY() + r, player.getZ() + r);
        List<Entity> found = level.getEntities((Entity) null, area, e -> e != player);
        if (found.size() > MAX_ENTITIES) {
            found = new ArrayList<>(found);
            found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
        }
        int n = Math.min(found.size(), MAX_ENTITIES);
        for (int i = 0; i < n; i++) {
            AABB b = found.get(i).getBoundingBox();
            entityBoxes.add(new float[] {
                (float) (b.minX - ox), (float) (b.minY - oy), (float) (b.minZ - oz),
                (float) (b.maxX - ox), (float) (b.maxY - oy), (float) (b.maxZ - oz)
            });
        }
    }

    public void reset() {
        hasOrigin = false;
        cursor = 0;
        java.util.Arrays.fill(cells, 0);
        java.util.Arrays.fill(unitLoaded, false);
        entityBoxes.clear();
        shapes.clear();
        passChanged = 0;
        lastPassChanged = -1;
        epoch++;
        markAllDirty();
    }

    /** paksa pemindaian ulang dari awal (dan kosongkan tabel bentuk) */
    public void rescan() {
        java.util.Arrays.fill(cells, 0);
        shapes.clear();
        cursor = 0;
        passChanged = 0;
        epoch++;
        markAllDirty();
    }

    // ------------------------------------------------------------------ akses untuk BridgeUploader

    public void markAllDirty() {
        java.util.Arrays.fill(dirty, true);
        fullDirty = true;
    }

    public boolean isDirty(int u) { return dirty[u]; }
    public void clearDirty(int u) { dirty[u] = false; }
    public boolean takeFullDirty() { boolean f = fullDirty; fullDirty = false; return f; }
    public int epoch() { return epoch; }
    public boolean hasOrigin() { return hasOrigin; }
    public int originX() { return ox; }
    public int originY() { return oy; }
    public int originZ() { return oz; }
    public List<float[]> entityBoxes() { return entityBoxes; }

    /** Isi satu unit 16^3 ke buffer 64x64 RGBA8 (tata letak: piksel (lx + 16*(ly&3), lz + 16*(ly>>2))). r + 256*g = id bentuk, a = 255. */
    public void fillUnit(int u, ByteBuffer out) {
        int ux = u % UNITS;
        int uz = (u / UNITS) % UNITS;
        int uy = u / (UNITS * UNITS);
        int bx = ux * UNIT, by = uy * UNIT, bz = uz * UNIT;
        for (int ly = 0; ly < UNIT; ly++) {
            for (int lz = 0; lz < UNIT; lz++) {
                for (int lx = 0; lx < UNIT; lx++) {
                    int id = cells[index(bx + lx, by + ly, bz + lz)];
                    int px = lx + 16 * (ly & 3);
                    int py = lz + 16 * (ly >> 2);
                    int o = (py * 64 + px) * 4;
                    out.put(o,     (byte) (id & 255));
                    out.put(o + 1, (byte) ((id >> 8) & 255));
                    out.put(o + 2, (byte) 0);
                    out.put(o + 3, (byte) 255);
                }
            }
        }
    }

    // ------------------------------------------------------------------ laporan

    public List<String> report() {
        List<String> out = new ArrayList<>();
        if (!hasOrigin) {
            out.add("Belum ada data: masuk ke dunia dulu.");
            return out;
        }
        int solid = 0;
        for (int c : cells) if (c != 0) solid++;
        int loaded = 0;
        for (boolean b : unitLoaded) if (b) loaded++;
        double avgMsUnit = scanUnits == 0 ? 0.0 : scanNanos / (double) scanUnits / 1.0e6;
        int upt = Math.max(1, unitsPerTick);
        double texKiB = shapes.texelCount() * 4.0 / 1024.0;

        out.add(String.format("Grid %d^3, asal (%d, %d, %d), memori sel %.2f MiB", SIZE, ox, oy, oz, cells.length * 4.0 / (1024.0 * 1024.0)));
        out.add(String.format("Unit 16^3 termuat: %d / %d, sel berisi: %d (%.1f%%)", loaded, UNITS_TOTAL, solid, 100.0 * solid / cells.length));
        out.add(String.format("Bentuk unik: %d, kotak total: %d, melebihi %d kotak: %d", shapes.size(), shapes.totalBoxes(), ShapeTable.MAX_BOXES, shapes.overflowCount()));
        out.add(String.format("Tabel bentuk bila dikirim ke shader: %d texel (~%.1f KiB RGBA8)", shapes.texelCount(), texKiB));
        out.add(String.format("Pemindaian: %.3f ms/unit, %d unit/tick (~%.2f ms/tick), satu putaran = %d tick", avgMsUnit, upt, avgMsUnit * upt, (UNITS_TOTAL + upt - 1) / upt));
        out.add(String.format("Putaran selesai: %d, sel berubah di putaran terakhir: %s", passesDone, lastPassChanged < 0 ? "-" : Long.toString(lastPassChanged)));
        out.add(String.format("Entity terlacak: %d (maks %d)", entityBoxes.size(), MAX_ENTITIES));
        return out;
    }

    public List<String> dumpShapes() {
        List<String> out = new ArrayList<>();
        for (int id = 1; id <= shapes.size(); id++) {
            ShapeTable.Entry e = shapes.get(id);
            StringBuilder sb = new StringBuilder();
            sb.append(id).append(" | ").append(e.name).append(" | ").append(e.sourceBoxes).append(" kotak");
            if (e.sourceBoxes > ShapeTable.MAX_BOXES) sb.append(" (DIPOTONG)");
            sb.append(" |");
            for (int i = 0; i < e.boxCount(); i++) {
                sb.append(" [");
                for (int k = 0; k < 6; k++) {
                    if (k > 0) sb.append(',');
                    sb.append(e.boxes[i * 6 + k]);
                }
                sb.append(']');
            }
            out.add(sb.toString());
        }
        return out;
    }
}
