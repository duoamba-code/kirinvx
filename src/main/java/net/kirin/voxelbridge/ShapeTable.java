package net.kirin.voxelbridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tabel bentuk: satu entri per BlockState unik yang pernah terlihat (id 1..N; 0 = udara).
 * Bentuk diambil dari VoxelShape milik game sendiri (getShape = bentuk outline), jadi blok mod ikut otomatis.
 * Tiap kotak disimpan dalam satuan 1/16 blok (0..16) supaya bisa langsung dipakai shader sebagai texel byte.
 */
public final class ShapeTable {
    public static final int MAX_BOXES = 16;

    public static final class Entry {
        public final String name;
        public final int[] boxes;        // 6 int per kotak: minx miny minz maxx maxy maxz (0..16)
        public final int sourceBoxes;    // jumlah kotak asli sebelum dipotong MAX_BOXES

        Entry(String name, int[] boxes, int sourceBoxes) {
            this.name = name;
            this.boxes = boxes;
            this.sourceBoxes = sourceBoxes;
        }

        public int boxCount() { return boxes.length / 6; }
    }

    private final Map<BlockState, Integer> ids = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();

    /** id bentuk untuk state ini; menghitung dan menyimpannya bila belum ada. pos hanya dipakai sebagai konteks getShape. */
    public int idFor(BlockState state, BlockGetter level, BlockPos pos) {
        Integer id = ids.get(state);
        if (id != null) return id;
        Entry e = build(state, level, pos);
        entries.add(e);
        int nid = entries.size();
        ids.put(state, nid);
        return nid;
    }

    private static int q(double v) {
        int r = (int) Math.round(v * 16.0);
        return r < 0 ? 0 : Math.min(r, 16);
    }

    private static Entry build(BlockState state, BlockGetter level, BlockPos pos) {
        VoxelShape shape = state.getShape(level, pos);
        List<AABB> list = shape.isEmpty() ? List.of() : shape.toAabbs();
        int n = Math.min(list.size(), MAX_BOXES);
        int[] q = new int[n * 6];
        for (int i = 0; i < n; i++) {
            AABB b = list.get(i);
            q[i * 6]     = q(b.minX);
            q[i * 6 + 1] = q(b.minY);
            q[i * 6 + 2] = q(b.minZ);
            q[i * 6 + 3] = q(b.maxX);
            q[i * 6 + 4] = q(b.maxY);
            q[i * 6 + 5] = q(b.maxZ);
        }
        return new Entry(state.toString(), q, list.size());
    }

    public int size() { return entries.size(); }

    public Entry get(int id) { return entries.get(id - 1); }

    public int totalBoxes() {
        int t = 0;
        for (Entry e : entries) t += e.boxCount();
        return t;
    }

    public int overflowCount() {
        int t = 0;
        for (Entry e : entries) if (e.sourceBoxes > MAX_BOXES) t++;
        return t;
    }

    /** texel RGBA8 yang dibutuhkan bila tabel ini dikirim ke shader: 1 header + 2 per kotak, per bentuk. */
    public int texelCount() {
        int t = 0;
        for (Entry e : entries) t += 1 + 2 * e.boxCount();
        return t;
    }

    public void clear() {
        ids.clear();
        entries.clear();
    }
}
