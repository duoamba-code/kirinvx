# Kirin Voxel Bridge 0.2.1, tahap 2 (Fabric 1.21.11, klien saja)

BELUM DIKOMPILASI atau DIJALANKAN oleh penulisnya (tidak ada JDK/Gradle/Minecraft/GPU di lingkungan pembuatan). Bila ada error, kirim teksnya apa adanya.

## Sistemnya
```
 dunia (klien)                       mod                                   GPU                      shader pack kirinPT
 BlockState ──getShape──▶ tabel bentuk (id -> kotak 1/16 blok)  ─┐
 96^3 sel di sekitar pemain ─▶ grid id bentuk                    ├─ glTexSubImage2D ─▶ tekstur "vxbridge" ─▶ vx_bridge.glsl ─▶ vx_raycast
 entity terdekat ─▶ kotak pembatas                               ─┘   (1536x640 RGBA8)
```
1. Daftar nama + angka di `config/kirinvx-shapes.txt` (`/kirinvx dump`) adalah tabel bentuk itu sendiri: `id | blockstate | jumlah kotak | [minx,miny,minz,maxx,maxy,maxz]`,
   angka dalam 1/16 blok (0..16). Tahap 2 mengirim tabel yang sama ke shader sebagai texel, jadi tidak ada yang perlu disalin tangan.
2. Shader pack mendeklarasikan tekstur kosong 1536x640 (`customTexture.vxbridge=textures/vxbridge.png`, aktif bila `VX_BRIDGE = 1`). Iris membuat objek GL-nya.
3. Mod memindai id tekstur GL (ukuran 1536x640 + format RGBA8), menemukan tekstur itu, lalu menimpanya tiap tick. TIDAK ADA mixin dan TIDAK ADA
   kode internal Iris yang dipakai; hanya OpenGL langsung (LWJGL). Binding tekstur dikembalikan persis seperti semula.
4. Di shader, blok yang tidak terdaftar di `block.properties` (blok mod) divoxel sebagai sid 253 (warna dari tekstur blok seperti biasa). Saat ray masuk sel
   itu, shader membaca id bentuk dari tekstur, mengambil daftar kotaknya, dan menguji ray terhadap kotak-kotak itu.

Tata letak tekstur: lihat `BridgeLayout.java` (mod) dan `lib/vx_bridge.glsl` (shader); keduanya HARUS sama.

## Cara memakai
1. Bangun mod seperti tahap 1 (lihat bagian bawah), taruh jar di `mods` bersama Fabric API, Iris, Sodium.
2. Pasang shader pack `kirinPT-0_2_0.zip`, buka pengaturan shader, set **VX_BRIDGE = 1** (Apply). Tanpa ini mod tidak menemukan tekstur.
3. Masuk dunia. Ketik `/kirinvx bridge`: harus tertulis "Tekstur vxbridge ditemukan: GL id [..]" dalam beberapa detik.
   - Belum ditemukan: `/kirinvx bridge find` untuk mencari ulang. Pemindaian membutuhkan beberapa detik dan diulang tiap ~10 detik.
4. Lihat blok mod di dunia: harus tampil dengan bentuk kotak-kotak aslinya, bukan hilang. `/kirinvx dump` untuk melihat tabelnya.

Perintah: `/kirinvx` (statistik), `/kirinvx bridge` (status unggahan), `/kirinvx bridge find`, `/kirinvx bridge toggle`, `/kirinvx dump`, `/kirinvx speed <1..216>`, `/kirinvx rescan`, `/kirinvx toggle`.

## Yang BELUM ada
- Entity: kotaknya sudah diunggah ke tekstur (alamat `ENT_BASE`), tetapi shader belum memakainya. Entity butuh warna (tekstur entity tidak dikenal pass shadow pack ini), jadi itu tahap 3.
- Event perubahan blok: grid masih dipindai bergulir (4 unit 16^3 per tick), bukan berbasis event.
- Bentuk dari `getShape` (outline), bukan model visual. Offset acak tanaman tidak dimodelkan.

## Risiko yang belum terbukti (periksa urut bila tidak jalan)
1. `customTexture.vxbridge=` mungkin tidak didukung, atau tekstur tidak terikat di pass yang membaca raycast. Gejala: mod "tidak menemukan tekstur" (log Iris
   memuat peringatan custom texture), atau blok mod tidak muncul padahal tekstur ditemukan. Kabari saya bagian log Iris yang menyebut vxbridge.
2. Iris bisa menyimpan tekstur custom dengan format selain RGBA8 (mod hanya menerima RGBA8) atau menimpanya saat reload; mod mencari ulang tiap ~5 detik.
3. Blok tak terdaftar yang bukan blok mod (mis. blok vanilla yang tidak ada di `block.properties`) ikut divoxel saat VX_BRIDGE = 1; air dan bubble column sudah dikecualikan.
4. Beban: pass shadow memproses lebih banyak quad. Di GPU lemah (Celeron 1007U) ini berat; gunakan hanya untuk uji.
5. Nama API yang mungkin gagal dikompilasi: `level.hasChunk(x, z)`, `level.getEntities(null, aabb, predicate)`, `ClientCommandManager` (lihat README tahap 1).

## Membangun
Sama seperti tahap 1: buat proyek dari https://fabricmc.net/develop/template (1.21.11, Mojang mappings), timpa `src/main/java/net/kirin/voxelbridge/*` dan
`src/main/resources/fabric.mod.json`, lalu `./gradlew build`.

## Perubahan 0.2.1
- Rentang pindai tekstur GL 4096 -> 32768 id; id yang bukan tekstur 2D diingat dan tidak di-bind ulang (menghilangkan pesan `GL_INVALID_OPERATION ... already bound to another target` yang berulang tiap ~11 detik di log).
- Format tekstur RGBA8 atau RGBA (tak berukuran) diterima.
- `/kirinvx bridge find`: pindai SINKRON sekarang dan cetak ringkasan (jumlah tekstur, id tertinggi, tekstur berukuran mirip).
- `/kirinvx bridge test`: uji mandiri sisi mod tanpa Iris (buat tekstur sendiri, unggah, baca balik, cek header dan isi unit).
- `/kirinvx bridge` kini menampilkan hasil pindai terakhir.
