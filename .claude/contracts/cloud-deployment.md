# Cloud Deployment

**Status:** Tamam (2026-08-22)
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §26.3 (dağıtım modları), §28.1 (kapma), §30.1 (izleme), §22.2 (izolasyon)

## Goal

Çok replikalı cloud kompozisyonu: ayrı ölçeklenen deployment'lar, süreçler arası canlı izleme,
ve cloud'a özgü olan üç şey — TLS, e-posta, sırlar.

## Kapatılması gereken bilinen sınır

#20'de yazıldı, #21'de tekrar edildi: `ExecutionUpdateBroker` süreç-yerel.

```text
Client ──watch(X)──▶ api-2          X'i süren: dispatcher-1
                     bellekteki liste
                     → hiçbir olay gelmiyor, akış da kapanmıyor
```

Tek düğümde doğru. Çok replikada **sessiz** bir arıza: hata yok, boş bir akış var — ki #20 bunun
"hiçbir şey olmuyor"dan ayırt edilemediğini yazıyor. Console'un demo ekranı cloud'da ilk bunu
yaşar.

## Karar: yayın Postgres `LISTEN/NOTIFY` üzerinden

```text
dispatcher-1                        api-2
   │ olay üretti                       │ watch(X) açık
   ▼                                   ▼
 NOTIFY pipemesh_execution ──────▶ LISTEN → kendi izleyicisine yazıyor
```

Veritabanı zaten paylaşılıyor; ikinci bir altyapı (Redis, NATS, Kafka) eklemek üçüncü bir
işletilecek şey demek. `LISTEN/NOTIFY` bunun için var ve bu ölçekte fazlasıyla yeter.

### Sıra numarası akışın özelliği, execution'ın değil

Bugün her broker kendi sayacını tutuyor. N replikada bu bozulur: iki pod aynı execution için
1'den saymaya başlar.

Çözüm sayacı paylaşmak **değil** — sıra numarasını **hizmet veren pod'un** vermesi. Bir izleyici
tek bir pod'dan besleniyor, dolayısıyla sıra o akış içinde monotonik; #20'nin "boşluk = bu sana
değil" anlamı da korunuyor çünkü filtrelemeyi aynı pod yapıyor.

Bunun kabul ettiği şey açıkça yazılmalı: **sıra numarası execution'lar arası bir imleç değil.**
`from_sequence` zaten uygulanmıyordu; bu onu kalıcı olarak "farklı bir iş" hâline getiriyor.

### `NOTIFY`'ın iki sınırı

**8000 bayt.** Büyük bir adım çıktısı bunu aşabilir. Aşan bildirim düşürülmüyor — yerine "bu
execution'da bir şey oldu, durumu yeniden oku" işareti gönderiliyor. Dürüst bozulma: izleyici
ayrıntıyı kaybediyor ama sessiz kalmıyor.

**Dayanıklı değil.** Dinlemeyen kaçırır. Bu bir gerileme değil — bugünkü broker da "live only"
ve aynı şeyi yapıyor.

## Üç deployment

```text
api           PIPEMESH_DISPATCH=off   gRPC sunar, iş sürmez        HPA: istek yükü
dispatcher    PIPEMESH_DISPATCH=on    iş sürer, çağrı almaz        HPA: kuyruk derinliği
console       Spring                  kimlik, plan, kota, ekranlar HPA: istek yükü
```

Veritabanı **verilmiş kabul ediliyor** — bu kompozisyon bir Postgres işletmiyor, birine bağlanıyor.
Şema `--migrate-only` ile ayrı bir job olarak koşuyor; uygulama pod'larının açılışı da güvenli
(advisory lock, #21) ama sıraya bağımlı olmamalı.

`api` ve `dispatcher` **aynı image**. Fark tek bir ortam değişkeni; ikinci bir artefakt yok.

## TLS ve sırlar

**TLS ingress'te sonlanıyor**, süreçlerin içinde değil. Küme içi trafik düz metin. Sertifika
yönetimini uygulamaya taşımak, her pod'a bir yenileme sorumluluğu vermek demek.

**Sırlar ortamdan.** Model anahtarları, veritabanı parolası, SMTP kimlik bilgisi — hiçbiri
konfigürasyon dosyasında. `ModelDefinition.secretFromEnvironment` zaten hangi değişkenin
tutacağını okuyor.

## E-posta

`LoggingVerificationLinkSender` bugün linki log'a yazıyor ve bunun ne demek olduğunu bağırıyor:
log'u okuyabilen herkes kaydolan her hesabı devralabilir. Cloud'da bu kabul edilemez.

Gerçek gönderici SMTP üzerinden, `@Primary` ile geliyor — `@ConditionalOnMissingBean` değil,
çünkü o bir `@Component` üzerinde hiçbir şey yapmıyor (#19'da öğrenildi).

## Acceptance Criteria

- [x] Bir pod'da sürülen execution, **başka bir pod'dan** izlenebiliyor (uçtan uca test)
- [x] İzleyicinin gördüğü sıra kendi akışında monotonik; iki pod arasında çakışma yok
- [x] 8000 baytı aşan güncelleme düşmüyor; "yeniden oku" işareti geliyor
- [x] Dinleyicisi olmayan bir bildirim kimseyi bozmuyor
- [x] `PIPEMESH_DISPATCH=off` pod'u iş sürmüyor ama izleme sunabiliyor
- [x] Şema `--migrate-only` job'ıyla kuruluyor; uygulama pod'ları da güvenle açılıyor
- [x] SMTP göndericisi yapılandırıldığında doğrulama linki e-posta ile gidiyor
- [x] SMTP yapılandırılmamışsa console **açılmıyor** — cloud'da log'a yazmak sessiz bir açık
- [x] Manifest'ler üç deployment'ı ayrı ölçeklenebilir tanımlıyor
- [x] Mevcut testler geçiyor — toplam 439 Java + 37 Python + 25 TypeScript

## Kapsam dışı

- **Ödeme.** #19'da da kapsam dışıydı; Stripe ayrı bir güvenlik yüzeyi.
- **Helm.** Düz manifest'ler; paketleme ayrı bir karar.
- **Otomatik ölçekleme politikası.** HPA hedefleri bir işletme kararı; kuyruk derinliği metriği
  gözlenebilirlik işi (§22.1).
- **`from_sequence` ile yeniden oynatma.** Yukarıdaki karar onu kalıcı olarak ayrı bir iş yapıyor.
- **Broker'ın Redis/NATS'a taşınması.** `LISTEN/NOTIFY` bu ölçekte yeter; SPI zaten takılacak yeri
  veriyor.

## Split Decision

**Decision:** single-prompt, üç aşama
**Tarih:** 2026-08-21

Contract net ve tek bir teknik karara dayanıyor: yayın nereden geçiyor. Geri kalanı o kararın
sonucu ya da konfigürasyon.

1. **Süreçler arası yayın** — `LISTEN/NOTIFY` üzerinde bir `ExecutionUpdatePublisher`,
   `ExecutionUpdateBroker`'ın ikinci bir kaynaktan beslenmesi, iki örnekli uçtan uca test.
2. **E-posta ve açılış katılığı** — SMTP göndericisi, ve yapılandırılmamışsa console'un
   açılmaması.
3. **Manifest'ler** — üç deployment, migrate job'ı, ingress.

### 1. aşamanın asıl sorusu: kim yayınlıyor, kim dinliyor

Broker bugün hem **gözlemci** (olayları üretiyor) hem **dağıtıcı** (izleyicilere yazıyor). Çok
replikada bu ikisi ayrılıyor: üreten pod yayınlıyor, dağıtan pod dinliyor, ve **çoğu zaman aynı
pod ikisini birden yapıyor**.

Doğru kesme noktası: broker'ın `deliver` yolu iki kaynaktan beslenebilmeli — kendi gözlemci
çağrılarından ve gelen bildirimlerden. Yayın tarafı ise gözlemciyi sarmalayan ince bir katman.

**Kendi bildirimini iki kez işlememeli.** Üreten pod olayı hem doğrudan hem `NOTIFY` üzerinden
görür; ya yayınlayan kendini elemeli ya da yerel teslimat bırakılıp her şey `NOTIFY`'dan
dönmeli. İkincisi daha az kod ve tek yol — ama tek düğümde gereksiz bir veritabanı gidiş
dönüşü ekliyor. Karar implementasyonda, ölçülerek değil gerekçeyle verilmeli ve yazılmalı.

### 2. aşama neden bir "katılık"

`LoggingVerificationLinkSender` on-prem için doğru varsayılan, cloud için sessiz bir açık.
Cloud kompozisyonunun bunu **açılışta reddetmesi** gerekiyor — çalışan ama hesapları log'a
sızdıran bir console, hiç açılmayan bir console'dan kötü.

### Kapsam dışı (ek)

- **Bildirim yükünün sıkıştırılması.** 8000 baytı aşan durum "yeniden oku" ile çözülüyor;
  sıkıştırma bir optimizasyon ve ölçüm ister.

### Risk points

- **`LISTEN` bağlantısı düşerse sessizce sağır kalmak.** Bir dinleyici bağlantısı koparsa hiçbir
  şey hata vermez, yalnızca olay gelmez — tam olarak kapatmaya çalıştığımız arızanın aynısı.
  Yeniden bağlanma ve bunun **görünür** olması gerekiyor.
- **Kendi bildirimini iki kez işlemek.** İzleyici her olayı iki kez görür; sıra numaraları da
  ikişer artar. Testi olmalı.
- **8000 bayt sınırının sessizce aşılması.** Postgres `NOTIFY`'ı reddeder ve bu bir istisna
  olarak gelir; yakalanmazsa olayı üreten adımı düşürebilir — §22.1'in "bir gözlemci
  execution'ı asla düşüremez" kuralı burada da geçerli.
- **`PIPEMESH_DISPATCH=off` pod'unun izleyebilmesi.** İzleme sürmeye bağlı olmamalı; aksi hâlde
  ayırmanın anlamı kalmaz. Kabul kriteri bunu ayrıca tutuyor.
- **Console'un açılışta ölmesi.** Katılık doğru ama yanlış yapılandırılmış bir cloud deploy'unda
  hata mesajı ne yapılacağını söylemeli, yalnızca "eksik" dememeli.

## Implementation Notes

**Tamamlandı:** 2026-08-22 — 6 yeni test (3 çapraz-süreç izleme, 3 cloud katılığı); toplam 439 Java.

### Yerel teslimat kaldı, `NOTIFY` üstüne eklendi

Preflight'ta ikilem olarak bırakılmıştı: her şey `NOTIFY`'dan mı dönsün, yoksa yerel teslimat
kalıp yayın üstüne mi eklensin. **İkincisi**, iki gerekçeyle: veritabanı olmayan bir kurulum
(on-prem bellek içi) birincisinde tamamen çalışmaz, ve tek düğüm kendisiyle konuşmak için
veritabanına gidip gelmez.

Yayınlayan kendi bildirimini eliyor — `publisherId` ile. Elemeseydi her akış ikiye katlanırdı;
testi var.

### Sıra numarası akışın özelliği

Broker'ın `deliver` yolu ikiye ayrıldı: olay **numarasız** üretiliyor ve yayınlanıyor,
numaralama izleyiciye hizmet veren süreçte oluyor. İki süreç bir sayacı kilit paylaşmadan
paylaşamaz, ve bir istemci zaten tek akış okuyor.

Filtre grubu da artık taşınmıyor, **türetiliyor** (`kindOf`): kural üreten ve sunan tarafta aynı
olsun diye.

### Bulunan: "api pod'u iş sürmez" doğru değildi

`PIPEMESH_DISPATCH=off` verdiğimde çapraz-süreç testi kırıldı — çünkü dispatch kapalıyken
`StartMode.INLINE` seçiliyordu, yani api replikası çağıranın thread'inde işi kendisi sürüyordu.
İki farklı soruyu tek bayrağa bağlamışım:

- **dispatch** — bu süreç bir sürücü döngüsü koşuyor mu
- **start** — `start()` işi çağıranın thread'inde mi sürüyor

Ayrıldılar: `PIPEMESH_START=inline|dispatched`. Varsayılan hâlâ ikisini birbirine bağlıyor
(dispatch kapalıysa inline), çünkü ikisine de hayır diyen **tek başına** bir süreç işi kabul
edip hiç koşmazdı. Cloud api replikası ikisine de hayır diyor ve bunu açıkça yazıyor.

### Bulunan: migrate hatırlamaya bağlıydı

Çapraz-süreç testi "could not create execution" ile başladı: `RuntimeAssembly.of()` migrate
etmiyordu, yalnızca `RuntimeMain` ediyordu. #21'in testleri sıraya bağlı olarak geçiyormuş —
biri migrate ediyor, sonrakiler ondan faydalanıyordu.

Artık birleştirici kendisi kuruyor. Bu kod tabanının kuralı zaten yazılıydı: *"durability that
depends on an embedder remembering is not durability."*

### Bulunan: `@ConditionalOnProperty` boş dizgiyi "var" sayıyor

SMTP göndericisini `console.mail.host` varlığına bağladım, ve `application.properties`'te
`${CONSOLE_MAIL_HOST:}` diye boş varsayılan tanımladım. Sonuç: gönderici **boş bir host'la**
etkinleşti ve 19 test düştü.

Boş varsayılanlar kaldırıldı; Spring `CONSOLE_MAIL_HOST` ortam değişkenini `console.mail.*`'a
zaten kendisi eşliyor. Tam da kaçınmaya çalıştığım sessiz yanlış yapılandırmanın kendisiydi.

### Katılık: paylaşılan deployment log'a link yazamaz

`console.cloud=true` iken `LoggingVerificationLinkSender` ile açılış **reddediliyor**. Çalışan
ama hesap linklerini log'a sızdıran bir console, hiç açılmayandan kötü — çünkü kimse fark etmez.

Hata mesajı ne yapılacağını söylüyor (`console.mail.host`, `console.mail.from`,
`console.baseUrl`), yalnızca "eksik" demiyor — preflight'ta risk olarak yazılmıştı.

### Üç deployment, iki image

`api` ve `dispatcher` aynı image, iki ortam değişkeni farkla. Manifest'lerde bu bir yorum değil,
görünen bir olgu.

### Devralınacak

- **Kuyruk derinliği metriği** — HPA'nın dispatcher'ı neye göre ölçekleyeceği; §22.1 işi.
- **Ödeme** — hâlâ kapsam dışı.
- **`from_sequence`** — bu contract onu kalıcı olarak ayrı bir iş yaptı.
