# LangChain Adapter

**Status:** Tamam (2026-08-21)
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §35 (LangChain opsiyonel adaptör), §26.2 (Runtime/SDK/Provider ayrımı), §14 (SDK worker'ları)

## Goal

LangChain ekosistemine erişmek, bağımlılığını almadan. §35'in cümlesi: *"The ecosystem is worth
reaching; the dependency is not worth taking."*

## Önce bir düzeltme: `pipemesh-langchain` bir Java modülü olamaz

DESIGN §35'in şeması `pipemesh-langchain`'i "OpenAI adapter" ve "MCP adapter" ile yan yana
koyuyor; o ikisi Java modülü. Ama LangChain bir Python kütüphanesi. Java tarafında adı benzeyen
şey LangChain4j — ayrı bir proje, ayrı bir karar.

Dolayısıyla adaptör **Python SDK'sında** yaşıyor. Bu bir taviz değil, §26.2'nin sonucu: bir
provider, runtime'ın dışarıya uzandığı yerdir ve Python'daki bir şeye uzanmanın yolu #14'ün
kurduğu worker protokolü.

## Yeni protokol gerekmiyor — asıl bulgu

Bir LangChain tool'u ya da chain'i, bugünkü `CapabilityWorker.Connect` üzerinden **zaten**
erişilebilir. Adaptörün yaptığı tek şey, LangChain nesnesini `worker.capability(...)`'ye
bağlamak:

```python
from pipemesh import PipeMeshWorker
from pipemesh.langchain import serve

worker = PipeMeshWorker("localhost:8080", organization="acme")
serve(worker, "summarize", my_langchain_chain)
worker.run()
```

Workflow tarafında hiçbir şey değişmiyor — hiçbir şey de LangChain'den haberdar değil:

```json
{"type": "capability", "capability": "summarize", "input": "$.article", "output": "summary"}
```

Bu, §9.8'in ("workflow bir capability'nin nasıl gerçeklendiğini asla öğrenmez") LangChain'e
uygulanmış hâli. Sıfır Java değişikliği, sıfır çekirdek bağımlılığı.

## Adaptörün kendisi de LangChain'e bağımlı değil

`import langchain` yok. Adaptör **şekle** bakıyor: `invoke()` metodu olan her şey. LangChain'in
`Runnable` ve `BaseTool` sınıflarının ikisi de bunu taşıyor.

Gerekçe §35'in cümlesinin kendisi: bağımlılığı almaya değmiyorsa, adaptörün de alması gerekmez.
Yan faydası, testlerin gerçek LangChain kurulumu gerektirmemesi — ve zararı, LangChain'in
`invoke()` sözleşmesini değiştirmesi hâlinde bunu derleme değil çalışma zamanı hatası olarak
görmek. İkisi de yazılıyor.

## Sınır: LangChain modeli bir `llm` adımını besleyemiyor

§35'in şeması LangChain'i "Models" kutusuna bağlıyor. Bugün bu mümkün değil ve sebebi net:
proto'da capability için gelen bir yol var (`CapabilityWorker`), model için yok. Bir SDK
process'inde yaşayan model, `llm` adımının arkasına ancak yeni bir RPC ile geçebilir.

Ayrıca istenip istenmediği ayrı bir soru: model bir hop ötede olursa maliyet muhasebesi (§39.1)
ve token akışı (§30) o hop'un arkasına düşer. Bir LangChain **chain**'i zaten uygulama mantığı
ve capability'nin arkası onun doğru yeri.

## Acceptance Criteria

- [x] `invoke()` metodu olan bir nesne capability olarak sunuluyor
- [x] Workflow onu düz bir capability adımıyla çağırıyor; LangChain adı hiçbir yerde geçmiyor
- [x] Sözlük dönen bir chain'in çıktısı olduğu gibi değişkene yazılıyor
- [x] Düz metin dönen bir chain'in çıktısı sarmalanıyor, kaybolmuyor
- [x] Nesnenin kendi adı ve açıklaması varsa kullanılabiliyor (`serve` adı zorunlu değil)
- [x] Chain'in fırlattığı hata, workflow'un dallanabileceği bir capability hatasına dönüşüyor
- [x] `invoke()` olmayan bir nesne, worker koşmadan önce reddediliyor
- [x] Adaptör hiçbir yerde `langchain` import etmiyor
- [x] Java tarafında tek satır değişiklik yok
- [x] Mevcut 366 Java + 22 Python + 22 TypeScript testi değişmeden geçiyor

## Split Decision

**Decision:** single-prompt, tek aşama
**Tarih:** 2026-08-21

Bu contract'ın büyük kısmı zaten **yazarken çözüldü**: yeni protokol gerekmediği anlaşılınca
geriye tek bir Python modülü ve testleri kaldı. Bölecek bir şey yok; bölmek, tek dosyalık bir
işi dört ajana dağıtmak olurdu.

Katmanlar: Python SDK (`pipemesh/langchain.py`), testler, README. Java yok, proto yok, SDK'lar
arası yok.

### Neden bu contract diğerlerinden kısa

Çünkü işin çoğunu #14 yaptı. Worker protokolü "uygulamanın kodu runtime'a nasıl ulaşır"
sorusunu genel olarak cevapladığı için, LangChain o cevabın bir örneği oluyor — özel bir vaka
değil. Bir adaptörün kısa olması, altındaki soyutlamanın doğru yerde durduğunun işareti.

### Kapsam dışı

- **LangChain modelinin `llm` adımını beslemesi.** Proto'da model için gelen yol yok; ayrıca
  maliyet muhasebesi ve token akışı bir hop arkasına düşerdi.
- **LangChain memory / agent executor.** PipeMesh'in kendi agent adımı (#16) ve kendi durum
  saklaması var; ikisini birleştirmek iki ayrı durum makinesini evlendirmek demek.
- **Async chain'ler (`ainvoke`).** Worker'ın bugünkü çalıştırma modeli thread havuzu; async
  desteklemek worker'ın kendi işi, adaptörün değil.
- **LangChain'in streaming'i.** §30 akışı model sınırında tanımlıyor; capability sınırında akış
  bugün yok.

### Risk points

- **Şekle bakmanın bedeli.** `invoke()` sözleşmesi değişirse hata çalışma zamanında görünür.
  Kabul edilen bir bedel — alternatifi tam da almamaya karar verdiğimiz bağımlılık. Ama en
  azından **erken** görünmeli: `invoke` yokluğu worker koşmadan önce reddedilmeli, ilk çağrıda
  değil.
- **Çıktının kaybolması.** LangChain nesneleri sözlük, dizgi, `AIMessage` benzeri nesne
  dönebiliyor. Sözlük olmayan bir çıktıyı sessizce boş sözlüğe çevirmek, cevabı kaybetmek olur.
- **Hata sınıflandırması.** Bir chain'in fırlattığı her istisna "tekrar denenebilir" sayılırsa,
  iş kuralı gereği reddeden bir chain boşuna üç kez çağrılır. #14'ün `CapabilityFailure`
  varsayılanı (`retryable=False`) doğru duruş; adaptör onu bozmamalı.

## Implementation Notes

**Tamamlandı:** 2026-08-21 — 12 yeni Python testi (`test_langchain.py`); Python 34, Java 366,
TypeScript 22. **Java'da tek satır değişiklik yok** (`git diff --stat` boş).

### Tahmin eden tasarım ilk gerçekçi testte kırıldı

İlk sürümde adaptör bir sezgi taşıyordu: girdi tek alanlıysa, chain muhtemelen o alanın
*değerini* istiyordur. Gerekçesi makul görünüyordu — LangChain chain'leri tek bir şey alır,
capability ise her zaman bir nesne alır.

`test_a_workflow_calls_a_chain_without_knowing_it_is_one` bunu anında kırdı: `{"tier": "gold"}`
tek alanlı ama bu onun *tamamı*. Chain sözlük yerine `"gold"` aldı ve `customer["tier"]` patladı.

Sezgi kaldırıldı, yerine açık alan adı geldi: `serve(..., field="article")`. Verilmezse chain
nesnenin tamamını alıyor. Bu, bu kod tabanının başka her yerde verdiği kararın aynısı —
çağıranın ne demek istediğini tahmin etmemek. Yanlış tahminin bir istisnası olmuyordu; sadece
bazen doğru oluyordu, ki daha kötüsü.

Adı olmayan alan da tahmin edilmiyor: `field` verilip girdide yoksa `langchain.missing_field`.

### Yeni protokol gerekmedi

Contract'ın en önemli cümlesi implementasyondan önce yazılmıştı ve doğru çıktı: bir LangChain
chain'i bugünkü `CapabilityWorker.Connect` üzerinden zaten erişilebilir. Adaptör 130 satır ve
hepsi çeviri — bağlantı, yeniden deneme, kayıt, kapanış hiç dokunulmadan #14'ten geliyor.

Bir adaptörün bu kadar kısa olabilmesi, altındaki soyutlamanın doğru yerde durduğunun kanıtı.
Ters durumda — her ekosistem için yeni bir RPC — soyutlama capability değil, ekosistem başına
özel vaka olurdu.

### Adaptör de LangChain'e bağımlı değil

`import langchain` yok; `invoke()` metodu olan her şey kabul ediliyor. Bunu test bile denetliyor
(`test_the_adapter_does_not_import_langchain` kaynağı okuyor), çünkü ileride birinin "kolaylık
olsun" diye import etmesi tam da §35'in reddettiği şeyi geri getirirdi.

Bedeli yazıldı: LangChain `invoke` sözleşmesini değiştirirse bu çalışma zamanında görünür. En
azından **erken** görünüyor — `invoke` yokluğu worker koşmadan önce `TypeError`, ilk çağrıda
değil.

### Çıktı kaybolmuyor

Chain sözlük, mesaj nesnesi (`AIMessage` gibi `.content` taşıyan), pydantic modeli veya düz
dizgi dönebiliyor. Sözlük olmayanı sessizce boş nesneye çevirmek cevabı kaybetmek olurdu:
`.content` varsa `{"content": ...}`, `model_dump()` varsa çağrılıyor, düz değer ise worker'ın
kendi `{"value": ...}` sarmalamasına bırakılıyor — adaptörün workflow'un sormadığı bir alan adı
uydurmasına gerek yok.

### Hata sınıflandırması bozulmadı

Chain'in fırlattığı `CapabilityFailure` olduğu gibi geçiyor — workflow'un dallanacağı cevap
zaten o. Diğer istisnalar `langchain.failed` ile ve `retryable=False` olarak sarılıyor; #14'ün
duruşu: iş kuralı gereği reddeden bir chain ikinci sorulduğunda farklı bir şey söylemiyor.

### DESIGN §35'te düzeltilen

Şema `pipemesh-langchain`'i Java modülleriyle yan yana koyuyordu. LangChain Python; Java'daki
benzeri LangChain4j, ayrı bir proje ve ayrı bir karar. §35.1 bunu yazıyor.

### Yolda düzeltilen — bu contract'ın dışında

Son tam koşuda `ExecutionDispatchTest`'ten iki test düştü; bu dilim Java'ya dokunmadığı için
sebep açıktı: testler **yarışlıydı**. `dispatchOnce()` hem kapıyor hem sürüyor, ve `WAITING`'e
sürülmüş bir execution artık kapılabilir değil — kirasıyla hiç ilgisi olmayan bir sebeple. Kira
ömrünü ölçen testler artık `ExecutionLeases`'e doğrudan soruyor; sürmek ayrı testlerde. Aynı
yarış Postgres tarafında da vardı ve şans eseri geçiyordu, o da düzeltildi.

#10'un kendi Implementation Notes'unda "kira sürmeyi düzenliyor, ilerletmeyi değil" yazıyordu.
Test bu ayrımı yapmayınca kırıldı.

### Devralınacak

- **LangChain modelinin `llm` adımını beslemesi.** Proto'da capability için gelen yol var, model
  için yok. Ayrıca istenip istenmediği ayrı soru: model bir hop ötede olursa maliyet muhasebesi
  (§39.1) ve token akışı (§30) o hop'un arkasına düşer.
- **Async chain'ler (`ainvoke`).** Worker'ın çalıştırma modeli thread havuzu; async desteklemek
  worker'ın işi, adaptörün değil.
- **LangChain4j.** Java tarafında gerçekten bir `pipemesh-langchain4j` modülü mümkün ve
  §35'in şemasına da uyar; ayrı bir contract.
