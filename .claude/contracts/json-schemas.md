# Workflow Schemas

**Status:** Draft
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §23.1 (inline kod yasağı), §8 (workflow tanımı), §27/§46 (genişletilebilirlik)

## Goal

"Workflow tanımı çalıştırılabilir kod taşımaz" kuralını **şema seviyesinde** zorlamak.

Bugün doğrulama kodda: `WorkflowDefinitionReader` alanları okuyor, `WorkflowCompiler` grafiği
denetliyor. Gövdesinde kod taşıyan bir step (`{"type":"code","code":"..."}`) reddediliyor —
ama yalnızca **hiçbir executor o tipi sahiplenmediği için.** Tanınan bir step tipine fazladan bir
alan eklemek (`{"type":"condition","expression":"...","code":"rm -rf /"}`) sessizce kabul ediliyor.

Walking skeleton contract'ında bu kriter ⚠️ **yapılmadı** olarak duruyor. CLAUDE.md ise
`additionalProperties: false` diyor. Aradaki fark bu contract'ın işi.

## Tasarımın zor kısmı

Kapalı bir şema ile açık bir step tipi kümesi çelişiyor gibi görünüyor:

- §46: yeni bir step tipi eklemek core'u değiştirmemeli
- §23.1: workflow tanımı bilinmeyen alan taşımamalı

Çözüm ikisini de koruyor: **her step tipi kendi config şemasını beyan eder.** Ortak alanları
(`id`, `type`, `retry`, `timeout`, `onFailure`) core bilir; tipe özgü alanları o tipi sahiplenen
executor bilir. Doğrulayıcı ikisini birleştirir.

```text
workflow.schema.json          ortak gövde: id, version, entry, steps, politika
        +
StepExecutor.configSchema()   o tipin kendi alanları
        =
bu step için kapalı şema
```

Böylece yeni bir step tipi eklemek hâlâ "yeni `StepExecutor` + şema" — walking skeleton
contract'ında yazan cümlenin ikinci yarısı nihayet gerçek oluyor.

## Doğrulayıcının büyümesi

`JsonSchemaValidator` şu an `additionalProperties`'i bilmiyor. Bu contract onu ekliyor — ve
**yalnızca onu.** `$ref`, `oneOf`, `pattern` hâlâ yok; gerekçe değişmedi: bunlar bir kütüphane
gerektirir ve runtime'ın taşıdığı her bağımlılığı embedder devralır.

## Acceptance Criteria

- [ ] Tanınan bir step tipine bilinmeyen alan eklemek reddediliyor (`code`, `script`, ne olursa)
- [ ] Hata mesajı **hangi alanın** reddedildiğini söylüyor
- [ ] Bilinmeyen bir step tipi hâlâ reddediliyor (bugünkü davranış korunuyor)
- [ ] Ortak alanlar (`retry`, `timeout`, `onFailure`) her step tipinde geçerli
- [ ] Workflow gövdesinde bilinmeyen üst düzey alan reddediliyor
- [ ] Şema beyan etmeyen bir step tipi çalışmaya devam ediyor (kısıtsız) — geriye dönük uyumluluk
- [ ] `examples/` altındaki tüm workflow'lar doğrulamadan geçiyor
- [ ] Doğrulama **yükleme anında**, çalışma anında değil: bozuk bir workflow hiç kaydedilmiyor
- [ ] `schemas/workflow.schema.json` diskte var ve ortak gövdeyi tanımlıyor
- [ ] Mevcut 233 test değişmeden geçiyor

## Split Decision

**Decision:** single-prompt
**Tarih:** 2026-08-21

**Reasoning:** Bir doğrulayıcı eklemesi, bir şema dosyası, beş executor'a birer beyan ve bir
kancalama noktası. Paralelleştirilecek parça yok.

### Build order

1. `JsonSchemaValidator`'a `additionalProperties: false`
2. `StepExecutor.configSchema()` + beş yerleşik executor'ın beyanı
3. `WorkflowValidator` — ortak gövde + tipe özgü şema birleşimi
4. `InMemoryWorkflowRegistry.register`'da kancalama (yükleme anında)

### Risk points

- **Fazla kapatmak.** Ortak alanları eksik listelemek, bugün çalışan workflow'ları kırar.
  Mevcut testler ve `examples/` bu riskin bekçisi.
- **Executor'a şema beyan ettirmenin bedeli.** Beyan etmeyen bir executor kısıtsız kalmalı;
  aksi halde üçüncü taraf bir step tipi ekleyen kişi, şema yazmadan hiçbir şey çalıştıramaz.
  Varsayılan "kısıt yok" olmalı, "hiçbir şeye izin yok" değil.

## Implementation Notes

### Tamamlandı (2026-08-21) ✅

**245 Java testi yeşil** (178 core + 13 Postgres + 16 provider + 9 MCP + 10 OTel + 19 gRPC).
Mevcut testlerin hepsi değişmeden geçti.

```
core/schema/     Schemas, WorkflowValidator, WorkflowShapeException,
                 JsonSchemaValidator'a additionalProperties
core/resources/  workflow.schema.json, step-common.schema.json
core/execution/  StepExecutor.configSchema()
```

**Tasarımın çözdüğü çelişki:** kapalı bir şema ile açık bir step tipi kümesi çelişiyor gibi
görünüyordu. Çözüm, kapıyı her tipin kendisinin kapatması: ortak alanları
(`id`, `retry`, `timeout`, `onFailure`) core biliyor, tipe özgü alanları o tipi sahiplenen
executor beyan ediyor, doğrulayıcı ikisini birleştiriyor. Walking skeleton contract'ındaki
*"yeni step tipi = yeni `StepExecutor` + şema kaydı"* cümlesinin ikinci yarısı nihayet gerçek.

**Kararlar:**

- **Beyan etmeyen executor kısıtsız kalıyor.** Varsayılan "kısıt yok", "hiçbir şeye izin yok"
  değil — aksi halde üçüncü taraf bir step tipi ekleyen kişi, şema yazmadan hiçbir şey
  çalıştıramazdı. Bir test bunu kilitliyor.
- **`additionalProperties` yalnızca `false` biçiminde destekleniyor.** Ek alanların neye
  benzeyeceğini *tarif eden* bir şema başka bir özellik; bu, bir şekli **kapatmak** için var.
  `$ref`, `oneOf`, `pattern` hâlâ yok ve gerekçe değişmedi.
- **Doğrulama yükleme anında.** Bozuk bir workflow hiç kaydedilmiyor; execution'ın ortasında
  keşfedilmiyor.
- **Sahiplenilmemiş step tipi hakkında şema susuyor.** Onu compiler bildiriyor ve JSON hakkında
  değil, tip hakkında cümlelerle bildiriyor. İki mekanizmanın aynı şeyi iki farklı dille
  söylemesi kafa karıştırırdı.
- **`register(JsonNode)` ham kaynağı alıyor.** `WorkflowDefinition` anlamadığı alanları çoktan
  düşürmüş oluyor — ve reddedilecek olan tam da o alanlar.

**Kapanan iddia:** walking-skeleton contract'ında ⚠️ "yapılmadı" duran kriter artık sağlanıyor.
`{"type":"condition","expression":"...","code":"rm -rf /"}` yükleme anında reddediliyor ve hata
mesajı hangi alanı reddettiğini söylüyor.

**Yapılmayan:** `capability.schema.json` ve `models.schema.json`. Capability kayıtları
`ConfigRepository` içinde okunurken doğrulanıyor ama şema dosyası yok; workflow'lar kadar
sık elle yazılmıyorlar ve inline kod riski onlarda yok.
