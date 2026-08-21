# Parallel and Transform

**Status:** Draft
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §9.5 (parallel), §9.6 (transform), §29 (concurrency model)

## Goal

Birbirine bağlı olmayan işlerin aynı anda yapılabilmesi, ve sonuçların deterministik biçimde
birleştirilebilmesi.

```json
{ "type": "parallel", "branches": ["search_venues", "load_preferences"], "join": "recommend" }
```

## Önce dürüst bir soru: bu bir step mi, motorun bir değişikliği mi?

Şimdiye kadar her yeni step tipi motora dokunmadan eklendi. Paralellik ilk bakışta o kuralı
kırıyor gibi: bir execution'ın **tek bir konumu** var (`currentStep`), paralellik ise birden çok
konum demek.

Üç yol düşünüldü:

**(A) Dal başına alt-execution.** Her dal kendi satırı, parent referansı, join'de birleşme.
Dayanıklı ve dağıtık dostu; ama execution yaşam döngüsünü ikiye katlıyor, süpürücüyü, resume'u
ve izolasyonu yeniden düşündürüyor.

**(B) Motorun çok-konumlu hâle gelmesi.** `currentStep` bir kümeye dönüşür. "Doğru" workflow
motoru tasarımı — ama bizim dayanıklılık modelimizle çarpışıyor: **tek satır, tek versiyon, tek
yazar.** İki dal aynı satırı ilerletmeye çalıştığında her adımda optimistic locking çatışması
olur. Çözmek için dal başına satır gerekir, yani (A).

**(C) Dallar step'in içinde koşar.** Paralel step, dallarını virtual thread'lerde yürütür ve
sonunda **tek bir `Continue`** döndürür — tek advance, tek satır yazımı.

**Seçilen: (C).** Gerekçe: pahalı olan kısım I/O (model ve capability çağrıları), kalıcılık ise
ucuz. Dalların *işi* eşzamanlı olurken *kalıcılığın* sıralı kalması, dayanıklılık modelini
bozmadan gerçek hızlanma veriyor. Ve bu, agent step'iyle aynı desen — bir kod tabanında aynı
şeklin ikinci kez doğru görünmesi, doğru şekil olduğunun işaretidir.

**Bedeli açıkça:** dal içindeki adımlar kendi history satırlarını ve kendi dayanıklılıklarını
almıyor. Paralel step'in ortasında çöken bir process, kurtarmada **tüm paralel step'i** yeniden
koşturur. Bu yüzden dal içinde `idempotent: false` bir capability varsa, kurtarma o execution'ı
durdurmalı — `repeatable` zaten bunu yapıyor, ama paralel step'in de dallarını sorması gerekiyor.

## Sınırlar — ve neden

| Sınır | Sebep |
|---|---|
| Dal askıya alınamaz (approval, wait) | Askıya alma execution'ın konumunu değiştirir; step'in içinde böyle bir konum yok. `parallel.branch_suspended` |
| Dal join'e ulaşmalı | Grafiğin geri kalanına dağılan bir dal, paralelliği değil karmaşayı ifade eder. `parallel.branch_escaped` |
| İki dal aynı değişkeni yazamaz | Sessiz "son yazan kazanır" hata ayıklanması imkânsız bir davranıştır. `parallel.conflicting_writes` |
| Bir dal düşerse step düşer | Kısmi sonuçla devam etmek, workflow'un görmediği bir eksikliği gizler. Step'in kendi `onFailure` politikası zaten var |

## Transform (§9.6)

Deterministik birleştirme — model çağırmadan:

```json
{ "type": "transform", "operation": "merge", "inputs": ["$.venues", "$.preferences"],
  "output": "context", "next": "recommend" }
```

Operasyonlar bilinçli olarak az: `merge` (obje birleştirme), `pick` (tek yol), `collect` (diziye
toplama). İfade dilinde olduğu gibi, daha fazlasına ihtiyaç duyan bir dönüşüm iş kuralıdır ve
capability'ye aittir (§23.1).

## Acceptance Criteria

- [ ] İki dal aynı anda koşuyor ve ikisinin çıktısı da join sonrası görünüyor
- [ ] Eşzamanlılık gerçek: iki yavaş dal, toplamlarından kısa sürede bitiyor
- [ ] Dal içindeki çok adımlı bir yol join'e kadar yürüyor
- [ ] Bir dal düşerse step düşüyor ve hangi dalın düştüğü görünüyor
- [ ] Askıya alan bir dal net bir hatayla reddediliyor
- [ ] Join'e ulaşmayan bir dal net bir hatayla reddediliyor
- [ ] İki dal aynı değişkeni yazarsa çakışma bildiriliyor
- [ ] Paralel step, dallarındaki adımların hiçbiri yeniden koşturulamaz değilse kurtarılabiliyor;
      biri bile değilse kurtarma duruyor
- [ ] Dal adımları telemetride görünüyor (hangi dal, kaç adım)
- [ ] `transform`: merge, pick, collect
- [ ] **`WorkflowExecutor` değişmiyor**
- [ ] Mevcut 276 test değişmeden geçiyor

## Split Decision

**Decision:** single-prompt, iki aşama
**Tarih:** 2026-08-21

1. **Parallel** — dal yürütücüsü, eşzamanlılık, sınırlar, kurtarma sorusu
2. **Transform** — üç operasyon

**Reasoning:** İkisi aynı contract'ta çünkü §9.5 ve §9.6 birlikte anlamlı (paralel dallar bir
transform'da birleşir), ama bağımsız yazılabilirler. Paralel önce: zor olan o.

### Risk points

- **Değişken izolasyonu.** Her dal kendi context kopyasını görmeli; biri diğerinin yarım
  yazdığını okursa sonuç sıralamaya bağlı olur ve testler yalancı biçimde geçer.
- **Thread güvenliği.** `ExecutionContext` immutable, `StepResult` immutable — ama dal
  sonuçlarının toplandığı yapı eşzamanlı yazılıyor.
- **Kurtarma sorusunun unutulması.** Paralel step'in `repeatable`'ı dallarını sormazsa,
  içinde ödeme alan bir dal olan bir step sessizce iki kez koşabilir. Bu, contract'ın en
  tehlikeli detayı.

## Implementation Notes

### Tamamlandı (2026-08-21) ✅

**292 Java testi yeşil** (218 core + 14 Postgres + 16 provider + 9 MCP + 10 OTel + 25 gRPC).
Mevcut testlerin hepsi değişmeden geçti. **`WorkflowExecutor` yine diff'te yok.**

```
core/execution/step/  BranchRun, ParallelStepExecutor, TransformStepExecutor
core/execution/       StepExecutor.repeatable(Step, ExecutionContext)
```

**Seçilen tasarım (C) işe yaradı ve bedeli belgeli:** dallar step'in içinde, virtual thread'lerde
koşuyor; sonunda tek `Continue`, tek advance, tek satır yazımı. Bir test iki 150ms'lik dalın
250ms'nin altında bittiğini doğruluyor — eşzamanlılık gerçek, kalıcılık sıralı.

**Kararlar:**

- **Dal ne yazdığını verir, bitirdiği context'i değil.** İkisi aynı değişkenlerden başlıyor;
  tam kopya döndürselerdi birleştirme, önce biteni sessizce ezerdi.
- **Çakışan yazım hata.** İki dal aynı değişkeni yazarsa `parallel.conflicting_writes`. "Son yazan
  kazanır" hangi dalın önce bittiğine bağlıdır — yani kimsenin tekrar üretemeyeceği bir bug.
- **Askıya alan dal reddediliyor.** Askıya alma execution'ın konumunu değiştirir; dalın kendi
  konumu yok. Terminal'e giden dal da reddediliyor: kardeşleri hâlâ çalışırken execution'ı
  bitirirdi.
- **Bir dal düşerse step düşer.** Kısmi sonuçla devam etmek, workflow'un görmediği bir eksikliği
  gizler; step'in kendi `onFailure` politikası zaten ne olacağına karar veriyor.
- **`repeatable(Step, ExecutionContext)`** — SPI'a bağlam eklendi. "Bu tekrarlanabilir mi?" sorusu
  bir *execution içindeki* step hakkında; başka step'leri çalıştıran bir step onları hangi
  workflow'da arayacağını bilmeden bulamaz.

**Contract'ın "en tehlikeli detayı" test edildi:** paralel step'in kurtarma sorusu dallarını
soruyor. Dalın **ikinci** adımında yeniden koşturulamaz bir şey varsa bile kurtarma duruyor —
üç test bunu kilitliyor. Bu olmasaydı, içinde ödeme alan bir dal bulunan bir paralel step, çöküş
sonrası sessizce iki kez koşabilirdi.

**Transform:** `merge` (sonraki kazanır), `pick` (ilk var olan), `collect` (sırayla, yok olanlar
`null` olarak). Yok olanı sessizce atlamak, sonrasındaki her şeyi kaydırırdı.

**Yapılmayan:** dal başına history satırı ve dal başına dayanıklılık (tasarım (A) gerekir),
dinamik dal sayısı (dallar statik olarak ilan edilir), `join` koşulu ("hepsi" dışında bir
tamamlanma kuralı yok), dal içinde askıya alma.
