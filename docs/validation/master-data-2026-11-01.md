# AVATAR kabul kontrolleri — 1 Kasım 2026

Dal: `feat/master-data`; devralınan API çalışması: `7113607`.
Çalışan main demosu değiştirilmedi. Testler ayrı MySQL (`3307`) ve dalın kaynak kodundan
derlenen Go API (`18080`) üzerinde yapıldı; `ban-mysql` veritabanı sıfırlanmadı.
PR ve merge bu çalışmanın kapsamında yapılmadı.

## Tamamlanan ana veri işi

- Türkçe/İngilizce ana veri sayfası: ürün kartları, ölçü birimleri, tedarikçiler,
  müşteriler/iletişim bilgileri, parçalama reçeteleri ve yeni kodla reçete sürümü.
- Şartnamenin görevlerine göre gruplanmış menü.
- Alış formunda daha önce hareketi olmayan yeni ürün/tedarikçinin de seçilebilmesi;
  pasif ürünün yeni alışta reddedilmesi.
- Parçalama formunda reçete seçimi ve reçeteden ürün/tür/dağıtım anahtarını doldurma.
  Fiili ağırlık ve çıktı lotları boş kalır; dolu satırlar bu işlemle ezilmez.
- Stok hareketlerinde müşteri iadesi; stoğu tükenmiş lotlardan da karantinaya iade.
- Reçeteye bağlanmış ürünün, henüz lotu olmasa da birim/türünün korunması; yeni reçete
  satırlarında ürün türü, aktiflik ve KG kontrolü. Alış sırasında ürün satırının kilitlenmesi.
- API, özellik ve şartname belgeleri; mevcut kurulum için migration açıklaması.

## Otomatik sonuçlar

| Kontrol | Sonuç |
|---|---|
| Go `go vet ./...`, `go test -count=1 -json ./...` | 89 üst düzey test geçti; bunların 41'i gerçek MySQL entegrasyon testi, atlanan test yok |
| MySQL `pytest database-mysql` | 65 geçti |
| React `npm test` | 9 dosyada 28 test geçti |
| React `npm run build` | Geçti |
| Playwright, bütün paket, Microsoft Edge | 22 geçti; beş yeni ana veri/entegrasyon senaryosu dahil |
| Android `lintDebug assembleDebug testDebugUnitTest` | Derleme/lint geçti; 51 birim testi geçti |
| ML `pytest ml-engine/tests` | 56 geçti; atlanan test yok |
| `verify-assessment-pdf.py` | Dört sayfalık PDF'de Türkçe metin ve gerekli senaryo kanıtları doğrulandı |
| `20261102_master_data.sql` | Ayrı test veritabanına uygulandı; kayıtlı parçalamanın dağıtım API'si 200 döndü |

Android lint uyarıları ve Gradle kullanımdan kalkma uyarıları mevcut; lint hatası yok.
Playwright bu makinede kurulu Edge ile çalıştırıldı. GitHub CI bu dal için bu çalışmada
çalıştırılmadı; yukarıdaki sonuçlar yerel kontrollerdir.

## Değerlendirmenin beş odağı

| Ölçüt | Doğrulanan davranış ve kanıt |
|---|---|
| Doğru maliyet | `TestIntegrationTask1EKP`: 1.543,50 € / 85 = 18,1588… €, ekranda 18,16 €. Hizmet yalnız 467,50 €; hammadde 1.076 €. `TestIntegrationButcheryFindingsAndAllocation`: 25 × 11,50 = 287,50 €, 351 € beyanı ve 22 kg parça toplamındaki fark açıklanıyor. Her iki dağıtım anahtarında toplam eşit. |
| Eksiksiz lot ilişkileri | `TestIntegrationTrace`, `TestIntegrationMassBalanceAccountsForEveryPack`, veritabanı lot-link testleri ve PDF: ET-2026-001 → URT-2026-045 → SUC-2026-045 → marketler. Yeni reçete testinde parça ve yan ürünün miktarlı bağlantıları da kontrol edildi. İkinci kademe yalnız kayıtlı sevkten geliyor. |
| Güvenilir stok | Negatif stok/yanlış işaret ve defter değişikliği engelleri, fazla iadenin reddi, sayım gerekçesi, geri çağrılmış/SKT geçmiş satışın reddi, senkronizasyon tekrarının tek kayıt olması test edildi. Yeni testler fiyat değişikliğinin geçmiş lot maliyetini değiştirmediğini ve başarısız işlemin yarım kayıt bırakmadığını kontrol ediyor. |
| Kullanılabilir Kotlin uygulaması | 51 kural/birim testi, APK/lint ve aşağıdaki gerçek emülatör işlemleri. Fiziksel telefon ve kamera/barkod donanımı bu çalışmada denenmedi. |
| Ölçülebilir YZ katkısı | 56 test; API ile model tutarlılığı, referans yöntemler, hata ölçümleri, zaman bölmesi ve veri kaynağı etiketleri. Aşağıdaki sayılar sentetik değerlendirmeden; gerçek saha başarımı olarak sunulamaz. |

Yeni davranışların testleri:

- `api-go/controllers/integration_master_data_test.go`: ürün kartı, audit aktörü ve
  eski/yeni değerler; tip/birim koruması; birim/ortak kayıtları; iade sınırı; reçete
  doğrulaması; varsayılan anahtar; ürün adı/fiyatı değiştiğinde iki anahtarlı dağıtım.
- `web-react/e2e/master-data.spec.js`: formlardan kayıt/düzenleme, iki dil,
  pasifleştirme, reçeteyle gerçek parçalama kaydı ve lot zinciri, tükenmiş lottan
  iade/fazla iade reddi; kayıtlı sekiz yazma rotasında oturumsuz isteğin reddi.

## Android emülatörüyle uçtan uca kontrol

Yeni derlenen `1.1.0-avatar` debug APK; SHA-256:
`04536471c45a09a991a4f214211cafefbb9bab95a7667a170f3c7e2ee286b94c`.
`Medium_Phone_API_37.0`, `emulator-5580`, başsız ve `-read-only -no-snapshot-save` ile
çalıştırıldı. Bu geçici örneğe APK kuruldu; asıl AVD verisi kaydedilmedi.
`adb reverse tcp:8080 tcp:18080` yalnız bu test örneğini test API'sine bağladı.

1. Canlı katalogda geçerli ürünler ve FEFO önerisi açıldı; SKT geçmiş kıyma satışı
   engelli gösterildi.
2. API bağlantısı kesildi. SUC-2026-046 lotundan bir paket satışı ekrandan kaydedildi.
   Cihazda “Waiting to send” göründü. Sunucudaki bu lotun satış satırı sayısı 1'de ve
   mağaza stoğu 4 pakette kaldı.
3. Bağlantı geri verilip gönderim tetiklendi. Durum “Sent” ve “Synced” oldu.
   Satış satırı sayısı 2'ye, stok 3 pakete geldi. Yeni satır: 1 paket / 24,90 €;
   `sale_line.stock_movement_id` ile bağlı `SALE` hareketi −1 paket.
4. “Send now” tekrarına rağmen ek satış oluşmadı. SQLite dosyası ayrıca okunarak
   yerel hareketin −1 / 24,90 €, yerel hareket ve `pos_outbox` durumlarının `SYNCED`,
   ECDSA imzasının dolu olduğu doğrulandı.
5. KUZU-2026-001 için `RCL-MD-EMU-001` geri çağırması oluşturuldu. Uygulama mağazadaki
   KZB-2026-001 ve KZY-2026-001 için “Sales paused” ekranını açtı. API yeniden
   kesilip uygulama kapatılıp açıldığında bloke ekranı çevrimdışı önbellekten korundu.

Yerel kanıtlar (Git'e eklenmeyen test çıktıları):
`web-react/test-results/assessment-verification/` altında Go JSON sonucu, Android ekran
XML/PNG'leri ve `android-sqlite-evidence.json`; `web-react/test-results/` altında
`assessment-tr.pdf`, `master-partners.png`, `master-recipe-butchery.png`.

## YZ ölçümünün sınırı

Mevcut `ml-engine/artifacts/ali/evaluation.json` zaman bölmeli sentetik değerlendirmesi:

| Çıktı | Model MAE | Geçmiş ortalama referansı MAE |
|---|---:|---:|
| Üretim paket sayısı | 1,307 paket | 2,548 paket |
| EKP | 0,128 € | 0,245 € |
| Parçalama, altı çıktı üzerinden ortalama | 0,118 kg | 0,184 kg |

Bunlar 1.500 üretim / 2.500 parçalama satırlık sentetik geçmişin zaman bölmeli
değerlendirme artefaktındaki toplu hata ölçümleridir; yeni saha verisiyle ölçüm veya
bu çalışmada yeniden eğitim yapılmadı. Model sahada daha iyi sonuç verir iddiası
kurulamaz. Lot izleme ve geri çağırma sonuçları kesin kayıt ilişkilerinden gelir.

## Yeniden çalıştırma

Temiz **ayrı test veritabanında** `schema.sql` ve `seed.sql` yükleyin; ana demo üzerinde
test/reset çalıştırmayın. Örnek yerel adresler bu çalışmada MySQL 3307 ve API 18080 idi.

```powershell
# Go 1.25 ortamında (yerel veya Docker), api-go dizininden:
$env:MYSQL_TEST_DSN='root:ban@tcp(127.0.0.1:3307)/food_intel?parseTime=true&multiStatements=true'
go vet ./...
go test -count=1 ./...

# web-react dizininden; API de aynı dalın kaynak kodundan derlenmiş olmalı:
npm.cmd test
npm.cmd run build
$env:E2E_API_BASE='http://127.0.0.1:18080'
$env:E2E_BROWSER_CHANNEL='msedge'
npx.cmd playwright test
```

Go entegrasyon, MySQL pytest ve tarayıcı testleri aynı veritabanında eşzamanlı
çalıştırılmamalı; her paket öncesi yalnız test veritabanı yeniden yüklenmelidir.
ML testlerinde hem `ml-engine` hem `api-go/controllers/aimodel` erişilebilir olmalı.
Android derlemesi için projenin Gradle sürümü, SDK 34 ve build-tools 35.0.0 gerekir.
