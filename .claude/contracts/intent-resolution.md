# Intent Resolution

**Status:** Draft
**Created:** 2026-08-20
**DESIGN.md kapsamı:** §19 (intent resolution), §20 (deterministic vs AI), §31 (`intents.json`), §26.4 (`process()`)

## Goal

Doğal dilde gelen bir mesajdan **hangi workflow'un koşacağına** karar vermek.

`process()` fiili proto'da ve iki SDK'da duruyor ama `UNIMPLEMENTED` dönüyor — yayınlanmış
yüzeydeki tek boşluk bu. DESIGN §45'in Phase 1'inde de açık kalan tek madde.

Kritik sınır (§19): **intent çözümlemesi bir workflow seçer, çalıştırmaz.** İkisini birbirine
karıştırmak, "model akışı seçti"nin sessizce "model akışı yönetiyor"a dönüşmesidir (§20, §37).

## Affected Modules

- [ ] `core/intent` (yeni) — `IntentRegistry`, `IntentResolver`, deterministik ve LLM çözümleyiciler
- [ ] `core/config` — `intents/intents.json` yüklemesi
- [ ] `core/execution` — `WorkflowRuntime.process(...)`
- [ ] `pipemesh-grpc` — `ProcessMessage` implementasyonu
- [ ] `sdk/python`, `sdk/typescript` — `process()` artık çalışıyor
- [ ] `examples/` — `intents.json`

## Contract

`intents/intents.json` (§31):

```json
{
  "intents": [
    {
      "id": "book_venue",
      "workflow": "venue_booking",
      "description": "Kullanıcı bir mekan ayırtmak veya etkinlik düzenlemek istiyor",
      "matches": ["mekan ayır", "book a venue", "organize a meetup"]
    },
    {
      "id": "request_refund",
      "workflow": "refund_request",
      "description": "Kullanıcı para iadesi istiyor",
      "matches": ["iade", "refund"]
    }
  ],

  "model": "fast",
  "prompt": "intent.classify.v1",
  "minimumConfidence": 0.6
}
```

Çözümleme sırası (§20):

```text
mesaj
  ↓
deterministik eşleşme  →  bulundu mu? evet → bitti, model hiç çağrılmadı
  ↓ hayır
model sınıflandırması  →  güven eşiğin üstünde mi?
                          ├── evet → o workflow
                          └── hayır → intent.unresolved
```

**Deterministik önce, çünkü ucuz ve kesin.** "refund" kelimesi geçen bir mesaj için modele para
ödemek ve gecikme eklemek, §20'nin tam olarak uyardığı şey.

**Eşiğin altı çözülemedi demektir, "en yakını" değil.** Model bir şey uydurmaya zorlanmamalı;
`intent.unresolved` net bir cevaptır, yanlış workflow'u çalıştırmak değildir.

## Denetlenebilirlik

Çözümleme execution'dan **önce** olduğu için ortada kaydedilecek bir step yok. Seçim
execution'ın değişkenlerine yazılıyor:

```json
{ "intent": { "id": "book_venue", "resolvedBy": "deterministic" } }
```

Model kullanıldıysa `model`, `promptVersion` ve `confidence` de eklenir. Böylece "bu execution
neden koştu?" sorusunun cevabı execution'ın kendi kaydında duruyor.

## Tasarım Soruları (preflight'ta netleşmeli)

1. **Model çıktısı nasıl güvence altına alınır?** Şema doğrulaması (#3) elimizde — sınıflandırma
   cevabı `{"intent": "...", "confidence": 0.0-1.0}` şemasına karşı doğrulanmalı mı?
2. **Bilinmeyen intent adı dönerse?** Model kayıtlı olmayan bir id uydurabilir.
3. **`process()` ne dönmeli — handle mi, seçilen intent mi?** SDK'daki imza `ExecutionHandle`;
   çözümlenemediğinde ne olacağı buna bağlı.
4. **Deterministik eşleşme nasıl?** Alt-dizi mi, kelime sınırı mı, regex mi? Regex bir workflow
   dosyasına gömülü ikinci bir dil olur (§23.1'in ruhu).

## Acceptance Criteria

- [ ] Deterministik eşleşen mesaj model çağrılmadan doğru workflow'u başlatıyor
- [ ] Deterministik eşleşme yoksa model sınıflandırması devreye giriyor
- [ ] Güven eşiğin altındaysa hiçbir workflow başlatılmıyor, `intent.unresolved` dönüyor
- [ ] Model kayıtlı olmayan bir intent id'si dönerse çözümlenemedi sayılıyor
- [ ] Seçilen intent ve nasıl seçildiği execution değişkenlerinde görünüyor
- [ ] `intents.json` config repo'sundan yükleniyor
- [ ] gRPC `ProcessMessage` çalışıyor; çözümlenemeyen mesaj net bir status koduyla dönüyor
- [ ] Python ve TypeScript `process()` fiilleri çalışıyor
- [ ] Intent kaydı olmayan bir runtime'da `process()` net bir hata veriyor (sessizce ilk workflow'u seçmiyor)
- [ ] Çözümleme workflow **çalıştırmıyor** — yalnızca seçiyor; seçim ile başlatma ayrı adımlar

## Split Decision

**Decision:** single-prompt (aşamalı, tek ajan)
**Tarih:** 2026-08-20

**Reasoning:** Yeni bir paket, bir config dosyası ve üç yerde aynı fiilin bağlanması. Zincirleme
bağımlı: SDK'lar gRPC'siz, gRPC de çözümleyicisiz çalışamaz. Paralelleştirilecek bağımsız parça yok.

### Build order

1. **Çözümleyiciler** — `IntentRegistry`, deterministik eşleşme, LLM sınıflandırması, eşik.
   Saf birimler, modelsiz test edilebilir.
2. **`intents.json` yüklemesi** ve `WorkflowRuntime.process(...)`.
3. **gRPC `ProcessMessage`** + iki SDK'nın `process()` fiili.
4. **Örnek** — `examples/approval-flow/intents/intents.json`.

### Tasarım sorularının cevapları

1. **Evet, şema doğrulaması kullanılıyor.** Sınıflandırma isteği `outputSchema` ile gidiyor ve
   cevap #3'ün doğrulayıcısından geçiyor. Elimizdeki parçayı yeniden kullanmak, ikinci bir
   "model cevabı düzgün mü" mekanizması yazmaktan iyi. Doğrulama düşerse çözümlenemedi sayılıyor.
2. **Kayıtlı olmayan intent id'si = çözümlenemedi.** Model uydurabilir; kayıt defterinde
   karşılığı olmayan bir id'yi kabul etmek, tam da "en yakınını çalıştır" hatası olur.
3. **`process()` çözümlenemediğinde istisna atıyor, `ExecutionHandle` dönmüyor.** Boş bir handle
   ya da "FAILED" bir execution uydurmak, hiç başlamamış bir şeyi başlamış gibi göstermek olurdu.
   gRPC tarafı `FAILED_PRECONDITION` — istemci için "isteğin yanlış" değil, "bu mesajdan ne
   yapacağımı çıkaramadım" demek.
4. **Deterministik eşleşme: kelime sınırlı, büyük/küçük harf duyarsız alt-dizi.** Regex
   kasıtlı olarak dışarıda — bir config dosyasına gömülü ikinci bir dil olurdu ve §23.1'in
   ruhuna aykırı. "iade" yazan bir mesajın "iadesiz" kelimesiyle eşleşmemesi için kelime sınırı
   şart.

### Risk points

- **Sınırın erimesi.** Bu contract'ın tek gerçek riski kavramsal: çözümleme, çalıştırmaya
  sızarsa (örneğin model "hangi adımdan başlayayım?" diye sorulursa) §37'nin tezi çöker.
  Çözümleyici bir `WorkflowId` döndürür, başka bir şey değil — ve bir test bunu kilitlemeli.
- **Sessiz maliyet.** Deterministik eşleşme kaçırılırsa her mesaj için model çağrılır. Hangi
  yolun kullanıldığı execution değişkenlerinde görünmeli ki bu fark edilebilsin.
- **Çok dilli eşleşme.** `matches` listesi Türkçe ve İngilizce ifadeleri bir arada tutuyor;
  bu bir eksiklik değil, deterministik katmanın doğası — kapsamadığı her şey modele düşüyor.

## Implementation Notes

### Tamamlandı (2026-08-21) ✅

**233 Java + 19 Python + 19 TypeScript testi yeşil.** DESIGN §45'in **Phase 1'i kapandı.**

```
core/intent/     IntentId, IntentDefinition, IntentRegistry, PhraseMatcher,
                 DefaultIntentResolver, ResolvedIntent, IntentUnresolvedException
core/execution/  ProcessRequest, WorkflowRuntime.process(...)
core/config/     intents.json yüklemesi
grpc/            ProcessMessage artık gerçek
sdk/*/           process() fiilleri çalışıyor
examples/        intents/intents.json + prompts/intent/classify.v1.md
```

**Tasarım kararları:**

- **Deterministik önce, model sonra.** "refund" kelimesi geçen bir mesaj için modele para ödemek
  ve gecikme eklemek §20'nin uyardığı şey. Bir test modelin hiç çağrılmadığını doğruluyor.
- **Kelime sınırı şart.** Sınır olmadan "iade" ifadesi "iadesiz" kelimesiyle eşleşiyor — yani
  *iade istemediğini söyleyen* bir mesaj iade akışını başlatıyor. Bir test tam bu cümleyle
  yazıldı. Regex kasıtlı olarak yok: config dosyasına gömülü ikinci bir dil olurdu.
- **Eşiğin altı "bilmiyorum"dur, "en yakını" değil.** Model uydurmaya zorlanmıyor; kayıtlı olmayan
  bir intent id'si dönerse de çözümlenemedi sayılıyor. Emin bir cevabın var olmayan bir workflow'u
  göstermesi hâlâ cevap değildir.
- **Model cevabı #3'ün doğrulayıcısından geçiyor.** Sınıflandırma isteği `outputSchema` ile
  gidiyor; şekli bozuk cevap orada düşüyor. İkinci bir "model cevabı düzgün mü" mekanizması
  yazmak yerine elimizdekini kullanmak.
- **Intent listesi prompt'a render ediliyor.** Yeni bir intent eklemek prompt düzenlemesi
  gerektirmiyor — bir test bunu kilitliyor.
- **`ProcessRequest` ayrı bir tip.** Workflow'u boş bırakılmış bir `ExecutionRequest` değil: biri
  "şunu çalıştır" diyor, diğeri "ne çalıştırılmalı?" diye soruyor. Bulanıklaştıran bir tip,
  hangisini yaptığını bilmeyen kod yazmayı kolaylaştırırdı.
- **`FAILED_PRECONDITION`, `INVALID_ARGUMENT` değil.** İstek düzgündü; runtime ne yapacağını
  çıkaramadı. İstemcinin bu farkı görememesi yanlış şeyleri yeniden denemesi demek.
- **Seçim execution'ın değişkenlerinde:** `intent.id`, `resolvedBy`, model kullanıldıysa
  `model`/`promptVersion`/`confidence`. "Bu execution neden koştu?" sorusunun cevabı kendi
  kaydında.

**Sınır korundu:** `IntentResolver` bir `WorkflowId` döndürüyor, başka hiçbir şey — hangi adımdan
başlanacağı, nelerin atlanacağı, nasıl ilerleneceği değil. Bir test bunu açıkça yazıyor
(`answersWithAWorkflowAndNothingElse`). §37'nin tezi burada sınandı ve ayakta.

**Yapılmayan:** çok dilli eşleşmenin ötesinde bir dil algılama, intent başına farklı model,
çözümlemenin kendi trace span'i (execution'dan önce olduğu için span'in ait olacağı trace henüz
yok — değişkenlere yazılıyor).
