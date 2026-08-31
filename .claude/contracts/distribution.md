# Distribution

**Status:** Implemented — awaiting the first tagged release
**Created:** 2026-08-26
**DESIGN.md kapsamı:** §26.2 (SDK sınırı), §25 (sürümleme ve yayın)

## Goal

Projeyi bulan birinin onu **kurabilmesi** ve ne işe yaradığını **ilk ekranda** anlaması. Bugün
ikisi de yok: SDK'lar hiçbir kayıt defterinde yayınlanmadı, ve README bir kategori adıyla açıyor.

## Neden şimdi

Mimari, star ya da müşteri getiren şey değil. Anlatı, dağıtım ve ilk-değere-varış süresi getiriyor.
Bu contract yalnızca o üçünü ele alıyor; motorda hiçbir şey değişmiyor.

Somut engel: bugün biri projeyi beğense **kuramıyor**. `pip install` edilebilir bir şey yok, `npm i`
edilebilir bir şey yok. AI altyapısına bakan kitle bu ikisiyle yaşıyor ve Java runtime'ı onların
ön kapısı değil — Python SDK'sı o kapı, ama README onu gömüyor.

## Paket adı: `pipemesh` PyPI'de alınmış

2026-08-26'da sorgulandı:

| ad | PyPI | npm | crates |
|---|---|---|---|
| `pipemesh` | **ALINMIŞ** — 2019, "pipe network meshes", terk edilmiş | boş | boş |
| `pipe-mesh-flow` | boş | boş | boş |
| `pipemesh-sdk` | boş | boş | boş |
| `pipemesh-client` | boş | boş | boş |
| `pipe-mesh` | boş | boş | boş |
| `@pipemesh/client` (npm skop) | — | **boş** | — |

**Dağıtım adı import adını belirlemiyor.** `pip install pipe-mesh-flow` → `import pipemesh`
tamamen olağan (`python-dateutil` → `import dateutil`). Yani ad seçimi tek satır kod değiştirmiyor,
ve `sdk/python/pyproject.toml`'da `name` ile `packages` ayrı alanlar.

Tek uyarı: aynı ortama hem eski `pipemesh` hem bizimki kurulursa `pipemesh/` dizini çakışır. Eski
paket 2019'dan beri ölü, risk ihmal edilebilir — ama bilinerek kabul edilmeli.

## Karar noktası: bu contract #30 ile çelişiyor

`.claude/contracts/project-rename.md` projenin adını değiştirmeyi planlıyor. **Yayın, adı kalıcı
olarak kilitler** — bir PyPI adı geri alınamaz. İkisi aynı anda doğru olamaz:

- **A: PipeMesh'te kal.** Bu contract uygulanır, #30 kapatılır. Maliyeti: adın PyPI'de alınmış
  olması ve alanın kalabalıklığı kabullenilir.
- **B: Önce yeniden adlandır.** #30 uygulanır, sonra bu contract yeni adla yayınlar. Maliyeti:
  dağıtım birkaç gün gecikir.

Kullanıcı `pipe-mesh-flow` istedi, yani yön A. **Yayın yapmadan önce #30 açıkça kapatılmalı** —
yarı canlı bir yeniden adlandırma contract'ı, hiç olmamasından kötüdür.

## Kapsam

### 1. Paketleri yayınla

- **Python:** dağıtım **`pipemesh-sdk`**, import `pipemesh`. `pyproject.toml` bugün
  `name = "pipemesh"` diyor — yalnız o satır değişir. `pipe-mesh-flow` yerine bu seçildi:
  `flow` eki `pipemesh`'in zaten çağrıştırdığını tekrarlıyor, ve `-sdk` npm'deki
  `@pipemesh/client` ile aynı şeyi söylüyor.
- **TypeScript:** `@pipemesh/client` — skop boş, `package.json` zaten bu adı taşıyor. Skop npm'de
  bir org olarak açılmalı.
- **Maven Central:** bu contract'ın dışında. `groupId` alan adı sahipliği ister ve Java runtime
  bu kitlenin ön kapısı değil.

Yayın CI'da bir `v*` tag'ine bağlanır, `.github/workflows/build.yml`'ın image yayınlayan işiyle
aynı tetikte. #25 zaten `VERSION`'ı ve proto uyumluluğunu koruyor; yayın bu korumaların
arkasından geçmeli, önünden değil.

### 2. README problemle açsın

Bugünkü ilk cümle bir kategori: *"A language-agnostic declarative runtime for AI workflows."*
Bunu Temporal da, Inngest de, LangGraph da söylüyor.

Üçüncü paragraftaki cümle asıl kanca: **yürütme, onu başlatan süreçten sağ çıkar.** Yukarı
çıkmalı, ve altında 30 saniyede koşan bir şey olmalı. `docker compose up` bugün var ve fena
değil — ama Docker ister ve okumak ister.

### 3. Tekrarlanabilir tek cümle

Bugün yok. Aday: **"Never retry what may already have happened."** Bir kere aynı ödemeyi iki kez
almış herkese bir şey ifade ediyor, ve arkasında çalışan bir mekanizma var (`idempotent: false`,
kurtarma taraması insana durur). Bir cümle seçilmeli ve README, demo ve depo açıklamasında
**aynı** cümle kullanılmalı.

## Kapsam dışı

- Motor, SDK'ların davranışı, proto — hiçbiri değişmiyor
- Maven Central
- Alan adı ve marka
- Demo'nun kendisi (#29 tamam)

## Acceptance Criteria

- [ ] #30 açıkça kapatıldı veya uygulandı — yayından **önce**
- [ ] `pip install pipe-mesh-flow` çalışıyor ve `import pipemesh` açılıyor
- [ ] `npm i @pipemesh/client` çalışıyor
- [ ] Yayın bir `v*` tag'iyle tetikleniyor, `mvn test` ve proto uyumluluk kontrolünden sonra
- [ ] Yayınlanan sürüm `VERSION` ile aynı — #25'in `ReleaseConsistencyTest`'i bunu zaten tutuyor
- [ ] README'nin ilk ekranı bir problem söylüyor, kategori değil
- [ ] Aynı ayırt edici cümle README'de, demoda ve GitHub açıklamasında
- [ ] Temiz bir makinede kurulum adımları baştan izlendi — belgelendiği gibi çalışıyor

## Split Decision

**Decision:** single-prompt

**Reasoning:** Üç maddenin toplamı ince bir katman — `pyproject.toml`'da bir satır,
`package.json`'da bir giriş noktası, CI'da bir iş, ve README. Üç bağımsız dikey dilim yok;
bölmek koordinasyon maliyeti ekler, iş çıkarmaz.

Daha önemlisi **sıra zorunlu**: yayın geri alınamaz, ve ortasında ajanın yapamayacağı insan
adımları var (npm org açmak, PyPI trusted publishing kurmak). Paralel ajanlar burada birbirini
bekler.

### Sıra

1. **#30 kapatılır.** `pipemesh-sdk` seçilmesi "PipeMesh'te kalıyoruz" demek. Yarı canlı bir
   yeniden adlandırma contract'ı bırakmak, ileride bu adı kimin kilitlediğini belirsiz yapar.
2. **Paketleri gerçekten kurulabilir hâle getir** — aşağıdaki TS hatası dahil.
3. **CI'a yayın işi**, `v*` tag'ine bağlı, `mvn test`'in arkasından.
4. **README ve tek cümle.** Kod değil, ama contract'ın asıl amacı bu.
5. **Yayın** — insan onayıyla, ve ancak 1-4 bittikten sonra.

### Risk points

Preflight sırasında ölçülerek bulundu, tahmin değil:

- **`@pipemesh/client` bugün import edilemez.** `package.json` `main: dist/index.js` diyor,
  derleme `dist/src/index.js` üretiyor. Yerel testler kaynaktan import ettiği için bunu hiç
  görmüyor — paketi kuran ilk kişi görür. Düzeltme tek satır; **kanıtı `npm test` değil, paketi
  kurup import etmek.**
- **Test dosyaları npm paketine giriyor** (`dist/test/*`). Zararsız ama yayınlanan yüzeyin parçası
  olmamalı.
- **Python tarafı sağlam.** Wheel kuruldu, temiz bir ortamda `import pipemesh` ve `PipeMesh`,
  `PipeMeshWorker` çalıştı, üretilmiş stub'lar paketin içinde. Ölçüldü.
- **Yayın geri alınamaz.** Bir PyPI sürüm numarası yeniden kullanılamaz; `0.1.0` bir kez yanlış
  çıkarsa `0.1.1` yayınlanır ve yanlışı herkes görür. İlk yayın temiz bir makinede baştan
  denenmeli.
- **Jetonlar kullanıcının.** PyPI trusted publishing (OIDC) jeton saklamamayı sağlıyor ve
  tercih edilmeli; npm için org ve jeton insan adımı.
- **Sürüm tutarlılığı zaten korunuyor.** `ReleaseConsistencyTest` `VERSION`, `pyproject.toml` ve
  `package.json`'ı karşılaştırıyor ve stub'ların proto'dan üretildiğini doğruluyor — yayın bu
  testin arkasından geçmeli.

## Implementation Notes

Dört maddenin dördü de yapıldı; kalan tek adım insan adımı — jetonlar ve ilk tag.

### #30 kapatıldı

`pipemesh-sdk` seçmek "PipeMesh'te kalıyoruz" demekti, ve yayın adı kalıcı kilitliyor. Contract
"Closed — not doing" olarak işaretlendi, analizi duruyor: ölçüm ve kontrol yöntemi bir gün
gerekirse hâlâ geçerli.

### TypeScript paketi import edilemiyordu

Preflight'ın bulduğu hata gerçekti: `main: dist/index.js`, derlemenin ürettiği `dist/src/index.js`.
`tsconfig`'in `rootDir: "."` olması derlemeyi bir seviye içeri itiyor ve kimse fark etmemiş, çünkü
**testler paketi hiç yüklemiyor** — `../src`'ten import ediyorlar. Suite yeşilken paket kurulamaz
durumdaydı.

`test/package.test.ts` bu boşluğu kapatıyor: `package.json`'ın kendi `main` alanından `require`
ediyor, bildirdiği tipleri arıyor, worker'ın çalışma zamanında okuduğu `.proto`'nun paketlendiğini
doğruluyor, ve `files`'ın test dizinini yayınlamadığını kontrol ediyor. Eski `package.json` ile
üçü birden düşüyor.

Kanıt testin kendisi değil: paket `npm pack` ile paketlenip boş bir projeye kuruldu ve
`require("@pipemesh/client")` çalıştı, proto yerindeydi, testler sızmamıştı.

Python tarafı sağlamdı ve aynı yöntemle doğrulandı — wheel temiz bir sanal ortama kuruldu,
`import pipemesh` ve dışa açılan dokuz ad çalıştı.

### Dağıtım adı import adını değiştirmedi

`pyproject.toml`'da yalnız `name` satırı değişti. `packages = ["pipemesh"]` aynı kaldı, yani
`pip install pipemesh-sdk` sonrası hâlâ `import pipemesh`. Tek satır, ve kodun hiçbir yerinde
karşılığı yok.

### Yayın bir tag'e bağlı, ve tag VERSION ile uyuşmak zorunda

`release` işi `v*` tag'lerinde koşuyor ve `test`'e bağlı. İlk adımı, tag'in `VERSION` ile
uyuştuğunu kontrol etmek: bir PyPI sürümü yeniden yüklenemez, dolayısıyla uyuşmazlığı yayından
*sonra* fark etmek `0.1.1` yayınlamak demek. `ReleaseConsistencyTest` zaten `VERSION` ile iki SDK
manifestosunu karşılaştırıyor; eksik olan halka tag'di.

PyPI için trusted publishing (OIDC) seçildi — saklanacak ve döndürülecek bir jeton yok. npm için
`NPM_TOKEN` gerekiyor, `--provenance` ile.

### README artık bir problemle açıyor

Eski ilk cümle bir kategoriydi ve rakiplerin hepsi aynısını söylüyordu. Yenisi bir soru soruyor —
*süreç ödemeyi kaydetmeden öldü, geri geldiğinde ne oluyor* — ve cevabı ayırt edici cümle:
**"Never retry what may already have happened."**

Aynı cümle üç yerde: README, demonun kaynak sayfası (ve orada hangi dosyanın onu bildirdiği),
ve örneğin README'si. Depo açıklaması insan adımı.

Sıralama değere varış süresine göre: **canlı demo** (kurulum yok), **docker compose** (bir komut),
**pip install** (kendi kodun). Paketler henüz yayınlanmadığı için o bölüm bunu açıkça söylüyor —
README'nin bugün yalan söylememesi, yarın doğru olmasından önemli.

### Kalan: insan adımları

- PyPI'de `pipemesh-sdk` için trusted publisher tanımı — yapıldı
- npm'de `@pipemesh` org'u ve `NPM_TOKEN` — jeton eklendi, kapsam yetkisi eksik
- GitHub depo açıklamasına aynı cümle
- `v0.1.0` tag'i — atıldı, yarım kaldı

### `v0.1.0`: yarım yayın

Tag atıldı, `test` geçti, PyPI `pipemesh-sdk 0.1.0`'ı aldı, `npm publish` düştü.
Jeton geçerliydi — `npm whoami` adımı tam da bunun için eklenmişti ve geçti. Geçmediği
şey `@pipemesh` kapsamına **yazma** yetkisiydi, ki `whoami` onu hiç sormaz. Bir kimlik
kontrolünün kanıtladığı şeyle bir yayının ihtiyaç duyduğu şey aynı değil.

Asıl ders sıralamada değil, tekrarlanabilirlikte: iş iki registry'ye yayınlıyordu ve
ikincisi kendi başına düşebiliyordu, ama birincisi geri alınamıyordu. Bu haliyle her
deneme bir sürüm numarası yakar. `skip-existing: true` bunu düzeltiyor — PyPI zaten
tuttuğu bir dosyayı atlıyor, ki bu "olmuş olabilecek bir şeyi tekrarlamak" değil:
registry elinde olduğunu açıkça söylüyor. Bilinmeyen olan taşıma hatası, bu değil.

PyPI ilk sırada kalıyor. Sezgi tersini söylüyor (geri alınamayan en sona), ama npm'in
`skip-existing`'i yok: npm önce koşsaydı, sonraki bir hata tag'i büsbütün yeniden
atılamaz hale getirirdi. Atlanabilen adım önce gider.
