# Runtime Distribution

**Status:** Tamam (2026-08-21)
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §26.3 (dağıtım modları), §31 (konfigürasyon deposu), §15/§38 (kurtarma), §46 (başarı ölçütü)

## Goal

Runtime'ın çalıştırılabilir olması: bir konfigürasyon dizininden kurulan bir `main()`, kendi
şemasını kuran, kurtarmayı koşan, ve tek komutla ayağa kalkan bir image.

## Bugünkü boşluk

`src/main` altında tek `main()` console'da. DESIGN §26.3 şunu yazıyor:

```bash
docker run pipemesh/runtime
```

```python
mesh = PipeMesh("localhost:8080")
```

Python SDK gerçekten böyle bağlanıyor, ama bağlanacağı şeyi **üretimde kimse çalıştıramıyor**.
`TestRuntimeServer` var — test kapsamında, elle kurulmuş. Yani bugün çalışan tek runtime
süreci, gönderilmeyen bir sınıf.

Bu, iki dağıtım stratejisinin de ilk adımı; on-prem onsuz hiç mümkün değil.

## İki dağıtım, iki kompozisyon — iki kod tabanı değil

```text
                    aynı image'lar
                          │
        ┌─────────────────┴─────────────────┐
        ▼                                   ▼
   ON-PREMISE                            CLOUD
   runtime                               runtime + console
   müşterinin kimliği (ya da hiç)        ConsolePrincipalResolver
   plan yok, kota yok                    plan, kota, dispatcher
   tek kiracı                            çok kiracı
```

Aradaki her fark bugün bir takılabilir parça: `PrincipalResolver`, `watchPermissions`,
`ServerInterceptor` listesi, `StartMode`, `ExecutionLeases`. Hiçbiri motorda bir dal değil ve
öyle kalmalı.

**Korunacak kural:** motorun içine giren ilk `if (cloud)` kompozisyon sınırının çöktüğü andır.

**On-prem'de ölçüm yok — bilinçli.** On-prem sözleşmeyle ödenir, sayaçla değil. Gerekirse aynı
`QuotaInterceptor` yerel bir planla takılır; yine kompozisyon, yine dal değil.

## Karışmaması gereken iki eksen

DESIGN §26.3 **Embedded / Remote / Shared** diyor — "uygulama runtime'a nasıl ulaşır" ekseni.
On-prem/cloud ise "**kim işletiyor**" ekseni. Dikler: bir on-prem müşteri embedded de
kullanabilir remote da. "On-prem = embedded" denklemi yanlış ve kurulmamalı.

## Giriş noktası ne karar verir, ne vermez

**Verir:** hangi adım tipleri kayıtlı (bunlar kod, konfigürasyon değil), state store, port,
kurtarma zamanlaması, şema.

**Vermez:** kimlik, plan, kota. Bunlar cloud kompozisyonunun işi ve burada **yokluğu
varsayılan**.

**Ve yokluğunu yüksek sesle söyler.** `PrincipalResolver` verilmemişse her çağıran anonim, yani
izolasyon yok (§22.2). Bu on-prem tek kiracı için dürüst bir konum, ama sessiz olmamalı —
`LoggingVerificationLinkSender`'ın kendi eksikliğini bağırması gibi.

## Konfigürasyon yüzeyi

```text
PIPEMESH_CONFIG        konfigürasyon dizini (§31)         zorunlu
PIPEMESH_PORT          gRPC portu                         varsayılan 8080
PIPEMESH_DB_URL        var olan bir Postgres'e;
                       verilmezse bellek içi              opsiyonel
PIPEMESH_DB_USER
PIPEMESH_DB_PASSWORD
PIPEMESH_RECOVERY_INTERVAL                                varsayılan 1m
PIPEMESH_DISPATCH      execution'ları bu süreç sürsün mü  varsayılan on
```

**Runtime veritabanı işletmiyor, var olan birine bağlanıyor.** Compose'daki Postgres yalnızca
on-prem tek düğüm kolaylığı; cloud'da veritabanı zaten vardır ve ayrı yönetilir.

**`PIPEMESH_DISPATCH` aynı image'ı üç şekilde koşturuyor:** yalnızca API, yalnızca sürücü, ya da
ikisi. Cloud'da bunlar ayrı deployment olur — uzun workflow patlaması daha çok *sürücü* ister,
daha çok API pod'u değil, ve tek süreçte birleşiklerse API'yi ölçeklemek boşuna kira yarışı
üretir. On-prem'de varsayılan `on` ve tek süreç yeterli.

Model anahtarları **konfigürasyon dosyasında değil** ortam değişkeninde; `ModelDefinition`
zaten hangi değişkenin tutacağını okuyor, anahtarın kendisini değil. Dosyalar paylaşılır,
sırlar paylaşılmaz.

**Veritabanı verilmezse bellek içi store.** Denemek kolay olmalı — ama açılışta bunun ne demek
olduğu yazılmalı: sürecin ömrüyle biten bir dayanıklılık, dayanıklılık değildir.

## Şema kurulumu birden çok replikada

`SchemaMigrator` bugün transaction kullanıyor ama **kilit almıyor**. İki replika birlikte
açılırsa ikisi de "uygulanmamış" görür, ikisi de `CREATE TABLE` dener, biri patlar. Tek düğümde
hiç görünmeyen, çok replikada **her deploy'da** görünen bir arıza.

İki şey birden gerekiyor:

- **`pg_advisory_xact_lock`** ile migration serileşiyor. Küçük, Postgres-yerlisi, ve kim
  çağırırsa çağırsın doğru — açılışta, init job'da, elle.
- **`--migrate-only`** modu: cloud şemayı ayrı bir adım olarak koşabilsin, uygulama pod'larının
  açılışına bağlamak zorunda kalmasın.

## Bilinen sınır: çok replikada canlı izleme

`ExecutionUpdateBroker` süreç-yerel — izleyiciler bellekte bir `Map`'te.

```text
Client ──watch(X)──▶ pod A          X'i süren: pod B
                     bellekteki liste
                     → hiçbir olay gelmiyor, akış da kapanmıyor
```

Tek süreçte doğru; çok replikada **sessiz** bir arıza — hata yok, boş bir akış var, ki #20 bunun
"hiçbir şey olmuyor"dan ayırt edilemediğini yazıyor.

Bu contract tek düğümü kapsıyor ve orada sorun yok. Çözümü — olayları Postgres
`LISTEN/NOTIFY` üzerinden yayınlayıp her pod'un her izlemeyi karşılayabilmesi — **#22'nin işi**,
ve o yapılana kadar cloud'da canlı izleme tek replikaya bağlı. Bilerek yazılıyor; sessizce
bırakılmıyor.

## Acceptance Criteria

- [x] `pipemesh-runtime` modülü bir `main()` taşıyor ve konfigürasyon dizininden kuruluyor
- [x] Kendi şemasını migrate ediyor; console olmadan da veritabanı hazır hâle geliyor
- [x] Kurtarma zamanlayıcısı varsayılan olarak koşuyor (unutulabilir olmamalı)
- [x] `PrincipalResolver` verilmemişse açılışta izolasyonun olmadığı yazılıyor
- [x] Veritabanı verilmemişse bellek içi store ve bunun anlamı açılışta yazılıyor
- [x] Model sırları yalnızca ortamdan; konfigürasyon dosyalarında anahtar yok
- [x] `TestRuntimeServer` elle kurulumu bırakıp aynı kurucuyu kullanıyor — gönderilen şeyle
      test edilen şey aynı olsun
- [x] Python SDK, image'dan çalışan runtime'a bağlanıp bir workflow koşuyor
- [x] `PIPEMESH_DISPATCH=off` ile süreç API sunuyor ama execution sürmüyor
- [x] `PIPEMESH_DISPATCH=on` tek başına koşan bir süreçte iş bitiriyor
- [x] Eşzamanlı iki migration çağrısı yarışmıyor; biri bekliyor, ikisi de başarılı
- [x] `--migrate-only` şemayı kurup çıkıyor, sunucu açmıyor
- [x] Tek komutlu **on-prem**: `docker compose up` ile runtime + Postgres
- [x] Image root olmayan bir kullanıcıyla koşuyor
- [x] `pipemesh-console` bu modüle bağımlı **değil** (kompozisyon, kalıtım değil)
- [x] Mevcut testler geçiyor — toplam 433 Java + 37 Python + 25 TypeScript

## Kapsam dışı

- **Cloud kompozisyonu.** Console, ayrı deployment'lar, plan/kota, TLS, e-posta — #22.
- **Çok replikada canlı izleme.** Yukarıda yazılı; `LISTEN/NOTIFY` yayını #22.
- **Kubernetes / Helm.** Compose **yalnızca on-prem** için; cloud ölçeklenen servisleri ayrı
  deployment olarak koşar ve o manifest'ler #22'nin işi.
- **Embedded mod için ayrı bir paket.** Embedded zaten kütüphaneyi doğrudan kullanmak demek;
  bu contract süreç modunu kapatıyor.
- **Image'ın yayınlanması.** Nereye push edileceği ve sürümleme ayrı bir iş.

## Split Decision

**Decision:** single-prompt, üç aşama
**Tarih:** 2026-08-21

Küçük görünüyor ve büyük kısmı gerçekten hazır: `ConfigRepository` workflow'ları, modelleri,
capability'leri, intent'leri, şemaları ve prompt'ları zaten okuyor. Eksik olan **birleştirici** —
bunları adım yürütücülerle birleştirip bir sunucu kuran parça. Bugün o parça yalnızca
`TestRuntimeServer`'da, elle, ve test kapsamında var.

1. **Birleştirici** — `RuntimeAssembly`: konfigürasyon + ortam → kurulmuş bir `PipeMeshServer`.
   Şema kurulumu ve kurtarma zamanlaması burada, unutulamaz biçimde.
2. **Giriş noktası ve image** — `main()`, Dockerfile, root olmayan kullanıcı, compose.
3. **Tek kaynak** — `TestRuntimeServer` aynı birleştiriciyi kullanmaya geçiyor.

### 3. aşama isteğe bağlı değil

Bugün `TestRuntimeServer` runtime'ı elle kuruyor. Birleştiriciyi yazıp onu bırakırsak, SDK
testlerinin kanıtladığı şey **gönderdiğimiz şey olmayan** bir kurulum olur. Aynı kurucudan
geçmeleri, bu contract'ın asıl güvencesi.

### Modül nereye

Yeni bir `pipemesh-runtime` modülü. `pipemesh-grpc`'ye eklemek cazip ama yanlış: grpc modülü
bir **sınır**, bu ise bir **uygulama** — Postgres'i, model sağlayıcılarını, MCP'yi ve OTLP'yi
birden bilmesi gerekiyor, ve sınır modülünün hiçbirini bilmemesi gerekiyor.

Bağımlılık yönü console'unkiyle aynı: uygulama modülleri kütüphane modüllerine bakar, tersi
asla. `ModuleBoundaryTest`'in listesi bir isim daha büyüyor.

### Kapsam dışı (ek)

- **Konfigürasyonun sıcak yeniden yüklenmesi.** Dosya değişince yeniden okumak cazip ve ayrı
  bir karar: koşan bir execution hangi sürümü görür sorusu #9'un cevapladığı soruyla aynı, ve
  onu tekrar açmak gerekiyor.

### Risk points

- **Sessiz açılış.** Kimlik çözücü yoksa izolasyon yok; veritabanı yoksa dayanıklılık yok.
  İkisi de yasak değil — on-prem tek kiracı ve yerel deneme için doğru varsayılanlar — ama
  ikisi de **açılışta yüksek sesle** söylenmeli. Sessizce kabul edilen bir eksiklik, bir gün
  fark edilmeyen bir olaydır.
- **Sırların dosyaya sızması.** `ModelDefinition.secretFromEnvironment` doğru şeyi yapıyor;
  birleştirici onu atlayıp `settings` içinden anahtar okuyacak bir kolaylık eklememeli. Testi
  olmalı.
- **Bellek içi store'un varsayılan olması.** Kolaylık ile yanlış üretim kurulumu arasındaki
  fark tek bir ortam değişkeni. Uyarı yetmeyebilir; en azından tek satırda görünmeli.
- **Image'ın boyutu ve kullanıcısı.** Root koşan bir image on-prem müşterisinin güvenlik
  incelemesinde takılır — sonradan düzeltmek, dağıtılmış bir image'ı geri çağırmak demek.
- **`docker compose up` gerçekten çalışmalı.** Çalışmayan bir "tek komut" vaadi, hiç vaat
  etmemekten kötü. Kabul kriteri elle değil koşularak doğrulanmalı.

## Implementation Notes

**Tamamlandı:** 2026-08-21 — 10 yeni test, toplam 433 Java. `docker compose up` elle değil
**koşularak** doğrulandı: Python SDK container'a bağlandı, workflow tamamlandı, ve iki execution
runtime yeniden başlatıldıktan sonra veritabanında duruyordu.

### Test sunucusu gerçek birleştiriciye geçti — ve bir eksiği ortaya çıkardı

3. aşama "isteğe bağlı değil" diye yazılmıştı, haklıymış. `TestRuntimeServer`'ı `RuntimeAssembly`
üzerine taşıyınca SDK testleri **kırıldı**: birleştirici intent'leri hiç kurmuyordu, oysa SDK
testleri `process()` çağırıyor ve `ConfigRepository.intents()` zaten var.

Yani elle kurulmuş test sunucusu, gönderilecek şeyin yapamadığı bir şeyi yapıyordu ve kimse fark
etmiyordu. Taşımanın tek amacı buydu.

Workflow'lar da dizeden dosyaya taşındı (`sdk/testdata/`), çünkü birleştirici bir dizin okuyor —
SDK testleri artık konfigürasyon yolunu da sınıyor.

### Recovery ile dispatch aynı sayıyı paylaşamaz

İlk duman testinde runtime `CREATED` döndürüp öylece duruyordu. Sebep benim kısayolum:
dispatcher'a kurtarma aralığını (1 dakika) vermiştim. İkisi farklı soru soruyor — kurtarma
"bir süreç öldü mü", nadir ve pahalı; dispatch "iş var mı", sürekli ve tek indeksli sorgu.
Ayrıldılar: `PIPEMESH_DISPATCH_INTERVAL`, varsayılan 1 saniye.

### Migration eşzamanlı açılışta kilitleniyor

`pg_advisory_xact_lock`. Transaction tek başına yetmiyordu: izolasyon okumaları ayırır, iki
yazarın aynı şeye karar vermesini engellemez. Testi dört replikayı aynı anda migrate ettiriyor ve
sıfır hata bekliyor — kaybedenler bekliyor, patlamıyor.

`--migrate-only` de var, cloud şemayı ayrı bir adım olarak koşabilsin diye.

### İlk koşum bir API anahtarı istememeli

Compose çalıştı ama README'nin ilk komutu `examples/approval-flow`'u işaret ediyordu ve o bir
`llm` adımıyla başlıyor — anahtarsız haklı olarak `FAILED`. Bir ürünün ilk denemesi vendor hesabı
istememeli. `examples/hello` eklendi: bir koşul, iki son, sıfır dış bağımlılık. Compose artık ona
bakıyor.

### Fat jar yerine jar + lib

Shade eklentisinin bir bağımlılığı yerel önbellekte yoktu ve build offline. `lib/` dizini hem
çözüm hem daha iyisi: container katmanı ayrışıyor, ve `META-INF/services` birleştirme tehlikesi
hiç doğmuyor — gRPC taşımalarını o dosyalardan buluyor, birini düşüren bir birleştirme açılışta
şaşırtıcı bir "no provider" olurdu.

### Dockerfile modül listesi tutmuyor

İlk hâli modülleri tek tek kopyalıyordu ve **ilk koşuşta kırıldı**: parent pom `pipemesh-console`
diyor, image onu kopyalamıyordu, Maven her modülü okuyor. `COPY . .` + `.dockerignore` — yeni
modül eklendiğinde çürümeyen tek biçim.

### Modül sınırı bir isim daha büyüdü

`ModuleBoundaryTest` artık kütüphane modüllerinin ne console'a ne runtime uygulamasına bağımlı
olmadığını, **ve console'un runnable runtime'a bağımlı olmadığını** doğruluyor. İkisi yan yana
iki kompozisyon; biri diğerinin üstüne kurulu değil.

### Yolda: kapattığım kusur bir yarışı görünür yaptı

`DemoTest` düştü — ama sebebi yeni kod değil, **eski bir yarıştı**. Demo execution'ı başlatıp
sonra izlemeye başlıyor; 100 ms'de bir bakan bir dispatcher işi kimse dinlemeden bitirebiliyor.
Önce bu, `WatchExecution`'ın sonsuza kadar asılmasıyla örtülüyordu (aynı contract'ta düzeltildi);
artık akış temiz kapanıyor ve olayların olmadığı hemen görünüyor.

Yani düzeltme testi kırmadı, testin zaten yalan söylediğini ortaya çıkardı. Demo runtime'ının
dispatch aralığı 2 saniyeye çıkarıldı — üretimde de bir sürücü işi milisaniyede aramaz, ve
gerekçe kodda yazılı.

### Devralınacak

- **Çok replikada canlı izleme** — `ExecutionUpdateBroker` süreç-yerel; #22.
- **Image'ın yayınlanması** ve sürümleme.
