# Cost Budgets

**Status:** Tamam (2026-08-21)
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §39 (cost policies), §12 (model registry), §17 (politika ≠ graf kenarı)

## Goal

Bir execution'ın ne harcadığı bilinsin ve bir sınırı aşamasın:

```json
{ "id": "research", "version": "1.0", "entry": "plan",
  "budget": { "maxCost": "2.50", "maxModelCalls": 20, "maxTokens": 200000 },
  "steps": [ ... ] }
```

## Bu kod tabanında tekrarlayan karar

Adım bütçesi, agent tur sınırı, worker deadline'ı, wait timeout'u — hepsi aynı cümlenin
farklı yüzleri: **sınırsız olan şey er ya da geç kimsenin fark etmediği bir şeye dönüşür.**
Para bu listenin eksik üyesi. Döngüye giren bir agent bugün tur sınırında duruyor; on tur
boyunca uzun bağlam gönderen bir workflow'u durduran hiçbir şey yok.

## Fiyat kayıt bilgisidir, workflow içeriği değil

```json
// models.json
{ "alias": "reasoning", "protocol": "openai-compatible",
  "settings": { "model": "gpt-5", "inputPricePerMillion": "1.25",
                "outputPricePerMillion": "10.00" } }
```

Workflow parayı hiç anmıyor — capability'nin taşıma biçimini anmadığı gibi (§9.8). Model
değişince fiyat kaydı değişiyor, hiçbir workflow'a dokunulmuyor.

**Fiyatı olmayan model sıfır maliyetli sayılmıyor.** Yerel bir model gerçekten bedava
olabilir, ama "fiyatı yazmayı unuttum" ile "bu bedava" aynı şeye benzemekten çıkmalı: fiyatsız
model harcamayı `unpriced` olarak sayıyor ve `maxCost` bütçesi olan bir workflow fiyatsız bir
modele denk gelirse **reddediliyor**, sessizce sınırsız harcamıyor.

## Para tam sayı

`costMicros` — `long`, milyonda bir birim. Kayan noktalı para toplanınca kayar; burada toplanan
şey tam olarak para.

## Harcama execution durumudur

Kira (#10) execution durumu değildi çünkü operasyonel bir olguydu. Harcama tam tersi: kararı
execution'ın kendisi veriyor, restart'tan sağ çıkması gerekiyor ve "bu execution ne harcadı"
sorusu execution hakkında. Dolayısıyla kayıtta duruyor.

**Motor değişiyor** — ve bu sefer bunu baştan yazıyorum, sonradan düzeltmiyorum: bütçe
kontrolü adım yürütmenin içinde, mevcut adım bütçesinin yanında. Politikanın graf kenarına
dönüşmemesi (§17) tam olarak bunu gerektiriyor.

## Acceptance Criteria

- [x] LLM adımı harcamayı (çağrı, token, maliyet) kayda ekliyor
- [x] Harcama restart'ı geçiyor; ikinci process aynı toplamı görüyor
- [x] `maxCost` aşılınca execution `FAILED` ve sebep `execution.budget_exhausted`
- [x] `maxModelCalls` aşılınca aynı şey
- [x] `maxTokens` aşılınca aynı şey
- [x] Bütçe yazılmamışsa sınır yok, hiçbir şey değişmiyor
- [x] Sınıra tam denk gelen execution başarısız **olmuyor** (aşmak ile denk gelmek farklı)
- [x] Fiyatı olmayan model + `maxCost` bütçesi = yükleme anında reddediliyor
- [x] Fiyatı olmayan model + bütçesiz workflow = koşuyor, harcama `unpriced` sayılıyor
- [x] Maliyet tam sayı aritmetiğiyle; hiçbir yerde `double` yok
- [x] Harcama snapshot'ta görünüyor (kira'nın aksine — bu execution hakkında bir olgu)
- [x] Bütçe aşımı adımın ortasında değil, **bir sonraki adımdan önce** yakalanıyor
- [x] Mevcut 343 test değişmeden geçiyor (toplam 356)

## Split Decision

**Decision:** single-prompt, üç aşama
**Tarih:** 2026-08-21

Contract net ve tek konulu. Katmanlar (fiyatlandırma, muhasebe, zorlama) sıralı: muhasebe
fiyatlandırmasız, zorlama muhasebesiz test edilemez.

1. **Para ve fiyat** — `Money` (micros, tam sayı), `ModelPrice`, `models.json`'a opsiyonel
   fiyat alanları. Tek başına test edilebilir, hiçbir şeye dokunmuyor.
2. **Muhasebe** — `Spend` kaydı, `ExecutionRecord`'a eklenmesi, migration, adım sonrası
   toplama, Postgres restart testi.
3. **Zorlama** — `CostBudget`, workflow şemasında `budget`, motorda bir sonraki adımdan önce
   kontrol, fiyatsız model + `maxCost` reddi.

### Neden #11'in tamamı değil

Index'teki satır "cost tracking, evaluation, model routing" diyordu. Üçü tek dilim değil:

- **Evaluation** (kalite skoru) runtime'ın vereceği bir karar değil. "İyi" ne demek
  uygulamanın bilgisi — runtime'ın *ne zaman* çalıştığını bilip *ne yaptığını* bilmemesi
  kuralının (§3) tam da örneği. Doğru şekli muhtemelen bir capability, yeni bir motor kavramı
  değil; bu ayrı bir contract'ta konuşulmalı.
- **Model routing** ("basit iş → ucuz model") fiyat bilgisine dayanıyor, yani bu dilim onun
  ön koşulu. Yönlendirme kararının nerede verildiği — workflow mu, kayıt mı, model mi —
  başlı başına bir tasarım sorusu.

Bu yüzden index'te #11 bu dilim, #11b evaluation-and-routing olarak ayrılıyor.

### Kapsam dışı

- **Organizasyon seviyesinde bütçe.** "Bu kiracı bu ay 500 dolar harcayabilir" farklı bir
  soru: execution'lar arası, kalıcı, sıfırlanma dönemi olan bir sayaç. Execution bütçesi onun
  ön koşulu değil, komşusu.
- **Bütçe dolduğunda alternatif rota.** `onBudgetExhausted` ile ucuz modele düşmek cazip ama
  §17'nin fallback'i zaten var; ikisini birleştirmek ayrı bir iş.
- **Gerçek zamanlı maliyet akışı.** Harcama adım bitince kayda yazılıyor; akan bir yanıtın
  ortasında değil.

### Risk points

- **Fiyat değişince geçmiş değişmemeli.** Maliyet adım koştuğu anda hesaplanıp yazılıyor;
  sonradan fiyat listesinden türetilirse dünkü execution'ın maliyeti bugün başka görünür.
- **Sınıra denk gelmek ile aşmak.** Adım bütçesinde bu tam olarak bir hataydı ve düzeltilmişti
  ("a workflow whose last step lands exactly on the limit has not overrun anything"). Aynı
  hata para tarafında tekrar edilebilir; testi açıkça var.
- **Kontrolün yeri.** Adımın ortasında bütçe bitince adımı yarıda kesmek, sağlayıcıya gitmiş
  bir çağrıyı boşa harcamak demek. Kontrol bir sonraki adımdan önce; harcanan zaten harcanmış.
- **`ExecutionRecord` büyüyor.** Bugün 12 bileşen. Harcama tek bir JSONB alan olarak giriyor
  (`variables` ve `principal` gibi), dört ayrı sütun olarak değil.

## Implementation Notes

**Tamamlandı:** 2026-08-21 — 13 yeni test (11 `CostBudgetTest`, 2 `DurableSpendTest`);
toplam 356 Java + 22 Python + 22 TypeScript.

### Sessizce düşen alan — bu dilimin asıl bulgusu

`Spend`, `ExecutionRecord`'a eklendikten sonra her şey derlendi ve **harcama sıfır kaldı**.
Sebep: geriye uyum için bıraktığım eski imzalı constructor. `InMemoryStateStore.stamped()`
kaydı alan alan yeniden kuruyordu; yeni alanı yazmadığı için eski constructor'a düşüyor,
`Spend.NOTHING` alıyor ve derleyici hiçbir şey demiyordu. Aynı tuzak `RecoverySweeper`'da da
vardı.

Testler yakaladı, ama bunun tekrar etmesini engelleyen şey test değil: alan alan yeniden inşa
kaldırıldı. `ExecutionRecord` artık üç wither veriyor — `withSpend`, `movedTo`, `stamped` — ve
üç çağıran da onları kullanıyor. On dördüncü alan eklendiğinde sessizce düşecek yer kalmadı.

### Para tam sayı, ve yalnızca sonda yuvarlanıyor

`Money` bir `long` micros. `ModelPrice.costOf` `BigDecimal` ile hesaplayıp **bir kez** sonda
yuvarlıyor; adım adım yuvarlamak uzun bir koşuda birikirdi. `2.50` hiçbir yerde `double`
olmuyor — ikili kayan noktada temsil edilemiyor ve hatası sistematik.

### Fiyatsız model bedava değil

`Spend.unpricedCalls` ayrı bir sayaç. Fiyatlı ve fiyatsız çağrıyı toplamak, tüm maliyet gibi
okunan ama olmayan bir sayı üretirdi. Ve `maxCost` bütçesi olan bir workflow, fiyatı
kaydedilmemiş bir modele denk gelirse **derleme anında** reddediliyor: göremediği harcamayı
durduramayan bir bütçe, tam da var olma sebebi olan koşuları geçirir.

Bu kontrol motorun adım içeriğini okumasını gerektirmiyor — `StepExecutor.models(Step)` eklendi
(`outgoing(Step)` ile aynı gerekçe: tipi bilen, tipin alanlarını okur). `llm` ve `agent`
uyguluyor, diğerleri boş liste döndürüyor.

### Kontrolün yeri

Bütçe bir sonraki adımdan **önce** bakılıyor, adımın ortasında değil. Sağlayıcıya gitmiş bir
çağrı zaten ödendi; onu yarıda kesmek parayı kurtarmıyor, sadece karşılığını çöpe atıyor.

Aynı sebeple `maxCost: 0.02` testinde execution ikinci çağrıdan **sonra** duruyor: ikinci çağrı
bütçeyi aştı, üçüncüsü hiç yapılmadı. Aşımı önceden kestirmek, adımın ne harcayacağını önceden
bilmeyi gerektirirdi — bilinmiyor.

### Sınıra denk gelmek ≠ aşmak

Adım bütçesinde bu bir kez hata olmuştu ("a workflow whose last step lands exactly on the limit
has not overrun anything"). `CostBudget.exceededBy` katı `>` kullanıyor ve testi açık:
`maxModelCalls: 3` ile üç çağrı `COMPLETED`.

### Harcama ile kira arasındaki fark

#10'da kira `ExecutionRecord`'a girmemişti çünkü operasyonel bir olguydu. Harcama tam tersi:
kararı execution'ın kendisi veriyor, restart'tan sağ çıkması gerekiyor, ve soru execution
hakkında. Bu yüzden kayıtta, snapshot'ta, tek bir JSONB sütunda (`variables` ve `principal`
gibi) — dört ayrı sütun olarak değil.

### Motor değiştiği yerler

Bu sefer baştan yazılmıştı, sonradan düzeltilmedi: `drive()`'a bir sonraki adımdan önce bütçe
kontrolü, `write()`'a harcamanın adımla **aynı transaction'da** eklenmesi. İkincisi §15'in
kuralı — ayırmak, çökme anında parayı ya kaybetmek ya iki kez saymak olurdu.

Yolda bir tekrar da temizlendi: `exhausted()` ile `overspent()` aynı şeyi yapıyordu, ikisi de
`engineFailure(record, code, reason)` üzerinden geçiyor.

### Devralınacak

- **Organizasyon seviyesinde bütçe.** "Bu kiracı bu ay 500 dolar harcayabilir" — execution'lar
  arası, dönemsel bir sayaç. Komşu bir iş, ön koşul değil.
- **Bütçe dolunca ucuz modele düşmek.** §17'nin fallback'i zaten var; birleştirmek ayrı bir iş.
- **#11b evaluation ve model routing.** Routing bu dilimin fiyat bilgisine dayanıyor.
