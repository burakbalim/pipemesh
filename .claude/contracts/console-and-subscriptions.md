# Console and Subscriptions

**Status:** Tamam (2026-08-21)
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §22.2 (organizasyon izolasyonu), §23 (kimlik ve izin), §26.2 (Runtime/SDK/Provider), §39.1 (harcama muhasebesi)

## Goal

Deploy edilmiş bir PipeMesh'e insanların kendi kendine kaydolup demo planıyla workflow
çalıştırabilmesi: organizasyon kaydı, plan/kota aboneliği ve bunları yöneten bir web arayüzü.

## Bu, depodaki ilk ürün katmanı

Bugüne kadar her şey kütüphane şeklindeydi: altı Maven modülü, iki SDK, tek dış sınır gRPC.
Console bunlardan biri değil — bir **uygulama**. Bu yüzden bağımlılık yönü tek yönlü ve sert:

```text
pipemesh-console  ──depends on──▶  pipemesh-grpc, pipemesh-core
pipemesh-core     ──depends on──▶  (hiçbir zaman console'a)
```

Spring, web katmanı, oturum, parola — hepsi console'un içinde. `core/`'un framework-free olması
(§26.2) bu contract'tan sonra da doğru kalıyor; console'un Spring kullanması bunu bozmuyor,
çünkü console runtime değil.

## Console, `PrincipalResolver`'ın bugüne kadar boş kalan cevabı

`PrincipalResolver`'ın javadoc'u şunu söylüyor:

> The runtime cannot authenticate anyone — it has no idea what a valid token looks like in your
> deployment — so this is where an application plugs in the answer.

Ve DESIGN §22.2 şunu:

> A deployment that identifies nobody has no isolation, which is a property of not authenticating
> anyone rather than something the runtime can fix.

Bugün varsayılan `ANONYMOUS`. Console, "kimseyi tanımlamayan deployment"ı bitiren şey: API
anahtarı üretiyor, gRPC sınırındaki resolver onu doğruluyor, ve `Principal` nihayet gerçek bir
organizasyon taşıyor. #17'nin kurduğu izolasyon ilk kez gerçek bir kimliğe dayanıyor.

## Kota abonelik kavramı, bütçe execution kavramı

Karıştırılmaması gereken iki şey:

| | `CostBudget` (#11) | Kota (bu contract) |
|---|---|---|
| Neyi durdurur | Tek bir execution'ı | Organizasyonun tüm çalışmasını |
| Ömrü | Execution boyunca | Dönemsel, sıfırlanıyor |
| Kim yazdı | Workflow yazarı | Abonelik planı |
| Nerede bakılıyor | Motorun içinde, adım aralarında | Sınırda, execution başlamadan önce |

Kota **motora girmiyor**. Motor abonelik diye bir şey bilmiyor ve bilmemeli (§3). Kontrol
`StartExecution`'da, iş başlamadan önce. Girdi ise #11'in zaten yazdığı `Spend`: bir execution
bittiğinde harcadığı, organizasyonun dönemsel sayacına ekleniyor.

## Plan yalnızca sayı değil, yetenek de taşıyor

Kota bir plandaki sayılar; ama bir planın verdiği tek şey miktar değil. Canlı izleme
(`WatchExecution`, #20) açılıp kapanabilen bir özellik ve kararı planın:

```text
plan          → hangi izinler
API anahtarı  → o izinleri taşıyan Principal
gRPC sınırı   → izni olmayanı reddediyor
```

Mekanizma yeni değil: `Principal.holds(permission)` #8'den beri var ve #20 `WatchExecution`'ı
ona bağlıyor. Console'un yaptığı, planın izin listesini API anahtarının principal'ına
koymaktan ibaret. Motor hâlâ plan diye bir şey bilmiyor.

Kullanıcı bunu console'dan açıp kapatabiliyor — planı izin veriyorsa. Kapalıyken çağrı
`PERMISSION_DENIED` alıyor, boş bir akış değil (#20'nin kararı).

## Demo bir plan, özel durum değil

`if (isDemo)` yok. Demo, `plan` tablosunda küçük sayılar taşıyan bir satır. Aksi hâlde insanların
denediği yol, satın alacakları yoldan farklı bir kod yolu olur — ve demoda çalışan şeyin üründe
çalışacağının garantisi kalmaz.

Demo'nun küçük olması bir konfigürasyon, davranış değil.

## Halka açık bir demo, güvenlik yüzeyidir

Deploy edilmiş demo'ya herkes kaydolabiliyorsa:

- Kota **kaydolmadan önce** değil, kaydolduktan sonra da geçerli olmalı — e-posta doğrulaması
  bir kişinin sınırsız organizasyon açmasını engellemiyor, kota engelliyor
- Demo organizasyonunun ulaşabildiği capability kümesi ayrıca sınırlı olmalı; §23'ün izin
  modeli bunu zaten ifade edebiliyor, kullanmak gerekiyor
- Parola argon2 ile saklanıyor, doğrulama linki tek kullanımlık ve süreli
- API anahtarı yalnızca üretildiği anda görünüyor; saklanan şey hash'i

## API Contract

```text
POST /api/v1/organizations          organizasyon + ilk kullanıcı; demo planına düşer
POST /api/v1/sessions               e-posta + parola → oturum
POST /api/v1/verifications/{token}  e-posta doğrulama, tek kullanımlık
GET  /api/v1/organization           kim olduğum, planım, dönemsel kullanımım
POST /api/v1/api-keys               yeni anahtar; düz hâli yalnızca bu cevapta
DELETE /api/v1/api-keys/{id}        anahtarı iptal et
GET  /api/v1/usage                  dönem içi harcama: execution, token, maliyet
GET  /api/v1/plans                  plan listesi ve limitleri
```

Gövde adlandırması `snake_case` değil **camelCase** — bu depodaki JSON'ların tamamı öyle
(workflow tanımları, proto JSON eşlemesi). Monorepo'nun REST alışkanlığı burada geçerli değil.

## DB Schema Changes

Console'un kendi tabloları, `workflow_*` tablolarından ayrı:

```text
console_organization   id, name, plan_id, created_at
console_user           id, organization_id, email, password_hash, verified_at
console_verification   token_hash, user_id, expires_at, used_at
console_api_key        id, organization_id, key_hash, prefix, created_at, revoked_at
console_plan           id, name, max_executions, max_tokens, max_cost_micros, period, permissions
console_usage          organization_id, period_start, executions, tokens, cost_micros
```

`console_usage` bir sayaç, geçmiş defteri değil: geçmişi `workflow_execution` zaten tutuyor ve
iki yerde tutmak ikisinin ayrışması demek.

## Web UI

```text
/signup     organizasyon + kullanıcı oluştur
/verify     doğrulama linkinin indiği yer
/signin     oturum
/           kullanım / kota (bu dönem ne harcandı), plan
/keys       API anahtarları — üret, iptal et
/demo       çalıştırılabilir örnek workflow ve sonucu
```

`/demo` ekranı bu contract'ın "insanlar demoyu kullanabilsin" kısmı: hazır bir workflow,
çalıştır düğmesi, ve execution'ın canlı ilerleyişi. Altında hiçbir özel yol yok — kullanıcının
kendi API anahtarıyla, kendi organizasyonunda, aynı gRPC sınırından geçiyor.

## Acceptance Criteria

- [x] Kayıt bir organizasyon + bir kullanıcı yaratıyor ve demo planına düşürüyor
- [x] Doğrulanmamış kullanıcı oturum açamıyor
- [x] Doğrulama linki ikinci kez kullanılamıyor
- [x] Süresi geçmiş doğrulama linki reddediliyor
- [x] Parola hiçbir yerde düz saklanmıyor; hash argon2
- [x] API anahtarının düz hâli yalnızca üretim cevabında dönüyor; veritabanında hash'i var
- [x] İptal edilmiş anahtarla gRPC çağrısı `PERMISSION_DENIED`
- [x] Geçerli anahtar `Principal`'ı doğru organizasyonla dolduruyor
- [x] Bir organizasyonun anahtarı başka organizasyonun execution'ını okuyamıyor (#17 ile uçtan uca)
- [x] Kota dolmuşsa `StartExecution` reddediliyor, iş başlamadan
- [x] Biten execution'ın `Spend`'i dönemsel sayaca ekleniyor
- [x] Dönem değişince sayaç sıfırlanıyor, plan limitleri değişmiyor
- [x] Demo planı `plan` tablosunda bir satır; kodda `isDemo` dalı yok
- [x] Plan izin listesi taşıyor; API anahtarının `Principal`'ı onu taşıyor
- [x] Canlı izleme kapalıyken `WatchExecution` `PERMISSION_DENIED` veriyor
- [x] Kullanıcı console'dan canlı izlemeyi açıp kapatabiliyor; etkisi bir sonraki çağrıda
- [x] Planın vermediği bir izni kullanıcı kendine açamıyor
- [x] Demo organizasyonu ilan edilmemiş capability'lere ulaşamıyor
- [x] `/demo` ekranı gerçek bir workflow'u uçtan uca çalıştırıp sonucu gösteriyor
- [x] `pipemesh-core` hiçbir console sınıfına bağımlı değil (`mvn dependency:tree` ile)
- [x] Mevcut testler geçiyor — toplam 422 Java + 37 Python + 25 TypeScript

## Kapsam dışı

- **Gerçek ödeme.** Plan ve kota defteri var, Stripe yok. Ödeme sağlayıcısı ayrı bir contract
  ve ayrı bir güvenlik yüzeyi (webhook imzası, secret yönetimi).
- **Plan değiştirme akışı.** Ödeme olmadan "yükselt" düğmesinin anlamı yok; plan bugün elle
  değiştiriliyor.
- **Organizasyon başına izleme kotası.** Bugün izin var/yok; "ayda şu kadar izleme saati"
  ölçmeyi gerektiriyor ve ayrı bir iş.
- **Kullanıcı davet etme / roller.** Organizasyon başına tek kullanıcı yeterli; çok kullanıcı ve
  rol modeli ayrı bir iş.
- **Workflow düzenleme arayüzü.** Console kullanımı ve kimliği yönetiyor, workflow yazmayı değil.

## Split Decision

**Decision:** multi-agent (dikey dilimler) — ama **temel önce, tek elden**
**Tarih:** 2026-08-21

Bu, depodaki ilk contract ki bölmeyi gerçekten hak ediyor: dört katman (Spring backend, web UI,
kendi şeması, gRPC sınırına bağlanma) ve contract net — API, tablolar, ekranlar yazılı.

Ama dilimler **birbirinden bağımsız değil**, ve depoda Spring kodu için henüz hiçbir konvansiyon
yok. Paralel ajanlar her biri kendi kalıbını icat ederdi. Bu yüzden:

```text
Aşama 0  (tek elden)   temel: modül iskeleti, şema, organizasyon + kullanıcı, oturum
   │
   ├── Dilim A  (paralel)   API anahtarları + PrincipalResolver köprüsü
   ├── Dilim B  (paralel)   plan, kota, kullanım sayacı
   │
   └── Dilim C  (ikisinden sonra)   demo ekranı
```

### Aşama 0 — temel (tek elden)

`pipemesh-console` Maven modülü, `console_*` şeması ve migration, organizasyon + kullanıcı
kaydı, argon2 parola, e-posta doğrulama, oturum. `/signup`, `/verify`, `/signin` ekranları.

Bu aşama aynı zamanda **konvansiyonu kuruyor**: Spring katmanlaması, hata gövdesi biçimi,
test kalıbı (Testcontainers + MockMvc), frontend dosya düzeni. Sonraki dilimler ona bakarak
yazılıyor.

### Dilim A — anahtarlar ve köprü

`console_api_key`, üretme/iptal, hash'lenmiş saklama, `/keys` ekranı. Ve asıl parça:
`ConsolePrincipalResolver` — gRPC metadata'sındaki anahtarı doğrulayıp organizasyonu ve planın
izinlerini taşıyan bir `Principal` üretiyor. `PrincipalResolver`'ın javadoc'unda üç contract'tır
bekleyen "an application plugs in the answer" cümlesi burada kapanıyor.

### Dilim B — plan, kota, kullanım

`console_plan`, `console_usage`, dönemsel sayaç. `StartExecution` öncesi kota kontrolü ve biten
execution'ın `Spend`'inin sayaca eklenmesi. Kullanım/kota panosu.

**Kotanın nereye takılacağı bu dilimin asıl kararı.** Motora girmiyor (§3). Doğal yer, çağrıyı
zaten kesen bir gRPC interceptor'ı — `CallMetadata` deseninin ikinci kullanıcısı.

### Dilim C — demo

Hazır bir örnek workflow, "çalıştır" düğmesi, canlı ilerleme. #20'nin `step.started`'ı tam
buranın için: demo bir adımda takılıp sessiz kalmamalı.

## Tarayıcı gRPC konuşmuyor

Canlı ilerleme için tarayıcının `WatchExecution`'a bağlanması gerekiyor, ama tarayıcılar gRPC
server streaming'i doğrudan konuşamıyor — grpc-web ve bir proxy gerekir.

Bunun yerine **console backend'i akışı SSE olarak yeniden yayınlıyor**. Tarayıcı yalnızca
console ile konuşuyor; oturum çerezi zaten orada, ikinci bir kimlik yolu açılmıyor, ve Envoy
gibi bir bileşen deploy'a girmiyor.

Bu, #20'nin kararını doğruluyor: olay modeli transport'tan bağımsız tutulmuştu, ve ilk ikinci
transport buradan geldi.

## Frontend: React + Vite, `console/web`

Altı ekran, formlar ve canlı güncellenen bir görünüm. Angular bu iş için çok fazla şey
getiriyor; düz TypeScript ise canlı ekranı elle yazdırırdı. Depo zaten TypeScript araç zinciri
taşıyor (SDK), aynı dilde kalmak ikinci bir yapı hattı açmıyor.

### Risk points

- **Framework-free kuralının delinmiş görünmesi.** `pipemesh-core`'un console'a bağımlı
  olmadığı **testle** kanıtlanmalı (`dependency:tree`), yorumla değil. Bir modül bir kez yanlış
  yöne bağlandığında geri almak çok pahalı.
- **Demo'nun ayrı bir kod yolu olması.** `/demo` ekranı kullanıcının kendi anahtarıyla, kendi
  organizasyonunda, aynı gRPC sınırından geçmeli. Kısa yol açmak, demoda çalışanın üründe
  çalışacağı garantisini bitirir.
- **Kota kontrolünün yanlış yere düşmesi.** Motora girerse §3 bozulur ve geri alması zor.
  Interceptor, ve orada kalmalı.
- **Parola ve anahtar.** Argon2 parametreleri seçilmeli ve yazılmalı; anahtar düz hâliyle
  yalnızca üretim cevabında dönmeli. İkisi de sonradan düzeltilmesi en pahalı şeyler.
- **E-posta gönderimi.** Doğrulama linki kritik yolda: SMTP çalışmazsa kimse kaydolamıyor.
  Geliştirmede linkin log'a yazılması yeterli; üretimde bir sağlayıcı gerekiyor ve bu bir
  deploy bağımlılığı.
- **Dilimlerin şemayı ayrı ayrı değiştirmesi.** Migration dosyaları tek numaralı sıra
  paylaşıyor; iki dilim aynı numarayı alırsa biri sessizce koşmaz. Aşama 0 tüm tabloları
  yazsın, dilimler yalnızca doldursun.

### Ajan bölmesi hakkında

Bölme yukarıda planlandı, ama **ajanları ancak istenirse başlatırım**. İstenmezse aynı sıra
tek elden koşulur — A ve B'nin paralelliği sadece takvimi kısaltır, sonucu değiştirmez.

## Implementation Notes

### Aşama 0 — temel (2026-08-21)

`pipemesh-console` modülü ayakta: kayıt, e-posta doğrulama, oturum ve dört ekran. 21 yeni test
(9 kayıt, 9 oturum, 3 modül sınırı) — toplam 391 Java. SDK'lar etkilenmedi (37 Python, 25 TS).

**Spring BOM'u sırayı ele geçirdi.** `spring-boot-dependencies`'i parent'ın
`dependencyManagement`'ının **başına** koymak testcontainers sürümünü 1.20.4'ten 1.20.5'e
kaydırdı ve offline build kırıldı. `dependencyManagement`'ta ilk beyan kazanıyor; BOM sona
alındı ve yorumu yazıldı: **çalışma zamanı modüllerinin sürümlerini Spring seçmez.** Bir
framework'ün build'in ortasına bırakıldığında yaptığı şeyin ders niteliğinde örneği.

**Modül sınırı yorumla değil testle tutuluyor.** `ModuleBoundaryTest` altı çalışma zamanı
modülünün pom'unu okuyup ne console'a bağımlı olduklarını ne de Spring taşıdıklarını
doğruluyor. Yanlış yöne bağlanmış bir modül eklemesi ucuz, geri alması pahalı — fark edildiğinde
framework-free sözü zaten devralan herkes için bozulmuş olur.

**`@ConditionalOnMissingBean` sessizce hiçbir şey yapmıyor.** `LoggingVerificationLinkSender`'ı
öyle işaretlemiştim; o anotasyon yalnızca auto-configuration'daki `@Bean` metotlarında anlam
taşıyor, `@Component` üzerinde değil — context hiç ayağa kalkmadı. Düz `@Component` oldu, gerçek
gönderici `@Primary` ile geliyor. Hiçbir şey yapmayan bir koşul, koşulsuz olmaktan kötü.

**Token'lar SHA-256, parola argon2 — ve gerekçesi yazılı.** Doğrulama linki ve oturum token'ı
256 bit rastgelelik; tahmin edilecek bir şey yok, yavaş hash'in satın alacağı bir şey de yok.
Parola tam tersi durum. İkisini aynı şekilde saklamak, birinde gereksiz maliyet diğerinde
yetersiz koruma demek olurdu.

**Doğrulama linki tek ifadede harcanıyor.** `UPDATE ... WHERE used_at IS NULL AND expires_at > ?
RETURNING user_id`. Önce oku sonra yaz olsaydı, aynı anda gelen iki tıklama ikisi de boşluğu
bulurdu.

**Giriş reddi tek mesaj, doğrulanmamış hesap ayrı.** Yanlış adres ile yanlış parolayı ayırmak,
giriş formunu "hangi adreslerin hesabı var" sorgusuna çevirirdi. Doğrulanmamış hesap farklı:
kişi parolayı zaten bildiğini kanıtladı, dolayısıyla ona ne olduğunu söylemek hiçbir şey
sızdırmıyor — ve "e-posta veya parola yanlış" demek ona bir öğlen kaybettirirdi.

**Demo planı bir satır.** `V101__console_identity.sql` içinde `INSERT INTO console_plan`. Kodda
`isDemo` dalı yok, olmayacak.

### Dilim A — anahtarlar ve köprü (2026-08-21)

16 yeni test (11 anahtar/resolver, 5 uçtan uca HTTP) — toplam 407 Java.

**`PrincipalResolver`'ın javadoc'u kapandı.** Üç contract'tır orada duran cümle —
*"this is where an application plugs in the answer"* — artık bir implementasyona işaret ediyor.
`ConsolePrincipalResolver` metadata'daki anahtarı hash'leyip arıyor, organizasyonu ve **planın
izinlerini** taşıyan bir `Principal` üretiyor. §22.2'nin "kimseyi tanımlamayan deployment"
uyarısı bu deployment için artık geçerli değil.

**İzinler anahtarda değil planda.** `changingThePlanChangesWhatExistingKeysMayDo` bunu tutuyor:
abonelik içeriği değişince mevcut anahtarların yapabildikleri değişiyor, hiçbir şey yeniden
üretilmiyor. İzni anahtara kopyalamak, plan değişiminin sessizce etkisiz kalması demekti.

**Dört ret durumu birbirinden ayırt edilemiyor.** Başlık yok, bozuk, bilinmeyen, iptal edilmiş —
hepsi `ANONYMOUS`. Ve anonim olmak bir hata değil: izin istemeyen capability yine koşuyor, ret
gereksinimin bulunduğu yerde oluyor — ki neyin gerektiğini bilen tek yer orası.

**Sahiplik `WHERE`'de, önceden kontrolde değil.** `revoke(id, organizationId)` tek ifade; kontrol
ile yazma iki ayrı an olurdu ve yalnızca biri önemli olurdu. Başkasının anahtarını iptal etmek
"hiç yoktu" cevabını alıyor — ayırt etmek, hesabı olan herkese anahtar id'si arama imkânı verirdi.

**Gizli anahtar yalnızca üretim cevabında.** Saklanan hash; kaybolan anahtar kurtarılmıyor,
yenisi veriliyor. Geri gösterebilen bir console, console erişimi olan herkesin ürettiği her
anahtarı devralabildiği bir console olurdu.

**Yolda:** `@PathVariable String id` çalışmadı — Spring parametre adlarını okuyor, javac
`-parameters` olmadan atıyor, ve `spring-boot-starter-parent` kullanmadığımız için bayrak
gelmiyordu. Console modülüne eklendi; çalışma zamanı modüllerine bulaşmıyor.

### Dilim B — plan, kota, kullanım (2026-08-21)

8 yeni test — toplam 415 Java.

**Contract'ın şeması değişti, gerekçesi contract'ın kendi cümlesi.** `console_usage` tablosu
yazılmıştı; yazılmadı. Contract zaten şunu söylüyordu: *"geçmişi `workflow_execution` zaten
tutuyor ve iki yerde tutmak ikisinin ayrışması demek."* Sayaç tam olarak o ikinci yer olurdu —
yazma ile artırma arasında çöken bir process, bir backfill, bir bug: her biri iki sayıyı
ayırır. Kullanım artık `workflow_execution` üzerinde bir `SUM`. Bedeli her kota kontrolünde bir
toplama; sayaç ileride bir **önbellek** olarak eklenebilir, çünkü türetilebilir olan şey
önbelleklenebilir.

**Kota motora girmiyor, sınırda duruyor.** `QuotaInterceptor` yalnızca `StartExecution` ve
`ProcessMessage`'ı kesiyor. Okuma, devam ettirme ve izleme plana bir şey harcamıyor; onları
reddetmek birinin zaten ödediği işi ortada bırakırdı.

`RESOURCE_EXHAUSTED`, `PERMISSION_DENIED` değil: çağıranda bir sorun yok ve aynı çağrı gelecek
dönem çalışacak. İkisini karıştırmak, "tekrar deneme" ile "erken denedin" arasındaki farkı
silmek olur.

**Ön-kontrolün `>=`'i, #11'in `>`'inden farklı ve nedeni yazılı.** Workflow bütçesi *sonradan*
soruyor — "koşan şey aştı mı?" — dolayısıyla sınıra tam oturmak sorun değil. Kota *önceden*
soruyor: elli/elli olmak, bir sonrakinin elli birinci olması demek.

**Dönem hesabı takvim ayına değil, hesabın açıldığı güne bağlı.** Ayın 30'unda kaydolan birine
bir günlük dönem vermek istemiyoruz.

**Deployment iki şemayı da kuruyor.** Console, çalışma zamanının migration'larını da koşuyor —
kendi verisi onun tablolarını okuyor, ve yarısını kurmak "açılan ama ne harcandığını
söyleyemeyen" bir console demek. Tek migrator, tek geçmiş tablosu; bağımlılık yine tek yönlü.

**`PipeMeshServer` artık interceptor kabul ediyor.** Bir deployment'ın kendi kaygılarını
(kota, hız sınırı, denetim izi) sınıra koyabilmesi gerekiyordu ve motorun içine koymak §3'ü
bozardı.

**Yolda üç şey:**

1. **Ekleyeceğim indeks zaten vardı.** `workflow_execution_by_organization` V001'den beri
   duruyor ve `organization_id` ile başlıyor — daraltmayı yapan sütun o. Migration silindi;
   hiçbir şey yapmayan bir migration dosyası, olmayan dosyadan kötü.
2. **İki saat bir sayıya karar veriyordu.** `console_organization.created_at` veritabanının
   `now()`'ıyla yazılıyor, dönem aritmetiği ise enjekte edilen `Clock`'la ölçülüyordu.
   Artık uygulama yazıyor.
3. **Test verim kendi içinde tutarsızdı** — "bir saat önce" koşan bir execution, "şimdi"
   yaratılmış bir organizasyondan önce olamaz. Organizasyon on gün önce yaratılıyor.

### Dilim C — demo (2026-08-21)

7 yeni test — toplam 422 Java.

**Console'un runtime'a özel bir kapısı yok.** Asıl soru şuydu: console kullanıcının gizli
anahtarını saklamıyor (sadece hash), o hâlde runtime'a onun adına nasıl kimlik gösterecek?
Cevap: **koşum için bir anahtar üretiliyor, kullanılıyor, `finally` içinde iptal ediliyor.**

Alternatif — console'un anahtarsız "şu organizasyon adına" diyebilmesi — runtime'a yalnızca
demo için açılmış bir arka kapı olurdu. Bu hâliyle demo üretim yolunu izliyor: aynı API
anahtarı, aynı gRPC sınırı, aynı kota, aynı izinler. Burada çalışıyorsa orada çalışıyor — bir
demonun tek değeri bu.

**Demo dağıtık modda koşuyor, ve bu bir tercih değil zorunluluk.** Satır içi başlatmada hızlı
bir workflow, kimse izlemeye başlayamadan biterdi; ekran "olan bir şey" değil "olmuş bir şey"
gösterirdi. `StartMode.DISPATCHED` + dispatcher, #10'un tam da bunun için kurduğu şey.

**Tarayıcı gRPC konuşmuyor, console SSE yayınlıyor.** #20'nin "olay modeli transport'tan
bağımsız" kararının ilk karşılığı. Tarayıcı yalnızca console ile konuşuyor; oturum çerezi zaten
orada.

**Yolda bulunan gerçek kusur:** `WatchExecution`, **zaten bitmiş** bir execution'da sonsuza
kadar asılıyordu — arrival snapshot'ını gönderip `finished` olayını bekliyordu, ama o olay asla
gelmeyecekti. Javadoc'u bunu zaten vaat ediyordu:

> a client left blocked on an execution that finished ten minutes ago is a bug that looks like
> a hang

Doküman söz vermiş, kod tutmamıştı. Terminal durumdaki execution artık snapshot'tan hemen sonra
akışı kapatıyor. Demo bunu buldu — ve bu, demoyu gerçek yoldan geçirmenin kendi başına
değerinin kanıtı.

