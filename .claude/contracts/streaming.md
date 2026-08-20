# Streaming

**Status:** Draft
**Created:** 2026-08-20
**DESIGN.md kapsamı:** §30 (streaming), §13 (messaging provider), §26.4 (`stream()` fiili)

## Goal

Bir modelin cevabı üretilirken token'ların çağırana ulaşması. Bugün `MessagingProvider` yalnızca
`complete()` biliyor; kullanıcı 20 saniye boş ekrana bakıyor, cevap hazır olduğunda bir anda
düşüyor.

§30: *"The runtime should not force applications into request/response semantics."*

## Affected Modules

- [ ] `core/model` — `MessagingProvider.stream(...)`, `CompletionChunk`
- [ ] `core/observability` — `tokenProduced` olayı
- [ ] `core/execution/step` — `LlmStepExecutor` akış modunda çalışabiliyor
- [ ] `pipemesh-openai-compatible` — SSE (`text/event-stream`) okuma
- [ ] `pipemesh-opentelemetry` — akış token'ları span'i şişirmemeli

## Contract

Workflow adımı akış istediğini söyler:

```json
{ "id": "answer", "type": "llm", "model": "fast", "prompt": "chat.v1", "stream": true, "next": "done" }
```

Provider sınırı:

```java
default CompletionResponse stream(CompletionRequest request, Consumer<CompletionChunk> onChunk) {
    return complete(request);   // akış bilmeyen provider tek parça verir
}
```

**Callback, `Stream<CompletionChunk>` değil.** DESIGN §13'ün taslağı `Stream` diyordu; tembel
tüketilen bir `Stream` açık bir HTTP bağlantısını çağıranın ne zaman kapatacağına bağlar ve
step'in senkron sözleşmesini bozar. Callback hem bağlantı ömrünü provider'da tutuyor hem de
motorun adım modelini değiştirmiyor. §13 güncellenecek.

Token'lar çağırana **`ExecutionObserver` üzerinden** ulaşıyor:

```java
default void tokenProduced(TokenEvent event) { }
```

Gerekçe: `pipemesh.proto`'daki `WatchExecution` zaten execution olaylarını ve `TokenChunk`'ı tek
bir stream'de birleştiriyor (§26.4). İki ayrı fan-out mekanizması kurmak yerine tek kanal
kullanmak, ileride gRPC adaptörünün **sadece bir observer** olması demek. Her observer metodunun
default olması da tam bunun için tasarlanmıştı — bugün yazılmış bir exporter bu olayı görmeden
çalışmaya devam ediyor.

## Acceptance Criteria

- [ ] Provider akış modunda token'ları geldikçe callback'e veriyor, sonunda birleşik cevabı dönüyor
- [ ] Akış sırasında token sayıları raporlanmaya devam ediyor (maliyet metrikleri sessizce kaybolmuyor)
- [ ] Akış desteklemeyen provider tek parça vererek çalışmaya devam ediyor
- [ ] `"stream": true` diyen adım `tokenProduced` olayları yayınlıyor; demeyen adım hiç yayınlamıyor
- [ ] Birleşik cevap yine şemaya karşı doğrulanıyor (#3 ile çakışmıyor)
- [ ] Mevcut observer'lar değişmeden çalışıyor
- [ ] SSE ayrıştırıcısı `data: [DONE]`, boş satır ve parçalı JSON'u doğru işliyor

## Split Decision

**Decision:** single-prompt
**Tarih:** 2026-08-20

**Reasoning:** İki modül, dar bir yüzey. Paralelleştirilecek bağımsız parça yok.

### Build order

1. `CompletionChunk` + `MessagingProvider.stream(...)` default'u + `TokenEvent`/`tokenProduced`
2. `LlmStepExecutor` akış modu
3. OpenAI-uyumlu SSE okuma + `stream_options.include_usage`
4. DESIGN §13 güncellemesi

### Risk

**Token maliyeti kaybı.** OpenAI-uyumlu akışta `usage` varsayılan olarak gelmez;
`stream_options: {"include_usage": true}` istenmezse token sayaçları sessizce sıfırlanır ve
`llm.input_tokens` metriği yanlış olur. Sessiz yanlış, gürültülü hatadan kötüdür — bir test bunu
kilitleyecek.

## Implementation Notes

### Tamamlandı (2026-08-20) ✅

**185 test yeşil** (142 core + 8 Postgres + 16 provider + 9 MCP + 10 OTel).

```
core/model/           CompletionChunk, MessagingProvider.stream(...) default'u
core/observability/   TokenEvent, ExecutionObserver.tokenProduced
core/execution/step/  LlmStepExecutor akış modu
openai-compatible/    SSE okuma + stream_options.include_usage
DESIGN.md             §13 ve §30 güncellendi
```

**Tasarım kararları:**

- **Callback, `Stream` değil.** §13'ün taslağı `Stream<CompletionChunk>` diyordu; tembel tüketilen
  bir stream açık bir HTTP bağlantısını çağıranın onu ne zaman boşaltacağına bağlar ve step'in
  senkron sözleşmesini başkasının sorunu yapar. Callback bağlantı ömrünü provider'da tutuyor.
  DESIGN.md gerekçesiyle birlikte güncellendi.
- **Default akış = tek parça.** Akış bilmeyen bir provider `complete()`'e düşüp cevabı tek chunk
  olarak veriyor — akış isteyen her yerde çalışmaya devam ediyor, sadece parçalar büyük.
- **Token'lar observer kanalından.** `pipemesh.proto`'daki `WatchExecution` zaten execution
  olaylarını ve `TokenChunk`'ı tek stream'de birleştiriyor; ikinci bir fan-out mekanizması kurmak
  yerine tek kanal, ileride gRPC adaptörünün *sadece bir observer* olması demek. Her observer
  metodunun default olması tam bunun içindi — bir test bugün yazılmış bir observer'ın token'ları
  görmeden çalışmaya devam ettiğini doğruluyor.
- **`LlmStepExecutor` observer alıyor.** Token'lar step'in *içinde*, ortada raporlanacak bir
  sonuç yokken üretiliyor; motorun observer referansı bu iş için geç kalıyor.
- **Akış cevabın nasıl geldiğini değiştiriyor, ne olduğunu değil.** Step yine tam cevapla
  bitiyor, yine şemaya karşı doğrulanıyor (#3), yine tek değişken yazıyor.

**Riskin karşılığı:** `stream_options: {"include_usage": true}` istenmezse OpenAI-uyumlu akışta
`usage` hiç gelmez ve token sayaçları sessizce sıfır okur — `llm.input_tokens` metriği yanlış
olurdu. Bir test bunu kilitliyor: *"without this the token counts silently read zero"*.

**Yapılmayan:** akış sırasında şema doğrulaması. Doğrulama birleşik cevap üzerinde yapılıyor,
yani uyumsuz bir cevabın token'ları çağırana çoktan gitmiş oluyor. Kısmi JSON'u akış sırasında
doğrulamak ayrı bir iş; çağıran token'ları önizleme olarak görüp nihai değeri değişkenden almalı.
