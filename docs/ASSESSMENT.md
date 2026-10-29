# AVATAR Yapay Zeka Yarışması: değerlendirme projesi (şartname)

The assessment brief as given to the team (`AVATAR_Assessment.pdf`), transcribed verbatim.
Where each bullet is implemented and how it is proven: [SPEC_COMPLIANCE.md](SPEC_COMPLIANCE.md).

---

**Yazılım Geliştirici Aday Değerlendirme Projesi** | Üretim, Maliyet, Satış ve Lot İzlenebilirliği

**Amaç:** Gerçek bir gıda işletmesinde kullanılabilecek, hammaddeden nihai satışa kadar maliyet ve stok
hareketlerini yöneten bir uygulama geliştirmek. Üç ana görev ve iki zorunlu yapay zeka çalışması
birbiriyle entegre olmalıdır.

## 1. Görev – Dış Hizmetle Üretim ve Gerçek Maliyet (EKP)

İşletme dış üreticiye 100 kg et, 2 kg baharat ve 5 kg bağırsak gönderir. Üretici 85 adet 1 kg Narin Sucuk
teslim eder ve yalnızca 85 × 5,50 € işçilik/hizmet faturası keser. Hammaddeler işletmeye aittir;
bedelleri hizmet faturasında yer almaz.

| Maliyet unsuru | Örnek tutar |
|---|---|
| 100 kg et × 9,70 € | 970,00 € |
| 2 kg baharat × 24,00 € | 48,00 € |
| 5 kg bağırsak × 11,60 € | 58,00 € |
| 85 paket × 5,50 € hizmet | 467,50 € |
| **Toplam / 85 paket** | **1.543,50 € / 85 = 18,16 € EKP** |

- Hammadde kartları, alış fiyatları, ölçü birimleri, lot numaraları ve stokları yönetilir.
- Dış üretim emri, hammadde sevki, hizmet maliyeti, mamul kabulü ve miktar farkları kaydedilir.
- Gerçek mamul miktarına göre EKP hesaplanır; VKP, brüt kâr ve marj gösterilir.
- Tüm maliyet ve stok hareketleri geçmişe dönük incelenebilir olmalıdır.

## 2. Görev – Bütün Kuzu Parçalama ve Kotlin ile Satış

Bir bütün kuzu, önceden tanımlanan ürünlere (but, sırt, omuz, kaburga, kıyma vb.) parçalanır.
Parçalama reçetesi ve dağıtım anahtarı önceden tanımlanabilir; fiili ağırlıklar işlem sırasında kaydedilir.

- Giriş hayvanı/partisi, ağırlık, gerçek toplam alış maliyeti ve işlem tarihi kaydedilir.
- Parça ürünleri, miktarları, fire/yan ürünler ve ağırlık dengesi kontrol edilir.
- Maliyet, seçilen miktar veya değer ağırlıklı dağıtım anahtarına göre ürünlere aktarılır; toplam dağıtılan
  tutar toplam maliyete eşit olmalıdır.
- Oluşan ürünler lot bazında stoklanır ve Kotlin Android satış uygulamasından satılır.

> **Not:** Örnek görselde 25 kg × 11,50 € = 287,50 € eder; 351,00 € ile farklıdır. Görseldeki parça
> miktarları da 22 kg toplamaktadır. Aday bu tutarsızlıkları tespit edip açıklamalıdır.

## 3. Görev – Parti / Lot (Charge) İzlenebilirliği ve Geri Çağırma

**Zorunlu senaryo:** ET-2026-001 lot numaralı 100 kg et, URT-2026-045 üretim emrinde kullanılmış ve
SUC-2026-045 lot numaralı 85 paket sucuk üretilmiştir. Bu ürünler farklı marketlere sevk edilmiştir.
Sonradan et partisinde kalite sorunu ortaya çıkar.

| Kayıt | Örnek |
|---|---|
| Hammadde lotu | ET-2026-001 / 100 kg |
| Üretim emri | URT-2026-045 |
| Mamul lotu | SUC-2026-045 / 85 paket |
| Market A / B / C | 30 / 25 / 30 paket |

- Her hammadde girişine tedarikçi, parti/lot numarası, tarih, miktar ve uygun olduğunda son tüketim
  tarihi atanır.
- Bir üretim emrinde birden fazla hammadde lotu kullanılabilir; bir hammadde lotu birden fazla mamul
  lotuna dönüşebilir. Bu çoktan çoğa ilişkiler miktarlarıyla saklanır.
- Parçalama işlemlerinde de giriş lotundan oluşan bütün parça ürün lotlarına izlenebilir bağlantı kurulur.
- Depo transferi, satış, iade, yeniden işleme ve fire hareketleri lot bazında kaydedilir.
- İleriye izleme: Sorunlu hammadde lotundan etkilenen bütün mamul lotları, stoklar, sevkiyatlar ve
  bilinen alıcılar listelenir.
- Geriye izleme: Bir mamul lotundan kullanılan hammadde lotlarına, tedarikçilere ve üretim emirlerine
  ulaşılır.
- Geri çağırma ekranı etkilenen ürünleri bloke eder; müşteri/market bazlı miktar, sevkiyat ve iletişim
  listesi üretir. İkinci kademe dağıtım yalnızca kaydedilmişse kesin olarak gösterilir.
- Kotlin satış uygulamasında her satış satırının lotu, miktarı ve ilgili stok hareketi saklanır; geri çağrılan
  lotların satışı engellenir.

## 4. Zorunlu Yapay Zeka Çalışmaları

**A – Üretim verimi ve EKP tahmini:** Geçmiş üretim emirlerinden beklenen mamul miktarını ve birim
maliyeti tahmin eden model. Tahmin, gerçek üretim sonucu ve sapma birlikte gösterilir.

**B – Parçalama verimi tahmini:** Hayvan türü, giriş ağırlığı ve geçmiş parçalama kayıtlarından beklenen
parça ağırlıklarını tahmin eden model. Gerçek miktarlar ile farklar raporlanır.

Aday, örnek/anonymize verileri, model yaklaşımını, basit bir referans yöntemle karşılaştırmayı ve
tahmin hata ölçümünü sunmalıdır. Gerçek veri azsa sınırlılıklar açıkça belirtilmelidir. Lot izlenebilirliği ve
geri çağırma sonuçları KI tahminine değil, kesin kayıt ilişkilerine dayanmalıdır.

## 5. İsteğe Bağlı Yapay Zeka Özellikleri

- Fatura/irsaliye okuma: Ürün, miktar, lot ve maliyet bilgilerini öneri olarak çıkarma; insan onayıyla
  kaydetme.
- Anomali uyarıları: Beklenmedik fire, maliyet artışı, düşük üretim verimi veya tutarsız lot hareketlerini
  işaretleme.

## 6. Teknik Gereksinimler

| Bileşen | Teknik gereksinim |
|---|---|
| Android satış/operasyon uygulaması | Kotlin |
| Yerel veri saklama | SQLite |
| Merkezi veri saklama | MySQL |
| Backend ve API | Go veya uygun modern teknoloji; REST API |
| Web yönetim ve raporlama | React veya uygun modern web teknolojisi |
| KI servisleri | Uygun ML yaklaşımı ve API entegrasyonu |

SQLite ve MySQL arasında tutarlı senkronizasyon yaklaşımı açıklanmalıdır. En azından alış, üretim,
parçalama, lot hareketleri, satış ve geri çağırma raporları gösterilmelidir.

## 7. Beklenen Teslimat

- Çalıştırılabilir prototip, kaynak kodu ve kurulum açıklaması.
- Veri modeli, API açıklaması, örnek kayıtlar ve temel otomatik testler.
- Üç görevin uçtan uca demosu; özellikle sorunlu ET-2026-001 lotundan etkilenen marketlerin
  bulunması.
- KI yöntemlerinin açıklaması, örnek tahminler ve doğruluk değerlendirmesi.

**Değerlendirme odağı:** Doğru maliyet hesabı, eksiksiz lot ilişkileri, güvenilir stok hareketleri,
kullanılabilir Kotlin uygulaması ve ölçülebilir KI katkısı.
