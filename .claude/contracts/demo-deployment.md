# Demo Deployment

**Status:** Complete
**Created:** 2026-08-23
**DESIGN.md kapsamı:** §26.3 (dağıtım), §9.7 (wait), §16 (approval), §46 (başarı ölçütü)

## Goal

Bir sunucuda koşan, herkesin girip deneyebileceği bir demo: akışı başlatan bir müşteri görünümü,
onaylayan bir ikinci görünüm, ve ikisinin arkasındaki kaynağı gösteren bir sayfa.

## Deploy sınırı: CI GHCR'a kadar

CI test eder, image kurar, GHCR'a push eder ve **orada durur**. Deploy portaldan elle
tetikleniyor.

İki sebep, ikisi de yeterli:

- Repo herkese açık. Deploy tetikleyen bir adım, iç host adını ve secret adlarını o dosyaya
  yazmak demek — secret'ın değeri gizli olsa da adı ve hedefi altyapı bilgisidir.
- Elle tetiklenen bir deploy, bir sorunun ne zaman ve neden başladığını bilinir kılıyor.

## Testler image'dan önce koşuyor

`Dockerfile` `-DskipTests` ile build ediyor — doğru, çünkü image kurmak test koşturmak değil.
Ama sonucu şu: yalnızca image kuran bir pipeline, #25'te yazılan koruyucuların **hiçbirini**
çalıştırmaz — proto uyumluluğu, sürüm tutarlılığı, stub tazeliği.

O kontroller script'e değil teste konmuştu ki her koşuda çalışsınlar. CI'da `mvn test` ayrı ve
**önce** gelen bir adım olmalı; aksi hâlde kontroller dekoratif.

## `latest` değil `staging`

#25 `latest` yayınlamamaya karar verdi: *ne çalıştırdığını söyleyemeyen bir kurulum sorunu
bildiremez, ve on-prem müşterisi için kendiliğinden değişen bir bağımlılıktır.*

Staging'in her push'ta yeniyi çekmesi meşru bir istek ve kuralı delmeden karşılanıyor:

| Tetik | Etiket |
|---|---|
| `push: main` | `staging`, `sha-<kısa>` |
| `push: tags v*` | `X.Y.Z`, `X.Y` — `VERSION`'dan |

`staging` adı kimseyi yanıltmıyor ve bir on-prem müşterisi onu asla çekmez. Sürüm etiketleri
`VERSION`'dan okunuyor, otomatik artırılmıyor — her commit'i yayın saymak, "yayınlanmış sürüm
yeniden yazılmaz" cümlesini anlamsız kılardı.

## Model: LiteLLM

Demo'nun modeli LiteLLM proxy'sinden geliyor. Kod değişmiyor: `openai-compatible` sağlayıcısı
zaten OpenAI-uyumlu bir ucu konuşuyor, ve javadoc'undaki örnek zaten yerel bir proxy.

**Katman sınırı yazılmalı:** LiteLLM vendor tesisatının sahibi — hangi sağlayıcı, hangi vendor
anahtarı, vendor'lar arası yeniden deneme. PipeMesh execution'ın kendi anlamının sahibi —
bütçe, kota, şema doğrulama, alias'lar arası fallback. İki sistemin aynı parayı sayması, #24'te
reddedilen "ayrışacak iki sayı" durumudur.

**Bir alias bir fiyat kademesi.** LiteLLM aynı alias'ı farklı fiyatlı vendor'lara yönlendirirse
`maxCost` bulanıklaşır. Demo ücretsiz uçta koştuğu için fiyat sıfır, ve bütçe `maxModelCalls` +
`maxTokens` ile ifade ediliyor — ücretsiz bir uçta doğru ölçü zaten çağrı sayısı.

**Küçük model şemayı beceremez.** LLM adımı `outputSchema`'ya uymayan cevabı düşürüyor (§21).
Demo'nun şemaları küçük tutulmalı ve adımlarda `retry` olmalı; aksi hâlde demo rastgele kırılır
ve suç modelin değil tasarımın görünür.

## Üç görünüm

```text
/            müşteri  — talebi yazıyor, adımları canlı görüyor, seçeneklerden birini seçiyor
/approvals   onaycı   — bekleyen onayları görüyor, onaylıyor ya da reddediyor
/source      kaynak   — bu akışın workflow JSON'ı ve capability kodu, yan yana
```

`/source` süs değil: gösterilen şey **o an koşan** akışın tanımı. §46'nın başarı ölçütü —
"yeni bir davranış eklemek konfigürasyon işidir" — ancak insanların konfigürasyonu görmesiyle
iddia olmaktan çıkıyor.

## Demo ürünün parçası değil

Demo bir **SDK uygulaması**: capability'lerini kendi süreci sunuyor, execution'ı SDK ile
başlatıyor ve izliyor. Console'un `/demo` ekranını şişirmek, ürünle örneği birbirine karıştırırdı
— ve zaten gösterilmek istenen şey tam olarak bu ayrım.

## Halka açık bir demo bir güvenlik yüzeyi

- **Ziyaretçiler birbirini onaylayamamalı.** Onay ve seçim oturuma bağlı bir korelasyonla
  ayrılıyor. Aksi hâlde demo, izolasyonun çalışmadığını gösteren bir vitrin olur.
- **Kota demo planından geliyor**, demo uygulamasının kendi sayacından değil.
- **Kaynak sayfası yalnızca bu akışın dosyalarını** gösteriyor; dizin gezintisi yok.

## Subscribe sayfası testi

Console `console.cloud=true` iken SMTP'siz **açılmıyor** — bilerek (#22). İlk testte
`console.cloud=false` ile koşulur, doğrulama linki log'a düşer ve akış uçtan uca denenir.
Dışarıya açılmadan önce gerçek SMTP bağlanır. Bayrağın var olma sebebi bu.

## Acceptance Criteria

- [ ] CI önce `mvn test` koşuyor; testler düşerse image kurulmuyor
- [ ] `main` push'u `staging` ve `sha-*` etiketleriyle üç image'ı GHCR'a yolluyor
- [ ] `v*` etiketi `X.Y.Z` ve `X.Y` yolluyor; sürüm `VERSION`'dan okunuyor
- [ ] CI'da hiçbir deploy tetiği, iç host adı veya dağıtım platformu adı yok
- [ ] `deploy/demo/compose.yaml` GHCR'dan çekiyor, kendi Postgres'ini kurmuyor
- [ ] Compose var olan bir veritabanına ortam değişkeniyle bağlanıyor
- [ ] Demo LiteLLM üzerinden model çağırıyor; `models.json` dışında değişiklik yok
- [ ] Müşteri görünümü akışı başlatıyor ve adımları canlı gösteriyor
- [ ] Seçenekler görünüyor ve seçim execution'ı ilerletiyor
- [ ] Onaycı görünümü bekleyen onayı görüyor ve karar veriyor
- [ ] Bir ziyaretçi başkasının talebini ne görüyor ne onaylayabiliyor
- [ ] Kaynak sayfası koşan akışın workflow'unu ve capability kodunu gösteriyor
- [ ] Mevcut 470 Java + 47 Python + 30 TypeScript testi değişmeden geçiyor

## Kapsam dışı

- **Deploy otomasyonu.** Portaldan elle; yukarıdaki gerekçeyle.
- **Kalıcı demo verisi.** Ziyaretçi oturumu geçici; execution'lar zaten kalıcı.
- **Gerçek SMTP.** İlk test `console.cloud=false` ile; sağlayıcı ayrı bir karar.
- **Redis.** PipeMesh'in ona ihtiyacı yok; kurulu olması devreye almak için sebep değil.

## Split Decision

**Decision:** single-prompt, dört aşama
**Tarih:** 2026-08-23

Katmanlar sıralı: compose olmadan demo'nun bağlanacağı bir şey yok, demo olmadan görünümlerin
göstereceği bir şey yok.

1. **CI** — test → üç image → GHCR. Tek başına doğrulanabilir ve tek başına değerli.
2. **Compose** — `deploy/demo/compose.yaml`, GHCR'dan çeken, var olan veritabanına bağlanan,
   LiteLLM'i içeren.
3. **Demo backend** — FastAPI + Python SDK: capability'ler, oturum, SSE, kaynak okuma.
4. **Görünümler** — müşteri, onaycı, kaynak.

### Neden ayrı bir uygulama, console değil

Console ürün. Demo, ürünü **kullanan** bir örnek — ve gösterilmek istenen ayrım tam olarak bu.
Console'un `/demo` ekranı yerinde kalıyor (basit, tek workflow); bu proje onun yanına geliyor,
içine değil.

Python seçilmesinin sebebi de bu: demo bir **SDK uygulaması** ve SDK'nın gerçekten kullanıldığı
görünmeli.

### Oturum izolasyonu nasıl kuruluyor

Ziyaretçiye bir oturum kimliği veriliyor ve **talep numarası ondan türüyor**. Sonuç:

- `wait` adımının korelasyonu o talep numarası → başka ziyaretçinin yayını bu execution'ı
  bulamıyor (#7'nin anahtarı zaten organizasyon + ad + korelasyon)
- onay listesi yalnızca o oturumun execution'larını gösteriyor

Yani izolasyon demo'nun kendi filtresinden değil, runtime'ın zaten sahip olduğu eşleştirmeden
geliyor. Demo'nun filtre yazması gerekseydi, o filtre bir gün unutulurdu.

### CI'nın ilk koşuşu muhtemelen kırmızı olacak

Bu depoda hiç CI koşmadı. `mvn test` Docker gerektiriyor (Testcontainers) ve SDK testleri Java
sunucusu başlatıyor. GitHub runner'ında Docker var, ama Python ve Node adımlarının da kurulması
gerekiyor. İlk koşuş bunu ortaya çıkaracak ve düzeltmesi CI'nın parçası — contract'ın kabul
kriteri "testler CI'da koşuyor", "yazıldı" değil.

### Kapsam dışı (ek)

- **Demo'nun kendi testleri.** Görünümler elle doğrulanıyor; bir demo uygulamasına test paketi
  yazmak, gösterdiği şeyden fazla bakım getirir. Backend'in capability'leri ise SDK'nın zaten
  test edilmiş yolundan geçiyor.

### Risk points

- **Testlerin CI'da atlanması.** `-DskipTests` image için doğru; pipeline'da ayrı bir test adımı
  yoksa #25'in koruyucuları hiç koşmaz ve kimse fark etmez.
- **`staging` etiketinin `latest` gibi kullanılması.** Compose'da `staging` yazacak; bir gün
  birinin üretim compose'una kopyalaması, adının açıkça staging olmasıyla engelleniyor — ama
  README'de de yazmalı.
- **LiteLLM anahtarının repoya girmesi.** `models.json` yalnızca **hangi ortam değişkeninin**
  tutacağını yazıyor (`apiKeyEnv`); değeri dağıtımın ortamında duruyor. Bu kural zaten var, demo onu
  bozmamalı.
- **Küçük modelin şemayı beceremesi.** Demo rastgele kırılırsa suç tasarımın görünür. Şemalar
  küçük, adımlarda `retry`, ve gerekirse `onFailure: fallback`.
- **Kaynak sayfasının dizin gezintisine dönüşmesi.** Yalnızca bu akışın dosyaları, sabit bir
  liste — kullanıcıdan gelen yol parametresi yok.

## Implementation Notes

Dört aşama da bitti: CI → compose → demo backend → görünümler. Testler **470 Java + 47 Python +
30 TypeScript**, hepsi yeşil. Motor değişmedi: `WorkflowExecutor.java`'da tek satır yok — demo bir
uygulama, bir çalışma zamanı özelliği değil.

### Ne kuruldu

| Yer | Ne |
|---|---|
| `.github/workflows/build.yml` | `test` → `publish`; matris `[runtime, console, demo]`, `staging` + `sha-*`, tag'lerde `VERSION`'dan `X.Y.Z` |
| `deploy/demo/compose.yaml` | dört servis, kendi Postgres'ini kurmuyor, LiteLLM dahil |
| `deploy/demo/litellm.yaml` | `fast` / `reasoning` alias'ları, `reasoning → fast` fallback |
| `demo/app/` | FastAPI: `main.py` (yalnız route), `conversations.py`, `trace.py`, `sources.py` |
| `demo/app/static/` | üç sayfa, düz JS, build adımı yok |
| `examples/vendor-selection/procurement.py` | üç yetenek — tek tanım, iki çağıran |

### Testler image'dan önce koşmalı derken, testleri yanlış dizinden koşturmuşum

Sözleşmenin kendi uyarısını yazdıktan sonra CI'ın Python adımını repo kökünden koşturdum.
`TestRuntimeServer` yapılandırma dizinini `../../sdk/testdata` diye çözüyor — yani **cwd'ye
göre**. Kökten koşunca `/Users/burakbalim/codes/sdk/testdata` arıyor, sunucu port basmadan ölüyor
ve 34 test hata veriyor.

TypeScript adımı zaten `working-directory: sdk/typescript` ile koşuyordu; Python'ı da aynı
kurala aldım. Ders, yeni değil ama bu sefer kendi yazdığım koruyucunun içine düştüm: *bir
koruyucu yazmak, onun çalıştığını görmek değildir.* İlk push'ta kırmızı olacaktı.

### Tarayıcıda çıkan üç hata, curl'de çıkmayan

Uçtan uca akış curl ile dört yolun dördünde de geçti — sipariş, ret, bütçe aşımı, eşik altı.
Sonra sayfayı açtım:

1. **`classList.add("")` istisna atıyor.** İzleme satırının rengini bir haritadan alıyordum ve
   varsayılan rengi boş string ile temsil ediyordum. `suspended` geldiği anda JS ölüyor; sayfa
   seçenekleri hiç göstermiyor. curl bunu göremezdi çünkü JS koşmuyor. Boş-string hilesini
   kaldırdım: her tonun gerçek bir sınıf adı var.

2. **Bir sentinel, iki anlam.** Onay kutusunda `shown = ""` hem "henüz çizilmedi" hem "liste boş"
   demekti. Son onay verildiğinde imza `""` oluyor, `shown` da `""`, sayfa "değişmemiş" deyip
   kartı sonsuza kadar ekranda bırakıyor. `null` ile ayırdım. API doğru cevap veriyordu; yanlış
   olan sayfaydı — ve bunu ancak ekranda görünce fark ettim.

3. **`models.json`'ı yanlış şekilde yazmışım.** `{"models": [{"alias": ...}]}` diye liste yazdım;
   yükleyici alias'la anahtarlanmış bir nesne bekliyor. Sonuç: `understand` adımı modele hiç
   ulaşmadan başarısız, `attributes` boş. Boş `attributes` iyi bir ipucuydu — model çağrılmış
   olsaydı token sayıları orada olurdu.

### Küçük model için akışa iki şey eklendi

- İki `llm` adımına `retry`. `llm.schema_violation` zaten retryable ilan edilmiş; eksik olan onu
  harcamaya izin veren politikaydı.
- `place_order`'a `onFailure: { strategy: goto, goto: over_budget }`. Öncesinde 21.900'lük
  seçim onaylandıktan sonra yürütmeyi öldürüyordu — halka açık bir demoda bu bir sonuç değil,
  bir çökme gibi görünür. Artık okunabilir bir durumda bitiyor. Bu, motoru değiştirmeden
  §18'in görünür olduğu yer.

### Fiyat yazmamak, sıfır yazmaktan dürüst

`models.json`'a `inputPricePerMillion: "0.00"` yazmayı düşündüm. Yazmadım: ücretsiz uçtaki bir
model bugün ücretsiz, ama LiteLLM'in arkasındaki hedef değişince o sıfır sessizce yalan söyler.
Kayıtsız fiyat "bedava" değil "bilinmiyor" demek (§39) ve akışın para bütçesi yok, dolayısıyla
hiçbir şey reddedilmiyor. Doğru cevap, söylenmeyeni söylememek.

### Kaynak sayfası neyi okuduğunu iddia ediyorsa onu okumalı

Sayfa "çalışan süreç tarafından diskten okundu" diyor. İlk hâlde runtime checkout'taki
`examples/vendor-selection`'ı mount ediyordu, demo ise kendi image'ındaki kopyayı gösteriyordu.
İkisi normalde aynı, ama *aynı kalacakları garanti değil* — ve ayrıldıkları gün sayfa yalan
söylerdi. Compose artık aynı dizini ikisine de bağlıyor. Demo'nun kendi kodu image'dan geliyor,
ki o da doğru: çalışan o.

### Oturum ayrımı bedava geldi

Ziyaretçinin `requestId`'si oturumdan türetiliyor, yani iki ziyaretçi aynı adımda beklerken
birini uyandıran olay diğerine ulaşamıyor. Bunu yapan `wait`'in korelasyonu; demo'da filtre yok.
Sayaç yerine rastgele son ek kullandım — sayaç süreçle sıfırlanır ve yeniden başlayan bir demo
eski bir bekleyeni uyandırabilirdi.

### Demo'nun izleme kaydı ayrı tutuluyor

Runtime'ın akışı yürütme bitince kapanıyor ve o an bağlı olana veriliyor. Tarayıcı öyle değil:
geç gelir, yeniler, tünelde bağlantı düşer. `Trace` her yürütme için uygulamanın kendi kaydını
tutuyor, tarayıcılar ona bağlanıp kopuyor. Bu bir demo kısayolu değil, bir çalışma zamanını
izleyen uygulamanın olağan şekli — ama ikinci bir doğruluk kaynağı olmamalı: hiçbir şey burada
otoriter değil, "gerçekte ne oluyor" sorusunun cevabı hâlâ `mesh.get`.

### Sunucu hazırdı, sayfa bağlanmamıştı

`GET /api/requests` uç noktasını yazdım, `conversations._follow`'a "sekmeyi kapatıp dönen biri ne
olduğunu bulmalı" diye yorum düştüm — ve hiçbir sayfa o ucu çağırmadı. Yenileyince demo sıfırlanıyordu.
Kendi yorumuyla çelişen kod, yani hata; #22'de öğrendiğim şeyin aynısı başka bir yerde.

Şimdi sayfa açılışta son yürütmeyi buluyor: mesajı, izleme kaydını ve **şu an yapılacak olanı**
geri çiziyor. İzleme kaydı yeniden oynatılıyor ama konuşma anlatılmıyor — ziyaretçi onu zaten
okudu, tekrar anlatmak yeni bir hareket gibi görünürdü. Sınırı `snapshot`'a eklenen `seen`
belirliyor: kaç olay çoktan olmuş.

Konuşma o anki durumu **anlık görüntüden** çiziyor, yeniden oynatmadan değil. Böylece demo süreci
yeniden başlamış ve bellekteki kayıt gitmiş olsa bile sayfa doğru yeri gösteriyor — yürütme diskte,
kayıt değil.

Bir de composer artık sebebini söylüyor. Yalnızca soluklaşan bir alan bozuk görünür, ve bu sayfa
bir yöneticiyi günlerce bekleyebilir.

### Onay kutusu runtime'ın değil

`/approvals` listesi uygulamanın. Kimin neyi onayladığı şirketin işi; bir gelen kutusu tutan
çalışma zamanı bunu belirlemeye başlamış olurdu (§3). Runtime'ın verdiği şey iki cümle: bir
yürütme orada durdu, ve aynı kararı iki kez vermek bir kez sayılır.
