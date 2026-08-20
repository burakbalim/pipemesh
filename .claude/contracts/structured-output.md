# Structured Output and Schema Registry

**Status:** Draft
**Created:** 2026-08-20
**DESIGN.md kapsamı:** §21 (structured output), §11 (prompt registry), §24 (versiyonlama), §31

## Goal

Bir LLM adımının ürettiği çıktının **gerçekten** beklenen şekilde olduğunu garanti etmek.

Bugün durum şu: `outputSchema` provider'a gönderiliyor (`response_format: json_schema`) ama dönen
cevap **hiçbir yerde doğrulanmıyor**. Model şemaya uymayan bir JSON dönerse, ya da JSON yerine
metin dönerse, o değer sessizce bir değişkene yazılıyor ve bir sonraki adım eksik alanla
çalışıyor. Walking skeleton contract'ında bu kriter yanlışlıkla ✅ işaretlenmişti.

> Şemayı **istemek** ile şemaya **uymasını sağlamak** aynı şey değil. Modeller uyumsuz cevap
> verir; runtime'ın işi bunu adımın sınırında yakalamak, üç adım sonra tuhaf bir hata olarak
> patlamasına izin vermek değil.

## Affected Modules

- [ ] `core/schema` (yeni) — minimal JSON Schema doğrulayıcı
- [ ] `core/config` — `schemas/` dizininden şema kaydı okuma
- [ ] `core/execution/step` — `LlmStepExecutor` çıktıyı doğrular
- [ ] `examples/approval-flow` — workflow şemaya id ile referans verir
- [ ] Mobile / Admin portal — n/a

## Contract

Workflow şemaya **id ile** referans verir, gövdesini taşımaz:

```json
{
  "id": "extract",
  "type": "llm",
  "model": "fast",
  "prompt": "venue_booking.extraction.v1",
  "outputSchema": "venue-request",
  "output": "request",
  "next": "validate"
}
```

`schemas/venue-request.json` config repo'sunda durur (§31). Inline şema da desteklenir (obje
verilirse) ama örnekler id kullanır — şema paylaşılan bir artefakt, workflow'un içine gömülen bir
metin değil (§24).

Doğrulama başarısız olursa:

```json
{ "code": "llm.schema_violation", "retryable": true }
```

**Retryable, çünkü modeller ikinci denemede sıklıkla uyar.** #2'nin retry politikasıyla doğrudan
birleşiyor: `maxAttempts: 2` yazan bir step, uyumsuz cevabı kendiliğinden düzeltme şansı verir.

## Doğrulayıcı kapsamı

Tam JSON Schema değil, bilinçli olarak dar bir alt küme:

```text
type          object | array | string | number | integer | boolean | null
properties    özyinelemeli
required
items         dizi elemanları
enum
```

`$ref`, `allOf`/`anyOf`/`oneOf`, `pattern`, `format`, sayısal sınırlar **yok**. Gerekçe: bunlar
bir kütüphane gerektirir ve runtime'ın taşıdığı her bağımlılığı embedder devralır. Model çıktısı
şemaları pratikte bu alt kümede kalıyor; kapsam dışına çıkan bir ihtiyaç ayrı bir modül olarak
gelmeli, core'a bağımlılık olarak değil.

## Tasarım Soruları (preflight'ta netleşmeli)

1. **Doğrulama nerede?** LLM step'inde mi, yoksa provider sınırında mı? Provider'da olursa her
   provider tekrar yazar; step'te olursa capability çıktıları doğrulanmadan kalır.
2. **Capability çıktıları da doğrulanmalı mı?** `CapabilityDescriptor.outputSchema` zaten var ama
   kullanılmıyor.
3. **Uyumsuz cevabı ne kadar saklayalım?** Hata mesajında modelin ham çıktısı olmalı mı — hata
   ayıklama için değerli, ama log'a PII sızdırabilir.

## Acceptance Criteria

- [ ] Şemaya uyan çıktı değişkene yazılıyor, akış devam ediyor
- [ ] Eksik `required` alanı olan çıktı `llm.schema_violation` ile düşüyor
- [ ] Yanlış tipte alan (`"price": "bedava"` ama şema `number`) düşüyor
- [ ] JSON olmayan cevap düşüyor — bugünkü "metin olarak geç" davranışı şema varken geçerli değil
- [ ] Şema ihlali `retryable: true` — retry politikasıyla ikinci deneme yapılabiliyor
- [ ] Workflow şemaya id ile referans verebiliyor; şema `schemas/` dizininden yükleniyor
- [ ] Inline şema da çalışmaya devam ediyor
- [ ] Bilinmeyen şema id'si compile zamanında değil, çalışma zamanında net bir hata veriyor
- [ ] Şemasız LLM step'i bugünkü davranışını koruyor (metin döner, doğrulama yok)
- [ ] Doğrulayıcı iç içe obje ve dizileri kontrol ediyor
- [ ] Hata mesajı **hangi alanın** neden reddedildiğini söylüyor

## Split Decision

**Decision:** single-prompt
**Tarih:** 2026-08-20

**Reasoning:** Tek bir yeni paket (`core/schema`) ve iki dokunuş noktası (`LlmStepExecutor`,
`ConfigRepository`). Paralelleştirilecek bağımsız parça yok.

### Build order

1. **Doğrulayıcı** — `JsonSchemaValidator`, `SchemaViolation`; saf fonksiyon, bağımsız test.
2. **Şema kaydı** — `SchemaRegistry` + `ConfigRepository.schemaRegistry()`.
3. **LLM step entegrasyonu** — id/inline çözümleme, doğrulama, `llm.schema_violation`.
4. **Örnek** — `venue-booking.json` şemaya id ile referans verir.

### Tasarım sorularının cevapları

1. **Doğrulama step'te.** Provider sınırında olursa her provider aynı kodu tekrar eder ve
   provider'lar arasında davranış ayrışır. Step'te olması ayrıca şemayı *runtime'ın* garantisi
   yapıyor, sağlayıcının nezaketi değil — OpenAI-uyumlu bir endpoint `json_schema`'yı
   desteklemese bile garanti duruyor.
2. **Capability çıktıları bu contract'ta doğrulanmıyor.** Alan (`outputSchema`) duruyor ama
   MCP tool çıktısının şemasını zorlamak ayrı bir karar: bir tool sözleşmesini ihlal ettiğinde
   workflow'u düşürmek mi yoksa geçirmek mi doğru, tartışılması gereken bir soru. Not düşüldü.
3. **Ham çıktı hata mesajında yok, step history'de var.** `output_snapshot` zaten tüm cevabı
   saklıyor ve o kayıt erişim kontrolü altında; hata mesajı log'a ve telemetriye gidiyor, oraya
   model çıktısı koymak PII sızdırmanın kolay yolu.

## Implementation Notes

### Tamamlandı (2026-08-20) ✅

**174 test yeşil** (137 core + 8 Postgres + 10 provider + 9 MCP + 10 OTel).

```
core/schema/          JsonSchemaValidator, SchemaViolation, SchemaRegistry, InMemorySchemaRegistry
core/config/          ConfigRepository.schemaRegistry()
core/execution/step/  LlmStepExecutor doğrulama yapıyor
examples/             venue-booking.json şemaya id ile referans veriyor
```

**Tasarım kararları:**

- **Doğrulayıcı kütüphane değil, ~150 satır.** `type`, `properties`, `required`, `items`, `enum`.
  `$ref`/`allOf`/`pattern` yok — bunlar bir bağımlılık gerektirir ve runtime'ın taşıdığı her
  bağımlılığı embedder devralır. **Desteklenmeyen anahtar reddedilmiyor, yoksayılıyor:**
  doğrulayıcı küçük diye geçerli bir cevabı düşürmek, şemanın istediğinden azını kontrol
  etmekten kötü olurdu.
- **İhlal `retryable: true`.** Şekli bir kez yoksayan model ikinci geçişte sıklıkla uyar;
  #2'nin retry politikası bir deneme harcamaya değip değmeyeceğine karar veriyor. Bir test
  bunu uçtan uca gösteriyor: `maxAttempts: 2` ile ilk uyumsuz cevap düzeliyor.
- **Hata mesajı alanı isimlendiriyor:** `$.location is required`. "cevap şemaya uymadı" birini
  stack trace avına gönderir.
- **Şema id ile referanslanıyor, gövdesi workflow'a gömülmüyor.** Şema paylaşılan bir artefakt;
  aynı şekli çıkaran iki workflow'un birbirinden ayrı düşecek birer kopyaya sahip olması
  §24'ün tersi. Inline şema hâlâ destekleniyor ama örnekler id kullanıyor.
- **Şemasız step aynen eskisi gibi.** Şema istemeyen bir adım hiçbir şey istememiş olur;
  bugünkü metin davranışı korunuyor.

**Düzeltilen yanlış iddia:** walking-skeleton contract'ında "şema ihlali `Failed` dönüyor"
kriteri ✅ işaretlenmişti. Aslında şema yalnızca *isteniyordu*; `OpenAiCompatibleProviderTest`
isteğin şeklini ve JSON olmayan cevabın metin olarak geçtiğini doğruluyordu — doğrulama hiçbir
yerde yoktu. Kriter ❌ olarak düzeltildi ve bu contract'a devredildi. Artık gerçekten sağlanıyor.

**Yapılmayan:** capability çıktılarının doğrulanması. `CapabilityDescriptor.outputSchema` alanı
duruyor ama kullanılmıyor — bir MCP tool'u sözleşmesini ihlal ettiğinde workflow'u düşürmek mi
geçirmek mi doğru, ayrıca karar verilmesi gereken bir soru. Not düşüldü.
