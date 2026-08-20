# Application Capabilities

**Status:** Draft
**Created:** 2026-08-20
**DESIGN.md kapsamı:** §9.8 (capability, task değil), §10 (capability mimarisi), §26.1 (inbound stream)

## Goal

Bir uygulamanın kendi kodunun, kendi dilinde ve kendi process'inde yaşayıp runtime tarafından
capability olarak çağrılabilmesi.

Bugün capability'ler MCP tool'u, REST/gRPC endpoint'i veya in-process Java fonksiyonu olabiliyor.
Eksik olan, tasarımın en çok üzerinde durduğu hâl: **başka bir dildeki uygulama kodu.** İki SDK
yazıldı ama ikisi de runtime'ı yalnızca *çağırabiliyor*; runtime onların içindeki koda ulaşamıyor.
§26.1'in "iki yönlü trafik" tasarımının yarısı boş.

Kanıtlanacak şey şu: **aynı workflow'un bir capability'si MCP tool'u iken diğeri birinin Python
fonksiyonu olabilmeli — ve workflow ikisini ayırt edememeli.**

## Affected Modules

- [ ] `pipemesh-grpc` — `CapabilityWorker.Connect` servisi, worker kayıt defteri
- [ ] `core/capability` — worker'a yönlendiren `CapabilityProvider`
- [ ] `sdk/python`, `sdk/typescript` — worker tarafı: fonksiyon kaydı ve invocation döngüsü
- [ ] `examples/` — bir capability MCP, biri uygulama worker'ı olan workflow

## Contract

Capability kaydı yalnızca taşımayı değiştiriyor; workflow'a dokunulmuyor:

```json
{
  "id": "calculate_discount",
  "kind": "application",
  "owner": "billing-team",
  "execution": { "type": "worker", "capability": "calculate_discount", "idempotent": true }
}
```

Worker tarafı (Python):

```python
worker = PipeMeshWorker("localhost:8080", organization="acme")

@worker.capability("calculate_discount")
def calculate_discount(customer):
    return {"rate": 0.20 if customer["tier"] == "gold" else 0.05}

worker.run()
```

Bağlantıyı **worker açıyor** (§26.1): worker'ın erişilebilir adrese, sertifikaya ya da firewall
istisnasına ihtiyacı olmuyor. Runtime invocation'ları açık stream üzerinden itiyor.

## Tasarımın zor sorusu: worker invocation'ı aldıktan sonra ölürse

Runtime "istek gitti mi, işlendi mi" bilemez. Bu, retry ve kurtarma için yazdığımız ayrımın en sert
sınavı:

```text
worker ölümü
     ↓
capability idempotent mi?
     ├── evet  → başka bir worker'a yönlendir
     └── hayır → execution FAILED (execution.unrecoverable), insan baksın
```

`idempotent: false` bir capability uzak worker'da koşuyorsa, **worker ölümü insan müdahalesi
demektir.** Bunu yumuşatmak (örneğin "muhtemelen ulaşmadı, tekrar dene") tam olarak çift tahsilat
üreten varsayımdır.

İkinci sonuç: worker ölümü **yeni bir öksüz sınıfı** yaratıyor — execution `RUNNING`'de kalıyor.
`RecoveryScheduler` bu contract'tan hemen önce yazıldı; bu iş onu zorunlu kılan sebep.

## Tasarım Soruları (preflight'ta netleşmeli)

1. **Invocation zaman aşımı worker'da mı runtime'da mı?** Step'in kendi `timeout` politikası var;
   worker tarafında ayrı bir sınır gerekir mi, yoksa runtime'ınki yeterli mi?
2. **Aynı capability'yi birden çok worker sunarsa?** Rastgele mi, sıra ile mi, yoksa ilk bağlanan
   mı? Yük dağıtımı bu contract'ın işi mi #10'un mu?
3. **Worker kaydı organizasyona bağlı mı?** `WorkerRegistration.organization_id` proto'da var —
   bir organizasyonun worker'ı başka bir organizasyonun execution'ını görmemeli. Bu #17'nin işi
   ama sınır burada çiziliyor.
4. **Worker hiç yoksa ne olur?** Step hemen mi düşsün, yoksa bir süre worker beklesin mi?

## Acceptance Criteria

- [ ] Worker bağlanıp sunduğu capability'leri bildiriyor
- [ ] Runtime, `worker` tipindeki bir capability'yi bağlı worker'a yönlendiriyor
- [ ] Worker'ın döndürdüğü sonuç adımın çıktısı oluyor, akış devam ediyor
- [ ] Worker hata döndürürse step `Failed` oluyor ve retry politikası uygulanıyor
- [ ] **Worker invocation ortasında ölürse:** idempotent capability yeniden yönlendiriliyor,
      idempotent olmayan `execution.unrecoverable` ile duruyor
- [ ] Bağlı worker yokken çağrı net bir hatayla düşüyor (sonsuza kadar beklemiyor)
- [ ] Worker bağlantısı koptuğunda kayıt defterinden düşüyor; yeniden bağlanabiliyor
- [ ] **Workflow JSON'ında `worker` kelimesi geçmiyor** — capability adı yeterli
- [ ] Aynı workflow'un bir adımı MCP tool'unu, diğeri uygulama worker'ını çağırıyor ve workflow
      ikisini ayırt etmiyor
- [ ] Python ve TypeScript worker'ları aynı sözleşmeyi uyguluyor
- [ ] Worker çağrıları da step telemetrisine ve trace'e giriyor

## Split Decision

**Decision:** single-prompt (aşamalı, tek ajan)
**Tarih:** 2026-08-20

**Reasoning:**

Beş dokunuş noktası var (gRPC servisi, core provider, iki SDK, örnek) ama **paralellik ancak
birinci aşamadan sonra doğuyor**: hiçbir SDK worker'ı, Java tarafındaki kayıt defteri ve
yönlendirme olmadan bağlanamaz. Sonrasında Python ve TypeScript worker'ları birbirinden bağımsız —
ama SDK'ları yazarken öğrendiğimiz şey, aynı sözleşmenin her dilde farklı tuzağı olduğu
(TypeScript'te Struct kodlaması, Python'da tembel generator). Birini bitirip diğerini onun kanıtlanmış
şekline göre yazmak, ikisini paralel yazıp iki ayrı tuzağa aynı anda düşmekten iyi.

### Build order

1. **Worker kayıt defteri ve yönlendirme** — `CapabilityWorker.Connect`, invocation korelasyonu,
   `WorkerCapabilityProvider`. Java tarafında bir test worker'ı ile doğrulanır.
2. **Ölüm ve yokluk hâlleri** — worker invocation ortasında ölürse, hiç worker yoksa, bağlantı
   koparsa. Organizasyon sınırı burada çizilir.
3. **Python worker SDK'sı** + ayrı process testi.
4. **TypeScript worker SDK'sı** — aynı sözleşme, kanıtlanmış şekle göre.
5. **Örnek** — bir adımı MCP tool'u, bir adımı worker olan workflow.

### Tasarım sorularının cevapları

1. **Zaman aşımının sahibi runtime.** Step'in `timeout` politikası zaten motorda uygulanıyor
   (iptal değil, terk). Worker'a deadline *bilgi olarak* gönderilecek ki kendi işini boşuna
   sürdürmesin, ama adımın kaderine motor karar veriyor. **Ek olarak `WorkerCapabilityProvider`'ın
   kendi varsayılan sınırı olmalı:** step'te `timeout` yazmayan bir workflow, ölü bir worker
   yüzünden sonsuza kadar bloke olmamalı.
2. **Round-robin.** Bağlı worker'lar arasında sıra ile; yük farkındalığı #10'un işi ve burada
   yapmaya çalışmak erken bir optimizasyon olur. Idempotent bir capability'nin retry'ının *başka*
   bir worker'a gitmesi zaten doğru davranış.
3. **Worker kaydı organizasyona bağlı — ve bu sınır şimdi çizilmeli.** Bir organizasyonun
   worker'ı başka bir organizasyonun invocation'ını almamalı. Tam izolasyon #17'nin işi ama
   yönlendirmeyi sonradan organizasyon farkındalığı kazandırmak, sonradan sütun eklemek kadar
   pahalı. Filtre baştan konuyor.
4. **Worker yoksa hızlı düşsün, `retryable: true`.** Beklemek için yeni bir mekanizma icat etmek
   yerine mevcut retry politikası kullanılıyor: `maxAttempts` yazan bir step, kısa bir worker
   yeniden başlatmasını kendiliğinden atlatır. Sonsuza kadar beklemek, çağıranın göremediği bir
   hang üretir.

### Risk points

- **Tek stream, çok invocation.** Bir worker'a aynı anda birden fazla çağrı gidebilir; korelasyon
  `invocation_id` ile, ve **stream'e tek yazar** kuralı burada da geçerli — `UpdatePump`'ta
  öğrendiğimiz şeyin aynısı. İki thread'in aynı worker stream'ine yazması gRPC ihlali.
- **`CapabilityProvider.invoke` senkron.** Worker cevabını bekleyen bir latch gerekiyor. Bu
  provider I/O olduğu için transaction dışında ve motorun `within(timeout)` sarmalayıcısının
  içinde — yeni bir mekanizma gerekmiyor, ama varsayılan sınır (soru 1) olmazsa bu bekleyiş
  sınırsız olur.
- **Ölüm tespiti.** Worker'ın öldüğünü stream'in kapanmasından anlıyoruz; ama yarım kalan
  invocation'ların sahibini bilmek için "hangi worker hangi invocation'ı tutuyor" kaydı gerekiyor.
  Bu kayıt bellekte — runtime da ölürse execution `RUNNING`'de kalır ve `RecoveryScheduler`
  toplar. İki mekanizmanın devreye girdiği yer burası ve testte ikisi birden gösterilmeli.
- **Kapsam kayması.** Yük dağıtımı, worker sağlık kontrolü, çift yönlü akış (worker'ın runtime'a
  olay göndermesi) bu dilime girmemeli.

## Implementation Notes

### Aşama 1-2 — Kayıt defteri, yönlendirme, ölüm hâlleri (2026-08-20) ✅

**216 Java testi yeşil** (153 core + 13 Postgres + 16 provider + 9 MCP + 10 OTel + 15 gRPC).

```
core/capability/   CapabilityCall (yeni), CapabilityProvider imzası
grpc/              CapabilityWorkerService, WorkerRegistry, ConnectedWorker,
                   WorkerCapabilityProvider
```

**Tasarım kararları:**

- **`CapabilityProvider.invoke` artık `CapabilityCall` alıyor.** Provider'lar organizasyonu
  göremiyordu, ama yönlendirme onsuz yapılamaz. Yerel bir tool'a ulaşan provider bunu kullanmıyor;
  uzak worker'a yönlendiren onsuz çalışamıyor — bu yüzden parametre, provider'ın uzanıp alacağı
  bir şey değil. Proto zaten `organization_id` ve `traceparent` alanlarını öngörmüştü.
- **Worker stream'ine tek yazar.** Bir worker'a eşzamanlı çağrılar gidiyor; `ConnectedWorker.invoke`
  gönderimi senkronize ediyor. `UpdatePump`'ta öğrendiğimiz kuralın ters yönden gelişi.
- **Cevap sorusunu `invocationId` ile buluyor.** Worker sırayla cevap vermek zorunda değil ve
  sıklıkla vermeyecek.
- **Worker ölümü `retryable: false`.** Worker çağrıyı aldı ve öldü; bu taraf çalışıp çalışmadığını
  bilmiyor. Bunu çağıran adına karara bağlamak tam olarak çift tahsilat üreten tahmin —
  yeniden denemeye izin veren şey capability'nin kendi idempotency beyanı ve o daha yukarıda
  kontrol ediliyor.
- **Worker yoksa `retryable: true`.** Beklemek için yeni mekanizma icat edilmedi; mevcut retry
  politikası kısa bir worker restart'ını kendiliğinden atlatıyor. Sonsuza kadar beklemek
  çağıranın göremediği bir hang üretirdi.
- **Organizasyon filtresi yönlendirmede.** Bir organizasyonun worker'ı diğerinin invocation'ını
  almıyor; bir test bunu doğruluyor. Tam izolasyon #17 ama sınır burada çizildi.
- **`WorkerCapabilityProvider`'ın kendi varsayılan sınırı var** (60 sn). Step'in `timeout`
  politikası otorite ve motor onu uyguluyor; bu, `timeout` yazmayan bir workflow'un ölü bir worker
  yüzünden sonsuza kadar bloke olmaması için.

**Testte düzeltilen bir yapaylık:** ilk hâlde worker ölümünü kayıt defterini elle dürterek
canlandırıyordum. Gerçek bir worker ölümü stream'i kapatır — test artık worker'ın invocation'ı alıp
hattı kapatmasıyla çalışıyor, yani `onCompleted → unregister → abandon` yolunun tamamı sınanıyor.

### Aşama 3-5 — SDK worker'ları ve karışık workflow (2026-08-20) ✅

**218 Java + 17 Python + 17 TypeScript testi yeşil.** Contract tamamlandı.

```
sdk/python/pipemesh/worker.py      PipeMeshWorker, CapabilityFailure
sdk/typescript/src/worker.ts       aynı sözleşme; structs.ts paylaşıldı
examples/                          calculate-capacity.json (worker) + venue-booking'e ek adım
pipemesh-grpc/  (test)             MixedCapabilityWorkflowTest
```

**Tasarım kararları:**

- **Tek kuyruk, tek yazar.** Python'da giden mesajlar bir `queue`'dan tek bir istek iterator'ına
  akıyor; aynı anda cevaplayan birkaç invocation stream'i bozamıyor. Java tarafındaki
  senkronizasyonun ve `UpdatePump`'ın aynı kuralı, üçüncü kez.
- **Invocation'lar okuma thread'inin dışında koşuyor.** Yavaş bir capability, worker'ın diğer
  çağrıları almasını durdurmamalı.
- **Beklenmeyen exception da bir cevaptır.** Kullanıcının planlamadığı bir hata yakalanıp
  `worker.raised` olarak dönüyor. Kaçmasına izin vermek, execution'ı hiç gelmeyecek bir cevabı
  beklerken bırakırdı — ve bu, hata gibi görünmeyen bir hang'dir. İki dilde de bir test bunu
  kilitliyor.
- **`CapabilityFailure` varsayılan olarak `retryable: false`.** Hayır diyen bir iş kuralı, ikinci
  kez sorulduğunda farklı bir şey söylemiyor. Taşıma sorunu ayrı bir şey ve runtime onu kendisi
  sınıflandırıyor.
- **Bare değer isimli alana sarmalanıyor** (`{"value": ...}`): Struct'ın alan adı olmadan şekli yok.

### Kabul kriterinin kalbi

`MixedCapabilityWorkflowTest`: tek workflow, iki capability — biri ayrı process'te çalışan gerçek
bir MCP sunucusu, diğeri gRPC'den bağlanmış bir worker. İki adım **aynı cümle**, ve workflow
metninde ne "mcp" ne "worker" geçiyor. Test ikisinin çıktısını da doğruluyor.

`examples/approval-flow` da aynı şeyi gösteriyor: `venue_search` bir MCP tool'u,
`calculate_capacity` birinin uygulamasındaki bir fonksiyon; `venue-booking.json`'da ikisi
birbirinden ayırt edilemiyor.

**Paylaşılan test sunucusu:** `TestMcpServer` artık `pipemesh-mcp` test-jar'ı olarak yayınlanıyor.
Kopyalamak iki tanesinin birbirinden ayrı düşmesi demekti.

**Yapılmayan:** yük dağıtımı (round-robin var, yük farkındalığı #10), worker sağlık kontrolü,
worker'ın runtime'a olay göndermesi.
