# Project Rename

**Status:** Draft
**Created:** 2026-08-23
**DESIGN.md kapsamı:** §26.1 (proto = sözleşme), §31 (yapılandırma dizini), §26.3 (dağıtım)

## Goal

Projenin adını `pipemesh`'ten başka bir ada çevirmek — kaynak, paketler, tel üzerindeki sözleşme,
image'lar ve depo dahil, geriye eski adı taşıyan hiçbir kalıcı iz bırakmadan.

## Sancılı mı: hayır, ama tam olarak dört yerde `sed` olmaktan çıkıyor

Ölçüm (2026-08-23): **2660 geçiş, 381 dosya, 317 yol.** Büyük bir diff, küçük bir risk — çünkü
geçişlerin çoğunu derleyici ve testler yakalıyor.

### Bedava olan kısım (%90'ı)

| Biçim | Adet | Kaçırırsam ne olur |
|---|---|---|
| `io.pipemesh.*` | 1713 | derlenmez |
| `PipeMesh` (düzyazı) | 310 | hiçbir şey — kozmetik |
| `pipemesh-*` (modül adları) | 181 | Maven reactor patlar |
| `PIPEMESH_*` (ortam değişkenleri) | 111 | çalışma zamanında sessizce varsayılana düşer |

İlk üçü kendini ele veriyor. Dördüncüsü vermiyor — bu yüzden ayrı bir kontrol gerekiyor
(aşağıda kabul ölçütlerinde).

### Bedava olmayan dört yer

**1. `pipemesh_schema_history` — adın başkasının veritabanına yazıldığı tek yer.**

`SchemaMigrator` göç geçmişini bu tabloda tutuyor ve SQL'de değil Java'da yaratıyor, bu yüzden
migration dosyalarını taramak onu bulmuyor. Adı değişirse mevcut bir veritabanında geçmiş **boş**
görünür, migrator V001'den başlar ve `CREATE TABLE workflow_execution` zaten var olduğu için
düşer. Yüksek sesle düşüyor — sessiz bozulma değil — ama redeploy'u durdurur.

Üç seçenek, ve bu bir karar:
- yeni adla yeni tablo + eski adı okuyup taşıyan bir göç adımı
- eski adı bilerek korumak (`pipemesh_schema_history` kalır; tuhaf ama bozulmaz)
- hiçbir şey yapmamak ve mevcut veritabanlarını atmak — **bugün geçerli**, çünkü üretimde
  veritabanı yok; yarın değil

Doğru cevap ilki değil: göç yazmak, taşınacak bir şey olduğunda doğrudur. Şu an yok.

**2. `package pipemesh.v1` — tel üzerindeki sözleşme.**

gRPC metot yolları paket adını taşıyor (`/pipemesh.v1.PipeMesh/StartExecution`). Değişirse eski
bir SDK yeni bir runtime'a `UNIMPLEMENTED` alır. #25'te yazılan `ProtoCompatibilityTest` bunu
**engellemek için** var, dolayısıyla bilerek kırmızıya dönecek ve temel çizgisi (`release/proto/`)
elle sıfırlanacak.

Bu, sözleşmeyi kırmanın bedava olduğu **son an**: git tag'i yok, yayınlanmış sürüm yok, dolayısıyla
kimsenin sabitlediği bir şey yok. Bir yayından sonra aynı iş bir major sürüm ve bir geçiş dönemi
demek.

**3. `ghcr.io/burakbalim/pipemesh-{runtime,console,demo}`.** Üçü de bugün itildi. Yeni ad = yeni
yol; eskiler öksüz kalır, silinir. Ucuz.

**4. Depo adı.** GitHub eski URL'i yönlendiriyor, yani yumuşak. Ama `README.md`, demo'nun kaynak
sayfasındaki bağlantı ve commit trailer'ları elle güncellenmeli.

### Bugün ucuz olan, yarın olmayacak

| | Durum | Ad değişince |
|---|---|---|
| git tag | **yok** | kimse bir sürüme sabitlenmemiş |
| PyPI `pipemesh` | **yayınlanmamış** | yayınlandıktan sonra ad kalıcı olarak kapanır |
| npm `@pipemesh/client` | **yayınlanmamış** | aynı |
| Maven Central | **yayınlanmamış** | `groupId` kalıcıdır |
| veritabanı tabloları | **temiz** — hepsi `workflow_*` / `console_*` | göç gerekmez |
| dış kullanıcı | **yok** | — |

Yani cevap: **şimdi yap, ya da pahalılaşmasını kabul et.** Bugünkü bedel bir günlük mekanik iş ve
tek bir dikkatli karar. İlk `v0.1.0` tag'inden veya ilk PyPI yayınından sonra bu, bir major sürüm
ve bir uyumluluk katmanı.

## Ad kararı: açık

### `perdure` elendi — ve neden kaçırdığımı yazmak önemli

crates.io'da `perdure` var (0.2.0-alpha.1, 2026-06-09, 20 indirme):

> *"Durable, exactly-once execution for agent workflows: typed goals with enforced authority,
> receipts, approvals, crash-safe resume, and replay."*

Bu, bu projenin tarifi. Aynı ad, aynı kategori, hatta aynı kelimeler.

İki hata yaptım:

1. **crates.io'yu hiç sorgulamadım.** PyPI, npm, Maven ve GitHub'a baktım; Rust kayıt defterini
   listeye almamıştım.
2. **Yıldız saydım, açıklama okumadım.** `robzilla1738/perdure` benim GitHub çıktımın içindeydi —
   "★2" yazıyordu ve geçtim. Yanlış ölçüt: *kendi kategorinde* 2 yıldızlı bir proje, alakasız 16
   yıldızlıdan çok daha önemli.

Crate küçük ve terk edilmiş bile olabilir; adı yine de kullanmak savunulabilir. Ama adı
değiştirme sebebi "ayırt edici ve bulunabilir olsun"du, ve o özellik gitti: aynı arama ikisini
birden döndürüyor. Karar kullanıcının, ama bilerek verilmeli.

### Asıl ders: tarif eden ad, rakibinin de bulacağı addır

`perdure`'e dayanıklılık semantiğinden yürüyerek vardım. Crate'in yazarı aynı akıl yürütmeyi
yapmış. Aynı şeyi yapan herkes aynı kelime havuzundan çekiyor — bu yüzden aday üretimi
**tarif eden**den **keyfi**ye çevrildi. Stripe, Kafka, Redis kendilerini anlatmıyor.

### Kontrol yöntemi (bundan sonra bu)

Bir ad şu üç soruyu geçmeden aday değil:

| soru | nerede |
|---|---|
| yayınlayacağımız yerde boş mu | PyPI, npm (yalın + skop), Maven `groupId` |
| **aynı kategoride bir şey var mı** | GitHub *açıklamaları*, crates.io, PyPI özetleri — yıldız değil |
| bir alan adı var mı | `.dev` şart değil; `.io`, `.sh`, `.run` de olur — TLD çok, paket ad alanı tek |

Bu yöntem `fusee`yi (Nintendo Switch exploit'i aramayı kirletiyor), `fairlead`i
(`fairlead/fairlead-python` + `-java` diye bir org var — aynı şekil), `mullion`u
(`mullionlabs/mullion-ts`, LLM context yönetimi) ve `kevel`i (gerçek bir adtech şirketi) eledi.
Eski yöntem dördünü de geçirirdi.

### Aday: `cantle`

Eyerin arkadaki yükseltisi — binicinin geriye kaymasını engelleyen parça. Tarif etmiyor, keyfi;
iki hece, sert C, Türkçe konuşan için de kolay.

**2026-08-23'te doğrulandı:**

| yüzey | durum |
|---|---|
| PyPI `cantle` | **boş** |
| npm `cantle`, `@cantle/client` | **boş** |
| crates.io `cantle` | **boş** |
| Maven `dev.cantle`, `io.cantle` | **boş** |
| GitHub `burakbalim/cantle` | **boş** |
| `cantle.dev`, `cantle.io`, `cantle.sh`, `cantle.run` | **boş** |
| GitHub'da `cantle` | yalnızca kişisel depolar, en yükseği ★1 — kategori çakışması yok |
| `cantle.com` | Localrider.com'a yönleniyor — binicilik sitesi, yazılım değil |

Tek zayıf yanı dürüstçe: **`candle`'a bir harf uzaklıkta.** Sesli anlatımda ve yazımda
karıştırılabilir. Bunu bilerek kabul etmek gerekiyor.

Marka kontrolü (USPTO/EUIPO) hâlâ yapılmadı — hangi ad seçilirse seçilsin yapılmalı.

## Kapsam

**Değişecek:**
- Java paketleri `io.pipemesh.*` → `<önek>.<ad>.*`. Önek, sahip olunan alan adının tersi:
  `cantle.io` alınırsa `io.cantle` (mevcutla birebir takas), `cantle.dev` alınırsa `dev.cantle`
  (ek olarak `src/*/java/io/` → `src/*/java/dev/` taşıması). İkisi de mekanik
- Maven `groupId`, `artifactId`, modül dizin adları
- Proto `package` ve `option java_package`; `release/proto/` temel çizgisi sıfırlanır
- SDK paket adları: Python `pipemesh` → `perdure`, TypeScript `@pipemesh/client` → `@perdure/client`
- Ortam değişkeni önekleri `PIPEMESH_*` → `PERDURE_*`; `CONSOLE_*` dokunulmaz
- `SchemaMigrator`'daki geçmiş tablosu adı — karar yukarıda
- Image adları, compose dosyaları, Kubernetes manifestleri, CI matrisi
- `DESIGN.md`, `README.md`, `CLAUDE.md`, tüm contract'lar, demo sayfalarındaki metinler

**Değişmeyecek:**
- Veritabanı tablo ve indeks adları — hiçbiri projeyi anmıyor, dokunmak bedelsiz risk
- İş alanı adları: `workflow`, `execution`, `capability`, `step` — bunlar projenin adı değil
- `console` modülü ve tabloları

## Acceptance Criteria

- [ ] `git grep -i pipemesh` hiçbir şey döndürmüyor
- [ ] `git ls-files | grep -i pipemesh` boş
- [ ] `mvn -o test` yeşil: 470 Java testi
- [ ] Python 47, TypeScript 30 testi yeşil — stub'lar yeni proto paketinden yeniden üretilmiş
- [ ] `ProtoCompatibilityTest` yeni temel çizgiye karşı yeşil, ve temel çizgi bilerek sıfırlandığı
      `CHANGELOG.md`'de yazıyor
- [ ] Ortam değişkeni kontrolü: eski `PIPEMESH_*` adlarından hiçbiri hiçbir yerde okunmuyor —
      derleyicinin yakalamadığı tek sınıf bu
- [ ] Boş bir veritabanına karşı runtime ayağa kalkıp şemayı uyguluyor
- [ ] Demo yığını uçtan uca koşuyor: talep → seçim → onay → sipariş
- [ ] `deploy/demo/compose.yaml` ve `deploy/cloud/*` yeni image adlarını çekiyor
- [ ] GHCR'daki eski `pipemesh-*` image'ları silinmiş

## Split Decision

_To be filled by Agent 0_

## Implementation Notes

_To be filled as work progresses_
