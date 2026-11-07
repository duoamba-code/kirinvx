package net.kirin.voxelbridge;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Mengisi tekstur "vxbridge" milik shader pack dari data VoxelWorld.
 *
 * Mod TIDAK memakai internal Iris. Shader pack mendeklarasikan tekstur kosong 1536x640 (customTexture.vxbridge); Iris membuat objek GL-nya.
 * Mod mencari objek GL itu dengan memindai id tekstur GL (ukuran + format cocok), lalu menimpa isinya dengan glTexSubImage2D tiap tick.
 * Binding tekstur aktif dikembalikan persis seperti sebelumnya, jadi cache status GL milik Minecraft tidak terganggu.
 *
 * 0.2.1: rentang pindai 32768 id, id non-2D diingat (tidak di-bind ulang, jadi tidak ada lagi pesan GL_INVALID_OPERATION berulang),
 * format RGBA8 atau RGBA diterima, ada laporan pindai, pindai sinkron (/kirinvx bridge find) dan uji mandiri (/kirinvx bridge test).
 */
public final class BridgeUploader {
    private static final int SCAN_MAX = 32768;      // id tekstur GL yang dipindai
    private static final int SCAN_PER_TICK = 1024;
    private static final int MAX_UNITS_PER_TICK = 48;
    private static final int GL_RGBA_UNSIZED = 0x1908;

    public volatile boolean enabled = true;

    private static final class ScanResult {
        int textures, twoD, maxId;
        final List<Integer> found = new ArrayList<>();
        final List<String> near = new ArrayList<>();
    }

    private final List<Integer> ids = new ArrayList<>();
    private final BitSet notTwoD = new BitSet();
    private ScanResult cur = new ScanResult();
    private ScanResult last;
    private int cyclesLogged;
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

    /** Memeriksa satu id tekstur. Pemanggil wajib menyimpan dan mengembalikan binding GL_TEXTURE_2D. */
    private void probe(int id, ScanResult r) {
        if (notTwoD.get(id)) { r.textures++; return; }
        if (!GL11C.glIsTexture(id)) return;
        r.textures++;
        r.maxId = id;
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        if (GL11C.glGetError() != 0) { notTwoD.set(id); return; }        // bukan tekstur 2D (cubemap, array, 3D): jangan coba lagi
        int w = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
        int h = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
        int f = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
        if (GL11C.glGetError() != 0) { notTwoD.set(id); return; }
        r.twoD++;
        if (w == BridgeLayout.W && h == BridgeLayout.H) {
            if (f == GL11C.GL_RGBA8 || f == GL_RGBA_UNSIZED) r.found.add(id);
            else r.near.add("id " + id + " ukuran sama tetapi format 0x" + Integer.toHexString(f));
        } else if ((w == BridgeLayout.W || h == BridgeLayout.H) && r.near.size() < 8) {
            r.near.add("id " + id + " " + w + "x" + h + " format 0x" + Integer.toHexString(f));
        }
    }

    private void scanStep() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        int prev = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        drainErrors(false);
        int end = Math.min(scanCursor + SCAN_PER_TICK, SCAN_MAX + 1);
        for (int id = scanCursor; id < end; id++) probe(id, cur);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prev);
        drainErrors(false);
        scanCursor = end;
        if (scanCursor > SCAN_MAX) finishCycle();
    }

    private void finishCycle() {
        last = cur;
        cur = new ScanResult();
        scanCursor = 1;
        if (!last.found.isEmpty()) {
            ids.clear();
            ids.addAll(last.found);
            needFull = true;
            cooldown = 0;
            KirinVoxelBridge.LOG.info("Tekstur jembatan ditemukan: GL id {} (dari {} tekstur, {} 2D)", ids, last.textures, last.twoD);
        } else {
            cooldown = 100;
            if (cyclesLogged++ < 5) {
                KirinVoxelBridge.LOG.info("Pindai tekstur selesai: {} tekstur, {} 2D, id tertinggi {}; TIDAK ada 1536x640 RGBA8. Mirip: {}",
                        last.textures, last.twoD, last.maxId, last.near);
            }
        }
    }

    private boolean quickMatch(int id) {
        if (!GL11C.glIsTexture(id)) return false;
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        if (GL11C.glGetError() != 0) return false;
        int w = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
        int h = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
        int f = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
        return w == BridgeLayout.W && h == BridgeLayout.H && (f == GL11C.GL_RGBA8 || f == GL_RGBA_UNSIZED);
    }

    private void verify() {
        int prev = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        drainErrors(false);
        ids.removeIf(id -> !quickMatch(id));
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prev);
        drainErrors(false);
        if (ids.isEmpty()) {
            scanCursor = 1;
            cooldown = 0;
            cur = new ScanResult();
        }
    }

    /** Pindai SINKRON sekarang juga (beberapa ms), untuk /kirinvx bridge find. */
    public List<String> scanNow() {
        List<String> out = new ArrayList<>();
        failure = null;
        int prev = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        drainErrors(false);
        ScanResult r = new ScanResult();
        for (int id = 1; id <= SCAN_MAX; id++) probe(id, r);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prev);
        drainErrors(false);
        last = r;
        cur = new ScanResult();
        scanCursor = 1;
        cooldown = 0;
        out.add(String.format("Pindai selesai: %d tekstur ada, %d bertipe 2D, id tertinggi %d (batas pindai %d).", r.textures, r.twoD, r.maxId, SCAN_MAX));
        if (r.found.isEmpty()) {
            ids.clear();
            out.add("TIDAK ada tekstur 1536x640 RGBA8. Artinya Iris belum membuat tekstur vxbridge: cek (1) pack yang aktif memang kirinPT 0.2.x,");
            out.add("(2) VX_BRIDGE = 1 pada pack itu, (3) shader pack aktif (bukan 'off'), lalu muat ulang shader (R) dan coba lagi.");
            if (!r.near.isEmpty()) out.add("Tekstur dengan ukuran mirip: " + r.near);
        } else {
            ids.clear();
            ids.addAll(r.found);
            needFull = true;
            out.add("Ditemukan: GL id " + r.found + ". Unggahan dimulai.");
        }
        return out;
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
        drainErrors(false);
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
            drainErrors(true);
        }
        for (int u : units) world.clearDirty(u);
        java.util.Arrays.fill(rowDirty, false);
    }

    /** record = true: simpan kode error pertama untuk status (hanya dari unggahan, bukan dari pemindaian). */
    private void drainErrors(boolean record) {
        int e;
        int guard = 0;
        while ((e = GL11C.glGetError()) != 0 && guard++ < 32) {
            if (record && lastGlError == 0) lastGlError = e;
        }
    }

    // ------------------------------------------------------------------ uji mandiri

    /**
     * Menguji sisi mod tanpa Iris: membuat tekstur 1536x640 sendiri, mengisinya lewat jalur unggah yang sama, lalu membacanya kembali
     * dan memeriksa header + satu unit. Bila ini OK tetapi tekstur Iris tidak ditemukan, masalahnya ada di sisi shader pack / Iris.
     */
    public List<String> selfTest(VoxelWorld world) {
        List<String> out = new ArrayList<>();
        if (!world.hasOrigin()) {
            out.add("Masuk ke dunia dulu.");
            return out;
        }
        int prev = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int prevAlign = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
        drainErrors(false);
        int tex = GL11C.glGenTextures();
        ByteBuffer rb = null;
        List<Integer> saved = new ArrayList<>(ids);
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, BridgeLayout.W, BridgeLayout.H, 0,
                               GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            int err0 = GL11C.glGetError();
            out.add("Buat tekstur uji (id " + tex + "): " + (err0 == 0 ? "OK" : "GAGAL, error GL 0x" + Integer.toHexString(err0)));

            ids.clear();
            ids.add(tex);
            world.markAllDirty();
            resetTable();
            syncedEpoch = world.epoch();
            syncTable(world);
            writeHeaderAndEntities(world);
            upload(world);

            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
            rb = MemoryUtil.memAlloc(BridgeLayout.W * BridgeLayout.H * 4);
            GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, rb);
            int err1 = GL11C.glGetError();

            int hdr = ((BridgeLayout.DATA_Y + BridgeLayout.HDR_BASE / BridgeLayout.W) * BridgeLayout.W + BridgeLayout.HDR_BASE % BridgeLayout.W) * 4;
            boolean magic = (rb.get(hdr) & 255) == BridgeLayout.MAGIC_R && (rb.get(hdr + 1) & 255) == BridgeLayout.MAGIC_G
                         && (rb.get(hdr + 2) & 255) == BridgeLayout.MAGIC_B && (rb.get(hdr + 3) & 255) == BridgeLayout.MAGIC_A;
            int ox = (rb.get(hdr + 4) & 255) | ((rb.get(hdr + 5) & 255) << 8) | ((rb.get(hdr + 6) & 255) << 16) | ((rb.get(hdr + 7) & 255) << 24);

            // unit pertama yang berisi sel bukan-kosong
            int checkedUnit = -1;
            boolean unitOk = true;
            ByteBuffer expect = MemoryUtil.memAlloc(BridgeLayout.UNIT_PX * BridgeLayout.UNIT_PX * 4);
            try {
                for (int u = 0; u < VoxelWorld.UNITS_TOTAL; u++) {
                    world.fillUnit(u, expect);
                    boolean any = false;
                    for (int i = 0; i < expect.capacity(); i += 4) if (expect.get(i) != 0 || expect.get(i + 1) != 0) { any = true; break; }
                    if (!any) continue;
                    checkedUnit = u;
                    int x0 = (u % BridgeLayout.UNITS_PER_ROW) * BridgeLayout.UNIT_PX;
                    int y0 = (u / BridgeLayout.UNITS_PER_ROW) * BridgeLayout.UNIT_PX;
                    for (int py = 0; py < BridgeLayout.UNIT_PX && unitOk; py++) {
                        for (int px = 0; px < BridgeLayout.UNIT_PX; px++) {
                            int a = ((y0 + py) * BridgeLayout.W + x0 + px) * 4;
                            int b = (py * BridgeLayout.UNIT_PX + px) * 4;
                            if (rb.get(a) != expect.get(b) || rb.get(a + 1) != expect.get(b + 1)) { unitOk = false; break; }
                        }
                    }
                    break;
                }
            } finally {
                MemoryUtil.memFree(expect);
            }

            out.add("Baca-balik tekstur: " + (err1 == 0 ? "OK" : "error GL 0x" + Integer.toHexString(err1)));
            out.add("Header magic: " + (magic ? "OK" : "SALAH") + ", asal x = " + ox + " (mod: " + world.originX() + ")");
            out.add(checkedUnit < 0 ? "Belum ada unit berisi sel (grid masih kosong), tunggu beberapa detik lalu ulangi."
                                    : "Isi unit " + checkedUnit + ": " + (unitOk ? "OK" : "TIDAK COCOK"));
            out.add("Tabel bentuk: " + world.shapes.size() + " bentuk tersinkron.");
            out.add(magic && unitOk && err1 == 0 ? "HASIL: sisi mod BERFUNGSI. Bila tekstur Iris tidak ditemukan, penyebabnya di pack/Iris."
                                                  : "HASIL: ada masalah di sisi mod (lihat baris di atas).");
        } catch (Throwable t) {
            KirinVoxelBridge.LOG.error("selfTest gagal", t);
            out.add("selfTest error: " + t);
        } finally {
            if (rb != null) MemoryUtil.memFree(rb);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prev);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, prevAlign);
            GL11C.glDeleteTextures(tex);
            drainErrors(false);
            ids.clear();
            ids.addAll(saved);
            needFull = true;               // tekstur sungguhan (bila ada) diisi ulang dari awal
            world.markAllDirty();
        }
        return out;
    }

    public void forceFind() {
        ids.clear();
        cur = new ScanResult();
        scanCursor = 1;
        cooldown = 0;
        failure = null;
    }

    // ------------------------------------------------------------------ status

    public List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("Jembatan: " + (enabled ? "aktif" : "dimatikan") + (failure != null ? ", DIMATIKAN karena error: " + failure : ""));
        if (ids.isEmpty()) {
            out.add("Tekstur vxbridge BELUM ditemukan (butuh shader pack kirinPT 0.2.x dengan VX_BRIDGE = 1). Pindai: id " + scanCursor + " / " + SCAN_MAX
                    + (cooldown > 0 ? ", jeda " + cooldown + " tick" : ""));
            if (last != null) {
                out.add("Pindai terakhir: " + last.textures + " tekstur, " + last.twoD + " 2D, id tertinggi " + last.maxId
                        + (last.near.isEmpty() ? "" : ", mirip: " + last.near));
            }
            out.add("Ketik /kirinvx bridge find untuk pindai sekarang, /kirinvx bridge test untuk menguji sisi mod.");
        } else {
            out.add("Tekstur vxbridge ditemukan: GL id " + ids);
        }
        out.add(String.format("Unggahan: %d unit 64x64, %d baris data. Tabel: %d bentuk tersinkron%s", unitUploads, rowUploads, syncedShapes,
                tableOverflow ? " (TABEL PENUH, sebagian bentuk tak terkirim)" : ""));
        out.add("Kode error GL pertama dari unggahan: " + (lastGlError == 0 ? "tidak ada" : "0x" + Integer.toHexString(lastGlError)));
        return out;
    }
}
