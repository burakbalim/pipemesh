# TypeScript SDK

**Status:** Implemented
**Created:** 2026-08-20
**DESIGN.md kapsamı:** §26.2, §26.4
**Kaynak:** #13'ten kesilen ikinci dil

## Goal

Node uygulamalarının PipeMesh runtime'ına ulaşması. Python SDK ile aynı sözleşme, aynı proto,
farklı araç zinciri.

## Implementation Notes

### Tamamlandı (2026-08-20) ✅

**11 test yeşil** — hepsi gerçek Java sunucusuna karşı, ayrı process, gerçek soket.

```
sdk/typescript/src/client.ts   PipeMesh, PipeMeshError, Update, Struct çevirisi
sdk/typescript/test/           Java runtime'ı child process olarak başlatan node:test paketi
```

**Tasarım kararları:**

- **Codegen yok.** `@grpc/proto-loader` proto'yu çalışma anında okuyor; kuran kişinin protoc'a
  ihtiyacı olmuyor. Python'da stub'lar üretilip commit'lendi çünkü orada dinamik yükleme yok —
  aynı sonuç, farklı yol.
- **Proto pakete kopyalanıyor.** İlk hâli paket dışına (`../../../proto`) uzanıyordu; repoda
  çalışırdı ve npm'den kurulan pakette **ilk gün kırılırdı.** `npm run build` artık proto'yu
  `dist/proto/`'ya kopyalıyor.
- **`watch()` eager ve iptal edilebilir.** Python'daki aynı karar; ek olarak `for await`'tan
  `break` etmek çağrıyı iptal ediyor. Etmeseydi sunucu kimsenin okumadığı bir aboneyi beslemeye
  devam eder, Node process'i de bir şey hâlâ dinlediği için kapanmazdı.
- **Test bağımlılığı yok.** Node 20'nin `node:test` çalıştırıcısı kullanıldı; pakette yalnızca
  `@grpc/grpc-js`, `@grpc/proto-loader` ve derleme için `typescript`.

### Test'in yakaladığı gerçek hata

İlk hâlde `execute` çağrıları `FAILED` dönüyordu. Sebep: `input` düz JavaScript objesi olarak
gönderiliyordu ama `google.protobuf.Struct` ham şeklini bekliyor — sayılar sayı olarak varmıyor,
`$.input.price > 100` koşulu karşılaştırılamıyor ve step düşüyordu. **Kodlama hatası runtime'a
düşmüş bir step olarak ulaşıyordu**, olduğu yerden çok uzakta. Struct çevirisi artık iki yönde de
açıkça yazılı.

Python'da bu sorun çıkmadı çünkü protobuf'ın Python kütüphanesi `Struct.update()` ile dönüşümü
kendisi yapıyor. Aynı sözleşmenin iki dilde farklı tuzakları var; ikisini de gerçek sunucuya karşı
test etmenin sebebi bu.
