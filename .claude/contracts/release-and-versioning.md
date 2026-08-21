# Release and Versioning

**Status:** Draft
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

- [ ] Sürüm numarası tek bir yerde; beş artefakt onu okuyor
- [ ] Proto'da alan kaldırma veya numara değiştirme build'i düşürüyor
- [ ] Yalnızca alan ekleyen bir değişiklik düşürmüyor
- [ ] Commit'li stub'lar depodaki proto ile aynı değilse build düşüyor
- [ ] Image etiketleri `X.Y.Z` ve `X.Y`; `latest` üretilmiyor
- [ ] Yayınlanmış bir `X.Y.Z` etiketini yeniden yayınlamak reddediliyor
- [ ] Bir sürüm önceki SDK, bir sonraki runtime'a bağlanıp workflow koşuyor (uyum testi)
- [ ] Yayın adımları tek bir komutla koşuyor ve elle sıra gerektirmiyor
- [ ] `CHANGELOG` proto değişikliklerini ayrı bir başlıkta listeliyor

## Kapsam dışı

- **İmzalama ve SBOM.** Tedarik zinciri güvenliği ayrı bir iş ve ayrı araçlar.
- **Java SDK'sının Maven Central'a yayınlanması.** GPG, staging ve hesap gerektiriyor.
- **Otomatik sürüm yükseltme (release-please gibi).** Önce numaranın ne vaat ettiği yazılmalı;
  otomasyon o karardan sonra gelir.

## Split Decision

_To be filled by Agent 0_

## Implementation Notes

_To be filled as work progresses_
