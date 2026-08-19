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

### Aşama 2 — Compiler + in-memory execution (2026-08-19) ✅

İlk uçtan uca koşum: tamamen JSON'la tanımlanmış bir workflow, runtime kodunda hiçbir değişiklik
olmadan çalışıyor. 48 test yeşil (3 ardışık koşum).

```
workflow/    WorkflowDefinitionReader, WorkflowFormatException, WorkflowCompiler,
             WorkflowCompilationException, ExecutionGraph, WorkflowRegistry,
             InMemoryWorkflowRegistry
expression/  JsonPath, Comparison, ConditionExpression, ExpressionException
execution/   StepExecutors, WorkflowExecutor
execution/step/  ConditionStepExecutor, TerminalStepExecutor
state/memory/    InMemoryStateStore
```

**Tasarım kararları:**

- **`StepExecutor.outgoing(Step)` eklendi.** Compiler grafiğin kenarlarını doğrulamak zorunda, ama
  condition'ın `onTrue`, approval'ın `onApproved` kullandığını bilmemeli — bunu config'i yorumlayan
  bilir. Kenarları executor'a sormak, core'un hiç duymadığı step tipleri için de kenar
  doğrulamasının çalışmasını sağlıyor. Bu, "yeni step tipi core'a dokunmamalı" kriterinin
  compiler tarafındaki karşılığı.
- **Compiler bulduğu tüm sorunları tek seferde bildiriyor**, ilkinde durmuyor. Bir workflow'u
  derleme başına bir hata düzelterek onarmak kötü bir gün geçirme biçimi.
- **Cycle geçerli kabul ediliyor** (retry/clarification loop meşru), ama `WorkflowExecutor`'da
  koşum başına **step budget** var (varsayılan 1000). Sonsuza dönen bir motor, sebep bildirerek
  duran bir motordan daha kötü.
- **Terminal step'ler açık düğüm**, "adım kalmadı" değil. `COMPLETED` ile `CANCELLED` farklı
  sonuçlar; grafik hangisinin nerede olduğunu göstermeli.
- **Expression dili kasten fakir:** `and`/`or`, aritmetik, fonksiyon çağrısı yok. Bunlara ihtiyaç
  duyan bir koşul zaten iş kuralıdır ve bir capability'ye ait (§23.1). Sıralama yalnızca sayılarda
  tanımlı; string'i sayıyla karşılaştırmak tahmin etmek yerine hata veriyor.
- **`WorkflowDefinitionReader` step config'ini yorumlamıyor.** Tanımadığı bir step tipi sorunsuz
  parse oluyor, compile aşamasında tip hakkında bir mesajla düşüyor — JSON hatası gibi değil.
- **Sıralama garantisi:** `WorkflowExecutor` önce step'i çalıştırıp sonra persist ediyor. Provider
  I/O'nun açık transaction içinde olmaması bu sıralamayla sağlanıyor; `StateStore.advance` de
  step history + yeni state'i birlikte yazıyor.

**Bilinçli olarak yapılmayan:** `WorkflowRuntime` implementasyonu. `resume` gerçek olmadan
(approval executor Aşama 3'te) yarım bir implementasyon bırakmak yerine motor `WorkflowExecutor`
olarak duruyor; `DefaultWorkflowRuntime` Aşama 3'te gelecek.

### Aşama 3 — Persistence + resume (2026-08-19) ✅

**Dilimin tezi kanıtlandı:** approval bekleyen bir execution, onu başlatan process'ten sağ çıkıyor.
63 test yeşil (57 core + 6 Postgres/Testcontainers), 2 ardışık koşumda stabil.

```
core/execution/       ResumableStepExecutor, DefaultWorkflowRuntime, WorkflowExecutor.resume(...)
core/execution/step/  ApprovalStepExecutor
core/state/memory/    InMemoryApprovalStore
pipemesh-postgres/    SchemaMigrator, PostgresStateStore, PostgresApprovalStore, JsonColumn,
                      V001__execution_state.sql
```

**Tasarım kararları:**

- **Postgres ayrı modül.** `core/` JDBC'ye bağımlı olmamalı; `StateStore` core'da tanımlı,
  implementasyonu `pipemesh-postgres`'te. Embedder JDBC'yi istemiyorsa almıyor.
- **`ResumableStepExecutor` ayrı arayüz.** Bir sinyalin ne anlama geldiğine — approval hangi dala
  gider — suspend'i yazan executor karar verir. Motor yalnızca "bir şey geldi" bilgisine sahip.
  `WorkflowExecutor.resume` step'in resumable olup olmadığına bakar, config'i yorumlamaz.
- **Approval id türetiliyor, üretilmiyor:** `executionId + ":" + stepId`. Aynı step'e tekrar
  girmek aynı id'yi veriyor; `ON CONFLICT DO NOTHING` ile ikinci satır oluşmuyor.
- **Idempotency iki katmanlı ve ikisi de generic:** (1) `resume` yalnızca `WAITING` durumundaki
  execution'ı ilerletir — ikinci çağrı olduğu yeri döner, hata değil; (2) `UPDATE ... WHERE
  version = ?` — yarışan iki resume'dan biri kazanır, diğeri stale olarak reddedilir. Hiçbiri
  approval'a özgü bilgi içermiyor.
- **`advance` tek transaction:** execution UPDATE + step history INSERT birlikte commit ediliyor;
  UPDATE 1 satır etkilemezse rollback + `StaleExecutionException`.
- **`ExecutionRecord`'a `createdAt`/`updatedAt` eklendi.** `ExecutionSnapshot` bu alanları
  uyduruyordu (`clock.millis()`); gözlenebilirlik iddiası olan bir sistemde kabul edilemez.
  Zamanı store yazıyor — satırın gerçekten indiği yer.
- **Migration için Flyway yok**, ~90 satırlık `SchemaMigrator`. Migration aracı bir uygulamanın
  seçimi olabilir; bir kütüphanenin dayatması olmamalı — runtime'ın taşıdığı her bağımlılığı
  embedder devralır.

**Restart testinin dürüst sınırı:** her "process" tamamen yeni bir nesne grafiği (yeni DataSource,
yeni store'lar, yeni registry, yeni runtime) — restart'ı geçen tek şey veritabanı. Bu, in-memory
state'in taşınmadığını gerçekten kanıtlıyor. Kanıtlamadığı şey: JVM'in uçuş halindeki bir
transaction'ın ortasında ölmesi. Onun için ayrı JVM fork'u gerekir; optimistic locking + tek
transaction bunu tasarımsal olarak karşılıyor ama test etmiyor.

### Aşama 4a — LLM ve capability step'leri (2026-08-19) ✅

Dilimin tam zinciri tek testte koşuyor: **LLM → condition → capability → approval → resume**.
78 test yeşil (72 core + 6 Postgres). Model ve capability bu aşamada dublör; ikisi de aynı
arayüzün arkasında, gerçek provider takıldığında workflow değişmiyor.

```
core/prompt/          PromptId, PromptTemplate, PromptRegistry, InMemoryPromptRegistry
core/model/           InMemoryModelRegistry
core/capability/      InMemoryCapabilityRegistry
core/execution/       StepAttributes
core/execution/step/  LlmStepExecutor, CapabilityStepExecutor
```

**Tasarım kararları:**

- **Step telemetrisi generic.** `StepResult.Continue` ve `Failed` artık `attributes` taşıyor;
  motor bunları yorumlamadan step history'ye yazıyor. Dört isim (`llm.model`,
  `llm.prompt_version`, `llm.input_tokens`, `llm.output_tokens`) tipli sütunlara kaldırılıyor —
  bu bir *adlandırma konvansiyonu* (OTel semantic conventions ruhunda), motorun LLM'i anlaması
  değil. Motorun hiç duymadığı bir step tipi de istediğini raporlayabilir, kaybetmez.
  `workflow_step_history.attributes` (JSONB) sütunu eklendi.
- **Başarısızlık da maliyet taşır.** `Failed` de attributes alıyor: token harcayıp sonra düşen
  bir model çağrısı o token'ları yine de harcadı.
- **Prompt versiyonu id'nin parçası** (`venue_booking.extraction.v1`), arama parametresi değil.
  Workflow yazıldığı metni sabitliyor; yeni versiyon yeni artefakt, çalışan workflow'un altındaki
  bir düzenleme değil (§11, §24).
- **Prompt şablonu yalnızca ikame yapıyor** — koşul yok, döngü yok, ifade yok. Prompt içindeki
  bir template motoru, kimsenin seçmediği ikinci bir programlama dilidir ve orada büyüyen mantık
  workflow'un her testinin dışında kalır. Bir test bunu kilitliyor: ikame edilen metin tekrar
  şablon olarak işlenmiyor (`doesNotTreatSubstitutedTextAsATemplate`).
- **`CapabilityStepExecutor` provider'ı `execution.type` ile seçiyor.** Beşinci bir taşıma türü
  eklemek ne bu sınıfı ne de bir workflow'u değiştiriyor. Bir test workflow JSON'ında "mcp"
  kelimesinin geçmediğini doğruluyor.
- **İkisi de provider I/O ve persist'ten önce çalışıyor** — transaction sınırı korunuyor.

**Yapılmayan:** permission enforcement. `CapabilityDescriptor.permissions` okunuyor ama kontrol
edilmiyor; kimin çalıştırdığı bilgisi (principal) henüz yok. Contract #8'e ait (§23).

### Aşama 4b — Model provider (2026-08-19) ✅

89 test yeşil (73 core + 6 Postgres + 10 provider).

**Karar #5 kapatıldı — vendor değil, protokol seçildi.** `pipemesh-openai-compatible` modülü
OpenAI chat-completions protokolünü konuşuyor; tek implementasyon şunların hepsine bağlanıyor:
OpenAI, Ollama, vLLM, LiteLLM proxy, OpenRouter, Groq, Together. DESIGN.md §12 zaten
`"protocol": "openai-compatible"` diye birinci sınıf kavram olarak listelemiş.

- **Vendor SDK'sı kullanılmadı.** Bu runtime'ın modelden istediği şey bir istek ve iki token
  sayısı. SDK auth/retry/streaming/tipli modeller ve kalabalık bir bağımlılık ağacı getirirdi —
  ve PipeMesh'i gömen herkes onu devralırdı. JDK'nın `HttpClient`'ı + Jackson yeterli; modül
  **sıfır yeni bağımlılık** ekliyor.
- **API anahtarı opsiyonel.** Yerel Ollama/vLLM anahtar istemiyor; `apiKey` null ise
  `Authorization` başlığı hiç gönderilmiyor. LiteLLM proxy'si önüne konursa anahtar yönetimi de
  tek yerde toplanıyor.
- **Structured output isteniyor, dayatılmıyor.** `response_format: json_schema` gönderiliyor;
  cevap JSON değilse metin olarak dönüyor — cevabı kaybetmek, ham metni geri vermekten kötü.
  Şema doğrulaması çağıranın işi (§21).
- **Testler gerçek HTTP server'a karşı** (JDK'nın gömülü `HttpServer`'ı): ağ yok, anahtar yok,
  container yok. Mock'lanmış bir client yalnızca kodun kendini yazıldığı gibi çağırdığını
  doğrulardı; burada tel üzerinden giden istek ve dönen cevap sınanıyor.

**Ayrıca kapatılan bir açık:** bir step executor exception fırlatırsa tüm koşum çöküyordu —
execution'ın ulaştığı durum hiç yazılmıyor, sebebi hiçbir yere kaydedilmiyordu. `WorkflowExecutor`
artık bunu `StepResult.Failed("step.threw", ...)`'e çeviriyor. Step'ler model, tool ve başkalarının
servislerine uzanıyor; onlar exception fırlatır. Fırlayan bir step başarısız bir step'tir,
başarısız bir motor değil.

### Aşama 4c — MCP client (2026-08-19) ✅

**98 test yeşil** (73 core + 6 Postgres + 10 provider + 9 MCP). Aşama 4 tamamlandı.

```
pipemesh-mcp/  McpServerConnection, McpCapabilityProvider
               (test) TestMcpServer, McpCapabilityProviderTest, WorkflowOverMcpTest
```

- **SDK:** `io.modelcontextprotocol.sdk:mcp-core` 0.16.0 + `mcp-json-jackson2`, stdio transport.
  Buradaki bağımlılık ağacı (reactor-core dahil) kabul edilebilir çünkü **modülde izole**:
  MCP istemeyen embedder almıyor. Aynı gerekçeyle model provider'ında SDK'yı reddetmiştik —
  orada yüzey çok darken burada protokolün kendisi karmaşık ve SDK gerçek iş yapıyor.
- **Bağlantılar uzun ömürlü ve paylaşımlı.** stdio üzerinden bir MCP server bir child process;
  capability çağrısı başına bir tane başlatmak her tool çağrısına process açılış maliyeti
  yüklerdi.
- **Skaler girdi sarmalanıyor.** MCP tool'ları isimli argüman alır, workflow ise `$.request.location`
  ile düz bir string verebilir; `execution.argument` adı altında sarmalanıyor (varsayılan `input`).
- **Tool çıktısı JSON ise yapılandırılmış, değilse metin.** Tool'un hangisini seçtiği workflow'u
  ilgilendirmemeli.
- **Transport hatası retryable, tool hatası değil.** Bir tool çağrısı process sınırı geçiyor;
  ağ/proses hatasını yeniden denenebilir saymak dürüst varsayılan, tool'un "hayır" demesi ise
  yeniden denemeyle düzelmez.

**Test yaklaşımı — kendi yazdığımız stdio server.** `TestMcpServer` ayrı bir JVM process'i olarak
başlatılıyor (testin kendi classpath'i ile), gerçek JSON-RPC handshake ve tool çağrısı gerçek
pipe'lar üzerinden oluyor. npm registry, ağ ya da kurulu Node gerekmiyor; CI'da deterministik.

**Kabul kriteri karşılandı:** `WorkflowOverMcpTest` bir workflow'u gerçek MCP tool'una kadar
koşturuyor ve workflow JSON'ında "mcp" kelimesinin geçmediğini doğruluyor.

### Aşama 5 — Observability (2026-08-20) ✅

**114 test yeşil** (87 core + 8 Postgres + 10 provider + 9 MCP).

```
core/observability/  ExecutionObserver, ExecutionEvent, StepEvent, CompositeExecutionObserver,
                     LoggingExecutionObserver, TraceContext, TelemetryAttributes
core/execution/      OrganizationId, ExecutionRequest
```

İki gereksinim tasarımı şekillendirdi: **organizasyon bazlı** ve **birden fazla observability
aracı** (New Relic, Datadog vb.).

- **Vendor başına adaptör yazılmadı.** Datadog, New Relic, Grafana, Honeycomb hepsi OTLP yutuyor;
  doğru cevap tek bir OTLP exporter'ı, vendor modülü ancak native API isteyen için gerekli.
  `CompositeExecutionObserver` aynı anda birkaç backend'e dağıtıyor — backend değiştiren bir
  organizasyon bir hafta ikisine birden gönderebilmeli.
- **`ExecutionObserver`'ın her metodu default.** Sonradan gelecek bir olay (retry, branch join,
  bütçe uyarısı) bugün yazılmış bir implementasyonu bozmamalı. Genişlemeye açıklık burada.
- **Observer bir execution'ı düşüremez.** Fırlattığı her şey motora ulaşmadan yutuluyor; hangi
  observer'ın patladığı `onFailure` ile bildiriliyor — sessizce yutmak bozuk bir exporter'ın bir
  ay fark edilmemesinin yoludur. İki test: bozuk exporter workflow'u düşürmüyor, ve bozuk olan
  sağlamı susturmuyor.
- **Organizasyon ilk yazımdan itibaren taşınıyor** (`workflow_execution.organization_id` +
  `(organization_id, workflow_id, created_at)` index'i, WAITING index'i de organizasyonla
  başlıyor). Sonradan eklemek her satırı migrate etmek ve her dashboard'u yeniden etiketlemek
  demekti. Tek kiracılı kurulum hiç düşünmek zorunda değil — `OrganizationId.DEFAULT`.
- **`ExecutionRequest` nesnesi.** Parametre listesini genişletmek yerine istek nesnesi: idempotency
  key, deadline, principal, priority — hepsi olası sonraki alanlar ve her biri aksi halde tüm
  çağıranları ve proto binding'ini kırardı.
- **Trace bir bekleyişi aşıyor.** Üç gün approval bekleyip başka bir process'te biten bir
  execution doğal olarak iki alakasız trace üretirdi — tam da birinin ne olduğunu anlamaya
  çalıştığı anda. `traceparent` state ile persist ediliyor, resume'da geri okunuyor. Çağıran zaten
  bir trace içindeyse `ExecutionRequest.within(traceparent)` ile workflow onun altına asılıyor.
  Postgres testi bunu restart'ın iki yakasında doğruluyor.

**Etiketleme izolasyon değil.** Bir organizasyonun diğerinin execution'larını okuyamaması ve
tüketimin ölçülmesi ayrı bir iş — contract #17.

### Aşama 5b — OTel exporter (2026-08-20) ✅

**123 test yeşil** (87 core + 8 Postgres + 10 provider + 9 MCP + 9 OTel). Contract #18 kapandı.

`pipemesh-opentelemetry` bizim olaylarımızı gerçek OTel span ve metriklerine çeviriyor. Artık
"observability tool'larını destekliyoruz" cümlesi doğru: Datadog, New Relic, Grafana, Honeycomb
kod değil **config** meselesi (OTLP endpoint'i).

- **Sadece `opentelemetry-api` bağımlılığı.** Hangi exporter'ın koşacağı, nereye işaret edeceği ve
  nasıl örnekleneceği uygulamanın kararı; SDK'yı çeken bir kütüphane bu kararı ona bağımlı olan
  herkes adına vermiş olur. SDK yalnızca test kapsamında.
- **Execution bir trace'tir, span değil.** Üç gün bekleyen bir workflow onu başlatan process'ten
  uzun yaşıyor; açık tutulacak bir span yok. Her step, execution'ın saklanan trace context'ine
  parent'lanmış bir span oluyor. Bir test bunu doğruluyor: bekleyip resume olan bir workflow'un
  tüm span'leri tek trace'te.
- **Span'ler açık zaman damgasıyla kaydediliyor.** Olay bittikten sonra bildiriliyor ama span
  gerçekten koştuğu aralığı gösteriyor.
- **`approval.wait_time` saklanan kayıttan ölçülüyor** (`atEpochMillis - lastWrittenAtEpochMillis`),
  bir timer'dan değil — üç gün süren bir bekleyiş, o sürenin çoğunda hiçbir process izlemiyorken
  bile doğru raporlanıyor. Bunun için `ExecutionEvent`'e `startedAt`/`lastWrittenAt` eklendi.
- **§22 metriklerinin hepsi üretiliyor:** `workflow.executions`, `workflow.duration`,
  `step.duration`, `approval.wait_time`, `llm.input_tokens`, `llm.output_tokens`. Hepsi
  organizasyon etiketli.

Testler gerçek OTel SDK'sı + in-memory exporter'larla koşuyor: doğrulanan şey bir backend'in
gerçekten alacağı span ve metrikler.

### Aşama 6 — Config repo ve proto (2026-08-20) ✅

**132 test yeşil** (96 core + 8 Postgres + 10 provider + 9 MCP + 9 OTel). **Dilim tamamlandı.**

```
core/config/            ConfigRepository, ModelDefinition, ModelProviderFactory, ConfigException
openai-compatible/      OpenAiCompatibleProviderFactory
examples/approval-flow/ workflows/ models/ capabilities/ prompts/ schemas/ + README
proto/pipemesh.proto    API contract'ı (implementasyon yok)
```

- **`examples/` ancak onu yükleyen bir şey varsa bir şey kanıtlar.** Bu yüzden asıl iş
  `ConfigRepository`: §31 yapısını okuyup registry'leri kuruyor. Test dosyaları saymıyor,
  **her iki workflow'u da derliyor** — dilimin "ikinci workflow sadece config eklenerek çalışır"
  kriteri artık dosya sisteminden doğrulanıyor.
- **`ModelProviderFactory` SPI'ı.** Yükleyici `models.json`'ı okumayı bilir ama bir
  `openai-compatible` endpoint'inin neye ihtiyacı olduğunu bilmez. Yeni bir protokol yeni bir
  factory olarak geliyor, core hiçbir şey öğrenmeden.
- **Secret dosyada değil, adı dosyada.** `apiKeyEnv: "OPENAI_API_KEY"` — config dosyaları
  commit'lenir, anahtarlar commit'lenmemeli.
- **İkinci workflow (`refund_request`) tek satır kod olmadan eklendi.** Farklı adım sırası,
  farklı prompt, aynı runtime.
- **`proto/pipemesh.proto` yazıldı** (§26.1, §26.4): `StartExecution`, `ProcessMessage`,
  `SubmitApproval`, `GetExecution`, `WatchExecution` (server stream) ve worker'ın açtığı
  `CapabilityWorker.Connect` bidi stream'i. Organizasyon ve traceparent alanları var;
  `StartExecutionRequest`'te idempotency key/deadline/principal/priority için `reserved` aralık
  bırakıldı. Implementasyon #12'nin işi.

## Dilim tamamlandı — kabul kriterleri

| Kriter | Durum |
|---|---|
| JSON workflow runtime değişmeden yükleniyor ve çalışıyor | ✅ `ConfigRepositoryTest` |
| LLM step structured output istiyor, şema ihlali `Failed` dönüyor | ✅ `OpenAiCompatibleProviderTest` |
| Condition LLM çağırmadan deterministik değerlendiriyor | ✅ `ConditionExpressionTest` |
| Capability step MCP tool'unu çağırıyor, workflow'da "mcp" geçmiyor | ✅ `WorkflowOverMcpTest` |
| Approval'da `WAITING` persist ediliyor, thread bloklanmıyor | ✅ `ApprovalResumeTest` |
| **Restart testi** — process ölüyor, resume kaldığı yerden `COMPLETED` | ✅ `DurableApprovalRestartTest` |
| `onRejected` yolu `CANCELLED`, side-effect yok | ✅ `ApprovalResumeTest` |
| Aynı approval iki kez → ikincisi yok sayılıyor | ✅ `ApprovalResumeTest` |
| Trace tüm step'leri, model, prompt versiyonu ve token'ları içeriyor | ✅ `FullSliceTest` |
| Restart sonrası aynı trace | ✅ `DurableApprovalRestartTest`, `OpenTelemetryExecutionObserverTest` |
| Yeni step tipi `WorkflowExecutor`'a dokunmadan ekleniyor | ✅ `ThrowingStepTest` (özel executor) |
| Compiler geçersiz grafiği reddediyor | ✅ `WorkflowCompilerTest` |
| İkinci workflow sadece config ile çalışıyor | ✅ `ConfigRepositoryTest` |
| Proto yazıldı, girdi/çıktılar kayıpsız map edilebiliyor | ✅ `proto/pipemesh.proto` |
| Şema gövdesinde kod taşıyan step'i reddediyor | ⚠️ **Yapılmadı** — `workflow.schema.json` henüz yazılmadı |
| Workflow step'i transport/owner/version taşımıyor | ✅ Tasarımla; `ConfigRepositoryTest` doğruluyor |

**Kalan tek eksik:** JSON Schema dosyaları (`schemas/workflow.schema.json` vb.) yazılmadı.
`WorkflowDefinitionReader` + `WorkflowCompiler` doğrulamayı kodda yapıyor ve inline kod taşıyan bir
step zaten hiçbir executor tarafından sahiplenilmediği için compile'da düşüyor — ama şema
seviyesinde `additionalProperties: false` garantisi yok. Ayrı bir iş olarak kayda geçirildi.

### Aşama 4 planı

`LlmStepExecutor` + tek model provider, `CapabilityStepExecutor` + MCP client. Bu aşamanın kritik
kuralı: ikisi de transaction sınırının **dışında** çağrılmalı (`WorkflowExecutor` step'i
persist'ten önce çalıştırıyor, bu sıralama korunmalı). **LLM provider kararı (#5) burada
gerekecek.**
