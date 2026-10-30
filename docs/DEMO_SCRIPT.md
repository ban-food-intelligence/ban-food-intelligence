# Demo script (max. 15 minutes)

Shot list for the screen recording. Narration is in Turkish (as spoken in the video); stage
directions are in English. Times are cumulative targets.

## Before recording

```bash
docker compose up -d --build
./scripts/demo-reset.sh            # clean seed; prints the demo path
adb reverse tcp:8080 tcp:8080      # USB phone (or emulator)
```

- Dashboard open at http://localhost:5173, language **TR**, light mode, browser zoom 100%.
- Phone: app installed, Settings → Server → *Test connection* shows "connected", Sync tab shows
  0 waiting. Do Not Disturb on, brightness up, screen recording via `scrcpy` or the phone's recorder.
- Have `curl` in a terminal for the API shots (optional).

## 1. Introduction (0:00 - 1:00)

**Shot:** README on GitHub, architecture diagram.

> "Merhaba, biz BAN ekibiyiz. Bu prototip bir gıda işletmesinin hammaddeden son satışa kadar
> maliyetini, stokunu ve lot izlenebilirliğini takip ediyor. Merkezde MySQL veritabanı, üzerinde Go
> ile yazılmış bir REST API, ofis için React paneli ve mağaza için çevrimdışı çalışan bir Android
> kasa uygulaması var. İş kuralları veritabanında: negatif stok, geri çağrılmış lotun satışı ve
> hareket defterinin değiştirilmesi tetikleyicilerle engelleniyor."

## 2. Task 1: external production and EKP (1:00 - 3:00)

**Shot:** Dashboard → *Purchases* (ET-2026-001, 100 kg × 9.70 €), then *Production (EKP)*,
order URT-2026-045 expanded.

> "Görev 1: fason üretim. 100 kilo dana eti, baharat ve bağırsak fasoncuya gönderildi. Malzeme
> maliyeti 1.076 euro, hizmet bedeli paket başına 5,50 euro, toplam 1.543,50 euro. Gerçekleşen
> miktar 85 paket olduğu için gerçek birim maliyet, yani EKP, 18,16 euro. Satış fiyatı 24,90 euro;
> paket başı brüt kâr 6,74 euro, marj yüzde 27,07. Planlanan ve gerçekleşen miktar farkı da burada
> görünüyor."

**Shot:** expand the cost breakdown (lot by lot), then *Lot movements* filtered to ET-2026-001.

> "Her maliyet kalemi hangi lottan geldiğiyle birlikte görünüyor. Lot hareketleri sekmesinde her
> giriş ve çıkış, bakiyesiyle birlikte listeleniyor. Bu defter sadece ekleme kabul ediyor;
> geçmiş bir hareket değiştirilemez."

**Shot (optional, 1 min):** *Production* → *New production order*: URT-2026-047, the meat, spice and
casing lots, PIN → the AI forecast appears (packages and EKP with the 80 % interval and the
baseline). Then *Receive finished goods* with the actual packs: the EKP is computed and the
deviation from the forecast is shown. The seed has no raw material left for a new order, so record
the three receipts under *Purchases* before recording (or do it before the video).

> "Yeni bir fason emri açtığımda hammadde fasona sevk ediliyor ve yapay zekâ aynı anda beklenen
> paket sayısını ve EKP'yi tahmin ediyor. Mamulü teslim aldığımda gerçek EKP hesaplanıyor ve
> tahminle karşılaştırılıyor."

## 3. Task 2: butchery (3:00 - 6:00)

**Shot:** *Butchery*, the rejected record PRC-2026-000 at the top.

> "Görev 2: bütün kuzu parçalama. Görevdeki tabloda iki tutarsızlık var ve sistem ikisini de
> buluyor. Birincisi maliyet: 25 kilo çarpı 11,50 euro 287,50 euro eder, ama tabloda 351 euro
> yazıyor. Aradaki 63,50 euro için belge olmadığından kullanılmıyor. İkincisi ağırlık: parçaların
> toplamı 22 kilo, giriş 25 kilo; 3 kilo kayıtsız. Bu kayıt bu yüzden reddedildi."

**Shot:** scroll to PRC-2026-001 (posted): weight balance green, allocation table.

> "Doğru kayıtta 3 kilo kemik ve fire olarak kaydedildi, denge sağlandı. 287,50 euro parçalara
> dağıtıldı; toplam tam olarak maliyete eşit."

**Shot:** switch the allocation key between *Quantity* and *Value*.

> "Maliyeti ağırlığa göre ya da satış değerine göre dağıtabiliyoruz. Yuvarlamada en büyük kalan
> yöntemi kullanıyoruz, bu yüzden kuruşlar her zaman toplama eşit. Fire maliyet almıyor."

**Shot:** phone, *Sell* tab: pick KZB-2026-001 (Kuzu but), enter 0,5 kg, confirm. Then *Sync* tab
until "0 waiting". Then dashboard *Sales* (refresh) shows the new line with cost and gross profit.

> "Parça lotları kasada satılıyor. Kuzu buttan yarım kilo satıyorum. Satış önce telefondaki SQLite
> veritabanına yazılıyor, imzalanıyor ve arka planda sunucuya gönderiliyor. Panelde satış, lot
> maliyeti ve brüt kârla birlikte görünüyor."

**Shot (30 s):** still on *Sell*: Kuzu kıyma (KZY-2026-001) is listed under *SKT geçti, satış
engellendi*; tap it. (The seed calendar is relative to the day of `demo-reset.sh`: the 2-day minced
lamb always expired yesterday.)

> "Kıymanın son tüketim tarihi dün doldu. Kasa bu lotu satmıyor, sunucu da son tüketim tarihi
> geçmiş bir lotun satışını ve sevkini reddediyor. Lot raftan kaldırılıp fire olarak kaydedilir."

## 4. Task 3: trace and recall (6:00 - 11:00)

**Shot:** *Trace & recall*, lot ET-2026-001, *Forward trace*.

> "Görev 3: izlenebilirlik. ET-2026-001 numaralı dana eti lotundan ileri izleme yapıyorum. Bu lot
> URT-2026-045 üretim emriyle SUC-2026-045 sucuk lotuna dönüştü. Sucuk Market A'ya 30, Market B'ye
> 25, Market C'ye 30 paket gönderildi. Market C'nin bir lokantaya 10 paket sattığı da kayıtlı olduğu
> için ikinci seviye olarak görünüyor. Bu sonuçların hepsi veritabanındaki lot ilişkilerinden
> geliyor, yapay zekâ kullanılmıyor."

**Shot:** switch to *Backward trace* with SUC-2026-045.

> "Geriye doğru izlemede sucuk lotunun hangi et, baharat ve bağırsak lotlarından, hangi
> tedarikçiden geldiği görünüyor. Bir lot birden çok lota, birden çok lot da bir lota bağlanabiliyor."

**Shot:** forward trace ET-2026-001 again → *Start recall* → enter the supervisor PIN `246813`
→ confirm (only a supervisor can start a recall; it is recorded under that name). Show the recall report:
affected lots RECALLED, customer list with quantities, shipment documents, contacts; download the
CSV; open print view.

> "Şimdi geri çağırma başlatıyorum. Kök lot ve ondan üretilen tüm lotlar tek işlemde bloke
> ediliyor. Her müşteri için gönderilen miktar, sevk belgesi ve iletişim bilgisi listeleniyor.
> İkinci seviye müşteri sadece kayıtlıysa gösteriliyor. Bu listeyi CSV olarak indirip arama
> listesi olarak kullanabiliyoruz."

**Shot:** phone, within 15 s: *Sell* shows "1 aktif geri çağırma". Type `SUC-2026-045` in the
search: the lot is listed as recalled, sale blocked. *Recall* tab lists ET-2026-001 and
SUC-2026-045 as blocked. (All 85 packs of SUC-2026-045 went to the markets, so no lot in this
shop is affected and sales continue.)

> "Kasada geri çağırma hemen görünüyor. Elimdeki bir paketi aratırsam satışın engellendiğini
> görüyorum. Bu mağazada etkilenen lot yok, bu yüzden diğer satışlar devam ediyor."

**Shot:** dashboard, start a second recall on **ET-2026-002** (its sausage lot SUC-2026-046 has
3 packs in this shop). Within 15 s the phone shows the *Sales paused* stop screen.

> "Bu sefer geri çağırma mağazadaki bir lotu etkiliyor: satış duruyor ve ürünler raftan
> kaldırılana kadar bekliyor."

**Shot:** enter a wrong PIN once ("4 attempts left"), then the demo PIN → unlocked. *Sell* tab:
SUC-2026-046 is shown as recalled and opening it shows "sale blocked".

> "Yanlış PIN girersem kalan deneme sayısı gösteriliyor; beş yanlış denemede cihaz beş dakika
> kilitleniyor. Doğru PIN ile sorumlu kişi rafın boşaltıldığını onaylıyor ve bu bir denetim
> kaydına yazılıyor. Geri çağrılmış lot satış ekranında kilitli; internet olmasa bile telefondaki
> önbellekten engelleniyor."

**Shot (optional, 30 s):** cut the connection (`adb reverse --remove tcp:8080`, or stop Wi-Fi when
using a LAN address): the pill turns *Çevrimdışı* within about 15 s. The recalled lot stays
blocked; a normal sale shows "Gönderilmeyi bekliyor" on *Sync*. Restore the connection
(`adb reverse tcp:8080 tcp:8080`): within a few seconds the sale is sent and the pill turns
*Gönderildi*, without restarting the app. (Airplane mode alone does not cut a USB `adb reverse`
connection.)

## 5. AI (11:00 - 13:30)

**Shot:** dashboard *AI*: MAPE chart and metric table.

> "Yapay zekâ bölümünde iki görev var. A: fason üretimde verim ve EKP tahmini. B: parçalamada tür
> bazında parça ağırlıkları. Modeller sentetik geçmiş veriyle eğitildi ve zaman bölmeli test
> kümesinde basit bir ortalama tahminiyle karşılaştırıldı. Örneğin paket sayısında model yüzde
> 1,18 hata yapıyor, temel yöntem yüzde 2,24. Parça ağırlıklarında model yüzde 3,27, temel yöntem
> yüzde 4,79."

**Shot:** comparison table rows for URT-2026-045 and PRC-2026-001.

> "Her demo kaydı için gerçek değer, model tahmini ve temel tahmin yan yana. Dürüst olmak
> gerekirse PRC-2026-001 tek bir kayıt ve orada temel yöntem daha yakın; değerlendirmeyi tek
> kayda değil test kümesinin tamamına dayandırıyoruz. Yapay zekâ sadece tahmin ve öneri veriyor;
> izlenebilirlik ve geri çağırma kararları her zaman kayıtlı verilerden geliyor."

**Shot (optional):** phone *Lots → Receive goods from an invoice*: photo of a sample invoice →
read fields → user checks and approves.

> "İsteğe bağlı olarak fatura okuma da var: NVIDIA'nın görüntü modeli alanları okuyor, kullanıcı
> kontrol edip onaylamadan hiçbir şey kaydedilmiyor."

## 6. Close (13:30 - 14:30)

**Shot:** README *Tests* section, terminal running `./scripts/check-all.sh` (sped up), ending with
"clean seed restored".

> "Tüm iş kuralları otomatik testlerle doğrulanıyor: Go birim ve entegrasyon testleri, veritabanı
> testleri, Android birim testleri ve panel derlemesi. Demo verisi tek komutla temiz hale geliyor.
> Teşekkürler."

## Troubleshooting

| Symptom | Fix |
|---|---|
| Phone shows "offline" / *Test connection* fails | Is the API up (`curl localhost:8080/health`, Docker Desktop running)? USB: `adb reverse tcp:8080 tcp:8080` again (needed after every reconnect); Settings → Server should be *USB cable*. Wi-Fi: same network, correct IP, Windows firewall allows port 8080. |
| Dashboard shows "Server not reachable" | `docker compose ps`; `docker compose up -d`; check http://localhost:8080/health. |
| Recall stop does not appear on the phone | Wait up to 15 s. The stop screen only appears when the recall reaches a lot stocked in this shop: ET-2026-002 does (SUC-2026-046), ET-2026-001 does not (its packs all went to markets A/B/C). |
| PIN says "locked" | 5 wrong attempts → wait 5 minutes. `./scripts/demo-reset.sh` restarts the API, which clears the server-side counter; the phone's own counter clears with its app data (`adb shell pm clear com.ban.foodintelligence.pos` or Android Settings → Apps → Storage → Clear). |
| Sale stays "waiting" on *Sync* | Network required; tap *Send now*. A sale rejected by the server (e.g. not enough stock) is shown with its reason and not retried. |
| Data from a previous run is visible (extra sales, an active recall) | `./scripts/demo-reset.sh`, then clear the app's data on the phone (see above) or reinstall the APK. |
| Docker not running | Start Docker Desktop, wait for "Engine running", then `docker compose up -d`. |
