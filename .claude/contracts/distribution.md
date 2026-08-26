# Distribution

**Status:** Draft
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

- **Python:** dağıtım `pipe-mesh-flow`, import `pipemesh`. `pyproject.toml` bugün
  `name = "pipemesh"` diyor — yalnız o satır değişir.
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

_To be filled by Agent 0_

## Implementation Notes

_To be filled as work progresses_
