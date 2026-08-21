# Streaming Progress

**Status:** Tamam (2026-08-21)
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §30 (streaming), §22.1 (gözlemci sınırı), §15/§38 (kurtarma), §26.1 (proto otorite)

## Goal

Çalışan bir execution'ı izleyen client'ın, uzun bir adım sürerken sessizlik yerine ne olduğunu
görmesi: `step.started` ve `execution.recovered` olayları — ve akışın **açılıp kapanabilmesi**.

## Bugünkü sessizlik

`ExecutionUpdate` altı olay taşıyor: `started`, `step_finished`, `suspended`, `resumed`,
`finished`, `token`. Kırk saniye süren bir capability adımında izleyici — LLM token'ı yoksa —
**hiçbir şey görmüyor**; adım bitince tek seferde öğreniyor.

"Bir saattir ne oluyor?" sorusunun bugünkü cevabı: adım bitene kadar hiçbir şey.

## İkinci boşluk: kurtarma tele çıkmıyor

`ExecutionObserver.executionRecovered` var ve javadoc'u neden ayrı bir olay olduğunu söylüyor:

> a resume follows a decision somebody made, this follows a failure nobody reported

Ama `ExecutionUpdateBroker` bu metodu uygulamıyor. Yani çöken bir process'in execution'ı
`RecoverySweeper` tarafından toplandığında, izleyen client hiçbir şey görmüyor. Gözlemci tarafı
olayı biliyor, wire bilmiyor — telemetride görünen bir şeyin SDK'da görünmemesi, iki tüketicinin
aynı olay akışından beslendiği iddiasını (§22.1) yarısından deliyor.

## Açıp kapamak: üç ayrı anahtar, karıştırılmamalı

"Streaming açılabilir kapanabilir olsun" tek bir düğme değil. Üç farklı soru var ve üçünün
cevabını verecek taraf farklı:

| Soru | Kim karar verir | Nerede duruyor |
|---|---|---|
| Bu adımın model çıktısı token token aksın mı? | Workflow yazarı | `llm` adımının `"stream": true` alanı — **zaten var** |
| Ben izlerken neyi almak istiyorum? | İzleyen client | `WatchExecutionRequest`'e filtre — **bu contract** |
| Bu organizasyon izleyebilir mi? | Deployment / plan | `Principal` izni, sınırda kontrol — **bu contract**, planı #19 verir |

### Motor hâlâ bilmiyor

Olaylar **her zaman üretiliyor**, çünkü aynı olaylar telemetriyi de besliyor (§22.1) —
üretimi kapatmak gözlenebilirliği kapatmak olurdu. Kapanabilen şey **teslimat**, ve kontrol
sınırda. Motor abonelik, plan ya da tercih diye bir şey bilmiyor (§3); #19'un kotasıyla aynı
duruş.

### İzin yeni bir kavram değil

`Principal.holds(permission)` bugün genel ama yalnızca `CapabilityInvoker` kullanıyor.
`WatchExecution` de aynı mekanizmayı kullanıyor: `stream:watch` iznini tutmayan çağıran
izleyemiyor. Yeni bir alan, yeni bir tablo, yeni bir kavram yok — #8'in kurduğu şeyin ikinci
kullanıcısı.

### Kapalı olmak sessiz olmamalı

İzni olmayan çağıran `PERMISSION_DENIED` alıyor; **boş bir akış değil**. Açılıp hemen kapanan
boş bir akış "hiçbir şey olmuyor"a benziyor ve bozuk bir workflow'dan ayırt edilemiyor.
Kapalı bir özelliğin kapalı olduğunu söylemesi gerekiyor.

## Adım-altı olay eklenmiyor — bilinçli sınır

Kolay genişleme `llm.started`, `capability.started`, `capability.completed` eklemek olurdu.
Reddediliyor, ve gerekçe bu kod tabanının kendi dayanıklılık kuralı:

**Adım, dayanıklılığın birimi.** Adım-altı yaşam döngüsünü yayınlamak, hiçbir history satırı
olmayan şeyleri tele koymak demek. Client `capability.started` görür, process çöker, adım
tekrar koşar — ve o olay hiç olmamış olur. İzleyicinin gördüğü, kalıcı olanla çelişemez.

Token'lar bilinçli istisnadır ve zaten öyle yazılmıştır: onlar kullanıcıya gösterilen çıktının
kendisi, execution'ın durumu hakkında bir iddia değil.

`step.started` bu sınırın **içinde**: adımın kendisi zaten history'nin birimi, ve başlaması
bitmesi kadar gerçek bir olay. Tekrarlanırsa ikinci kez yayınlanır — ki doğrusu da budur,
çünkü adım gerçekten ikinci kez başlamıştır.

## `started` ile karışmamalı

Wire'da hâlihazırda `ExecutionStarted` var ve iki işi birden yapıyor: abonelik açılırken sequence 0
çerçevesi olarak mevcut durumu veriyor. Yeni olay **adım** seviyesinde ve ayrı bir mesaj:

```protobuf
message StepStarted {
  string step_id = 1;
  string step_type = 2;
  uint32 attempt = 3;   // yeniden deneme de bir başlangıçtır, ve görünür olmalı
}

message ExecutionRecovered {
  string step_id = 1;
  bool repeated = 2;    // adım tekrar koşuldu mu, yoksa insana mı bırakıldı
}
```

`attempt` önemsiz görünüyor ama değil: retry politikası (§17) sessizce çalışıyor ve izleyen taraf
bugün üç denemeyi tek bir adım gibi görüyor.

## Sıralama garantisi

Olaylar `sequence` ile numaralanıyor ve `from_sequence` ile yeniden oynatılabiliyor. Yeni
olaylar aynı sayaçtan geçmeli; ayrı bir yol açmak, tek yazarlı akışa ikinci bir yazar koymak
olur — #12'de `UpdatePump`'ın çözdüğü sorunun aynısı.

## Acceptance Criteria

- [x] Adım başlarken `step.started` yayınlanıyor, bitmesini beklemeden
- [x] `step.started` ile `step_finished` aynı `sequence` sayacından, sırayla geliyor
- [x] Yeniden denenen adım her denemede `step.started` yayınlıyor, `attempt` artıyor
- [x] `ExecutionObserver.stepStarted` ekleniyor ve tüm gözlemcilerde varsayılanı boş
- [x] Kurtarılan execution `execution.recovered` yayınlıyor; `repeated` doğru
- [x] Tekrarlanamaz adımda duran kurtarma da olay yayınlıyor (`repeated=false`)
- [x] Adım-altı olay eklenmiyor: `llm.started`, `capability.started` proto'da yok
- [x] Python SDK yeni olayları veriyor; bilinmeyen olay tipi istemciyi düşürmüyor
- [x] TypeScript SDK aynısı
- [x] Bir gözlemcinin `stepStarted`'da fırlatması execution'ı düşürmüyor (§22.1)
- [x] Uzun bir adım koşarken izleyici adımın başladığını görüyor (uçtan uca test)
- [x] `stream:watch` izni olmayan çağıranın `WatchExecution`'ı `PERMISSION_DENIED`
- [x] Reddedilen izleme boş akışla değil, hata ile bitiyor
- [x] `unrestricted` principal her zaman izleyebiliyor (gömülü kullanım kırılmıyor)
- [x] İzleyici token istemediğini söyleyebiliyor ve token almıyor
- [x] İzleyici ilerleme olayları istemediğini söyleyebiliyor; durum olayları yine geliyor
- [x] Filtre teslimatta uygulanıyor; olaylar yine de üretiliyor (telemetri etkilenmiyor)
- [x] Mevcut testler geçiyor — toplam 370 Java + 37 Python + 25 TypeScript

## Kapsam dışı

- **Organizasyon başına akış kotası.** "Ayda şu kadar izleme saati" bir abonelik sorusu ve
  #19'un kota defterine ait; burada yalnızca izin var/yok.
- **Adım içi ilerleme yüzdesi.** Bir adımın "%40 bitti"si ancak adımın kendi bildiği bir şey ve
  bunu bildirecek bir kanal açmak, adım-altı olay kapısını arka taraftan açmak olur.
- **WebSocket / SSE.** Event modeli transport'tan bağımsız; ikinci bir transport ayrı bir karar
  ve bugün gerçek bir ihtiyaç yok.
- **Geçmiş olayların kalıcı saklanması.** `from_sequence` bellekteki tampondan oynatıyor;
  kalıcı olay defteri ayrı bir iş (#7'de aynı gerekçeyle kapsam dışı bırakılmıştı).

## Split Decision

**Decision:** single-prompt, üç aşama
**Tarih:** 2026-08-21

Katmanlar (core, proto+broker, iki SDK) yine sıralı: broker proto'suz, SDK broker'sız test
edilemez. Küçük bir dilim — asıl işi tasarım kararı yapıyor, kod miktarı değil.

1. **Core** — `ExecutionObserver.stepStarted`, `runStep`'in deneme döngüsünde yayınlanması,
   `RecoverySweeper`'ın `repeated` bilgisini olaya koyması.
2. **Sınır** — proto'ya `StepStarted`, `ExecutionRecovered` ve `WatchExecutionRequest`'e filtre;
   `ExecutionUpdateBroker`'ın iki yeni metodu; `watchExecution`'da `stream:watch` kontrolü.
3. **SDK'lar** — Python ve TypeScript'te yeni olay tipleri, filtre parametresi, uçtan uca test.

### Yeri belli: `runStep`'in deneme döngüsü

`WorkflowExecutor.runStep` zaten `for (int attempt = 1; ; attempt++)` döngüsünü kuruyor ve
`executor.execute` çağrısı orada. `stepStarted` tam o satırın öncesine giriyor — böylece
`attempt` uydurulmadan, zaten elde olan değerden geliyor.

### Asıl tasarım sorusu: `step.started` neden kalıcılık sınırının içinde

Contract adım-altı olayları reddediyor ama `step.started` de adım koşmadan **önce**, yani
hiçbir şey yazılmadan önce yayınlanıyor. Çelişki gibi görünüyor; değil, ve fark şu:

**Adımın başladığı kalıcı durumla doğrulanıyor.** Execution `RUNNING` ve `current_step` o adımı
gösteriyor; process ölse bile veritabanı "bu execution şu adımda duruyordu" diyor. İzleyicinin
gördüğü şeyin arkasında bir kayıt var.

Adım-altı olayın böyle bir arkası yok. `capability.started` yayınlansa, çağrının denendiğini
hiçbir yerde hiçbir şey söylemez — client'ın gördüğü tek tanık olur, ve o tanık çökmeyle
birlikte yalancı çıkar.

Bu ayrım implementasyonda bir kurala dönüşüyor: **bir olay, yalnızca kalıcı durumun
doğrulayabileceği bir şeyi iddia edebilir.**

### Kapsam dışı (ek)

- **Olayı `advance` ile aynı transaction'a almak.** `step.started` bir bildirim, bir yazma
  değil; kalıcı olan zaten `current_step`.

### Risk points

- **Gözlemci zinciri.** Yeni metot `ExecutionObserver`'a varsayılanı boş olarak giriyor, yoksa
  her uygulama derlenmez hâle gelir. `CompositeExecutionObserver` de uygulamalı, yoksa fan-out
  sessizce kopar — ve fırlatan bir gözlemci execution'ı düşürmemeli (§22.1), ki bu zaten
  `guarded` sarmalayıcının işi ama testi olmalı.
- **Sayaç.** Yeni olaylar aynı `sequence` sayacından geçmeli. Ayrı yol açmak, #12'de
  `UpdatePump`'ın çözdüğü "bir akışa iki yazar" sorununu geri getirir.
- **Sürüm uyumsuzluğu.** SDK'lar proto'yu paketleriyle taşıyor; eski bir SDK yeni olayı
  görürse düşmemeli. Python'da `Update(sequence, kind or "unknown")`, TypeScript'te `default`
  dalı zaten var — kırılmadıklarının testi eklenmeli.
- **Gürültü.** Her adım için bir olay daha, yani akış iki katına yakın büyüyor. Filtre tam bu
  yüzden var: isteyen ilerleme olaylarını kapatabiliyor.
- **Filtrenin varsayılanı.** Boş filtre "hepsini ver" demeli, "hiçbirini verme" değil — proto3'te
  ayarlanmamış alan sıfırdır ve yanlış tarafı seçmek, mevcut client'ları sessizce susturur.
- **İznin varsayılanı.** `PrincipalResolver`'ın varsayılanı `ANONYMOUS` ve hiçbir izin tutmuyor.
  `stream:watch` zorunlu tutulursa, bugün çalışan her gömülü kullanım kırılır — bu yüzden
  `unrestricted` (yani `Principal.SYSTEM`) muaf, ve kontrol yalnızca uzaktan çağrıda.

## Implementation Notes

**Tamamlandı:** 2026-08-21 — 370 Java (+3), 37 Python (+3), 25 TypeScript (+3).

### Aynı hata, üçüncü dil

TypeScript testleri **asıldı**, düşmedi. Sebep Java'da #12'de öğrenilenin aynısı: okunmayan bir
server stream akış kontrolü uyguluyor ve aynı bağlantıdaki `approve` çağrısı hiç dönmüyor.
Mevcut TS testi (`ends the watch when the execution ends`) tam bu yüzden önce iteratörü alıp ilk
çerçeveyi okuyor; ben `for await` ile kısa yoldan yazınca kalıbı bozdum.

Üç dilde üç kez aynı ders: **aboneliğin ne zaman kurulduğu ve ne zaman boşaltıldığı, neyin
sessizce kaybolduğunu belirliyor.** Yeni testler ortak bir `watchThenApprove` yardımcısına
alındı ve gerekçe orada yazılı.

### İzin varsayılanı: contract'ta yazılan çözüm yetmedi

Preflight'ta "`unrestricted` muaf, kontrol yalnızca uzaktan çağrıda" demiştim. Uygularken
görüldü ki bu yetmiyor: `PrincipalResolver`'ın varsayılanı `ANONYMOUS` ve o da uzak bir çağıran.
`stream:watch` koşulsuz zorunlu tutulsa, resolver kurmamış her deployment ve tüm SDK testleri
`PERMISSION_DENIED` alırdı.

Çözüm gereksinimi **principal tarafında değil sunucu tarafında** opt-in yapmak:
`PipeMeshServer(..., List<String> watchPermissions)`, varsayılanı boş. Boşken izleme herkese
açık — ki kimseyi tanımlamayan bir deployment'ın zaten uygulayabileceği tek şey bu (§22.2).
Canlı izlemeyi bir plan özelliği olarak satan deployment buraya bir izin adı yazıyor.

`Principal.missingFrom(...)` ikisini birden çözüyor: boş liste herkese açık, `unrestricted`
principal zaten hiçbir şey eksik değil. Özel durum yazmaya gerek kalmadı.

### `resume` edilen adım "başlamıyor"

`WorkflowExecutor.resume` çözümlenebilir executor'ı doğrudan çağırıyor, `runStep`'ten
geçmiyor — dolayısıyla o adım için `step.started` yayınlanmıyor. Doğrusu bu: o adım günler önce
başladı, ve `resumed` zaten ona ne olduğunu söylüyor. Testi var
(`aResumedStepIsNotAnnouncedAsStarting`), yoksa ileride "eksik" sanılıp eklenirdi.

### Filtre teslimatta, numaralama öncesinde

`sequence` filtrelemeden **önce** atanıyor. Filtreleyen izleyici boşluk görüyor ve boşluk
"bu sana değil" diyor — kayıpla karıştırılmıyor. Üç dilde de testi var: `[0, 1, 2, 4, 5]`.

Durum olayları filtrelenemiyor. `finished`'i atlayabilen bir akış, çağıranı çoktan bitmiş bir
şeyi beklerken bırakırdı.

### Kurtarma artık iki uçlu

`executionRecovered` yalnızca **devam eden** kurtarmada yayınlanıyordu; tekrarlanamaz adımda
duran kurtarma sessizce başarısız oluyordu. Şimdi ikisi de yayınlanıyor ve `repeated` ayırıyor.
İnsana bırakılan kurtarma, tam da duyulması gereken taraf.

`executionRecovered(ExecutionEvent)` imzası `RecoveryEvent` alacak şekilde değişti — tek metot
iki metottan iyi, ve tek implementasyon (OTel) de bu depoda.

### Yolda düzeltilen

`RecoverySweeperTest` bayat sınıfla koştu: Maven'ın `testCompile`'ı yalnızca **test** kaynakları
değişince derliyor, ana kaynak değişince değil. `@Override` artık uymayan bir metot, hiç
çağrılmadığı için testi boş listeyle düşürdü. Ders: SPI imzası değiştirdikten sonra tam koşu
şart, tek modül koşusu yanıltıyor.

### Devralınacak

- **`from_sequence` hâlâ uygulanmıyor.** Proto'da alan var, broker javadoc'u nedenini yazıyor:
  yeniden oynatma, olayları saklamayı ve ne kadar saklanacağına karar vermeyi gerektiriyor.
- **Adım içi ilerleme.** Bilinçli olarak yok; gerekçe contract'ta.
