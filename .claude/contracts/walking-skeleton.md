# Walking Skeleton — End-to-End Durable Workflow

**Status:** Draft
**Created:** 2026-08-19
**DESIGN.md kapsamı:** §7, §8, §9.1, §9.2, §9.3, §9.4, §10, §12, §13, §14, §15, §16, §22, §25, §26, §27, §28 (kısmi)

## Goal

Tek bir workflow JSON'ının uçtan uca çalıştığı en ince dikey dilimi (walking skeleton) implemente
etmek: `JSON Workflow → LLM → Condition → MCP → Approval → Resume → Observable execution`.
Amaç framework'ü büyütmek değil, execution modelini kanıtlamak — süreç yeniden başlatıldığında
askıdaki bir workflow'un aynı yerden devam edebildiğini ve her adımın izlenebilir olduğunu göstermek.

Bu dilimin geçtiği an, DESIGN.md §46'daki "başarı testi"nin ilk yarısı sağlanmış olur: yeni bir
workflow eklemek runtime'ı değiştirmeyi gerektirmemelidir.

## Affected Modules

DESIGN.md §40'taki yapıya göre:

- [ ] `core/workflow` — `WorkflowDefinition`, `Step`, `WorkflowCompiler`, `ExecutionGraph` (§7, §25)
- [ ] `core/execution` — `WorkflowExecutor`, `StepExecutor` SPI + 4 implementasyon, `ExecutionContext` (§14, §27)
- [ ] `core/state` — `ExecutionState`, `StateStore` arayüzü + kalıcı implementasyon (§15)
- [ ] `core/scheduler` — runnable step kuyruğu, resume tetikleyicisi (§16, §28)
- [ ] `core/expressions` — condition expression evaluator (§9.3)
- [ ] `providers/models` — `MessagingProvider` arayüzü + tek somut LLM provider (§13)
- [ ] `registry/workflow`, `registry/capability`, `registry/model`, `registry/prompt` — dosya tabanlı, read-only (§11, §12, §31)
- [ ] `integrations/mcp` — MCP client, tek server, tool invocation (§10)
- [ ] `observability/tracing` — execution trace + temel metrikler (§22)
- [ ] `schemas/` — `workflow.schema.json`, `capability.schema.json`, `model.schema.json`
- [ ] `proto/pipemesh.proto` — API contract'ı yazılır (servis implementasyonu YOK, §26.1)
- [ ] `examples/approval-flow/` — dilimi koşturan çalışan örnek + config repo
- [ ] Database migration — execution state şeması (aşağıda)
- [ ] Infrastructure — n/a (Phase 1 tek process, distributed worker yok)
- [ ] Python SDK — n/a bu dilimde (bkz. contract #12)
- [ ] Mobile (React Native) — n/a
- [ ] Admin portal (Angular) — n/a

## Reference Workflow

Dilimi tanımlayan kanonik örnek — `examples/approval-flow/workflows/venue-booking.json`.
Bu JSON, runtime kodu değişmeden çalışmalıdır:

```json
{
  "id": "venue_booking",
  "version": "1.0",
  "entry": "extract_request",

  "steps": [
    {
      "id": "extract_request",
      "type": "llm",
      "model": "fast",
      "prompt": "venue_booking.extraction.v1",
      "outputSchema": "venue_request",
      "output": "request",
      "next": "validate"
    },
    {
      "id": "validate",
      "type": "condition",
      "expression": "$.request.valid == true",
      "onTrue": "search_venue",
      "onFalse": "rejected"
    },
    {
      "id": "search_venue",
      "type": "capability",
      "capability": "venue_search",
      "input": "$.request.location",
      "output": "venues",
      "next": "approval"
    },
    {
      "id": "approval",
      "type": "human_approval",
      "message": "Book this venue?",
      "onApproved": "done",
      "onRejected": "rejected"
    },
    { "id": "done",     "type": "terminal", "status": "COMPLETED" },
    { "id": "rejected", "type": "terminal", "status": "CANCELLED" }
  ]
}
```

Eşlik eden config artefaktları (§31 yapısı):

```text
examples/approval-flow/
├── workflows/venue-booking.json
├── models/models.json          # "fast" -> provider + model id
├── capabilities/venue-search.json    # provider.type = "mcp"
├── prompts/venue-booking/extraction.v1.md
└── schemas/venue-request.json  # structured output şeması
```

## API Contract

Bu dilimde runtime in-process bir kütüphane olarak çalışır; gRPC sınırı (DESIGN.md §26.1)
implemente **edilmez**. Ancak nihai otorite `proto/pipemesh.proto`'dur — aşağıdaki Java API'si
onun in-process binding'i olarak tasarlanmalı, tersi değil.

### Boundary constraints (proto sonradan yazılsa bile bugünden zorunlu)

Aşağıdaki kurallar ihlal edilirse #12 contract'ı core'u yeniden yazmak zorunda kalır:

- Tüm id'ler string (`ExecutionId`, `WorkflowId` value object olabilir ama serialize edilebilir olmalı)
- `variables` sınırı JSON olarak geçer — `ExecutionContext` içinde serialize edilemeyen nesne tutulmaz
- `start`/`resume`/`snapshot` girdi ve çıktıları tamamen serialize edilebilir; in-memory handle,
  callback referansı, `Future` veya açık kaynak (connection, stream) dönmez
- `ExecutionSnapshot` Java'ya özgü tip sızdırmaz (enum -> string, `Instant` -> epoch millis)
- Execution event'leri stream'e çevrilebilecek şekilde ayrık ve sıralı üretilir (§26.1 `WatchExecution`)

### Runtime giriş noktaları

```java
public interface WorkflowRuntime {

    // Yeni execution başlatır; ilk suspend noktasına kadar ilerletir.
    ExecutionHandle start(WorkflowId workflowId, ExecutionInput input);

    // Askıdaki bir execution'ı dışarıdan gelen kararla devam ettirir.
    ExecutionHandle resume(ExecutionId executionId, ResumeSignal signal);

    // Salt-okunur durum sorgusu (polling ve observability için).
    ExecutionSnapshot snapshot(ExecutionId executionId);
}

public record ExecutionHandle(
    ExecutionId executionId,
    ExecutionStatus status,   // §15: CREATED|RUNNING|WAITING|COMPLETED|FAILED|CANCELLED
    String currentStep
) {}

public sealed interface ResumeSignal {
    record Approval(String approvalId, boolean approved, String decidedBy, String comment)
        implements ResumeSignal {}
}
```

`start` ve `resume` **thread bloklamaz** (§16). Approval'a gelen execution `WAITING` durumunda
persist edilir ve çağıran thread serbest bırakılır.

### Step executor SPI (genişletme noktası)

```java
public interface StepExecutor {
    boolean supports(StepType type);
    StepResult execute(Step step, ExecutionContext context);
}

public sealed interface StepResult {
    record Continue(String nextStepId, Map<String, Object> variables) implements StepResult {}
    record Suspend(SuspensionReason reason, Duration timeout)          implements StepResult {}
    record Terminate(ExecutionStatus status)                            implements StepResult {}
    record Failed(String code, String message, boolean retryable)       implements StepResult {}
}
```

Bu dilimde 4 implementasyon: `LlmStepExecutor`, `CapabilityStepExecutor`, `ConditionStepExecutor`,
`ApprovalStepExecutor` (+ `TerminalStepExecutor`). Yeni bir step tipi eklemek `WorkflowExecutor`'ı
değiştirmemelidir — kabul kriteri.

### Model provider sınırı (§13)

```java
public interface MessagingProvider {
    String id();
    CompletionResponse complete(CompletionRequest request);
    // stream(...) bu dilimde YOK — Phase 2
}
```

`CompletionResponse` token kullanımını taşımalı (`inputTokens`, `outputTokens`) — observability
metrikleri buradan beslenir.

### Capability provider sınırı (§10)

```java
public interface CapabilityProvider {
    String type();                       // "mcp" | "http" | "inprocess"
    CapabilityResult invoke(CapabilityDescriptor capability, JsonNode input);
}
```

Bu dilimde tek implementasyon: `McpCapabilityProvider`. Workflow JSON'ı `"type": "mcp"` ifadesini
**görmez** — sadece `"capability": "venue_search"` der; MCP eşlemesi capability descriptor'ında kalır.

### JSON şemaları

`schemas/workflow.schema.json` bu dilim için şu step tiplerini tanımlar:
`llm`, `capability`, `condition`, `human_approval`, `terminal`. Diğer tipler (§9.5–9.8) şemada
**yoktur** — bilinçli olarak Phase 2/3'e bırakıldı.

Şema `additionalProperties: false` ile kapalı olmalı: workflow tanımının gövde taşıyan hiçbir alanı
(`code`, `script`, `expression` dışında serbest metin) kabul etmemesi §23.1'in şema seviyesindeki
karşılığıdır — inline kod bir kabul kriteri olarak reddedilir.

## DB Schema Changes

State store, resume'u mümkün kılan tek bileşen (§15). Migration `001_execution_state.sql`:

```text
workflow_execution
  execution_id        (PK)
  workflow_id
  workflow_version
  status              CREATED|RUNNING|WAITING|COMPLETED|FAILED|CANCELLED
  current_step
  variables           JSON  -- ExecutionContext.variables snapshot'ı
  created_at, updated_at
  version             -- optimistic locking; çift-ilerletmeyi engeller

workflow_step_history
  id                  (PK)
  execution_id        (FK)
  step_id
  step_type
  status              SUCCESS|FAILED|SUSPENDED
  input_snapshot      JSON
  output_snapshot     JSON
  model_id            -- llm step ise
  prompt_version      -- llm step ise (§24)
  input_tokens, output_tokens
  latency_ms
  started_at, finished_at

workflow_approval
  approval_id         (PK)
  execution_id        (FK)
  step_id
  message
  status              PENDING|APPROVED|REJECTED|EXPIRED
  decided_by, comment, decided_at
  expires_at
```

Kritik invariant: **bir step'in sonucu ile execution'ın yeni durumu aynı transaction'da yazılır.**
Aksi halde restart sonrası ya adım kaybolur ya iki kez çalışır.

## Events

Phase 1'de dış broker yok (§28'deki dağıtık model Phase 4). Ancak scheduler, in-process bir event
kuyruğu üzerinden çalışmalı ki sonradan broker'a taşınırken executor değişmesin:

```text
ExecutionStarted        -> scheduler: ilk step'i kuyruğa al
StepCompleted           -> scheduler: next step'i kuyruğa al
ExecutionSuspended      -> state persist, kuyruktan düş
ApprovalReceived        -> scheduler: execution'ı yeniden kuyruğa al
ExecutionTerminated     -> trace'i kapat
```

Event yayınlama noktası `WorkflowExecutor`, tüketici `Scheduler`. Executor'ın `Scheduler`'a doğrudan
bağımlılığı olmamalı.

## Observability

Her execution tek bir trace üretir; her step bir span (§22).

Span attribute'ları:
```text
workflow.id, workflow.version, execution.id, step.id, step.type
llm.model, llm.prompt_version, llm.input_tokens, llm.output_tokens
tool.mcp_server, tool.name
approval.wait_time_ms
```

Bu dilimde zorunlu metrikler:
```text
workflow.duration          histogram
workflow.success_rate      counter (status label'lı)
llm.latency                histogram
llm.input_tokens / llm.output_tokens
tool.latency, tool.failure_rate
approval.wait_time
```

Restart sonrası resume edilen execution, **aynı trace'e** bağlanmalı (trace context state ile
birlikte persist edilir) — yoksa "observable execution" iddiası askıdaki workflow'larda kopar.

## Out of Scope (bilinçli olarak dışarıda)

Phase 2+: streaming (§30), retry/timeout policy (§17, §18), fallback model, prompt A/B, intent
resolution (§19), parallel (§9.5), transform (§9.6), wait/event step (§9.7), application
capability'leri ve SDK worker'ları, gRPC servisi (§26.1), SDK'lar, LangChain adapter (§35), workflow versiyon
migration (§24), cost tracking, distributed worker (§28), capability permission modeli (§23 — sadece
şemada alan olarak yer tutulur, enforcement yok).

Bu maddeler bu contract'ta **implemente edilmeyecek**; her biri kendi contract'ına ayrılacak
(bkz. `.claude/contracts/README.md`).

## Acceptance Criteria

- [ ] `venue-booking.json` runtime kodunda hiçbir değişiklik olmadan yüklenip çalışır
- [ ] LLM step, `venue-request.json` şemasına uyan structured output üretir; şema ihlali `Failed` döner
- [ ] Condition step, `$.request.valid == true` ifadesini LLM çağırmadan deterministik değerlendirir
- [ ] Capability step, MCP server'daki `search` tool'unu çağırır; workflow JSON'ında "mcp" kelimesi geçmez
- [ ] Approval step'e gelindiğinde execution `WAITING` olarak persist edilir ve **çağıran thread bloklanmaz**
- [ ] **Restart testi:** approval beklerken process öldürülür, yeniden başlatılır, `resume(...)`
      çağrılır ve workflow kaldığı yerden `COMPLETED`'a ulaşır
- [ ] `onRejected` yolu `CANCELLED` ile biter; hiçbir side-effect çalışmaz
- [ ] Aynı `approvalId` ile iki kez `resume` çağrılırsa ikincisi yok sayılır (idempotency)
- [ ] Execution trace'i tüm step'leri, model id'sini, prompt versiyonunu ve token sayılarını içerir
- [ ] Restart sonrası resume edilen execution aynı trace id'sine bağlanır
- [ ] **Genişletilebilirlik testi:** yeni bir step tipi eklemek `WorkflowExecutor` dosyasına
      dokunmadan mümkün (yeni `StepExecutor` + şema kaydı yeterli)
- [ ] `WorkflowCompiler` geçersiz grafiği reddeder: bilinmeyen `next`/`onTrue`/`onFalse` hedefi,
      ulaşılamayan step, entry eksikliği
- [ ] İkinci bir örnek workflow (farklı adım sırası) sadece config eklenerek çalışır
- [ ] `pipemesh.proto` yazılmıştır ve `WorkflowRuntime`'ın tüm girdi/çıktıları ona
      kayıpsız map edilebilir (derleme değil, gözden geçirme kriteri)
- [ ] Şema, gövdesinde kod taşıyan bir step'i (`{"type":"code","code":"..."}`) reddeder (§23.1)
- [ ] Workflow step'i capability'nin nasıl çalıştığına dair hiçbir alan taşımaz — transport, owner,
      version ve permission yalnızca capability registration'ında bulunur (§9.8)
- [ ] Runtime hiçbir iş kuralı içermez: örnekteki tüm business logic capability provider'ın arkasındadır

## Open Decisions (preflight'ta netleşecek)

1. ~~**Dil/build**~~ — **Karar verildi (2026-08-19):** Java 21 + Maven multi-module. `core/`
   framework-free saf Java; Spring entegrasyonu ayrı `spring/` modülünde (bu dilimde yok).
   Python SDK ayrı bir contract (#12) — bu dilimin kapsamında **değil**.
2. **State store:** Phase 1 için PostgreSQL mi, embedded (H2/SQLite) mı? Restart testi ikisiyle de
   yapılabilir; Postgres prod'a daha yakın.
3. **Expression dili:** JSONPath alt kümesi mi, JMESPath mi, SpEL mi? Determinizm ve sandbox
   açısından en dar olan tercih edilmeli.
4. **MCP client:** resmi MCP Java SDK mı, minimal JSON-RPC client mı? Transport: stdio mu, HTTP mu?
5. **LLM provider:** dilimde tek provider yeterli — hangisi? (structured output desteği belirleyici)
6. **Approval transport:** approval kararının runtime'a nasıl ulaştığı örnekte HTTP endpoint mi,
   yoksa sadece kütüphane API çağrısı mı?
7. ~~**Remote boundary**~~ — **Karar verildi (2026-08-19):** gRPC (DESIGN.md §26.1).
   Bu dilimde **implemente edilmez**, ama `proto/pipemesh.proto` bu dilimle birlikte yazılır ki
   core API'si serialize edilemez bir şekle kaymasın. Bkz. aşağıdaki "Boundary constraints".
8. **SDK capability yönü (açık):** runtime'ın SDK içindeki bir capability'i çağırma mekanizması —
   worker'ın açtığı bidi stream (önerilen) mi, yoksa runtime'ın SDK'nın host ettiği servise
   dial etmesi mi? Karar #12'de verilebilir; bu dilimi etkilemez çünkü tek capability provider MCP.

## Split Decision

**Decision:** single-prompt (aşamalı, tek ajan)
**Tarih:** 2026-08-19

**Reasoning:**

Contract net — API şekli, DB şeması, event'ler, 15 kabul kriteri tanımlı. Ama multi-agent'ı
engelleyen iki yapısal sebep var:

1. **Repo tamamen boş.** Kod yok, `pom.xml` yok, paket yapısı yok, hiçbir tip tanımlı değil.
   Paralel ajanlar `Step`, `ExecutionContext`, `StepResult`, `StateStore` üzerinde aynı anda
   çalışırdı — bunlar dilimdeki her parçanın bağlı olduğu ortak yüzey. Interface'ler yerleşmeden
   paralellik sadece merge çakışması üretir.
2. **Dikey dilim zaten bu contract'ın kendisi.** "LLM adımı", "MCP adımı", "approval adımı" birer
   slice gibi görünüyor ama hepsi `WorkflowExecutor`, `ExecutionContext`, `StateStore` ve
   `workflow.schema.json` dosyalarına dokunuyor. Bunlara slice demek, katman bölmeyi gizlemektir.

Multi-agent bu contract için değil, **#12/#13 (gRPC + SDK'lar)** için doğru olur: orada proto
sınırı gerçek bir kesme çizgisi ve SDK'lar birbirinden bağımsız.

### Build order (tek ajan, sırayla)

Ajan bu sırayı takip etmeli; her aşama bir sonrakinin ön koşulu:

1. **Foundation** — Maven multi-module iskelet, Java 21, `core/` framework-free.
   `WorkflowDefinition`, `Step`, `ExecutionContext`, `StepResult`, `ExecutionStatus`,
   `StateStore` + `CapabilityProvider` + `MessagingProvider` arayüzleri. Hiç implementasyon yok.
   → Bu aşama biterken "Boundary constraints" listesi gözden geçirilir.
2. **Compiler + in-memory execution** — `WorkflowCompiler` (grafik doğrulama: bilinmeyen hedef,
   ulaşılamaz step, entry eksikliği), `ConditionStepExecutor`, `TerminalStepExecutor`,
   in-memory `StateStore`. İlk çalışan workflow: sadece condition + terminal.
3. **Persistence + resume** — Postgres `StateStore`, migration, optimistic locking,
   `ApprovalStepExecutor`, `resume()`, idempotency. **Restart testi burada yazılır.**
4. **Dış dünya** — `LlmStepExecutor` + tek model provider, `CapabilityStepExecutor` + MCP client.
   Transaction sınırının dışında çağrılmaları bu aşamanın kritik kuralı.
5. **Observability** — trace/span, metrikler, restart sonrası trace devamlılığı.
6. **Örnek + proto** — `examples/approval-flow/` config repo'su, ikinci örnek workflow,
   `proto/pipemesh.proto` (implementasyon yok).

Aşama 3 bittiğinde dilimin tezi (durability) kanıtlanmış olur; 4–6 onun üzerine biner.
Aşama 3'ten sonra istenirse yeni bir preflight ile 4/5/6 paralelleştirilebilir — o noktada
arayüzler sabit olduğu için çakışma yüzeyi küçük.

### Preflight'ta kapatılan kararlar

| # | Karar | Gerekçe |
|---|---|---|
| 2 | **PostgreSQL** + Testcontainers; `StateStore` arayüzü + test için in-memory impl | Restart testinin gerçek olması gerekiyor; `variables` için JSONB, çift-ilerletme için `version` sütunu |
| 3 | **Dar, kendi yazdığımız evaluator**: JSONPath okuma + sabit karşılaştırma grameri (`== != > < >= <=`, literal) | SpEL keyfi method çağırabilir — §23.1'in yasakladığı şeyi expression alanından geri sokar. Determinizm ve sandbox için en dar dil |
| 4 | **Resmi MCP Java SDK** (`io.modelcontextprotocol.sdk:mcp`), **stdio** transport | Stdio çekirdekte var, web framework gerektirmiyor — `core/`'un framework-free kalmasıyla uyumlu; örnek için ağ kurulumu gerekmez |
| 6 | **Sadece kütüphane API'si** (`runtime.resume(...)`) — HTTP endpoint yok | HTTP, gRPC sınırının (#12) işi. Dilime taşımak kapsamı şişirir |

**Kullanıcı onayı bekleyen tek karar — #5 LLM provider.** Belirleyici kriter structured output
desteği; hangi sağlayıcının API anahtarına erişim olduğu da pratikte belirleyici. Tasarım gereği
tek satırlık config değişikliğiyle değiştirilebilir olacak, o yüzden yanlış seçim maliyeti düşük.

#8 (SDK capability yönü) açık kalmaya devam ediyor — bu dilimi etkilemiyor, #12'de karara bağlanacak.

### Risk points

- **Sahte restart testi.** Sadece in-memory state temizleyen bir test durability kanıtlamaz.
  Test gerçekten process'i öldürüp yeniden başlatmalı (ayrı JVM veya Testcontainers ile DB'yi
  ayakta tutup uygulamayı yeniden kurmak).
- **Transaction sınırı.** Core'da Spring yok, dolayısıyla `@Transactional` yok — tx sınırları elle
  yönetilecek. LLM ve MCP çağrıları **açık transaction içinde olmamalı** (CLAUDE.md'nin
  "no cross-service calls inside a transaction" kuralının bu projedeki karşılığı). Bu, tasarımın
  en kolay bozulan yeri.
- **Trace devamlılığı.** Restart sonrası aynı trace'e bağlanmak trace context'inin state ile
  birlikte persist edilmesini gerektirir; sonradan eklemek zor.
- **Kapsam kayması.** 8 modül tek dilim için çok görünüyor; kabul kriterleri sınırı tutan tek şey.
  Retry, streaming, parallel, task/application capability, SDK — hiçbiri bu dilime girmemeli.
- **CLAUDE.md uyumu.** Monorepo'nun CLAUDE.md'si Spring/GetSpeakHub'a özgü; pipemesh ayrı repo ve
  framework-free. Evrensel kurallar (SRP, ~30 satır fonksiyon, ~200 satır sınıf, early return,
  ölü kod yok) geçerli; Controller/Service/Repository katman kuralları bu projede karşılıksız.
  Pipemesh'in kendi `CLAUDE.md`'si yazılmalı.

## Implementation Notes

### Aşama 1 — Foundation (2026-08-19) ✅

Maven multi-module iskelet + `pipemesh-core`. Sadece model tipleri ve arayüzler; hiçbir
implementasyon yok. `mvn test` yeşil (17 test).

```
io.pipemesh.core.workflow     WorkflowId, WorkflowVersion, StepId, StepType, Step, WorkflowDefinition
io.pipemesh.core.execution    ExecutionId, ExecutionStatus, ExecutionContext, StepResult,
                              SuspensionReason, StepExecutor, ExecutionInput, ExecutionHandle,
                              ExecutionSnapshot, ResumeSignal, WorkflowRuntime
io.pipemesh.core.state        ExecutionRecord, StepRecord, StateStore, StaleExecutionException,
                              ApprovalRecord, ApprovalStore
io.pipemesh.core.capability   CapabilityId, CapabilityKind, CapabilityDescriptor,
                              CapabilityResult, CapabilityProvider, CapabilityRegistry
io.pipemesh.core.model        ModelId, CompletionRequest, CompletionResponse,
                              MessagingProvider, ModelRegistry
```

**Tasarım kararları:**

- **`StepType` enum değil, string sarmalayan record.** Enum olsaydı her yeni step tipi core'u
  değiştirmeyi gerektirirdi — "yeni primitif eklemek `WorkflowExecutor`'a dokunmamalı" kabul
  kriterinin doğrudan ihlali. Aynı gerekçeyle `SuspensionReason.kind` de string.
- **`Step` yalnızca `id` + `type` + ham `config` (JsonNode).** Motor config'in şeklini bilmez;
  tipi sahiplenen executor yorumlar. Sealed bir `Step` hiyerarşisi genişletilebilirliği kırardı.
- **`StepResult` ve `CapabilityResult` sealed.** Bunlar motorun kendi sonuç kelime dağarcığı ve
  kapalı olmaları gerekiyor: yeni bir varyant zaten motorun değişmesi demek.
- **`ExecutionContext` immutable**, `with(...)`/`at(...)` yeni context döner. Tüm JsonNode alanları
  hem girişte hem çıkışta `deepCopy()` — test edildi.
- **Boundary constraints uygulandı:** tüm id'ler string sarmalayan record, `variables` JSON
  (`ObjectNode`), `ExecutionSnapshot` zaman alanları epoch millis, hiçbir dönüş tipinde `Future`,
  callback veya açık kaynak yok. `WorkflowRuntime` proto'nun in-process binding'i olacak şekilde
  yazıldı.
- **`ApprovalStore` `StateStore`'dan ayrı.** Farklı soruya cevap veriyor ("kim onay bekliyor?"),
  ve execution variable'larını okumaması gereken insan-yüzlü araçlar tarafından sorgulanacak.
  `settle(...)` `Optional` döner — tekrarlanan resume sinyali burada düşer (idempotency).
- **`StateStore.advance(record, step)` tek metot.** Step history ve yeni execution state'i tek
  transaction'da yazma kuralını API şekliyle zorunlu kılıyor; iki ayrı metot olsaydı kural
  yalnızca yorumda kalırdı.
- **`ExecutionRecord.traceContext` baştan var.** Restart sonrası aynı trace'e bağlanmak sonradan
  eklenemez; Aşama 5'e bırakılsaydı state şeması değişmek zorunda kalırdı.

**Bağımlılıklar:** yalnızca `jackson-databind` (runtime), `junit-jupiter` (test). AssertJ
başlangıçta eklendi sonra çıkarıldı — kütüphane için gereksiz test bağımlılığı, ayrıca yerel
ortamda çözülemiyordu (aşağıya bakın).

**Ortam notu:** `~/.m2/settings.xml` içindeki aktif `artifactory` profili `central`'ı
`artifactory.justlife.com`'a yönlendiriyor; VPN dışında erişilemiyor ve online build asılıyor.
Şu an build yalnızca `mvn -o` (offline, yerel cache) ile çalışıyor. Public bir repo için bu
kabul edilemez — kişisel projeler için ayrı bir settings dosyası veya profil devre dışı bırakma
gerekiyor. Repo'nun kendi pom'u temiz, düzeltme ortam tarafında.

### Sıradaki — Aşama 2

`WorkflowCompiler` (grafik doğrulama), `ConditionStepExecutor`, `TerminalStepExecutor`,
in-memory `StateStore`. İlk çalışan workflow: condition + terminal.
