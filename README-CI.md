# Membangun jar lewat GitHub Actions

Belum diuji di GitHub oleh penulisnya (YAML hanya dicek sintaksnya). Bila ada langkah merah, buka log langkah itu dan kirim teksnya.

## 1. Siapkan repo
Akar repo = folder proyek (ada `src/`, `build.gradle`, `.github/`).
**Penting:** salin juga `gradlew`, `gradlew.bat`, dan folder `gradle/wrapper/` dari template resmi https://fabricmc.net/develop/template
(Minecraft 1.21.11, Mojang mappings). Wrapper itu yang menentukan versi Gradle yang cocok dengan Loom.
`build.gradle` / `gradle.properties` di sini hanya contoh; bila template Anda berbeda, pakai milik template dan cukup timpa `src/`.

## 2. Unggah dari Termux
```
pkg install git
cd ~/modpacks/kirinvx          # folder proyek
git init && git add . && git commit -m "awal"
git branch -M main
git remote add origin https://github.com/USERNAME/NAMA-REPO.git
git push -u origin main
```
Saat diminta password, pakai Personal Access Token (GitHub -> Settings -> Developer settings -> Fine-grained tokens, izin: Contents = Read and write).
Buat repo kosong lebih dulu di github.com (tanpa README).

## 3. Ambil jar
Tab **Actions** -> workflow **Build** -> pilih run terbaru -> bagian **Artifacts** -> `kirinvx-jar` (zip berisi jar). Build berjalan otomatis tiap push.
Manual: **Actions -> Build -> Run workflow**; di situ Anda bisa mengubah versi JDK, argumen Gradle, dan versi Gradle.

Rilis dengan tautan unduh tetap: `git tag v0.2.0 && git push origin v0.2.0` -> workflow **Release** membuat GitHub Release berisi jar.

## 4. Kustomisasi
Semua ada di `.github/workflows/reusable-gradle-build.yml` (input `with:` di `build.yml`):

| Input | Bawaan | Fungsi |
|---|---|---|
| `java-version` | 21 | JDK Temurin |
| `gradle-args` | `build --no-daemon` | mis. `remapJar`, `build -x test`, `build --stacktrace` |
| `working-directory` | `.` | bila proyek ada di subfolder |
| `gradle-version` | kosong | dipakai hanya bila TIDAK ada gradlew |
| `jar-dir` | `build/libs` | folder keluaran jar |
| `exclude-patterns` | `*-sources.jar *-dev.jar *-javadoc.jar` | jar yang dibuang |
| `artifact-name` | `jar` | nama artifact |
| `retention-days` | 30 | lama artifact disimpan |

## 5. Dipakai di proyek lain
Cara A, salin `reusable-gradle-build.yml` ke `.github/workflows/` repo lain, lalu buat `build.yml` seperti di repo ini.
Cara B, panggil langsung dari repo ini (repo harus publik, atau diatur agar boleh diakses):
```yaml
jobs:
  build:
    uses: USERNAME/NAMA-REPO/.github/workflows/reusable-gradle-build.yml@main
    with:
      java-version: '21'
      gradle-args: 'build --no-daemon'
```
`package-zip.yml` adalah contoh tanpa Gradle: mengemas folder apa pun (mis. shader pack) menjadi zip.

## Bila gagal
- "Tidak ada gradlew": salin wrapper dari template (langkah 1).
- Error Loom / versi Gradle: samakan `gradle/wrapper/gradle-wrapper.properties` dan `build.gradle` dengan template untuk 1.21.11.
- Error kompilasi Java: kirim barisnya; kemungkinan nama API (lihat README.md bagian "Bila gagal dikompilasi").
- Laporan Gradle diunggah otomatis sebagai artifact `laporan-build` bila build gagal.
