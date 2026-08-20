# Application Capabilities

**Status:** Draft
**Created:** 2026-08-20
**DESIGN.md kapsamı:** §9.8 (capability, task değil), §10 (capability mimarisi), §26.1 (inbound stream)

## Goal

Bir uygulamanın kendi kodunun, kendi dilinde ve kendi process'inde yaşayıp runtime tarafından
capability olarak çağrılabilmesi.

Bugün capability'ler MCP tool'u, REST/gRPC endpoint'i veya in-process Java fonksiyonu olabiliyor.
Eksik olan, tasarımın en çok üzerinde durduğu hâl: **başka bir dildeki uygulama kodu.** İki SDK
yazıldı ama ikisi de runtime'ı yalnızca *çağırabiliyor*; runtime onların içindeki koda ulaşamıyor.
§26.1'in "iki yönlü trafik" tasarımının yarısı boş.

Kanıtlanacak şey şu: **aynı workflow'un bir capability'si MCP tool'u iken diğeri birinin Python
fonksiyonu olabilmeli — ve workflow ikisini ayırt edememeli.**

## Affected Modules

- [ ] `pipemesh-grpc` — `CapabilityWorker.Connect` servisi, worker kayıt defteri
- [ ] `core/capability` — worker'a yönlendiren `CapabilityProvider`
- [ ] `sdk/python`, `sdk/typescript` — worker tarafı: fonksiyon kaydı ve invocation döngüsü
- [ ] `examples/` — bir capability MCP, biri uygulama worker'ı olan workflow

## Contract

Capability kaydı yalnızca taşımayı değiştiriyor; workflow'a dokunulmuyor:

```json
{
  "id": "calculate_discount",
  "kind": "application",
  "owner": "billing-team",
  "execution": { "type": "worker", "capability": "calculate_discount", "idempotent": true }
}
```

Worker tarafı (Python):

```python
worker = PipeMeshWorker("localhost:8080", organization="acme")

@worker.capability("calculate_discount")
def calculate_discount(customer):
    return {"rate": 0.20 if customer["tier"] == "gold" else 0.05}

worker.run()
```

Bağlantıyı **worker açıyor** (§26.1): worker'ın erişilebilir adrese, sertifikaya ya da firewall
istisnasına ihtiyacı olmuyor. Runtime invocation'ları açık stream üzerinden itiyor.

## Tasarımın zor sorusu: worker invocation'ı aldıktan sonra ölürse

Runtime "istek gitti mi, işlendi mi" bilemez. Bu, retry ve kurtarma için yazdığımız ayrımın en sert
sınavı:

```text
worker ölümü
     ↓
capability idempotent mi?
     ├── evet  → başka bir worker'a yönlendir
     └── hayır → execution FAILED (execution.unrecoverable), insan baksın
```

`idempotent: false` bir capability uzak worker'da koşuyorsa, **worker ölümü insan müdahalesi
demektir.** Bunu yumuşatmak (örneğin "muhtemelen ulaşmadı, tekrar dene") tam olarak çift tahsilat
üreten varsayımdır.

İkinci sonuç: worker ölümü **yeni bir öksüz sınıfı** yaratıyor — execution `RUNNING`'de kalıyor.
`RecoveryScheduler` bu contract'tan hemen önce yazıldı; bu iş onu zorunlu kılan sebep.

## Tasarım Soruları (preflight'ta netleşmeli)

1. **Invocation zaman aşımı worker'da mı runtime'da mı?** Step'in kendi `timeout` politikası var;
   worker tarafında ayrı bir sınır gerekir mi, yoksa runtime'ınki yeterli mi?
2. **Aynı capability'yi birden çok worker sunarsa?** Rastgele mi, sıra ile mi, yoksa ilk bağlanan
   mı? Yük dağıtımı bu contract'ın işi mi #10'un mu?
3. **Worker kaydı organizasyona bağlı mı?** `WorkerRegistration.organization_id` proto'da var —
   bir organizasyonun worker'ı başka bir organizasyonun execution'ını görmemeli. Bu #17'nin işi
   ama sınır burada çiziliyor.
4. **Worker hiç yoksa ne olur?** Step hemen mi düşsün, yoksa bir süre worker beklesin mi?

## Acceptance Criteria

- [ ] Worker bağlanıp sunduğu capability'leri bildiriyor
- [ ] Runtime, `worker` tipindeki bir capability'yi bağlı worker'a yönlendiriyor
- [ ] Worker'ın döndürdüğü sonuç adımın çıktısı oluyor, akış devam ediyor
- [ ] Worker hata döndürürse step `Failed` oluyor ve retry politikası uygulanıyor
- [ ] **Worker invocation ortasında ölürse:** idempotent capability yeniden yönlendiriliyor,
      idempotent olmayan `execution.unrecoverable` ile duruyor
- [ ] Bağlı worker yokken çağrı net bir hatayla düşüyor (sonsuza kadar beklemiyor)
- [ ] Worker bağlantısı koptuğunda kayıt defterinden düşüyor; yeniden bağlanabiliyor
- [ ] **Workflow JSON'ında `worker` kelimesi geçmiyor** — capability adı yeterli
- [ ] Aynı workflow'un bir adımı MCP tool'unu, diğeri uygulama worker'ını çağırıyor ve workflow
      ikisini ayırt etmiyor
- [ ] Python ve TypeScript worker'ları aynı sözleşmeyi uyguluyor
- [ ] Worker çağrıları da step telemetrisine ve trace'e giriyor

## Split Decision

_To be filled by Agent 0_

## Implementation Notes

_To be filled as work progresses_
