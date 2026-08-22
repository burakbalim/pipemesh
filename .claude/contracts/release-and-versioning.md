# Release and Versioning

**Status:** Tamam (2026-08-22)
**Created:** 2026-08-22
**DESIGN.md kapsamı:** §26.1 (proto otorite), §24 (versiyonlama), #21/#22 (image'lar)

## Goal

Yayınlanabilir artefaktlar: iki image, üç SDK paketi, ve aralarındaki uyumun ne anlama geldiği.

## Asıl soru: sürüm numarası neyin sözü

Beş artefakt var — `pipemesh/runtime`, `pipemesh/console`, Python, TypeScript ve Java SDK'ları —
ve hepsi bir numara taşıyacak. O numaranın neyi vaat ettiği yazılmazsa, uyumluluk bir dilek
hâline gelir.

**Vaat proto üzerinden veriliyor.** §26.1 proto'yu otorite ilan ediyor; dolayısıyla uyumluluğun
tek anlamlı ölçüsü o dosya:

```text
MAJOR   proto'da kırıcı değişiklik    — alan kaldırıldı, anlamı değişti, RPC gitti
MINOR   proto'ya toplayıcı ekleme     — yeni alan, yeni RPC, yeni enum değeri
PATCH   proto değişmedi               — hata düzeltmesi, iç değişiklik
```

**SDK bir aralıkla uyumlu, bir sürümle değil.** 0.3 SDK'sı 0.3 ve 0.4 runtime'larıyla konuşur;
proto3 bilinmeyen alanı yok sayar ve #20'de bilinmeyen olay tipinin istemciyi düşürmediği zaten
test ediliyor. Bunu yazmak, her deploy'da beş paketi birlikte yükseltme zorunluluğunu kaldırıyor.

## Proto'yu kıran değişiklik nasıl yakalanır

İnsan dikkatiyle değil. Yayınlanmış proto ile yeni proto karşılaştırılıyor; kaldırılan alan,
değişen numara veya değişen tip **build'i düşürüyor**.

`reserved` bloklarının varlığı bu yüzden önemli: `StartExecutionRequest`'te `reserved 5 to 9`
zaten duruyor ve bu kontrol onu anlamlı kılıyor.

## Image etiketleri yalan söylememeli

```text
pipemesh/runtime:0.4.1     değişmez, tek bir build
pipemesh/runtime:0.4       o minor'ün en yenisi
pipemesh/runtime:latest    yok
```

`latest` **yayınlanmıyor**. Hangi sürümü çalıştırdığını bilmeyen bir kurulum, sorunu bildiremez;
ve on-prem müşterisi için "latest" sessizce değişen bir bağımlılık demek.

Etiket ile içerik arasındaki bağ da yalnızca söz olmamalı: yayınlanmış bir sürüm etiketi
**yeniden yazılmıyor**; aynı numarayı ikinci kez yayınlamak build'i düşürüyor. Workflow
sürümlerinde verilen kararın aynısı (§24.1): sürüm değişmezse kimlik olur, değişirse etikettir.

## Üç SDK, tek kaynak

Python ve TypeScript paketleri proto'yu **taşıyor** (stub'lar commit'li, TS `dist/proto/`'ya
kopyalıyor). Yayın sırasında bunların depodaki proto ile aynı olduğu doğrulanmalı — #9'da
Python stub'larının elle yeniden üretilmesi gerektiği zaten görüldü, ve unutulduğunda 18 test
düşmüştü. Yayın anında unutulursa test düşmez, **kullanıcı düşer**.

## Acceptance Criteria

- [x] Sürüm numarası tek bir yerde; beş artefakt onu okuyor
- [x] Proto'da alan kaldırma veya numara değiştirme build'i düşürüyor
- [x] Yalnızca alan ekleyen bir değişiklik düşürmüyor
- [x] Commit'li stub'lar depodaki proto ile aynı değilse build düşüyor
- [x] Image etiketleri `X.Y.Z` ve `X.Y`; `latest` üretilmiyor
- [x] Yayınlanmış bir `X.Y.Z` etiketini yeniden yayınlamak reddediliyor
- [x] Bir sürüm önceki SDK, bir sonraki runtime'a bağlanıp workflow koşuyor (uyum testi)
- [x] Yayın adımları tek bir komutla koşuyor ve elle sıra gerektirmiyor
- [x] `CHANGELOG` proto değişikliklerini ayrı bir başlıkta listeliyor

## Kapsam dışı

- **İmzalama ve SBOM.** Tedarik zinciri güvenliği ayrı bir iş ve ayrı araçlar.
- **Java SDK'sının Maven Central'a yayınlanması.** GPG, staging ve hesap gerektiriyor.
- **Otomatik sürüm yükseltme (release-please gibi).** Önce numaranın ne vaat ettiği yazılmalı;
  otomasyon o karardan sonra gelir.

## Split Decision

**Decision:** single-prompt, üç aşama
**Tarih:** 2026-08-22

Depoda **CI yok** ve bu contract'ın kriterleri "build düşer" diyor. Dolayısıyla düşecek şey
Maven build'i olmalı: yerelde de, ileride kurulacak herhangi bir CI'da da aynı şekilde koşar.
Kontrolleri script'e koymak, koşulmadıkları gün sessizce yok olmaları demekti.

1. **Tek sürüm kaynağı** — kökte bir `VERSION` dosyası ve üç artefaktın onunla aynı olduğunu
   doğrulayan bir test.
2. **Proto uyumluluğu** — yayınlanmış proto'nun kopyası (`proto/released/`) ile mevcut proto'nun
   karşılaştırılması; alan kaldırma, numara veya tip değişimi testi düşürüyor.
3. **Yayın adımı** — tek komut: kontroller, etiket, image'lar; `latest` yok, kullanılmış etiket
   reddediliyor.

### Üretmek yerine doğrulamak

Sürümü tek yerden **üretmek** (pom'dan pyproject'e yazmak) cazip ama kırılgan: üretici koşmadığı
gün dosyalar sessizce ayrışır. **Doğrulamak** ise ayrışmayı build'in kendisi yakalar. Aynı
gerekçe stub'lar için de geçerli.

### Proto kontrolü neden elle yazılıyor

`buf breaking` doğru araç, ama bu depo offline koşuyor ve bir binary daha getirmek her
geliştiriciye bir kurulum yüklüyor. Bunun yerine dar bir kontrol: her mesaj için
`numara:ad:tip` kümesi çıkarılıyor; **kaybolan veya değişen** bir giriş build'i düşürüyor,
eklenen serbest.

Bu, tam proto semantiği değil ve öyle olduğunu iddia etmiyor. §26.1'in koruduğu şeyin —
"yayınlanmış bir alanın anlamı değişmez" — bu depodaki dar karşılığı, tıpkı koşul ifadeleri ve
şema alt kümesi gibi.

### Stub tazeliği bir hash ile

Python stub'ları commit'li ve #9'da yeniden üretilmedikleri için 18 test düşmüştü. O gün testler
yakaladı; **yayın anında** yakalamaz — kullanıcı yakalar. Stub'ların yanına proto'nun hash'i
yazılıyor; proto değişip hash güncellenmediyse build düşüyor, ve hash'i güncellemenin tek doğal
yolu stub'ları yeniden üretmek.

### Kapsam dışı (ek)

- **CI kurulumu.** Kontroller `mvn test` ile koşuyor; hangi CI'da koşacakları ayrı bir karar ve
  bu contract onu gerektirmiyor.

### Risk points

- **`VERSION` ile pom'un `-SNAPSHOT`'ı.** Maven geliştirme sürümünü `-SNAPSHOT` ile işaretliyor;
  karşılaştırma bunu bilmeli, yoksa test ya hep kırmızı olur ya da anlamsız gevşetilir.
- **Yayınlanmış proto kopyasının güncellenmeyi unutması.** Kopya yalnızca yayın anında
  tazelenmeli; her değişiklikte tazelenirse kontrol hiçbir şey yakalamaz. Yayın adımının parçası
  olmalı, geliştirmenin değil.
- **`reserved` bloklarının kontrolü atlaması.** Kaldırılan bir alan `reserved`'a konmuşsa bu
  doğru davranış — kontrol bunu kaldırma saymamalı, yoksa doğru yolu cezalandırır.
- **Etiketin içerikle bağının kopması.** Aynı `X.Y.Z`'yi ikinci kez yayınlamak reddedilmezse
  sürüm bir etiket olur, kimlik değil — #9'da workflow sürümleri için verilen kararın aynısı.

## Implementation Notes

**Tamamlandı:** 2026-08-22 — 4 yeni test; toplam 465 Java.

### Kontroller script'te değil, testte

Depoda CI yok ve kriterler "build düşer" diyordu. Kontroller `mvn test` içinde: yerelde de,
ileride kurulacak herhangi bir CI'da da aynı koşuyorlar. `bin/release.sh` onları **çağırıyor**,
sahiplenmiyor — yalnızca script'te yaşayan bir emniyet, birinin elle yayınladığı gün yok olur.

### Kontrolün yakaladığı da doğrulandı

Geçen bir test, yakalamayan bir testten ayırt edilemez. `StartExecutionRequest.input`'un
numarasını 2'den 11'e aldım ve kontrol düştü:

```
StartExecutionRequest.input:google.protobuf.Struct was removed without reserving 2
```

Sonra geri alındı. Kontrolü yazmakla kontrolün çalıştığını bilmek ayrı şeyler.

### `reserved` doğru yolu cezalandırmıyor

Kaldırılan bir alan `reserved`'a konmuşsa kontrol bunu ihlal saymıyor — numara geri
kullanılamaz ve hiçbir istemci şaşırmaz. Preflight'ta risk olarak yazılmıştı: aksi hâlde doğru
kaldırma biçimi cezalandırılır ve insanlar yanlış olanı seçer.

`StartExecutionRequest`'teki `reserved 5 to 9` bloğu bu kontrolle nihayet bir işe yarıyor.

### Üretmek yerine doğrulamak

Sürüm `VERSION`'da; pom, pyproject ve package.json onunla **karşılaştırılıyor**. Üretmek
(pom'dan diğerlerine yazmak) cazipti ama üretici koşmadığı gün dosyalar sessizce ayrışır.
Doğrulama ayrışmayı build'in kendisine yakalatıyor.

Maven'ın `-SNAPSHOT`'ı karşılaştırmadan önce çıkarılıyor — preflight'ta yazılmıştı, yoksa test ya
hep kırmızı olurdu ya anlamsız gevşetilirdi.

### Stub tazeliği hash ile

#9'da Python stub'ları yeniden üretilmediği için 18 test düşmüştü. O gün testler yakaladı;
**yayın anında yakalamazlar** — kullanıcı yakalar. Stub'ların yanında proto'nun SHA-256'sı var;
proto değişip hash güncellenmediyse build düşüyor, ve hash'i güncellemenin doğal yolu stub'ları
yeniden üretmek.

### Yayınlanmış proto kopyası nerede yaşıyor

`proto/` altına koymak protoc'u kırdı — o dizindeki her şey derleniyor ve aynı paketin ikinci
kopyası yinelenen tanım demek. `release/proto/`'ya taşındı, ve yalnızca **yayın adımında**
tazeleniyor: her değişiklikte tazelenseydi kontrol kendisiyle karşılaştırır ve hiçbir şey
doğrulamazdı.

### `latest` yok

Ne çalıştırdığını söyleyemeyen bir kurulum sorunu bildiremez, ve on-prem müşterisi için `latest`
kendiliğinden değişen bir bağımlılık. `X.Y.Z` ve `X.Y` var.

Kullanılmış bir etiketi yeniden yayınlamak reddediliyor — #9'un workflow sürümleri için verdiği
kararın aynısı: değişmezse kimlik, değişirse etiket.

### Devralınacak

- **CI.** Kontroller hazır; hangi CI'da koşacakları ayrı bir karar.
- **İmzalama ve SBOM**, **Maven Central'a Java SDK'sı**, **otomatik sürüm yükseltme** — hepsi
  kapsam dışıydı ve öyle kalıyor.
