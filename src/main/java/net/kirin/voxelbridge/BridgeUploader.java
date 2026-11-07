package net.kirin.voxelbridge;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Mengisi tekstur "vxbridge" milik shader pack dari data VoxelWorld.
 *
 * Mod TIDAK memakai internal Iris. Shader pack mendeklarasikan tekstur kosong 1536x640 (customTexture.vxbridge); Iris membuat objek GL-nya.
 * Mod mencari objek GL itu dengan memindai id tekstur GL (ukuran + format cocok), lalu menimpa isinya dengan glTexSubImage2D tiap tick.
 * Binding tekstur aktif dikembalikan persis seperti sebelumnya, jadi cache status GL milik Minecraft tidak terganggu.
 *
 * Hanya berjalan di thread klien (END_CLIENT_TICK), yang juga thread render.
 */
public final class BridgeUploader {
    private static final int SCAN_MAX = 4096;       // id tekstur GL yang dipindai
    private static final int SCAN_PER_TICK = 256;
    private static final int MAX_UNITS_PER_TICK = 48;

    public volatile boolean enabled = true;

    private final List<Integer> ids = new ArrayList<>();
    private final List<Integer> candidates = new ArrayList<>();
    private int scanCursor = 1;
    private int cooldown = 0;
    private int tickCount = 0;
    private boolean needFull = false;

    private final ByteBuffer unitBuf = MemoryUtil.memAlloc(BridgeLayout.UNIT_PX * BridgeLayout.UNIT_PX * 4);
    private final ByteBuffer dataBuf = MemoryUtil.memAlloc(BridgeLayout.DATA_TEXELS * 4);
    private final boolean[] rowDirty = new boolean[BridgeLayout.DATA_ROWS];

    private int syncedEpoch = -1;
    private int syncedShapes = 0;
    private int nextBoxAddr = BridgeLayout.BOX_BASE;
    private boolean tableOverflow = false;

    // statistik / status
    private long unitUploads;
    private long rowUploads;
    private int lastGlError;
    private String failure;

    public void tick(Minecraft mc, VoxelWorld world) {
        if (!enabled || failure != null) return;
        try {
            tickCount++;
            if (mc.level == null) {
                ids.clear();
                return;
            }
            if (!world.hasOrigin()) return;

            if (ids.isEmpty()) {
                scanStep();
                if (ids.isEmpty()) return;
            }
            if (tickCount % 100 == 0) verify();
            if (ids.isEmpty()) return;

            if (needFull) {
                world.markAllDirty();
                resetTable();
                needFull = false;
            }
            syncTable(world);
            writeHeaderAndEntities(world);
            upload(world);
        } catch (Throwable t) {
            failure = t.toString();
            KirinVoxelBridge.LOG.error("BridgeUploader dimatikan karena error", t);
        }
    }

    // ------------------------------------------------------------------ mencari tekstur

    private void scanStep() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        int prev = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int end = Math.min(scanCursor + SCAN_PER_TICK, SCAN_MAX);
        for (int id = scanCursor; id < end; id++) {
            if (!GL11C.glIsTexture(id)) continue;
            if (matches(id)) candidates.add(id);
        }
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prev);
        drainErrors();
        scanCursor = end;
        if (scanCursor >= SCAN_MAX) {
            scanCursor = 1;
            if (candidates.isEmpty()) {
                cooldown = 200;            // tidak ketemu (shader pack mati / VX_BRIDGE 0): coba lagi ~10 detik
            } else {
                ids.clear();
                ids.addAll(candidates);
                needFull = true;
                KirinVoxelBridge.LOG.info("Tekstur jembatan ditemukan: GL id {}", ids);
            }
            candidates.clear();
        }
    }

    /** Mengikat tekstur id lalu membaca ukuran dan format level 0. Pemanggil wajib mengembalikan binding sebelumnya. */
    private boolean matches(int id) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        int w = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
        if (GL11C.glGetError() != 0 || w != BridgeLayout.W) return false;
        int h = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
        int f = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
        return h == BridgeLayout.H && f == GL11C.GL_RGBA8;
    }

    private void verify() {
        int prev = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        ids.removeIf(id -> !GL11C.glIsTexture(id) || !matches(id));
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prev);
        drainErrors();
        if (ids.isEmpty()) {
            scanCursor = 1;
            cooldown = 0;
        }
    }

    public void forceFind() {
        ids.clear();
        candidates.clear();
        scanCursor = 1;
        cooldown = 0;
        failure = null;
    }

    // ------------------------------------------------------------------ isi buffer data (CPU)

    private void putTexel(int addr, int r, int g, int b, int a) {
        int o = addr * 4;
        dataBuf.put(o,     (byte) r);
        dataBuf.put(o + 1, (byte) g);
        dataBuf.put(o + 2, (byte) b);
        dataBuf.put(o + 3, (byte) a);
        rowDirty[addr / BridgeLayout.W] = true;
    }

    private void resetTable() {
        for (int i = 0; i < dataBuf.capacity(); i++) dataBuf.put(i, (byte) 0);
        java.util.Arrays.fill(rowDirty, true);
        syncedShapes = 0;
        nextBoxAddr = BridgeLayout.BOX_BASE;
        tableOverflow = false;
    }

    private void syncTable(VoxelWorld world) {
        if (world.epoch() != syncedEpoch) {
            resetTable();
            syncedEpoch = world.epoch();
        }
        ShapeTable shapes = world.shapes;
        for (int id = syncedShapes + 1; id <= shapes.size(); id++) {
            if (id >= BridgeLayout.BOX_BASE) { tableOverflow = true; break; }
            ShapeTable.Entry e = shapes.get(id);
            int n = e.boxCount();
            if (nextBoxAddr + 2 * n > BridgeLayout.HDR_BASE) { tableOverflow = true; break; }
            int off = nextBoxAddr - BridgeLayout.BOX_BASE;
            putTexel(id, off & 255, (off >> 8) & 255, (off >> 16) & 255, n);
            for (int i = 0; i < n; i++) {
                int[] b = e.boxes;
                putTexel(nextBoxAddr,     b[i * 6],     b[i * 6 + 1], b[i * 6 + 2], 255);
                putTexel(nextBoxAddr + 1, b[i * 6 + 3], b[i * 6 + 4], b[i * 6 + 5], 255);
                nextBoxAddr += 2;
            }
            syncedShapes = id;
        }
    }

    private void putInt(int addr, int v) {
        putTexel(addr, v & 255, (v >> 8) & 255, (v >> 16) & 255, (v >>> 24) & 255);
    }

    private static int enc16(float blocks) {
        int v = Math.round((blocks + 8.0f) * 256.0f);   // offset +8 blok supaya koordinat sedikit negatif tetap valid
        return v < 0 ? 0 : Math.min(v, 65535);
    }

    private void writeHeaderAndEntities(VoxelWorld world) {
        putTexel(BridgeLayout.HDR_BASE, BridgeLayout.MAGIC_R, BridgeLayout.MAGIC_G, BridgeLayout.MAGIC_B, BridgeLayout.MAGIC_A);
        putInt(BridgeLayout.HDR_BASE + 1, world.originX());
        putInt(BridgeLayout.HDR_BASE + 2, world.originY());
        putInt(BridgeLayout.HDR_BASE + 3, world.originZ());

        List<float[]> ents = world.entityBoxes();
        int n = Math.min(ents.size(), VoxelWorld.MAX_ENTITIES);
        putTexel(BridgeLayout.ENT_BASE, n, 0, 0, 0);
        for (int i = 0; i < n; i++) {
            float[] b = ents.get(i);
            int a = BridgeLayout.ENT_BASE + 1 + 3 * i;
            int x0 = enc16(b[0]), y0 = enc16(b[1]), z0 = enc16(b[2]);
            int x1 = enc16(b[3]), y1 = enc16(b[4]), z1 = enc16(b[5]);
            putTexel(a,     x0 & 255, x0 >> 8, y0 & 255, y0 >> 8);
            putTexel(a + 1, z0 & 255, z0 >> 8, x1 & 255, x1 >> 8);
            putTexel(a + 2, y1 & 255, y1 >> 8, z1 & 255, z1 >> 8);
        }
    }

    // ------------------------------------------------------------------ unggah ke GL

    private void upload(VoxelWorld world) {
        boolean full = world.takeFullDirty();
        int cap = full ? VoxelWorld.UNITS_TOTAL : MAX_UNITS_PER_TICK;
        List<Integer> units = new ArrayList<>();
        for (int u = 0; u < VoxelWorld.UNITS_TOTAL && units.size() < cap; u++) {
            if (world.isDirty(u)) units.add(u);
        }

        int prevBinding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int prevRow = GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH);
        int prevAlign = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
        try {
            for (int id : ids) {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);

                for (int u : units) {
                    world.fillUnit(u, unitBuf);
                    unitBuf.position(0).limit(unitBuf.capacity());
                    int x = (u % BridgeLayout.UNITS_PER_ROW) * BridgeLayout.UNIT_PX;
                    int y = (u / BridgeLayout.UNITS_PER_ROW) * BridgeLayout.UNIT_PX;
                    GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, x, y, BridgeLayout.UNIT_PX, BridgeLayout.UNIT_PX,
                                          GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, unitBuf);
                    unitUploads++;
                }

                for (int r = 0; r < BridgeLayout.DATA_ROWS; r++) {
                    if (!rowDirty[r]) continue;
                    int start = r * BridgeLayout.W * 4;
                    dataBuf.position(start).limit(start + BridgeLayout.W * 4);
                    GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, BridgeLayout.DATA_Y + r, BridgeLayout.W, 1,
                                          GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, dataBuf);
                    dataBuf.position(0).limit(dataBuf.capacity());
                    rowUploads++;
                }
            }
        } finally {
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, prevRow);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, prevAlign);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevBinding);
            drainErrors();
        }
        for (int u : units) world.clearDirty(u);
        java.util.Arrays.fill(rowDirty, false);
    }

    private void drainErrors() {
        int e;
        int guard = 0;
        while ((e = GL11C.glGetError()) != 0 && guard++ < 32) {
            if (lastGlError == 0) lastGlError = e;
        }
    }

    // ------------------------------------------------------------------ status

    public List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("Jembatan: " + (enabled ? "aktif" : "dimatikan") + (failure != null ? ", DIMATIKAN karena error: " + failure : ""));
        if (ids.isEmpty()) {
            out.add("Tekstur vxbridge BELUM ditemukan (butuh shader pack kirinPT dengan VX_BRIDGE = 1). Pemindaian: id " + scanCursor + " / " + SCAN_MAX
                    + (cooldown > 0 ? ", jeda " + cooldown + " tick" : ""));
        } else {
            out.add("Tekstur vxbridge ditemukan: GL id " + ids);
        }
        out.add(String.format("Unggahan: %d unit 64x64, %d baris data. Tabel: %d bentuk tersinkron%s", unitUploads, rowUploads, syncedShapes,
                tableOverflow ? " (TABEL PENUH, sebagian bentuk tak terkirim)" : ""));
        out.add("Kode error GL pertama yang tercatat: " + (lastGlError == 0 ? "tidak ada" : "0x" + Integer.toHexString(lastGlError)));
        return out;
    }
}
