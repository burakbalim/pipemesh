# Workflow Versioning

**Status:** Tamam (2026-08-21)
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §24 (versiyonlama, reprodüksiyon), §25 (derleme)

## Goal

Bir execution, **başladığı workflow sürümünde** bitsin. Bugün bitmiyor.

## Bugünkü boşluk

`ExecutionRecord` `workflowVersion` alanını taşıyor, snapshot'ta ve telemetride görünüyor —
ama hiçbir yerde *kullanılmıyor*. Kayıt defteri yalnızca id ile anahtarlanıyor:

```java
// InMemoryWorkflowRegistry
graphs.put(definition.id(), graph);          // v2 kaydı v1'i siliyor

// DefaultWorkflowRuntime:126 — resume
workflows.find(record.workflowId())          // kayıttaki sürüm okunmuyor
```

Sonuç, #1 ve #7'nin uzun bekleyişleriyle doğrudan çelişiyor: dün onaya düşen bir execution,
bu sabahki deploy'dan sonra **yeni grafikte** devam ediyor. En iyi hâlde `currentStep` artık
yok ve execution patlıyor; en kötü hâlde adım hâlâ var ama başka bir şey yapıyor ve kimse
fark etmiyor. §24'ün söz verdiği "reprodüksiyon" bu hâliyle yazılı bir niyet, davranış değil.

## Karar: sürüm kimliğin parçası

```text
kayıt:    (id, version) → graph          — v2 kaydı v1'i yerinden etmiyor
başlatma: version verilmemişse en yeni; verilmişse tam olarak o
devam:    her zaman kayıttaki sürüm      — tercih değil, kural
```

Askıya alınmış bir execution'ın hangi grafikte devam edeceği bir *tercih* olamaz; çünkü tercihi
yapacak olan, execution'ın nerede durduğunu bilmeyen taraf.

### "En yeni" ne demek

Sürümler dizgi (`"1.0"`, `"2.1"`, `"10.0"`). Dizgi olarak sıralamak tuzak: `"10" < "9"`.
Bu yüzden sürüm dili **dar**: noktayla ayrılmış sayı öbekleri, sayı olarak karşılaştırılıyor.
Sayı olmayan bir öbek kayıt anında reddediliyor — sonradan sıralanamayan bir sürüm, sessizce
yanlış grafiği seçmek demek. (Bu kod tabanındaki diğer dar diller gibi: koşul ifadeleri,
şema alt kümesi, transform.)

### Sürüm değişmez

Aynı `(id, version)` farklı içerikle ikinci kez kaydedilirse reddediliyor. Aynı içerikle
kaydedilirse hiçbir şey olmuyor — yeniden başlatmada aynı dizini okumak hata olmamalı.
Değişmezlik olmadan sürüm numarası bir etiket, kimlik değil; "1.2'de koştu" cümlesi de bir
şey anlatmıyor.

## Acceptance Criteria

- [x] Aynı id'nin iki sürümü yan yana kayıtlı kalıyor; v2 kaydı v1'i düşürmüyor
- [x] Sürüm verilmeden başlatma en yeni sürümü seçiyor ve seçtiğini kayda yazıyor
- [x] Sürüm verilerek başlatma tam olarak o sürümde koşuyor
- [x] Kayıtlı olmayan bir sürümle başlatma, id'yi değil **sürümü** söyleyen bir hatayla reddediliyor
- [x] v1'de askıya alınmış execution, v2 kaydedildikten sonra **v1'de** devam ediyor
- [x] `"10.0"` `"9.0"`'dan yeni sayılıyor (dizgi sıralaması değil)
- [x] Sayı olmayan sürüm kayıt anında reddediliyor
- [x] Aynı `(id, version)` farklı içerikle yeniden kaydedilemiyor; aynı içerikle sorunsuz
- [x] Restart sonrası: yeni process v2'yi de kaydetse, v1'deki execution v1'de bitiyor
- [x] `StartExecution` proto'sunda opsiyonel `workflow_version`; iki SDK'da da geçiliyor
- [x] `WorkflowExecutor` değişmiyor
- [x] Mevcut 307 test değişmeden geçiyor

## Split Decision

**Decision:** single-prompt, üç aşama
**Tarih:** 2026-08-21

Katman sayısı yüksek görünüyor (core, grpc, proto, iki SDK) ama **dikey olarak bölünemiyor**:
hepsi tek bir kavramın — sürümün kimliğin parçası olmasının — aynı taraftan görünüşleri.
SDK dilimi core dilimi bitmeden anlamlı bir şey test edemez, bu yüzden paralel ajan bölmesi
sahte bir paralellik olurdu.

1. **Sürüm kimliği ve sıralaması** — `WorkflowVersion` sayısal öbek karşılaştırması + kayıt
   anında reddetme. Tek başına test edilebilir, hiçbir şeyi kırmaz.
2. **Kayıt defteri ve çalışma zamanı** — `WorkflowRegistry.find(id, version)`, "en yeni"
   çözümü, değişmezlik kontrolü, `resume`'un kayıttaki sürümü kullanması. Postgres restart
   testi burada; **migration gerekmiyor**, `workflow_version` sütunu #1'den beri yazılıyor
   ve okunmuyordu.
3. **Sınır** — proto'ya opsiyonel `workflow_version`, gRPC adaptörü, Python ve TypeScript.

### Neden 3. aşama atlanmıyor

Sürümü yalnızca Java API'sinde açmak, proto'yu tek kaynak sayan kuralı (§26.1) delerdi:
"başlatırken sürüm sabitle" bir çağıran ihtiyacı ve dışarıdan erişilemiyorsa yarısı yazılmış
demek. Alan `reserved 5 to 9` bloğunun içine girmiyor — o blok başka alanlar için ayrıldı;
`workflow_version` 10 numarayı alıyor.

### Kapsam dışı

- **Çalışan execution'ın yeni sürüme taşınması** (§24'ün "migrasyon" cümlesi). Askıya alınmış
  bir execution'ı v2'ye taşımak, `currentStep`'in v2'de ne anlama geldiğine karar vermeyi
  gerektiriyor — adım kaybolmuşsa, kalmış ama değişmişse, değişkenler artık uymuyorsa. Bu bir
  eşleme dili demek ve ayrı bir iş. Bu contract taşımayı *mümkün* kılıyor: kayıt hangi sürümde
  durduğunu zaten biliyor.
- **Sürüm silme / emeklilik.** Canlı execution'ı olan bir sürümü kaldırmak, kaldırma anında
  bilinemeyecek bir soru (bekleyenler ne olacak). Bugün kaldırma yok.
- **Prompt / capability / model sürümleri.** §24 hepsini sayıyor; workflow sürümü diğerlerinin
  ön koşulu ve tek başına tam bir dilim.

### Risk points

- **Sessiz davranış değişimi.** Bugün v2 kaydı v1'i düşürüyor; yarın düşürmeyecek. Aynı
  sürümü ikinci kez farklı içerikle kaydeden mevcut bir kullanım varsa artık hata alacak —
  istenen bu, ama testlerde de görülmeli.
- **"En yeni" seçiminin kayda yazılması.** Sürüm verilmeden başlatılan execution, seçilen
  sürümü kayda yazmazsa resume yine tahmin etmek zorunda kalır. Seçim başlangıçta bir kez
  yapılıp dondurulmalı.
- **Sıralama ile eşitliğin ayrışması.** `"1.0"` ile `"1.0.0"` sıralamada eşit ama kimlik olarak
  farklı. Kayıt defteri kimliğe bakıyor, seçim sıralamaya — ikisini karıştırmak, kaydedilenden
  başka bir sürümü çalıştırmak olur.

## Implementation Notes

**Tamamlandı:** 2026-08-21 — 15 yeni test (11 `WorkflowVersioningTest`, 4 `DurableVersionPinningTest`)
ve üç SDK testi × 2 dil; toplam 322 Java + 22 Python + 22 TypeScript, hepsi yeşil.

### Tip sistemi soruyu sordurdu

En etkili değişiklik en küçüğü oldu: `WorkflowRegistry.find(WorkflowId)` kaldırıldı, yerine
`find(id, version)` ve `latest(id)` geldi. Bunun sonucu, dört çağrı yerinin **hangi sürüm**
sorusuna cevap vermek zorunda kalması:

| Çağrı yeri | Cevap |
|---|---|
| `DefaultWorkflowRuntime.start` | sabitlenmişse o, değilse `latest` |
| `DefaultWorkflowRuntime.resume` | kayıttaki sürüm |
| `RecoverySweeper` | kaydın sürümü — **burada da sessiz bir hata vardı** |
| `ParallelStepExecutor` | context'in sürümü |

`RecoverySweeper` bulguydu: çökmüş bir execution'ı toplarken de id ile arıyordu, yani deploy
sonrası kurtarma yanlış grafikte devam ederdi. Contract bunu yazmıyordu; arayüz daralınca
kendiliğinden görünür oldu.

### Sürüm dili neden dar

`"10.0" < "9.0"` — dizgi sıralaması sessizce yanlış cevap verir. Bu yüzden `WorkflowVersion`
artık noktayla ayrılmış sayı öbekleri kabul ediyor ve `Comparable`. Sayı olmayan bir sürüm
**yazıldığı anda** reddediliyor; "en yeni" sorulduğu anda değil, çünkü o an artık yanlış grafik
seçilmiş ve kimse bakmıyor olur.

Sıralama ile kimlik ayrı tutuldu: `1.0` ile `1.0.0` karşılaştırmada eşit ama iki ayrı kayıt.
Kayıt defteri tam dizgiyle anahtarlıyor, yalnızca "en yeni" seçimi sıralamaya bakıyor. İkisini
birleştirmek, kayıtta yazandan başka bir sürümü çalıştırmak olurdu — preflight'ta risk olarak
yazılmıştı ve `identityIsTheStringEvenWhenOrderingCallsThemEqual` testi tam bunu tutuyor.

### Seçim bir kez yapılıyor

`WorkflowExecutor` kaydı yazarken sürümü zaten `graph.version()`'dan alıyordu. Yani "en yeni"
seçildiği anda donuyor ve resume'un tahmin edecek bir şeyi kalmıyor. Motorda tek satır
değişmedi; `git diff` boş — #16, #6 ve #7 ile aynı sonuç.

### Değişmezlik

Aynı `(id, version)` farklı içerikle ikinci kez kaydedilirse `IllegalStateException`; aynı
içerikle kaydedilirse eski grafik geri dönüyor. İkincisi bir kolaylık değil gereklilik:
process yeniden başlayıp aynı dizini okumak hata olmamalı. `WorkflowDefinition` bir record
olduğu için karşılaştırma bedava geldi.

### Sınır

Proto'ya `workflow_version = 10` eklendi — `reserved 5 to 9` bloğuna dokunulmadı, o blok başka
alanlar için ayrılmıştı. Boş dizgi "en yeni" demek; adı `""` olan bir sürüm değil. Python'da
`execute(..., version=...)`, TypeScript'te `execute(..., { version })`. Kayıtlı olmayan sürüm
`NOT_FOUND` ve mesajda sürüm numarası geçiyor — üç dilde de test edilen davranış bu.

Python stub'ları yeniden üretildi (`grpc_tools.protoc`); ilk koşuda 18 test
`has no "workflow_version" field` ile düştü, çünkü stub'lar depoda commit'li ve proto
değişikliği tek başına yetmiyor. README'deki üretim komutu bu yüzden var.

### Test sunucusuna eklenen

`TestRuntimeServer` artık `policy_check`'i iki sürümde kaydediyor: 1.0 `COMPLETED`, 2.0
`CANCELLED` ile bitiyor. Başka dildeki bir istemcinin hangi grafiğin koştuğunu anlayabilmesi
için gözlenebilir bir fark gerekiyordu; mevcut `venue_booking` testlerine dokunmamak için ayrı
bir workflow tercih edildi.

### Devralınacak

- **Çalışan execution'ın yeni sürüme taşınması.** Kayıt nerede durduğunu biliyor, dolayısıyla
  mümkün; ama `currentStep`'in yeni grafikte ne anlama geldiğine karar vermek bir eşleme dili
  demek.
- **Sürüm emekliye ayırma.** Bugün kaldırma yok. `anExecutionOnARolledBackVersionSaysSoRatherThanRunningSomethingElse`
  testi rollback'in bugünkü davranışını sabitliyor: execution açık bir hatayla duruyor,
  başka bir grafikte koşmuyor.
- **Prompt / capability / model sürümleri** (§24 hepsini sayıyor). Workflow sürümü ön koşuldu.
