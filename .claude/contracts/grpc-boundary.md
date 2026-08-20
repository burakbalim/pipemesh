# gRPC Boundary

**Status:** Implemented
**Created:** 2026-08-20
**DESIGN.md kapsamı:** §26.1 (client boundary), §26.4 (SDK fiilleri), §30 (streaming)

## Goal

Runtime'a Java dışından ulaşılabilmesi. README'nin tezi "language-agnostic runtime" ama bugüne
kadar yalnızca aynı JVM'deki bir çağıran erişebiliyordu.

## Split Decision

**Decision:** single-prompt. Tek modül, tek yönlü: codegen kurulumu, tip çevirisi, servis adaptörü,
stream dağıtıcısı. Paralelleştirilecek bağımsız parça yok.

### Kapsam

`PipeMesh` servisi: `StartExecution`, `SubmitApproval`, `GetExecution`, `WatchExecution`.

**Kapsam dışı — `CapabilityWorker.Connect`.** Proto'da tanımlı ama implemente edilmedi: worker'ın
açtığı stream, runtime'ın SDK içindeki bir capability'yi çağırması demek ve bu #14'ün
(application capabilities) konusu. gRPC sınırını kurmak ile business code'u uzak worker'a taşımak
ayrı işler.

**`ProcessMessage` bilinçli olarak `UNIMPLEMENTED`.** Hangi workflow'un koşacağına intent
resolution karar veriyor ve o yok. Tahmin etmek, tasarımın runtime'a yasakladığı şeyin ta kendisi
(§19, §20). İstemciye net bir kodla söyleniyor.

## Implementation Notes

### Tamamlandı (2026-08-20) ✅

**205 test yeşil** (149 core + 13 Postgres + 16 provider + 9 MCP + 10 OTel + 8 gRPC).

```
pipemesh-grpc/  PipeMeshService, ExecutionUpdateBroker, WireTypes, JsonStructs
                protobuf-maven-plugin ile proto/pipemesh.proto'dan codegen
```

**Tasarım kararları:**

- **Proto tek kaynak.** `protoSourceRoot` repo kökündeki `proto/` dizinini gösteriyor — SDK'ların
  üretileceği dosyanın aynısı derleniyor, kopyası değil. Kopya olsaydı ilk gün ayrışırdı.
- **Servis karar vermiyor.** Her metot bir isteği çeviriyor, tek bir runtime metodu çağırıyor,
  cevabı çeviriyor. Buradaki bir karar, in-process çağıranın alamadığı bir karar olurdu ve
  ikisinin ayrışması §26.1'in yasakladığı şey. `WireTypes` yalnızca çeviri yapıyor.
- **Hata kodları çağıranın davranışını belirliyor.** Kayıtlı olmayan workflow `NOT_FOUND`,
  bozuk girdi `INVALID_ARGUMENT`. Hepsini `INTERNAL` yapmak istemciye yanlış şeyleri yeniden
  denetirdi.
- **`WatchExecution` kendini kapatıyor.** Execution terminal duruma geldiğinde stream
  tamamlanıyor. Bir watcher ne zaman okumayı bırakacağını bilmek zorunda kalmamalı; on dakika
  önce bitmiş bir execution'da bloke kalan istemci, hang gibi görünen bir bug'dır. İptal
  edildiğinde de abonelik düşüyor.
- **`ExecutionUpdateBroker` bir `ExecutionObserver`.** Token'ları ve execution olaylarını tek
  kanalda birleştirme kararının (#4) karşılığını burada verdi: gRPC sınırı o kanalın **tek bir
  abonesi**, yanına eklenmiş ikinci bir mekanizma değil.
- **`from_sequence` onurlandırılmıyor.** Watcher abone olduğu andan itibarasını görüyor. Replay,
  güncellemeleri saklamak ve ne kadar süreyle saklanacağına karar vermek demek — stream eklemenin
  yan etkisi olarak değil, bilerek verilecek bir karar. Proto'da alan duruyor, davranış yok.

**Araç zinciri notu:** `protobuf-maven-plugin` 0.6.1 + `os-maven-plugin`; protoc ve
`protoc-gen-grpc-java` ikilileri Maven'dan iniyor, sistemde protoc kurulu olması gerekmiyor.

**Yol boyunca bulunan bir hata:** ilk halinde stream hiç kapanmıyordu ve test paketi 6 dakika
sonra timeout'a düştü. Sorun testte değil serviste ydi — execution bittiğinde stream'i
tamamlamamak gerçek bir istemciyi de sonsuza kadar bekletirdi.
