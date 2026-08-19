# PipeMesh Contracts

`DESIGN.md` mimarinin tamamını anlatır; bu dizin onu **uygulanabilir dilimlere** böler.
Her contract bağımsız olarak implemente edilip kabul edilebilir olmalıdır.

Kural: bir contract, DESIGN.md'nin hangi bölümlerini kapsadığını başında belirtir.
DESIGN.md değişirse contract'lar değil, tersi geçerli — contract'lar DESIGN.md'yi *daraltır*,
onunla çelişmez.

## Sıra

| # | Contract | Kapsam (DESIGN.md) | Durum |
|---|---|---|---|
| 1 | [walking-skeleton.md](walking-skeleton.md) | §7, §8, §9.1–9.4, §10, §12–16, §22, §25–28 | Draft |
| 2 | _reliability-policies_ | §17 Retry, §18 Failure handling, timeout, fallback model | Planlanan |
| 3 | _structured-output-and-prompts_ | §11 Prompt registry, §21 Structured output, §24 versiyonlama | Planlanan |
| 4 | _streaming_ | §30 Streaming, provider sınırında token akışı | Planlanan |
| 5 | _intent-resolution_ | §19 Intent resolution, §20 deterministic vs AI karar sınırı | Planlanan |
| 6 | _parallel-and-transform_ | §9.5 Parallel, §9.6 Transform, §29 Concurrency/join | Planlanan |
| 7 | _event-driven-wait_ | §9.7 Wait, §28 event-driven yürütme | Planlanan |
| 8 | _capability-permissions_ | §23 Security model, MCP tool/resource izinleri | Planlanan |
| 9 | _workflow-versioning_ | §24 Versiyonlama, çalışan execution'ların migrasyonu | Planlanan |
| 10 | _distributed-workers_ | §28 Queue + worker dağıtımı, §38 reliability | Planlanan |
| 11 | _cost-and-evaluation_ | §39 cost tracking, evaluation, model routing | Planlanan |
| 12 | _grpc-boundary_ | §26.1 gRPC servisi, `pipemesh.proto`, capability worker stream'i | Planlanan |
| 13 | _sdks_ | Python / TypeScript / Java SDK'ları — proto'dan üretilir | Planlanan |
| 14 | _application-capabilities_ | `kind: application` capability'leri, SDK worker'ları, business code sınırı | Planlanan |
| 15 | _langchain-adapter_ | §35 opsiyonel LangChain provider'ı (`pipemesh-langchain`) | Planlanan |
| 16 | _agent-loop_ | §9.9 sınırlı agent step'i — capability listesi, maxIterations, iterasyon izlenebilirliği | Planlanan |
| 17 | _multi-tenancy_ | §22.2 organizasyon izolasyonu, kota/metering — etiketleme değil *zorlama* | Planlanan |
| 18 | _otel-exporter_ | §22.1 `pipemesh-opentelemetry` — OTLP üzerinden Datadog/New Relic/Grafana | **Tamam** (2026-08-20) |

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
