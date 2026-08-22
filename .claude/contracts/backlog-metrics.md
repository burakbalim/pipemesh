# Backlog Metrics

**Status:** Tamam (2026-08-22)
**Created:** 2026-08-22
**DESIGN.md kapsamı:** §22.1 (gözlenebilirlik), §28.1 (kapma), #22 (ayrı deployment'lar)

## Goal

Dispatcher'ları neye göre ölçekleyeceğimizi ölçmek: bekleyen iş.

## Yanlış metrik: kuyruk derinliği

İlk akla gelen "kaç execution bekliyor". Sorun, ölçeklemenin kendi geri beslemesi:

```text
derinlik artar → dispatcher eklenir → hızlı boşalır → derinlik düşer
              → dispatcher azalır → derinlik artar → ...
```

Ve daha kötüsü: derinlik **hedefin ne olduğunu söylemiyor**. 50 bekleyen execution, hepsi 200 ms
sürüyorsa sorun değil; hepsi 40 saniye sürüyorsa ciddi.

## Doğru metrik: bekleyen işin yaşı

**En eski kapılmamış execution ne kadar süredir bekliyor.** Bu doğrudan hissedilen gecikme, ve
hedefi bir iş kararı olarak ifade edilebiliyor: "hiçbir iş 10 saniyeden fazla beklemesin."

Geri besleme de sönümlü: dispatcher eklendiğinde yaş düşer, ama kapasite yettiği sürece
yaklaşık sabit kalır — derinlik gibi sıfıra çakılıp salınmaz.

Derinlik yine de yayınlanıyor, ama **panoya**, HPA'ya değil: "kaç bekliyor" bir insanın sorusu,
"ne kadar bekliyor" bir ölçekleyicinin sorusu.

## Ölçen kim — ve tuzağı

Metrik veritabanının bir özelliği, sürecin değil. N dispatcher aynı sayıyı bildirirse:

- **gauge olarak** doğru (hepsi aynı gerçeği söylüyor),
- **toplanırsa** N katına çıkar ve tamamen yanlış olur.

Bu yüzden metrik `instance` etiketi taşımıyor ve dokümantasyonu toplanmaması gerektiğini
söylüyor. Etiketli bir gauge'ın sessizce toplanması, panonun yalan söylemesinin en yaygın yolu.

## Nerede yaşıyor

`pipemesh-opentelemetry` zaten OTLP'ye yazıyor (§22.1). Yeni bir ölçüm yolu değil, aynı yola
iki gözlem ekleniyor:

```text
pipemesh.backlog.age_seconds     en eski kapılmamış execution'ın yaşı
pipemesh.backlog.size            kapılmamış execution sayısı
```

Ölçüm bir sorgu; onu koşturan şey dispatcher'ın zaten koşan zamanlayıcısı — ikinci bir
zamanlayıcı yok (#7 ve #10'daki aynı karar).

## Acceptance Criteria

- [x] Boş kuyrukta yaş sıfır, boyut sıfır
- [x] Kapılmamış bir execution varken yaş onun bekleme süresi kadar
- [x] Kapılmış (kiralı) execution yaşa dahil değil — o bekleyen iş değil, koşan iş
- [x] `WAITING` execution'lar dahil değil; onlar bekleyen iş değil, bekleyen insan
- [x] Metrik `instance` etiketi taşımıyor
- [x] İki dispatcher aynı anda ölçtüğünde aynı değeri bildiriyor
- [x] Ölçüm bizim hiçbir zamanlayıcımızda koşmuyor — async gauge, toplama anında
- [x] Ölçüm hatası dispatch'i düşürmüyor (§22.1)
- [x] `deploy/cloud` HPA örneği yaşa göre ölçekliyor, derinliğe göre değil

## Kapsam dışı

- **HPA hedef değerleri.** "Kaç saniye kabul edilebilir" bir işletme kararı; contract mekanizmayı
  veriyor, sayıyı değil.
- **Organizasyon başına kuyruk.** Adalet (#10'da kapsam dışı bırakıldı) çözülene kadar
  organizasyon kırılımı yanıltıcı olur.
- **Tahmine dayalı ölçekleme.** Geçmişten gelecek yükü kestirmek ayrı bir iş.

## Split Decision

**Decision:** single-prompt, üç aşama
**Tarih:** 2026-08-22

1. **Ölçüm** — `ExecutionLeases.backlog()`: kapılmamış işin sayısı ve en eskisinin yaşı. Kapma
   sorgusunun yanında, çünkü "kapılmamış" tanımı zaten orada.
2. **Yayın** — `pipemesh-opentelemetry`'de iki async gauge.
3. **Kullanım** — `deploy/cloud`'a yaşa göre ölçekleyen bir HPA örneği.

### Kabul kriterinde bir düzeltme

Contract "ölçüm dispatcher'ın mevcut zamanlayıcısında koşuyor" diyordu. Uygularken daha iyisi
görüldü: **async gauge**. OTel toplarken callback'i çağırıyor, yani bizim hiçbir zamanlayıcımız
yok — "ikinci bir zamanlayıcı olmasın" ilkesinin daha katı hâli. Kriter buna göre düzeltiliyor.

Yan faydası: fırlatan bir callback yalnızca o toplamayı etkiliyor, dispatch'e hiç dokunmuyor
(§22.1'in "gözlemci execution'ı düşüremez" kuralı burada bedava geliyor).

### Ölçümün nerede olduğu bir tasarım kararı

`backlog()` `ExecutionLeases`'e giriyor, `StateStore`'a değil. "Kapılmamış" tanımı kiranın
tanımı: kirası olmayan ya da kirası dolmuş, sürülebilir durumdaki execution. `StateStore` kirayı
bilmiyor ve bilmemeli (#10'da kira execution durumu değil diye karar verilmişti).

### Kapsam dışı (ek)

- **Kuyruk yaşının organizasyon kırılımı.** Adalet çözülmeden (bkz. #10) kırılım yanıltıcı olur
  ve etiket kardinalitesi de bir maliyet.

### Risk points

- **Gauge'ın toplanması.** N dispatcher aynı sayıyı bildiriyor. `instance` etiketi olmamalı ve
  belgede toplanmaması yazmalı — etiketli bir gauge'ın sessizce toplanması, panonun yalan
  söylemesinin en yaygın yolu.
- **`WAITING` execution'ların yaşa karışması.** Onlar bekleyen iş değil, bekleyen insan; üç gün
  onay bekleyen bir execution kuyruk yaşını üç güne çıkarır ve HPA sonsuza kadar ölçeklenir.
  Bu, metriği doğrudan zararlı yapar.
- **Kirası olan işin yaşa karışması.** Kapılmış iş bekleyen iş değil, koşan iş. Karıştırmak
  dispatcher eklendikçe düşmeyen bir sayı üretir — yani ölçeklemenin geri beslemesi kopar.
- **Boş kuyrukta ne raporlanacağı.** Sıfır doğru cevap; "veri yok" değil. Eksik bir gauge, HPA
  tarafında son bilinen değeri sonsuza kadar korumak gibi davranabilir.

## Implementation Notes

**Tamamlandı:** 2026-08-22 — 8 yeni test (6 in-memory, 2 Postgres); toplam 461 Java.

### Ölçüm kirada, durumda değil

`backlog()` `ExecutionLeases`'e girdi. "Bekleyen" tanımı kiranın tanımı: sürülebilir durumda ve
ya hiç kapılmamış ya da kirası dolmuş. `StateStore` kirayı bilmiyor ve #10'da bilmemesi
kararlaştırılmıştı.

Postgres sorgusu `CLAIM`'in yüklemini **birebir** paylaşıyor — eylem yerine soru. İkisi
ayrışırsa metrik dispatcher'ın gördüğünden başka bir şey ölçer.

### Kabul kriteri düzeltildi: async gauge

Contract "dispatcher'ın zamanlayıcısında koşsun" diyordu. Async gauge daha iyisi çıktı: OTel
toplarken çağırıyor, bizim hiçbir zamanlayıcımız yok. "İkinci zamanlayıcı olmasın" ilkesinin
daha katı hâli, ve fırlatan bir callback yalnızca o toplamayı etkiliyor — §22.1'in kuralı
bedava geliyor.

### Metriği zararlı yapabilecek iki şey, ikisi de testli

- **`WAITING` execution kuyruğa sayılırsa:** üç gün onay bekleyen bir execution yaşı üç güne
  çıkarır ve HPA sonsuza kadar sürücü ekler — hiçbiri yardım edemez.
  `anExecutionWaitingForAPersonIsNotABacklog` bunu tutuyor.
- **Kapılmış iş sayılırsa:** dispatcher eklendikçe düşmeyen bir sayı olur, yani ölçeklemenin
  geri beslemesi kopar. `claimedWorkIsNotWaitingWork`.

### Toplanmama uyarısı koda ve manifest'e yazıldı

Her replika aynı gerçeği bildiriyor: **gauge olarak doğru, toplanırsa replika sayısıyla
çarpılıyor.** Bu yüzden `instance` özniteliği yok — etiketli bir gauge, bir gün toplanacak bir
gauge'dır. HPA örneği `AverageValue` kullanıyor ve nedenini yorumda söylüyor.

### Boş kuyruk sıfır bildiriyor

"Veri yok" değil. Bildirilmeyi bırakan bir gauge, HPA tarafında son bilinen değer gibi
davranabilir — kuyruk boşaldığında sonsuza kadar ölçekli kalmak demek.

### Devralınacak

- **HPA hedef değeri.** On saniye bir örnek, tavsiye değil; kabul edilebilir gecikme bir işletme
  kararı.
- **Metrik adaptörü.** HPA'nın OTLP'den okuyabilmesi için bir adapter (Prometheus adapter, KEDA)
  gerekiyor; hangisi bir küme kararı.
- **Organizasyon kırılımı.** Adalet (#10) çözülmeden yanıltıcı, ve etiket kardinalitesi bir
  maliyet.
