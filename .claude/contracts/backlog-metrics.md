# Backlog Metrics

**Status:** Draft
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

- [ ] Boş kuyrukta yaş sıfır, boyut sıfır
- [ ] Kapılmamış bir execution varken yaş onun bekleme süresi kadar
- [ ] Kapılmış (kiralı) execution yaşa dahil değil — o bekleyen iş değil, koşan iş
- [ ] `WAITING` execution'lar dahil değil; onlar bekleyen iş değil, bekleyen insan
- [ ] Metrik `instance` etiketi taşımıyor
- [ ] İki dispatcher aynı anda ölçtüğünde aynı değeri bildiriyor
- [ ] Ölçüm dispatcher'ın mevcut zamanlayıcısında koşuyor
- [ ] Ölçüm hatası dispatch'i düşürmüyor (§22.1)
- [ ] `deploy/cloud` HPA örneği yaşa göre ölçekliyor, derinliğe göre değil

## Kapsam dışı

- **HPA hedef değerleri.** "Kaç saniye kabul edilebilir" bir işletme kararı; contract mekanizmayı
  veriyor, sayıyı değil.
- **Organizasyon başına kuyruk.** Adalet (#10'da kapsam dışı bırakıldı) çözülene kadar
  organizasyon kırılımı yanıltıcı olur.
- **Tahmine dayalı ölçekleme.** Geçmişten gelecek yükü kestirmek ayrı bir iş.

## Split Decision

_To be filled by Agent 0_

## Implementation Notes

_To be filled as work progresses_
