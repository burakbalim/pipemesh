# Python SDK

**Status:** Implemented
**Created:** 2026-08-20
**DESIGN.md kapsamı:** §26.2 (Runtime/SDK/Provider ayrımı), §26.4 (SDK fiilleri)
**Kaynak:** #13'ten kesilen ilk dil

## Goal

Python uygulamalarının PipeMesh runtime'ına ulaşması. SDK workflow çalıştırmaz — runtime çalıştırır,
SDK'nın işi ona erişmektir (§26.2).

## Split Decision

**Decision:** single-prompt. TypeScript ayrı bir dilim (#13b): aynı proto, farklı araç zinciri;
birini bitirmek diğerini beklemek zorunda değil.

## Implementation Notes

### Tamamlandı (2026-08-20) ✅

**Java: 206 test** (149 core + 13 Postgres + 16 provider + 9 MCP + 10 OTel + 9 gRPC)
**Python: 11 test** — hepsi gerçek Java sunucusuna karşı, ayrı process, gerçek soket.

```
sdk/python/pipemesh/     client.py + proto'dan üretilen stub'lar
sdk/python/tests/        conftest.py Java runtime'ı child process olarak başlatıyor
pipemesh-grpc/           PipeMeshServer (uzak dağıtım modu), UpdatePump, TestRuntimeServer
```

**Tasarım kararları:**

- **`watch()` eager.** Generator olsaydı abonelik ancak ilk okumada kurulurdu ve arada olan her şey
  **sessizce** kaybolurdu. Çağrı anında abone olunuyor; ilk öğe her zaman `kind == "started"` ve o
  andaki durumu taşıyor — çağıranın "buradan itibaren dinliyorum" diyebileceği nokta.
- **Sunucu 0. kare olarak mevcut durumu gönderiyor.** Bu, yukarıdaki garantinin sunucu tarafı.
  `from_sequence` replay'i hâlâ yok; 0. kare "nereden başladığın" sorusunu cevaplıyor, "neyi
  kaçırdın" sorusunu değil.
- **`UpdatePump`: stream yazımı execution thread'inden ayrıldı.** Broker güncellemeleri
  workflow'u yürüten thread üzerinden doğrudan yazıyordu; yavaş bir izleyici runtime'ı bloke
  edebilirdi. Kuyruk sınırlı ve dolduğunda **en eskiyi düşürüyor** — alternatifler bellek tükenene
  kadar büyümek ya da runtime'ı en yavaş izleyicisinin hızına indirmek. Güncelleme kaçıran bir
  izleyici her zaman mevcut durumu okuyabilir.
- **`ExecutionStatus` düz string enum.** `status == "WAITING"` bir Python geliştiricisinin
  beklediği gibi okunuyor, log'da sayı değil kelime çıkıyor.
- **`PipeMeshError` gRPC status kodunu taşıyor.** Bir hatadan sonraki yararlı soru "bu çağıranın
  hatası mıydı sunucunun mu?" — cevabı yeniden denemenin anlamlı olup olmadığını belirliyor.
- **Üretilen stub'lar commit'li.** Paketi kuran kişinin protoc'a ihtiyacı olmasın; yeniden üretme
  komutu README'de.

### Yol boyunca bulunan üç şey

1. **`watch()` lazy'ydi** — yukarıdaki eager düzeltmesi. Testte değil SDK'da gerçek bir tuzaktı.
2. **Stream'e iki yazar.** 0. kare pump dışından yazılıyordu; gRPC tek yazar ister. Pump'a alındı.
3. **Blocking stub'ın drenaj kuralı.** Java testleri asılıyordu: bir blocking server-stream
   yalnızca çağıran `next()` içindeyken callback'lerini pompalıyor, yani kimsenin okumadığı bir
   stream aynı kanaldaki diğer çağrıları da durduruyor. Python bunu zaten doğru yapıyordu (ilk
   kareyi okuyup sonra ilerliyor); Java testleri de aynı sıraya alındı. **Sunucuda hata yoktu** —
   ama `UpdatePump` yine de kaldı, çünkü işaret ettiği tehlike gerçekti.

Java testleri artık in-process transport yerine **gerçek soket** kullanıyor: streaming çağrıları
başka bir dildeki istemcinin yaptığının aynısı olsun diye.

**Kapsam dışı:** TypeScript SDK (#13b), Java uzak client'ı (üretilen stub'lar zaten var),
`CapabilityWorker` yönü (#14).
