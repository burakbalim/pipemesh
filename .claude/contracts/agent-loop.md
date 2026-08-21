# Agent Loop

**Status:** Draft
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §9.9 (agent step), §20, §37 (LLM orkestratör değildir), §23

## Goal

Önceden çizilemeyen işler için sınırlı bir döngü: model bir sonucu görüp başka bir capability
çağırmaya karar verebilsin.

§9.9 bunu zaten tasarladı ve kararı verdi: **döngü bir step, motorun bir modu değil.** Bu contract
onu inşa ediyor — ve aynı zamanda *"yeni bir step tipi eklemek `WorkflowExecutor`'a dokunmamalı"*
kuralının en zorlu sınavı. Agent step'i içinde model çağırıyor, capability çağırıyor, döngü
kuruyor; motor bunların hiçbirini öğrenmemeli.

## Contract

```json
{
  "id": "investigate",
  "type": "agent",
  "model": "reasoning",
  "prompt": "research.investigate.v1",
  "capabilities": ["search_docs", "read_page"],
  "maxIterations": 8,
  "output": "findings",
  "next": "summarize"
}
```

Model her turda ya bir capability çağırmak ister ya da cevabını verir:

```json
{"call": {"capability": "search_docs", "input": {"query": "..."}}}
{"answer": {"summary": "...", "sources": [...]}}
```

Bu şekil **#3'ün doğrulayıcısıyla** denetleniyor. Şekli bozuk bir cevap turu harcıyor, akışı
bozmuyor.

## Sınırlar — ve neden her biri var

| Sınır | Sebep |
|---|---|
| Capability listesi step'te **ilan edilir** | Model keşfetmez; erişebileceği küme workflow'un kararı |
| `maxIterations` **zorunlu** | Sınırsız döngü bir workflow değildir |
| Listede olmayan capability çağrısı reddedilir | Fence'in kendisi |
| Grafiği değiştiremez, approval isteyemez | Step biter bitmez deklaratif grafik geri devralır |
| Capability çağrıları **aynı izin kontrolünden** geçer | Agent, #8'in sınırını atlayamamalı |

Son satır kritik: agent step'i capability'leri **doğrudan çağırmıyor**, capability step'iyle
paylaşılan aynı çağırıcıyı kullanıyor. Böylece izin kontrolü, telemetri ve idempotency kuralları
tek yerde kalıyor — ve agent onları atlamak isteseydi bile yolu yok.

## §9.9'dan bir sapma

§9.9 *"her iterasyon bir step-history kaydıdır"* diyordu. Uygulamada step-history tablosu
**motorun ne yaptığını** kaydediyor — bir satır, bir step yürütmesi. Agent'ın iç turları farklı
bir granülerlik; onları satır olarak yazmak, motorun hiç çalıştırmadığı adımları uydurmak olurdu.

Bunun yerine turlar tek satırın `attributes` alanında duruyor: hangi capability'ler çağrıldı, kaç
tur sürdü, ne kadar token harcandı. Tablonun anlamı korunuyor, gözlenebilirlik kaybolmuyor.
DESIGN §9.9 bu gerekçeyle güncellenecek.

## Acceptance Criteria

- [ ] Model bir capability çağırmak isterse çağrılıyor, sonucu bir sonraki tura giriyor
- [ ] Model cevabını verince döngü bitiyor ve cevap `output` değişkenine yazılıyor
- [ ] `maxIterations` aşılırsa step `Failed` oluyor — sessizce son cevapla dönmüyor
- [ ] Listede olmayan bir capability istenirse reddediliyor ve **tur harcanıyor**, akış bozulmuyor
- [ ] Şekli bozuk model cevabı turu harcıyor, execution'ı düşürmüyor
- [ ] Capability çağrıları izin kontrolünden geçiyor: izinsiz principal için agent de reddediliyor
- [ ] Turlar telemetride görünüyor (kaç tur, hangi capability'ler)
- [ ] `maxIterations` yazılmamışsa step yükleme anında reddediliyor (#16b'nin şeması)
- [ ] `WorkflowExecutor` değişmiyor — tek satır bile
- [ ] Mevcut 267 test değişmeden geçiyor

## Split Decision

**Decision:** single-prompt
**Tarih:** 2026-08-21

**Reasoning:** Bir ortak çağırıcı çıkarımı, bir yeni executor, bir şema. Sıralı bağımlı.

### Build order

1. `CapabilityInvoker` — `CapabilityStepExecutor`'dan çıkarılan ortak çağrı yolu (izin kontrolü
   dahil). Mevcut davranış değişmemeli.
2. `AgentStepExecutor` — döngü, model sözleşmesi, sınırlar.
3. Şema beyanı + telemetri.

### Risk points

- **Fence'in sızması.** Agent'ın capability'lere kendi yolundan ulaşması, #8'i baypas etmek
  demek olurdu. Ortak çağırıcı bunu yapısal olarak imkânsız kılmalı — bir test izinsiz principal
  ile agent'ın da reddedildiğini göstermeli.
- **Sessiz tükenme.** `maxIterations` dolduğunda "elimizdeki en iyi cevabı" döndürmek, modelin
  bitmediği bir işi bitmiş göstermek olur. Açıkça `Failed`.
- **Motorun kirlenmesi.** Bu contract'ın asıl testi: `WorkflowExecutor.java` diff'te hiç
  görünmemeli.

## Implementation Notes

### Tamamlandı (2026-08-21) ✅

**276 Java testi yeşil** (202 core + 14 Postgres + 16 provider + 9 MCP + 10 OTel + 25 gRPC).
Mevcut testlerin hepsi değişmeden geçti.

```
core/capability/      CapabilityInvoker (CapabilityStepExecutor'dan çıkarıldı)
core/execution/step/  AgentStepExecutor
core/execution/       StepAttributes.AGENT_TURNS, AGENT_HISTORY
```

**Contract'ın asıl testi geçti: `WorkflowExecutor` diff'te hiç görünmüyor.** İçinde model çağıran,
capability çağıran, döngü kuran bir step tipi eklendi ve motor tek satır değişmedi. §46'nın
"abstraction sızıyor mu?" sorusuna verilen en sert cevap bu.

**Kararlar:**

- **Ortak çağırıcı, fence'i yapısal kılıyor.** Agent capability'leri doğrudan çağırmıyor;
  capability step'iyle aynı `CapabilityInvoker`'ı kullanıyor. İzin kontrolü, idempotency ve
  telemetri tek yerde — agent onları atlamak isteseydi bile yolu yok. Bir test izinsiz principal
  ile agent'ın da reddedildiğini gösteriyor.
- **Model sözleşmesi structured output, vendor tool-calling API'si değil.** `{"call": …}` ya da
  `{"answer": …}`, #3'ün doğrulayıcısıyla denetleniyor. Böylece runtime'ın konuşabildiği her
  sağlayıcıda çalışıyor — model sınırının SDK değil protokol konuşmasıyla aynı gerekçe (§13).
- **Bozuk ya da reddedilen tur, tur harcıyor; akışı bozmuyor.** Döngü sınırlı olduğu için şekli
  takip edemeyen bir model kendiliğinden tükeniyor.
- **Tükenme `Failed`.** "Elimizdeki en iyi cevabı" döndürmek, modelin bitirmediği işi bitmiş
  göstermek olurdu.
- **Model ne denediğini görüyor.** Her turda `$.agent.history` ve `$.agent.capabilities`
  prompt'a render ediliyor; olmasaydı her tur ilkini tekrarlardı.

**§9.9'dan sapma ve gerekçesi (DESIGN.md güncellendi):** §9.9 "her iterasyon bir step-history
kaydıdır" diyordu. Uygulamada o tablo **motorun ne yaptığını** kaydediyor; motorun hiç
çalıştırmadığı adımlar için satır uydurmak tablonun anlamını bozardı. Turlar tek satırın
`attributes` alanında: hangi capability, ne sonuç, kaç tur, ne kadar token.

**Yapılmayan:** paralel tool çağrısı (bir turda bir çağrı), agent'ın approval istemesi, turlar
arası model değişimi, `maxIterations`'a ek olarak token/maliyet bütçesi (#11'e ait).
