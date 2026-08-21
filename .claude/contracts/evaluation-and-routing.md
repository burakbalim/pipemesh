# Evaluation and Model Routing

**Status:** Tamam (2026-08-21)
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §39 (model routing, evaluation), §39.1 (fiyat), §3 (runtime ne bilmez)

## Goal

§39'un iki maddesini kapatmak:

```text
simple task  → cheap model
complex task → reasoning model
private task → local model
```

```text
Workflow → Execution → Evaluation → Quality Score
```

## Evaluation: yeni bir motor kavramı gerektirmiyor

"İyi cevap" ne demek uygulamanın bilgisi. Runtime'ın *ne zaman* çalıştığını bilip *ne yaptığını*
bilmemesi kuralı (§3) tam olarak bunu söylüyor — kalite eşiği koyan bir motor, uygulamanın işini
yapmaya başlar.

Bugün zaten yazılabiliyor: değerlendirme bir capability, eşik bir condition adımı.

```json
{"id": "score", "type": "capability", "capability": "grade_answer",
 "input": "$.answer", "output": "quality", "next": "gate"},
{"id": "gate", "type": "condition", "expression": "$.quality.score < 0.6",
 "onTrue": "ask_human", "onFalse": "deliver"}
```

Bu contract'ın evaluation kısmı bir **kanıt**: yukarıdaki uçtan uca koşan bir test, ve DESIGN'da
neden yeni bir primitive eklenmediğinin yazılması. Eklenmeyen özelliğin gerekçesi de tasarımın
parçası.

## Routing: eksik olan tek şey modelin değişkenden okunabilmesi

Bugün ne var:

- Alias'ın hangi sağlayıcıya gittiği kayıt bilgisi — deployment `models.json`'ı değiştirerek
  yönlendirme yapabiliyor (§12)
- Adım başına fallback modeli var (§18)
- Koşul adımı farklı alias kullanan dallara ayırabiliyor

Ne yok: **adımın modelini bir değişkenden almak.** Uygulama işi sınıflandırıp `$.route.model`'a
`"cheap"` yazsa bile, workflow bunu kullanamıyor; her seçenek için ayrı dal açmak gerekiyor.

```json
{"id": "answer", "type": "llm", "model": "$.route.model",
 "models": ["cheap", "reasoning", "local"], "prompt": "ask", "output": "a", "next": "done"}
```

Karar uygulamada kalıyor — değişkeni o yazdı. Runtime yalnızca yazılanı uyguluyor; "bu iş basit
mi" sorusuna hiçbir yerde cevap vermiyor, çünkü o soru §3'ün dışarıda tuttuğu şeyin ta kendisi.

### Dinamik seçim ilan edilmiş kümeden

`models` zorunlu — `model` bir JSONPath ise. Gerekçesi #16'nın agent adımıyla birebir aynı:
model neyi seçeceğine karar veriyorsa, **neler arasından** seçebileceğini workflow ilan etmeli.
İlan edilmemiş bir alias'a çözülen değer reddediliyor.

İkinci gerekçe §39.1'den: para bütçesi olan bir workflow'un derleme anında fiyatsız modele karşı
korunması, adımın çağırabileceği modellerin **derleme anında bilinebilmesini** gerektiriyor.
Serbest bir JSONPath bunu imkânsız kılardı; ilan edilmiş küme mümkün kılıyor.

## Acceptance Criteria

- [x] `model` düz bir alias olduğunda bugünkü davranış birebir aynı
- [x] `model` bir JSONPath olduğunda değişkenden okunan alias kullanılıyor
- [x] JSONPath kullanan adımda `models` yoksa yükleme anında reddediliyor
- [x] `models` içinde olmayan bir alias'a çözülürse adım başarısız (`llm.model_not_declared`)
- [x] Çözülen yol yoksa/boşsa adım başarısız, sessizce bir varsayılana düşmüyor
- [x] `StepExecutor.models(Step)` ilan edilen kümenin tamamını döndürüyor
- [x] Para bütçesi + ilan edilen kümede fiyatsız model = derleme anında reddediliyor
- [x] Değerlendirme capability + condition ile uçtan uca koşuyor (yeni primitive yok)
- [x] Mevcut 356 test değişmeden geçiyor (toplam 366)

## Split Decision

**Decision:** single-prompt, iki aşama
**Tarih:** 2026-08-21

Küçük bir dilim. Motor değişmiyor: routing tek bir `StepExecutor`'ın içinde, evaluation ise
zaten var olanın kanıtı.

1. **Dinamik model** — `LlmStepExecutor` içinde alias çözümü, `models` ilanı, şema, `models(Step)`
   güncellemesi, derleyicideki fiyat kontrolüyle bağlantısı.
2. **Evaluation kanıtı** — capability + condition ile uçtan uca test, DESIGN'a neden yeni
   primitive eklenmediğinin yazılması.

### Kapsam dışı

- **Agent adımında dinamik model.** Aynı numara oraya da uygulanabilir ama agent'ın modeli tur
  boyunca sabit; değiştirmek turlar arası tutarlılık sorusunu açar. Ayrı iş.
- **Runtime'ın kendi yönlendirme kuralı.** "Girdi 8000 token'dan büyükse pahalı modele geç"
  cazip ve yanlış: runtime'ın işin ne olduğuna dair hüküm vermesi demek. Kural uygulamada,
  sonucu değişkende.
- **Fiyata göre otomatik seçim.** İlan edilen kümeden en ucuzunu seçmek, "yeter mi" sorusuna
  cevap vermeyi gerektiriyor; cevabı olan taraf uygulama.

### Risk points

- **Sessiz varsayılan.** `$.route.model` çözülemezse bir varsayılana düşmek en kolay yol ve en
  kötüsü: workflow'un hangi modeli kullandığı görünmez olur. Çözülemeyen yol adımı düşürmeli.
- **İlan kümesinin unutulması.** JSONPath yazıp `models` yazmamak, hem bütçe kontrolünü hem
  #16'nın ilkesini delerdi; yükleme anında reddedilmeli, koşarken değil.
- **Literal ile yol karışması.** `$` ile başlayan bir alias adı teoride mümkün. Ayrım tek
  kurala bağlanmalı ve yazılmalı: `$.` ile başlıyorsa yoldur.

## Implementation Notes

**Tamamlandı:** 2026-08-21 — 10 yeni test (7 `ModelRoutingTest`, 3 `EvaluationTest`);
toplam 366 Java + 22 Python + 22 TypeScript. `WorkflowExecutor` diff'i boş.

### Eklenmeyen özellik de bir karar

Evaluation için tek satır motor kodu yazılmadı ve bu contract'ın yarısı bunun **gerekçesi**.
`EvaluationTest` capability + condition ile uçtan uca koşuyor: skorlayan bir capability, eşiği
koyan bir condition, zayıf cevabı insana götüren bir approval. Yeni bir primitive ne katardı
sorusunun cevabı "hiçbir şey", ne götürürdü sorusunun cevabı "sınırı".

Test bu iddiayı denetlenebilir tutmak için var. Kırılırsa DESIGN §39.2'deki cümle doğru olmaktan
çıkmış demektir.

### Routing'in gerçekte eksik olan tek parçası

§39'un tablosuna bakınca üç maddeden ikisi zaten çalışıyordu: alias'ın hangi sağlayıcıya gittiği
kayıt bilgisi (§12), adım başına fallback modeli var (§18). Eksik olan, uygulama işi zaten
sınıflandırmışken bunu workflow'a **söyleyememesiydi** — her seçenek için ayrı dal açmak
gerekiyordu.

`"model": "$.route.model"` bunu kapatıyor. Runtime "bu iş basit mi" sorusuna hâlâ hiçbir yerde
cevap vermiyor; değişkeni yazan taraf cevap verdi.

### İlan kümesi iki ayrı sebeple zorunlu

`models` listesi, `model` bir yol olduğunda zorunlu:

1. **#16'nın kuralı.** Seçen taraf yalnızca workflow'un ilan ettikleri arasından seçebilir.
   Agent adımında capability'ler için neyse, burada modeller için o.
2. **#11'in kontrolü.** Para bütçesi olan workflow'un derleme anında fiyatsız modele karşı
   korunması, adımın çağırabileceği modellerin derleme anında **bilinebilmesini** gerektiriyor.
   Serbest bir ifade bunu imkânsız kılardı; ilan edilmiş küme listeye çeviriyor.

İkisi bağımsız gerekçe ve aynı alana çıkıyor — `aMoneyBudgetChecksEveryModelTheStepCouldChoose`
testi ikisinin kesişimini tutuyor.

### `StepExecutor.validate(Step)`

"`model` bir yolsa `models` zorunludur" kuralının şemada yeri yok — şema alanların varlığını ve
tipini söylüyor, iki alan arasındaki ilişkiyi değil (§23.1 dar tutuyor). Derleyicinin bunu
kendi başına bilmesi ise adım tipinin ne demek olduğunu öğrenmesi olurdu.

Bu yüzden yeni bir default SPI metodu: `validate(Step)` derleyicinin kendi para biriminde —
problem listesi — cevap veriyor. `outgoing`, `repeatable`, `models` ile aynı desen: tipi bilen,
tipin kuralını söyler.

### Sessiz varsayılan yok

Çözülemeyen yol adımı düşürüyor (`llm.model_not_declared` değil, açık bir hata mesajıyla).
Bir varsayılana düşmek en kolay yoldu ve hangi modelin koştuğunu görünmez yapardı — alanın var
olma sebebinin tam tersi.

Literal ile yol ayrımı tek kurala bağlı ve yazılı: `$.` ile başlıyorsa yoldur.

### Devralınacak

- **Agent adımında dinamik model.** Aynı numara uygulanabilir, ama agent'ın modeli turlar boyunca
  sabit; değiştirmek turlar arası tutarlılık sorusunu açıyor.
- **Fiyata göre otomatik seçim.** İlan edilen kümeden en ucuzunu seçmek "yeterli mi" sorusunu
  cevaplamayı gerektiriyor; cevabı olan taraf uygulama.
