# PipeMesh Contracts

`DESIGN.md` mimarinin tamamını anlatır; bu dizin onu **uygulanabilir dilimlere** böler.
Her contract bağımsız olarak implemente edilip kabul edilebilir olmalıdır.

Kural: bir contract, DESIGN.md'nin hangi bölümlerini kapsadığını başında belirtir.
DESIGN.md değişirse contract'lar değil, tersi geçerli — contract'lar DESIGN.md'yi *daraltır*,
onunla çelişmez.

## Sıra

| # | Contract | Kapsam (DESIGN.md) | Durum |
|---|---|---|---|
| 1 | [walking-skeleton.md](walking-skeleton.md) | §7, §8, §9.1–9.4, §10, §12–16, §22, §25–28 | **Tamam** (2026-08-20) |
| 2 | [reliability-policies.md](reliability-policies.md) | §17 Retry, §18 Failure handling, timeout, fallback model | **Tamam** (2026-08-20) |
| 3 | [structured-output.md](structured-output.md) | §21 Structured output, §11 prompt registry, §24 versiyonlama | **Tamam** (2026-08-20) |
| 4 | [streaming.md](streaming.md) | §30 Streaming, provider sınırında token akışı | **Tamam** (2026-08-20) |
| 5 | [intent-resolution.md](intent-resolution.md) | §19 Intent resolution, §20 deterministic vs AI karar sınırı | **Tamam** (2026-08-21) |
| 6 | [parallel-and-transform.md](parallel-and-transform.md) | §9.5 Parallel, §9.6 Transform, §29 Concurrency/join | **Tamam** (2026-08-21) |
| 7 | [event-driven-wait.md](event-driven-wait.md) | §9.7 Wait, §28 event-driven yürütme | **Tamam** (2026-08-21) |
| 8 | [capability-permissions.md](capability-permissions.md) | §23 Security model — izin zorlaması | **Tamam** (2026-08-21) |
| 9 | [workflow-versioning.md](workflow-versioning.md) | §24, §24.1 — sürüm kimliğin parçası; migrasyon kapsam dışı | **Tamam** (2026-08-21) |
| 10a | [orphan-recovery.md](orphan-recovery.md) | §15, §38 — `RUNNING`'de takılı execution'ları toplama | **Tamam** (2026-08-20) |
| 10 | [distributed-workers.md](distributed-workers.md) | §28.1 — kira ile kapma, dispatcher, `StartMode` | **Tamam** (2026-08-21) |
| 11 | [cost-budgets.md](cost-budgets.md) | §39.1 — fiyat, harcama muhasebesi, execution bütçesi | **Tamam** (2026-08-21) |
| 11b | [evaluation-and-routing.md](evaluation-and-routing.md) | §39.2 — değişkenden model seçimi; evaluation'ın neden motor işi olmadığı | **Tamam** (2026-08-21) |
| 12 | [grpc-boundary.md](grpc-boundary.md) | §26.1 gRPC servisi (`CapabilityWorker` hariç → #14) | **Tamam** (2026-08-20) |
| 13a | [python-sdk.md](python-sdk.md) | Python client — proto'dan üretilen stub'lar + ince sarmalayıcı | **Tamam** (2026-08-20) |
| 13b | [typescript-sdk.md](typescript-sdk.md) | TypeScript client — aynı proto, `@grpc/proto-loader` | **Tamam** (2026-08-20) |
| 14 | [application-capabilities.md](application-capabilities.md) | SDK worker'ları (`CapabilityWorker.Connect`), business code sınırı | **Tamam** (2026-08-20) |
| 15 | [langchain-adapter.md](langchain-adapter.md) | §35.1 — Python SDK'sında adaptör; yeni protokol gerekmedi | **Tamam** (2026-08-21) |
| 16 | [agent-loop.md](agent-loop.md) | §9.9 sınırlı agent step'i — ilan edilmiş capability'ler, zorunlu sınır | **Tamam** (2026-08-21) |
| 16b | [json-schemas.md](json-schemas.md) | §23.1'in şema seviyesindeki karşılığı — kapalı step şemaları | **Tamam** (2026-08-21) |
| 17 | [multi-tenancy.md](multi-tenancy.md) | §22.2 organizasyon izolasyonu — okuma ve ilerletme sınırı | **Tamam** (2026-08-21) |
| 18 | _otel-exporter_ | §22.1 `pipemesh-opentelemetry` — OTLP üzerinden Datadog/New Relic/Grafana | **Tamam** (2026-08-20) |
| 19 | [console-and-subscriptions.md](console-and-subscriptions.md) | Ürün katmanı — organizasyon kaydı, plan/kota, web UI, demo | **Tamam** (2026-08-21) |
| 20 | [streaming-progress.md](streaming-progress.md) | §30.1 — `step.started`, `execution.recovered`, akış filtresi ve izni | **Tamam** (2026-08-21) |
| 21 | [runtime-distribution.md](runtime-distribution.md) | §26.3 — çalıştırılabilir runtime, kendi şeması, on-prem tek düğüm | **Tamam** (2026-08-21) |
| 22 | [cloud-deployment.md](cloud-deployment.md) | §30.2 — süreçler arası izleme, üç deployment, TLS, e-posta | **Tamam** (2026-08-22) |
| 23 | [billing-and-payment.md](billing-and-payment.md) | Ödeme SPI'si, abonelik durumu, plan değişimi; on-prem'de yok | Aşama 1-3 tamam |
| 24 | [backlog-metrics.md](backlog-metrics.md) | §22.1 — bekleyen işin yaşı, dispatcher ölçekleme sinyali | **Tamam** (2026-08-22) |
| 25 | [release-and-versioning.md](release-and-versioning.md) | §26.1 — sürüm numarasının vaadi, image ve SDK yayını | **Tamam** (2026-08-22) |
| 26 | [update-replay.md](update-replay.md) | §30.3 — dayanıklı imleç, kaçırılan olayların kurtarılması | **Tamam** (2026-08-22) |
| 27 | [remote-events.md](remote-events.md) | §9.7 — olay yayını için RPC; `wait` uzak istemciler için tamamlandı | **Tamam** (2026-08-23) |

Faz eşlemesi için DESIGN.md §45'e bakın: #1 Phase 1'i, #2–4 Phase 2'yi, #6–7 Phase 3'ü,
#9–11 Phase 4'ü karşılar. #12 fazlardan bağımsız — #1 tamamlanır tamamlanmaz başlayabilir.

## Stack kararı (2026-08-19)

Core Java 21 (framework-free, Maven multi-module); Spring entegrasyonu ayrı modül; dış sınır
gRPC (DESIGN.md §26.1); SDK'lar (Python, TypeScript, Java) tek bir `pipemesh.proto`'dan üretilir.

İki kural her contract için bağlayıcıdır:

1. **Proto otoritedir.** Java arayüzü proto'nun bir binding'idir; gRPC servisi core'un ince bir
   adaptörüdür, ikinci bir implementasyon değil.
2. **Workflow JSON'ına dile özgü hiçbir kavram sızmaz.** Java da, gRPC de engine'in
   implementasyon detayıdır — workflow artefaktının değil.
3. **Runtime / SDK / Provider ayrımı korunur** (§26.2). Runtime motordur, SDK ona erişimdir,
   Provider dış dünyaya erişimdir. LangChain, OpenAI, MCP ve managed platformlar provider'dır —
   hiçbiri core'un bağımlılığı olamaz.
4. **Business logic runtime'a girmez.** Workflow bir capability'yi *adlandırır*, kod taşımaz
   (§23.1). Inline kod taşıyan bir tasarım hiçbir contract'ta kabul edilmez.
5. **Tek çağırma primitifi: capability** (§9.8). `task` bir workflow step tipi değildir; runtime'ın
   iç execution unit kavramıdır. Ownership, versiyon, deployment ve permission capability
   registration'ının metadata'sıdır — workflow step'inin değil. Yeni bir backend (Kubernetes Job,
   Lambda, Temporal, Go worker) eklemek workflow DSL'ini genişletmemelidir; genişletiyorsa
   soyutlama sızıyor demektir.

#12 sınırı implemente eder, #13 SDK'ları üretir. #1 hiçbirini implemente etmez ama ikisini de
mümkün kılacak API şeklini zorunlu kılar.

## SDK fiilleri (§26.4)

SDK'nın üç fiili mevcut contract'lara dağılıyor — yeni bir execution modeli değil:

| Fiil | Karşılığı |
|---|---|
| `execute()` | #1 (workflow çalıştırma) + #12/#13 (uzak çağrı) |
| `process()` | #5 intent resolution — `execute()`'in önüne bir adım ekler |
| `stream()` | #4 streaming — aynı execution'ı `WatchExecution` ile izler |

`process()` bir workflow *seçer*, çalıştırmaz; `stream()` üçüncü bir çalıştırma yolu değil, aynı
koşumun gözlenmesidir. Bu ayrım korunmazsa "modeli akışı seçti" sessizce "modeli akışı yönetiyor"a
dönüşür (§20, §37).

## Akış

```text
/new-feature   -> contract taslağı (bu dizin)
/preflight     -> Split Decision bölümünü doldurur
implementasyon -> Implementation Notes bölümünü doldurur
```
