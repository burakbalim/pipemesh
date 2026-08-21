# Distributed Workers

**Status:** Tamam (2026-08-21)
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §28 (kuyruk + worker dağıtımı), §38 (güvenilirlik), §15 (kurtarma)

## Goal

Aynı veritabanına bakan birden çok runtime örneği işi **paylaşsın** — aynı execution'ı iki
kere sürmeden, merkezî bir dağıtıcı olmadan.

## Bugünkü durum

`start()` execution'ı çağıranın thread'inde durana kadar sürüyor. On adımlık bir workflow, on
model çağrısı boyunca açık kalan bir HTTP isteği demek. İkinci bir pod açmak yükü paylaşmıyor,
yalnızca hangi pod'a istek düştüyse orada koşuyor.

Kod bunu zaten iki yerde itiraf ediyor:

```java
// RecoverySweeper
// "Nothing here decides when to sweep. That belongs to whoever runs the runtime,
//  until distributed execution takes it over."

// RecoveryScheduler
// "Two runtimes sweeping at once is safe — the store's version check settles it —
//  but wasteful, and dividing the work between them is what distributed execution is for."
```

## Karar: iş atanmıyor, **kapılıyor**

Merkezî bir dağıtıcı yok. Her runtime örneği sahipsiz execution'ları soruyor ve birini
kiralıyor:

```text
claim(owner, süre, adet)  →  sahipsiz olanlardan en fazla `adet` tanesi, artık benim
renew(owner, süre)        →  hâlâ buradayım
release(owner)            →  bitti ya da bıraktım
```

Merkezî dağıtıcının kendisi bir tekil arıza noktası ve ikinci bir doğruluk kaynağı olurdu.
Kapma modelinde otorite tek yerde: satırın kendisi.

### Kuyruk veritabanı, broker değil

RabbitMQ/Kafka `core/`'a girmiyor — hem framework-free kuralını hem de "neyin koşulabilir
olduğu" sorusunun tek cevabı olmasını bozardı. `workflow_execution` tablosu bunu zaten biliyor;
kapma `FOR UPDATE SKIP LOCKED` ile tek bir `UPDATE`. Bu, gerçek ama sınırlı bir ölçeğe kadar
gider; broker sonraki bir karar ve takılacağı yer bu SPI.

### Kira execution durumu değil

`ExecutionRecord`'a sahip/kira alanı eklenmiyor. Kim sürüyor sorusu **operasyonel** bir olgu;
execution'ın kendi durumu değil ve snapshot'ta, telemetride, SDK'da görünmesi için bir sebep
yok. Kira ayrı bir satırda duruyor, kapma ile durum değişimi tek transaction'da.

### Kira, "canlı mı" sorusunun cevabı değil

Süresi dolmuş kira, sahibinin öldüğünü **kanıtlamıyor** — yalnızca yenilemeyi bıraktığını
söylüyor. Uzun süren bir adım da tam böyle görünür. Bu yüzden bugünkü iki emniyet olduğu gibi
kalıyor: sürüm kontrolü (iki yazardan yalnızca biri ilerletebilir) ve `repeatable` (etkisi
olmuş olabilecek adım ikinci kez koşmaz). Kira tahmini **iyileştiriyor**, ortadan kaldırmıyor —
`RecoverySweeper`'ın "the answer is a guess" cümlesi hâlâ doğru.

### Bugünkü davranış varsayılan kalıyor

`start()` gömülü modda hâlâ satır içi sürüyor. Dağıtık mod **açıkça seçiliyor**: execution
`CREATED` olarak yazılıp dönülüyor, onu bir dispatcher kapıyor. İki sebep: mevcut 322 test ve
SDK'nın "durana kadar bekler" sözü sessizce değişmemeli; ve tek pod çalıştıran birinin
dispatcher kurmak zorunda kalmaması gerekiyor.

## Acceptance Criteria

- [x] İki dispatcher aynı anda bakıyor; bir execution yalnızca birinde koşuyor
- [x] Kapılan execution ikinci bir `claim` çağrısına görünmüyor
- [x] Kirası dolan execution yeniden kapılabiliyor
- [x] Yenilenen kira dolmuş sayılmıyor; süren adım çalınmıyor
- [x] `release` sonrası execution bir daha kapılmıyor (bitmişse)
- [x] `claim` en fazla istenen adedi döndürüyor — bir sürü tüm kuyruğu çekmiyor
- [x] Dağıtık modda `start()` beklemeden dönüyor; execution'ı dispatcher bitiriyor
- [x] Gömülü mod (varsayılan) satır içi sürmeye devam ediyor
- [x] Kapan process ölürse execution başka bir dispatcher tarafından bitiriliyor (Postgres)
- [x] `repeatable` olmayan adımda duran execution, kira devrinde de tekrar koşmuyor
- [x] Kira `ExecutionSnapshot`'ta ve proto'da görünmüyor
- [~] `WorkflowExecutor` değişmiyor — **kriter düzeltildi.** `start`, `create` + `drive`
  olarak ikiye ayrıldı ve ilk durum `RUNNING` yerine `CREATED` oldu (19 satır). Gerekçe
  Implementation Notes'ta; adım yürütme mantığı değişmedi.
- [x] Mevcut 322 test değişmeden geçiyor (toplam 343)

## Split Decision

**Decision:** single-prompt, dört aşama
**Tarih:** 2026-08-21

Contract net: kapma modeli, kiranın nerede durduğu ve varsayılanın değişmemesi kararları
verilmiş. Katmanlar (core SPI, in-memory, Postgres, dispatcher) **birbirinin üstüne biniyor**,
yan yana durmuyor — Postgres implementasyonu SPI'sız, dispatcher kapmasız yazılamaz. Paralel
ajan bölmesi burada sıra bekleyen ajanlar demek olurdu.

1. **Kira SPI'si + in-memory** — `ExecutionLeases` arayüzü (`claim`/`renew`/`release`),
   in-memory implementasyon, tek başına test edilebilir.
2. **Dispatcher** — kapıyor, sürüyor, adım aralarında yeniliyor, bırakıyor. Gömülü mod
   varsayılan kalıyor; dağıtık mod `start()`'ı beklemeden döndürüyor.
3. **Postgres** — `FOR UPDATE SKIP LOCKED` ile kapma, V002 migration, iki process testi.
4. **Zamanlayıcı devri** — `RecoveryScheduler`'ın "dividing the work between them is what
   distributed execution is for" yorumunun kapatılması: dispatcher açıkken süpürme de
   kapılan işin parçası.

### Yenilemenin nerede olduğu — aşama 2'nin asıl sorusu

Kira adım *aralarında* yenileniyor, adımın *içinde* değil. Adımın içinden yenilemek, sürücü
thread'i bloke bir model çağrısındayken çalışacak ikinci bir thread demek; o thread execution
kaydına dokunursa tek-yazar kuralı bozulur. Adım arası yenileme, kiranın en uzun adım
timeout'undan uzun olmasını gerektiriyor — `RecoverySweeper.DEFAULT_THRESHOLD`'un bugün taşıdığı
kısıtın aynısı, aynı gerekçeyle.

### Kapsam dışı

- **Broker destekli kuyruk.** SPI takılacak yeri veriyor; RabbitMQ/Kafka ayrı bir karar ve
  `core/` bağımlılığı olmadan, ayrı modülde.
- **Adalet ve öncelik.** Bir organizasyonun kuyruğu diğerlerini aç bırakabilir. Bunu çözmek
  sıralama politikası demek ve tek başına bir dilim; bugün kapma sırası "en eski önce".
- **Otomatik ölçekleme sinyalleri.** Kuyruk derinliği metriği gözlenebilirlik işi (§22.1).
- **Adım seviyesinde dağıtım.** Dağıtılan birim execution; tek bir execution'ın adımlarını
  farklı pod'lara bölmek §29'un tek-yazar kararıyla çelişir.

### Risk points

- **Kira süresi ile adım süresi.** Kira en uzun adımdan kısaysa, koşan iş çalınır. İki sahip
  aynı anda ilerletemez (sürüm kontrolü) ama iş boşa gider ve `repeatable` olmayan adım
  execution'ı durdurur. Varsayılan kira `DEFAULT_THRESHOLD`'la aynı büyüklükte olmalı.
- **Sessizce mod değiştirmek.** Dağıtık modda `start()`'ın erken dönmesi, çağıran için
  davranış değişimi. Varsayılan gömülü kalmalı ve seçim tek yerde okunabilmeli.
- **Dispatcher'ın kapıp ölmesi.** Kapma ile sürme arasındaki pencerede ölen bir process,
  execution'ı kirası dolana kadar tutar. Kabul edilebilir gecikme, ama kira süresinin ne
  anlama geldiğini belirleyen şey bu — belgelenmeli.
- **`claim` ile `advance` yarışı.** Kapılan execution'ı aynı anda bir resume ilerletebilir.
  Kira sürmeyi düzenliyor, ilerletmeyi değil; tek-yazar garantisi hâlâ sürüm kontrolünde.

## Implementation Notes

**Tamamlandı:** 2026-08-21 — 21 yeni test (13 `ExecutionDispatchTest`, 8 `DistributedDispatchTest`);
toplam 343 Java + 22 Python + 22 TypeScript.

### Üç sapma

**1. `WorkflowExecutor` değişti (19 satır).** Kriteri yazarken #16/#6/#7/#9'dan kopyalamıştım;
orada doğruydu çünkü hepsi yeni adım tipiydi. Dağıtım adım tipi değil. Dağıtık `start`, kaydı
yazıp sürmeden dönebilmeyi gerektiriyor ve bunun için `start` ikiye ayrıldı:

```java
start(graph, id, request)  =  drive(graph, create(graph, id, request));
```

Adım yürütme mantığına dokunulmadı. Kriteri sessizce tikleyip geçmek yerine düzeltmek doğrusu.

**2. İlk durum `RUNNING` değil `CREATED`.** Bu kriter listesinde hiç yoktu; ilk dağıtım testi
buldu. Kayıt `RUNNING` doğuyordu çünkü satır içi başlatma hemen sürüyordu. Dağıtık modda bu bir
yalan: hiçbir şey koşmuyorken durum "koşuyor" diyor — hem çağırana hem `RecoverySweeper`'a.
Değişiklik tüm çekirdek takımını kırmadan geçti; kimse doğuştan `RUNNING` olmasına bel
bağlamamış.

**3. Kira yenileme adım aralarında değil, heartbeat ile.** Split Decision "adım aralarında"
diyordu. Uygularken iki şey görüldü: `drive()` zaten içeride döngü kuruyor, yani "adım arası"
diye bir yer yok; ve contract'ın kendi kararı bunu gereksiz kılıyor — **kira ayrı bir satır**,
dolayısıyla onu yenileyen ikinci bir thread execution kaydına dokunmuyor ve tek-yazar kuralı
bozulmuyor. Dispatcher kirayı `lease/3` aralıklı kendi zamanlayıcısıyla yeniliyor.

Bedeli dürüstçe yazıldı: **heartbeat process'in canlı olduğunu söyler, işin ilerlediğini
değil.** Tek adımda takılmış bir sürücü mutlu mesut yenilemeye devam eder. `RecoverySweeper`
tam bu yüzden duruyor — o, execution satırının en son ne zaman *yazıldığına* bakıyor. İkisi
farklı sorulara cevap veriyor ve ikisi de gerekli.

### Kapma modeli

`ExecutionLeases` üç metot: `claim` / `renew` / `release`. Postgres tarafında kapma tek bir
ifade — CTE ile `FOR UPDATE OF e SKIP LOCKED`, ardından `ON CONFLICT DO UPDATE`. `SKIP LOCKED`
işin özü: iki örnek aynı anda sorduğunda birbirinin kilitli satırının üstünden atlıyor, arkasına
dizilmiyor. İkinci pod gecikme değil kapasite ekliyor.

Yalnızca `workflow_execution` kilitleniyor; kira satırı henüz olmayabilir ve outer join'in
nullable tarafı zaten kilitlenemez.

### `token` neden var

Kira `(execution_id, owner, token)` ile doğrulanıyor. Aynı isimle geri gelen bir process —
Kubernetes'te olağan — önceki hayatının kirasını yenileyememeli. `release` de aynı üçlüyle
sınırlı; yoksa devredilmiş bir kirayı eski sahibi çıkarken silerdi.

### `isDrivable`

`WAITING` kapılabilir değil. Bekleyen execution takılmış değil, bekliyor; onu kapmak bir
sürücüyü ilerleyemeyecek işle meşgul etmek olurdu. `CREATED` ve `RUNNING` kapılabilir.

### Zamanlayıcı devri

`dispatchOnce()` imzası `RecoveryScheduler.RecoveryPass`'e uyuyor (#7'deki `ExpiredWaitSweeper`
ile aynı numara), dolayısıyla ikinci bir zamanlayıcı yok. `RecoveryScheduler`'daki "dividing the
work between them is what distributed execution is for" ve `RecoverySweeper`'daki "until
distributed execution takes it over" yorumları kapatıldı — ikisi de artık ne olduğunu söylüyor.

### Varsayılan korundu

`StartMode.INLINE` varsayılan. Dağıtık mod açıkça seçiliyor ve dokümante edilen bir tuzağı var:
dispatcher koşmayan bir `DISPATCHED` runtime işi kabul edip hiç çalıştırmaz.

### Yolda düzeltilen

`workflow_lease`'in foreign key'i yine `TRUNCATE`'i kırdı — ama bu kez tek bir yerde,
`TestTables`'ta. #7'de "bir sonraki tablo eklendiğinde kırılacak tek bir yer kalsın" diye
yazılmıştı; öyle oldu.

### Devralınacak

- **Broker destekli kuyruk.** Bugünkü model yoklama; tavanı gerçek ama sınırlı.
  `ExecutionLeases` takılacak yer.
- **Adalet ve öncelik.** Kapma sırası "en eski önce"; bir organizasyonun kuyruğu diğerlerini aç
  bırakabilir. Sıralama politikası ayrı bir dilim.
- **Kuyruk derinliği metriği.** Ölçekleme kararı için gerekli; gözlenebilirlik işi (§22.1).
