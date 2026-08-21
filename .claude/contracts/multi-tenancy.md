# Organization Isolation

**Status:** Draft
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §22.2 (organizasyon boyutu), §23 (security model)

## Goal

Bir organizasyonun diğerinin execution'larını **okuyamaması ve ilerletememesi.**

Organizasyon ilk yazımdan beri taşınıyor: sütun var, index var, her span ve metrik etiketli.
Worker yönlendirmesi de (#14) organizasyona göre filtreliyor. Ama:

```java
runtime.snapshot(someoneElsesExecutionId)   // değişkenleriyle birlikte döner
runtime.resume(someoneElsesExecutionId, …)  // ilerletir
```

DESIGN §22.2 bunu açıkça yazmıştı: *"Etiketleme izolasyon değil."* Bu contract o cümleyi kapatıyor.

## Sızıntının iki yüzü

**Okuma:** `GetExecution` başka bir organizasyonun execution id'siyle çağrıldığında değişkenlerini
döndürüyor — ve değişkenler iş verisi taşıyor.

**Başlatma:** bir çağıran kendini başka bir organizasyon olarak tanıtıp execution başlatabilirse,
o organizasyonun **worker'larına** ulaşır (#14 yönlendirmesi organizasyona bakıyor). Yani sızıntı
yalnızca veri değil, yetenek.

## Organizasyonu kim söyler?

#8'deki cevabın aynısı: **çağıran değil.** `Principal` artık bir organizasyon taşıyor ve onu
`PrincipalResolver` belirliyor.

Dürüst sınır: **kimliği çözülmemiş bir çağıranın organizasyonu da yoktur.** Resolver bağlamamış
bir kurulumda izolasyon yoktur — çünkü çağıranları ayırt etmeden kiracıları ayırmak mümkün değil.
Bunu gizlemek yerine söylemek doğru olanı; `Principal.organization` `Optional` ve boşluğu
"kurulum kimlik kurmadı" demek.

## Kurallar

| İşlem | Kural |
|---|---|
| `start` / `process` | Çağıranın organizasyonu biliniyorsa, istekteki organizasyonla aynı olmalı |
| `snapshot` | Execution'ın organizasyonu çağıranınkiyle aynı olmalı |
| `resume` | Aynı |
| `Principal.SYSTEM` | Hepsinden muaf — aynı process'teki kod zaten her şeye sahip |

İhlal → `OrganizationMismatchException`; gRPC'de `PERMISSION_DENIED`.

**Reddedilen okuma "bulunamadı" değil, "izin yok" demeli mi?** `NOT_FOUND` id'lerin varlığını
gizler (sızıntıyı azaltır) ama hata ayıklamayı zorlaştırır ve yanlış yönlendirir. `PERMISSION_DENIED`
seçildi: execution id'leri zaten UUID, tahmin edilerek bulunmuyorlar.

## Acceptance Criteria

- [ ] Başka organizasyonun execution'ını okumak reddediliyor
- [ ] Başka organizasyonun execution'ını resume etmek reddediliyor
- [ ] Kendi organizasyonunun execution'ını okumak/resume etmek çalışıyor
- [ ] Çağıran kendini başka bir organizasyon olarak tanıtıp execution başlatamıyor
- [ ] `Principal.SYSTEM` kısıtsız — in-process kullanım bugünkü gibi
- [ ] Kimliği çözülmemiş çağıran için kontrol yapılmıyor **ve bu davranış belgeleniyor**
- [ ] gRPC ihlali `PERMISSION_DENIED` dönüyor
- [ ] Mevcut 258 test değişmeden geçiyor

## Split Decision

**Decision:** single-prompt
**Tarih:** 2026-08-21

**Reasoning:** `Principal`'a bir alan, `DefaultWorkflowRuntime`'a üç kontrol, gRPC'ye bir eşleme.

### Build order

1. `Principal.organization` (`Optional`)
2. `DefaultWorkflowRuntime`: start/process/snapshot/resume kontrolleri
3. gRPC: `OrganizationMismatchException` → `PERMISSION_DENIED`

### Risk points

- **Kapsam kayması: satır seviyesi filtreleme.** `findStale` ve süpürücü organizasyondan bağımsız
  çalışmalı — onlar sistem işi. Kiracı sorgularını (bir organizasyonun tüm execution'larını
  listelemek) bu contract getirmiyor; öyle bir API yok.
- **Kota ve metering yok.** İzolasyon ile kaynak paylaşımı ayrı sorunlar; bu contract yalnızca
  "başkasının işine dokunamazsın" diyor, "ne kadar tüketebilirsin" demiyor.

## Implementation Notes

### Tamamlandı (2026-08-21) ✅

**267 Java + 19 Python + 19 TypeScript testi yeşil.** Mevcut testlerin hepsi değişmeden geçti.

```
core/capability/  Principal.organization (Optional), belongingTo
core/execution/   OrganizationMismatchException, DefaultWorkflowRuntime'da dört kontrol
grpc/             caller'a göre kapsama, PERMISSION_DENIED eşlemesi
```

**Kapanan sızıntı iki yüzlüydü:**

- **Okuma:** `GetExecution` başka bir organizasyonun id'siyle çağrıldığında değişkenlerini
  döndürüyordu — ve değişkenler iş verisi taşıyor.
- **Başlatma:** bir çağıran kendini başka bir organizasyon olarak tanıtabiliyordu; #14'ün worker
  yönlendirmesi organizasyona baktığı için bu, o organizasyonun **worker'larına ulaşmak** demekti.
  Sızıntı yalnızca veri değil, yetenek. Bir test bu cümleyle yazıldı.

**Kararlar:**

- **Organizasyonu çağıran değil resolver söyler.** gRPC artık organizasyonu çözülmüş
  principal'dan alıyor; istekteki `organization_id` yalnızca kimsenin tanımlanmadığı kurulumlarda
  — yani zaten baltalanacak bir izolasyon olmadığı yerde — kullanılıyor. Bir test "manager" olarak
  `organizationId: "rival"` göndermeyi deniyor ve execution yine `acme`'ye ait oluyor.
- **`PERMISSION_DENIED`, `NOT_FOUND` değil.** Id'nin varlığını gizlemek daha az sızdırır ama
  execution id'leri rastgele ve kimse tahminle bulmuyor; "bulunamadı" diye bildirilen bir "senin
  değil", operatöre öğleden sonrasını yanlış yerde aratır.
- **Kimliği çözülmemiş çağıran fenslenmiyor — ve bu belgeleniyor.** Çağıranları ayırt etmeden
  kiracıları ayırmak mümkün değil; resolver bağlamamış bir kurulumda izolasyon yoktur. Bunu
  gizleyen bir kontrol, olmayan bir güvenceyi varmış gibi gösterirdi. `Principal.ANONYMOUS`'ın
  organizasyonu `Optional.empty()` ve bir test bunu açıkça yazıyor.
- **`Principal.SYSTEM` muaf.** Aynı process'teki kod runtime'ı zaten kurdu.
- **`snapshot`/`resume`'un caller alan aşırı yüklemeleri var**; parametresiz hâlleri SYSTEM olarak
  çalışıyor, yani in-process kullanım bugünkü gibi.

**Yapılmayan:** kota ve metering (izolasyon ile kaynak paylaşımı ayrı sorunlar), kiracı sorguları
(bir organizasyonun tüm execution'larını listeleyen API yok), satır seviyesi güvenlik (kontrol
uygulama katmanında, veritabanında değil).
