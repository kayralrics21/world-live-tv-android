# GitHub ile APK oluşturma

1. GitHub üzerinde boş bir depo (repository) aç. `README` oluştur seçeneğini kapatabilirsin.
2. Kaynak ZIP dosyasını bilgisayarında aç. İçindeki **bütün dosya ve klasörleri** GitHub deposunun kök dizinine yükle. Yalnızca ZIP dosyasını yükleme.
3. GitHub'da **Actions** sekmesine gir. İstenirse iş akışlarına izin ver.
4. Sol taraftaki **Build APK** iş akışını seç ve **Run workflow** → **Run workflow** düğmesine bas. Alternatif olarak `main` dalına (branch) yükleme yapıldığında otomatik derleme başlar.
5. Derleme başarılı olduğunda ilgili çalışmayı aç. **Artifacts** bölümünden `world-live-tv-debug-apk` paketini indir.
6. ZIP'i aç, içindeki `app-debug.apk` dosyasını Android telefonuna aktar ve kur.

## Teknik ayarlar

- Java: 17
- Gradle: 8.13 (GitHub Actions kurulumu; projede `gradlew` gerekmez)
- Android Gradle Plugin: 8.13.2
- Android SDK: API 36
- Minimum Android sürümü: Android 8 (API 26)
- Çıktı: `app/build/outputs/apk/debug/app-debug.apk`

## Olası sorunlar

- `No matching variant` / `requires compileSdk`: Dosyaları doğru konumda yüklediğinden ve Android SDK 36'nın kurulduğundan emin ol.
- `License not accepted`: Action içindeki `sdkmanager --licenses` adımının çalıştığını kontrol et.
- `Artifact not found`: Önce **Build debug APK** adımının başarılı olması gerekir.
- `Gradle task not found`: Komutu **depo kök dizininde** `gradle :app:assembleDebug` olarak çalıştır.
- **Eski SDK `tools` paketini yükleme.** Projede bunun yerine `cmdline-tools/latest` kullanılır.

**Uygulamadaki tek kredi: KAYRA HAN AYDOĞAN**
