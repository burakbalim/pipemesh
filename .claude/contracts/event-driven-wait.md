# Event-Driven Wait

**Status:** Tamam (2026-08-21)
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §9.7 (wait), §16 (thread bloklamama), §28 (event-driven yürütme)

## Goal

Bir workflow'un dış dünyadan bir olay bekleyebilmesi:

```json
{ "type": "wait", "event": "payment_completed", "correlationKey": "$.order.id",
  "output": "payment", "next": "ship", "timeoutSeconds": 3600, "onTimeout": "chase_payment" }
```

Askıya alma altyapısı approval'dan hazır — `Suspend`, `WAITING`, `resume`, idempotency, restart.
Eksik olan tek şey **eşleştirme**.

## Asıl tasarım sorunu: olay hangi execution'ı bulacak?

Approval'da çağıran `approvalId`'yi biliyor. Olayda bilmiyor — ödeme servisi hangi execution'ın
beklediğini bilmez, yalnızca sipariş numarasını bilir. Bu yüzden bekleyiş bir **anahtarla**
kaydediliyor:

```text
askıya alınırken:  (organizasyon, "payment_completed", $.order.id → "A-4172")  kaydedilir
olay geldiğinde:   (organizasyon, "payment_completed", "A-4172")  aranır  →  execution bulunur
```

Anahtar askıya alma anında değişkenlerden hesaplanıyor; sonradan değişse bile bekleyiş
kaydedildiği anahtarla duruyor.

**Organizasyon anahtarın parçası.** Aksi halde bir kiracının yayınladığı olay, diğerinin
execution'ını ilerletirdi — #17'nin kapattığı sınırın olay tarafından delinmesi.

## Sınırsız bekleyiş olmamalı

Bu kod tabanında tekrarlayan bir karar var: adım bütçesi, agent tur sınırı, worker deadline'ı.
Süresiz bekleyen bir wait step'i aynı sorunun bir başka yüzü — sonsuza kadar `WAITING` kalan ve
kimsenin fark etmediği bir execution.

`timeoutSeconds` verilirse süresi dolan bekleyiş `onTimeout` adımına dallanıyor; verilmezse
bekleyiş süresiz — ama bu **bilinçli bir seçim** olarak yazılıyor, varsayılan olarak sessizce
oluşmuyor.

Süresi dolanları toplayan iş, `RecoveryScheduler`'ın zaten genel olan `RecoveryPass` arayüzüne
takılıyor — ikinci bir zamanlayıcı gerekmiyor.

## Acceptance Criteria

- [x] Wait step'i execution'ı `WAITING`'e alıyor, hiçbir thread tutulmuyor
- [x] Doğru anahtarla yayınlanan olay execution'ı buluyor ve `next`'e devam ettiriyor
- [x] Olayın gövdesi `output` değişkenine yazılıyor
- [x] Farklı anahtarla yayınlanan olay o execution'ı **bulmuyor**
- [x] Başka organizasyonun aynı anahtarlı olayı execution'ı bulmuyor
- [x] Aynı olay iki kez yayınlanırsa execution bir kez ilerliyor
- [x] Aynı olayı bekleyen iki execution varsa **ikisi de** ilerliyor (yayın tekile değil eşleşene gider)
- [x] Bekleyiş restart'ı geçiyor: başka bir process'te yayınlanan olay bulup ilerletiyor
- [x] `timeoutSeconds` dolduğunda `onTimeout` adımına dallanıyor
- [x] `onTimeout` yazılmamışsa süresi dolan bekleyiş execution'ı `FAILED` yapıyor
- [x] `WorkflowExecutor` değişmiyor
- [x] Mevcut 292 test değişmeden geçiyor

## Split Decision

**Decision:** single-prompt, üç aşama
**Tarih:** 2026-08-21

1. `WaitStore` + `WaitStepExecutor` + `ResumeSignal.Event` — in-memory ile uçtan uca
2. Postgres implementasyonu + migration + restart testi
3. Süre dolumu: `ExpiredWaitSweeper` + zamanlayıcıya takılması

### Kapsam dışı

- **Uzaktan olay yayını.** Proto'da olay RPC'si yok; eklemek proto + iki SDK demek. Bu contract
  in-process yayın API'sini veriyor, teli sonraki iş devralır.
- **Olay geçmişi / yeniden oynatma.** Bekleyeni olmayan bir olay düşürülüyor; saklamak ve ne
  kadar saklanacağına karar vermek ayrı bir iş — `from_sequence` ile aynı gerekçe.
- **Kalıp eşleşmesi.** Anahtar tam eşleşiyor; joker ya da aralık yok.

### Risk points

- **Yarış: yayın ile askıya alma.** Execution `WAITING` olarak yazılmadan önce gelen bir olay,
  bekleyeni bulamaz ve düşer. Bekleyiş kaydı **suspend'den önce** yazılmalı — approval'daki
  sıranın aynısı, aynı gerekçeyle.
- **Anahtarın hesaplanamaması.** `$.order.id` yoksa bekleyiş kimsenin bulamayacağı bir anahtarla
  kaydedilir. Bu, sessiz bir sonsuz bekleyiş demek — açıkça reddedilmeli.
- **İki execution, tek olay.** Eşleşen her execution ilerlemeli; ilkini bulup durmak, sessizce
  yarısını unutmak olur.

## Implementation Notes

**Tamamlandı:** 2026-08-21 — 15 yeni test (11 `WaitStepTest`, 4 `DurableWaitTest`), toplam 307 yeşil.

### Bekleyiş suspend'den önce yazılıyor

Sözleşmenin en dar yeri risk bölümünde yazılıydı ve kod aynen ona uydu: `WaitStepExecutor`
önce `WaitStore.register` çağırıyor, sonra `Suspend` dönüyor. Ters sırada, execution `WAITING`
olarak yazılmadan önce gelen bir olay bekleyeni bulamaz ve sessizce düşerdi. Approval'daki
sıranın aynısı, aynı gerekçeyle.

`register` idempotent: aynı `waitId` ile ikinci çağrı yeni kayıt açmıyor. `waitId` uydurulmuş
bir değer değil, `executionId + ":" + stepId` — kurtarma sırasında adım tekrarlanırsa aynı
anahtar yeniden hesaplanıyor, ikinci bir bekleyiş doğmuyor.

### Anahtar hesaplanamıyorsa bekleyiş reddediliyor

`correlationKey` yolu değişkenlerde yoksa adım `wait.no_correlation` ile başarısız oluyor.
Alternatif — boş anahtarla kaydetmek — kimsenin bulamayacağı, kimsenin fark etmeyeceği bir
sonsuz `WAITING` üretirdi. Bu kod tabanındaki genel duruşun aynısı: bilinmeyeni tahmin etmek
yerine durup insana bırakmak.

### Organizasyon anahtarın parçası, filtre değil

`EventKey(organization, name, correlation)` — üçü birden birincil anahtar. Yayın sorgusu
organizasyonu `WHERE`'de sonradan elemekle yetinseydi, eleme unutulduğu gün kiracı sınırı
sessizce delinirdi. Postgres tarafında da aynı: `workflow_wait_listening` kısmi indeksi
`(organization_id, event_name, correlation) WHERE status='WAITING'` — her yayınlanan olayda
çalışan tek arama bu.

### Yayın eşleşen herkese gidiyor

`waitingFor(key)` ilkini değil **hepsini** döndürüyor; `EventPublisher` her birini
`Principal.SYSTEM` olarak ilerletiyor. İlkini bulup durmak, yarısını sessizce unutmak olurdu.
Yayın, ilerlettiği execution'ların listesini döndürüyor — hiç bekleyen yoksa boş liste, hata
değil (bekleyeni olmayan olay düşürülüyor, kapsam dışı notundaki karar).

`settle` bekleyişi `DELIVERED`'a alıyor, dolayısıyla aynı olay ikinci kez yayınlandığında
`waitingFor` artık onu görmüyor — idempotency bekleyiş kaydında, çağıranın dikkatinde değil.

### Süre dolumu ikinci bir zamanlayıcı istemedi

`ExpiredWaitSweeper`, `RecoveryScheduler.RecoveryPass` arayüzünü uyguluyor; zaten koşan
kurtarma zamanlaması onu da taşıyor. Süresi dolan bekleyiş `Expired` sinyaliyle geri dönüyor,
`onTimeout` varsa oraya dallanıyor, yoksa execution `FAILED` oluyor — süresiz bekleyiş hâlâ
mümkün ama yalnızca `timeoutSeconds` yazılmadığında, yani bilinçli seçildiğinde.

### Motor değişmedi

`WorkflowExecutor` üzerinde `git diff` boş. #16 (agent) ve #6 (parallel) ile aynı sonuç:
yeni bir primitive `StepExecutor` + şema girdisi olarak geldi. Bekleyiş için gereken tek motor
kavramı — `ResumeSignal` — zaten vardı; ona iki kayıt eklendi (`Event`, `Expired`).

### Yolda düzeltilen

Yeni `workflow_wait` tablosunun `workflow_execution`'a foreign key'i, iki eski testin
`TRUNCATE`'ini kırdı (Postgres, referans verilen tabloyu tek başına boşaltmayı reddediyor).
Üçüncü kopya yazmak yerine liste `TestTables.empty` içine alındı — bir sonraki tablo eklendiğinde
kırılacak tek bir yer kalsın diye.

### Devralınacak

- **Uzaktan yayın.** Bugün olay yayını in-process bir API. Proto'da olay RPC'si yok; teli çekmek
  proto + iki SDK demek, ayrı iş.
- **Bekleyeni olmayan olay düşüyor.** Saklamak, ne kadar saklanacağına karar vermeyi gerektiriyor.
