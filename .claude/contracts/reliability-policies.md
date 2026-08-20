# Reliability Policies

**Status:** Draft
**Created:** 2026-08-20
**DESIGN.md kapsamı:** §17 (retry), §18 (failure handling), §9.x step config

## Goal

Bir step'in başarısızlığını **yürütme politikası** haline getirmek: yeniden deneme, zaman aşımı ve
yedeğe düşme workflow'un iş mantığına karışmadan, config'de tanımlanabilir olmalı. Bugün bir LLM
çağrısı düştüğünde execution kalıcı olarak `FAILED` oluyor — geçici bir ağ hatası kalıcı bir kayıp
üretiyor.

DESIGN.md §18'in cümlesi kriter: **"The runtime, not the LLM, controls failure semantics."**

## Affected Modules

- [ ] `core/execution` — retry/timeout sarmalayıcısı, attempt sayacı, `StepResult` semantiği
- [ ] `core/policy` (yeni) — `RetryPolicy`, `Backoff`, `TimeoutPolicy`, `FailurePolicy`
- [ ] `core/config` — politikaların workflow/step seviyesinde okunması
- [ ] `core/state` — attempt bilgisinin step history'ye yazılması
- [ ] `core/observability` — attempt ve retry metrikleri
- [ ] `pipemesh-postgres` — attempt sütunu (migration)
- [ ] Mobile / Admin portal — n/a

## Policy Contract

```json
{
  "id": "extract",
  "type": "llm",
  "model": "fast",

  "retry": {
    "maxAttempts": 3,
    "backoff": "exponential",
    "initialDelay": "500ms",
    "maxDelay": "30s"
  },

  "timeout": "20s",

  "onFailure": {
    "strategy": "fallback",
    "model": "local"
  }
}
```

Politika seviyeleri (§17): **global → workflow → step**, en dar olan kazanır. Provider seviyesi
(HTTP client timeout'u gibi) zaten provider'ın kendi işi ve bu contract'a girmiyor.

`onFailure.strategy` değerleri:

| Strateji | Anlamı |
|---|---|
| `fail` | varsayılan — execution `FAILED` |
| `fallback` | başka bir model/capability ile aynı step'i bir kez daha dene |
| `continue` | hatayı değişkene yaz, `next`'e devam et |
| `goto` | belirtilen step'e dal (örn. `clarification`) |

## Tasarım Soruları (preflight'ta netleşmeli)

1. **Retry nerede yaşıyor?** In-process döngü mü (basit, ama process ölürse kaybolur) yoksa
   persist edilen attempt + scheduler mi (dayanıklı, ama #10'un kapsamına girer)?
2. **Timeout nasıl uygulanır?** Step'i ayrı thread'de çalıştırıp `Future.get(timeout)` mı — bu
   iptal edilemeyen bir provider çağrısında thread sızdırır. Yoksa timeout'u provider'a mı
   devrederiz (HTTP client, MCP request timeout) ve step seviyesi yalnızca *bildirim* mi olur?
3. **Yan etkisi olan capability yeniden denenebilir mi?** `retryable` bayrağı provider'dan
   geliyor ama "ödeme al" idempotent değil. Capability registration'ının `idempotent: false`
   demesi ve runtime'ın onu yeniden denememesi gerekir mi?
4. **Fallback modeli aynı prompt'u mu alır?** Küçük bir modele reasoning prompt'u göndermek
   sessizce kalitesiz sonuç üretir — fallback ayrı prompt da tanımlayabilmeli mi?

## Acceptance Criteria

- [ ] Geçici olarak düşen bir step, politikada tanımlı sayıda yeniden denenip başarılı oluyor
- [ ] Yeniden denemeler arasında backoff uygulanıyor (exponential + jitter)
- [ ] `retryable: false` dönen bir hata **hiç** yeniden denenmiyor
- [ ] Her deneme step history'ye ayrı kayıt olarak yazılıyor — kaç kez denendiği görünür
- [ ] Deneme sayısı tükendiğinde `onFailure` stratejisi uygulanıyor
- [ ] `fallback` stratejisi ikinci bir model ile aynı step'i çalıştırıyor ve bunu telemetriye yazıyor
- [ ] `continue` stratejisi hatayı değişkene yazıp akışı sürdürüyor
- [ ] Step seviyesi politika workflow seviyesini eziyor; hiçbiri yoksa global varsayılan geçerli
- [ ] Timeout aşıldığında step `Failed` dönüyor ve retry politikası devreye giriyor
- [ ] Retry sırasında transaction açık kalmıyor (provider I/O persist'ten önce kuralı korunuyor)
- [ ] `pipemesh.step.attempts` metriği ve `attempt` span attribute'u üretiliyor
- [ ] Politikasız bir workflow bugünkü davranışını aynen koruyor (geriye dönük uyumluluk)

## Split Decision

**Decision:** single-prompt (aşamalı, tek ajan)
**Tarih:** 2026-08-20

**Reasoning:** Değişiklikler tek bir sınıfta yoğunlaşıyor — `WorkflowExecutor`'ın step çalıştırma
döngüsü. Politika modeli, retry döngüsü, timeout ve `onFailure` aynı beş satırın etrafında
katmanlanıyor; paralel ajanlar aynı metodu yeniden yazardı. Dilim de küçük: yeni bir modül,
yeni bir transport ya da yeni bir dış sistem yok.

### Build order

1. **Politika modeli** — `RetryPolicy`, `Backoff`, `TimeoutPolicy`, `FailurePolicy` +
   step/workflow/global çözümlemesi. Motor henüz kullanmıyor.
2. **Retry döngüsü** — `WorkflowExecutor` içinde, her deneme step history'ye ayrı kayıt.
   Migration: `workflow_step_history.attempt`.
3. **Timeout** — virtual thread + deadline.
4. **`onFailure` stratejileri** — `fail` / `fallback` / `continue` / `goto`.
5. **Telemetri** — `pipemesh.step.attempts`, span'de `attempt` attribute'u.

### Tasarım sorularının cevapları

1. **Retry in-process.** Persist edilen attempt + scheduler dayanıklı olurdu ama #10'un
   (distributed workers) kapsamı; geçici ağ hataları saniyeler içinde çözülüyor ve in-process
   döngü bunların ezici çoğunluğunu karşılıyor. **Dürüst sınır:** process backoff sırasında
   ölürse execution `RUNNING` durumunda öksüz kalır. Bu bugün de her step ortası çökmede
   geçerli — öksüz `RUNNING` execution'ları toplayan bir süpürücü ayrı bir iş (#10).
2. **Timeout virtual thread ile.** Step ayrı bir virtual thread'de koşuyor, motor deadline'da
   `Failed` dönüyor. **İptal etmiyoruz, terk ediyoruz:** provider çağrısı arka planda kendi
   timeout'una kadar sürebilir. Java 21 virtual thread'de bu ucuz — platform thread'i sızmıyor.
   Alternatif (timeout'u tamamen provider'a devretmek) step seviyesinde politika tanımlamayı
   anlamsız kılardı.
3. **`idempotent` capability registration'ında, varsayılan `true`.** Retry zaten yalnızca
   `retryable: true` dönen hatalarda tetikleniyor ve provider'lar bunu transport hataları için
   veriyor. **Ama transport hatası "istek gitti mi?" sorusunu cevapsız bırakır** — yan etkisi
   olan bir capability (`refund_payment`) registration'ında `"idempotent": false` demeli ve
   runtime onu asla yeniden dememeli. Varsayılanın izin verici olması bilinçli ve tartışılabilir
   bir seçim: çoğu capability okuma, ama yanlış tarafta hata yapmanın bedeli çift tahsilat.
   Bu yüzden `examples/`'daki `refund_payment` açıkça `idempotent: false` işaretlenecek.
4. **Fallback kendi prompt'unu tanımlayabilir.** Küçük bir modele reasoning prompt'u göndermek
   sessizce kalitesiz sonuç üretir; `onFailure` içinde opsiyonel `prompt` alanı olacak.

### Risk points

- **Geriye dönük uyumluluk.** Politikasız workflow'lar bugünkü davranışını korumalı; varsayılan
  politika "tek deneme, timeout yok, `fail`" olmalı. Mevcut 132 testin hepsi değişmeden geçmeli.
- **Retry ile step budget'ın karışması.** Bir deneme bütçeden bir adım yemeli mi? Hayır — bütçe
  grafikte kaç adım ilerlendiğini sayar, aynı adımın kaç kez denendiğini değil. Aksi halde
  retry'lı bir workflow sessizce daha kısa bir bütçeyle koşar.
- **Transaction sınırı.** Retry döngüsü persist'ten *önce* dönmeli; her denemeden sonra yazmak
  transaction'ı denemeler boyunca açık tutma riskini getirir.

## Implementation Notes

### Tamamlandı (2026-08-20) ✅

**151 test yeşil** (114 core + 8 Postgres + 10 provider + 9 MCP + 10 OTel). Mevcut 132 testin
hepsi değişmeden geçti — geriye dönük uyumluluk kriteri sağlandı.

```
core/policy/          Backoff, DurationText, RetryPolicy, FailurePolicy, StepPolicy
core/execution/       WorkflowExecutor retry döngüsü, timeout, onFailure
core/capability/      CapabilityDescriptor.idempotent()
pipemesh-postgres/    workflow_step_history.attempt
pipemesh-opentelemetry/ pipemesh.step.attempts + attempt span attribute'u
```

**Tasarım kararları:**

- **Her deneme ayrı satır.** Bir sayaç yerine her deneme step history'ye yazılıyor: kaç kez
  denendiği *ve her denemenin ne kadar sürüp nasıl düştüğü* bu tabloya bakan kişinin tam olarak
  aradığı şey. Denemeler arası yazma, backoff ortasında ölen bir process'in sessiz boşluk
  bırakmamasını da sağlıyor.
- **Jitter opsiyonel değil.** Aynı anda düşen her çağıran aynı anda yeniden dener ve yeni
  toparlanmış servisi bekletttiği kalabalıkla devirir. `Backoff` yarı-aralık jitter uyguluyor.
- **Timeout: iptal değil, terk.** Step virtual thread'de koşuyor, deadline'da motor beklemeyi
  bırakıyor ama çağrıyı durdurmuyor — kesintiyi yoksayan bir provider kendi timeout'una kadar
  devam eder. Alternatif (iptal edilemeyen bir isteğe execution'ı rehin vermek) daha kötü ve
  virtual thread'de terk edilen iş neredeyse bedava.
- **`idempotent` capability registration'ında, `false` diyeni runtime asla yeniden denemez.**
  Transport hatası "istek gitti mi?" sorusunu cevapsız bırakıyor; kart çeken bir capability için
  bu belirsizlikte yeniden denemek çift tahsilat demek. Kararı yalnızca registration bilebilir,
  bu yüzden reddetme oradan geliyor. `examples/`'daki `refund_payment` açıkça işaretlendi.
- **Fallback config overlay'i.** `onFailure.model`/`prompt` step config'inin üzerine biniyor ve
  step yeniden çalıştırılıyor — generic: bu alanlara sahip her step tipi motor onların ne demek
  olduğunu bilmeden fallback kazanıyor. Fallback kendi prompt'unu tanımlayabiliyor; küçük bir
  modele reasoning prompt'u göndermek sessizce kalitesiz sonuç üretir ve bunu fark etmek düpedüz
  bir hatadan zordur.
- **Retry bütçe yemiyor.** Bütçe grafikte kaç adım ilerlendiğini sayıyor, aynı adımın kaç kez
  denendiğini değil.

**Yol boyunca bulunan iki gerçek hata:**

1. **Bütçe sınırı hatası (Aşama 2'den beri duruyordu).** Son adım bütçeyi tam doldurup
   execution'ı bitirdiğinde, döngüden çıkışta koşulsuz `exhausted()` çağrıldığı için execution
   yanlışlıkla `FAILED` işaretleniyordu. `retriesDoNotEatTheStepBudget` testi yakaladı.
2. **`onFailure.goto` compiler'a görünmüyordu.** Politika hedefi bir kenar ama
   `StepExecutor.outgoing()` yalnızca step config'ini okuyor. Sonuç: goto hedefi "ulaşılamaz"
   sayılıyor, ve hatalı yazılmış bir hedef ancak bir şey gerçekten düştüğü gün fark edilirdi.
   `WorkflowCompiler` artık politika hedeflerini de kenar sayıyor.

**Yapılmayan:** dayanıklı retry. Process backoff sırasında ölürse execution `RUNNING` durumunda
öksüz kalıyor. Bu bugün her step ortası çökmede geçerli; öksüz `RUNNING` execution'ları toplayan
süpürücü #10'a ait.
