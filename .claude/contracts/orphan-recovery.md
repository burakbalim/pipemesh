# Orphan Recovery

**Status:** Draft
**Created:** 2026-08-20
**DESIGN.md kapsamı:** §15 (execution state), §28 (event-driven execution — kısmi), §38 (reliability)
**Kaynak:** #10 distributed-workers'tan kesilen odaklı parça

## Goal

`RUNNING` durumunda takılı kalmış execution'ları bulup yeniden sürmek.

Bugünkü boşluk: bir process bir step'in ortasında ölürse — deploy, OOM, retry backoff'u sırasında
kill — execution `RUNNING` olarak veritabanında kalıyor ve **onu kimse almıyor.** Kalıcı olarak
yarım. Dayanıklılık iddiasının en yumuşak karnı bu: `WAITING` bir execution resume ile devam
ediyor, ama `RUNNING` bir execution'ın sahibi öldüğünde sahipsiz kalıyor.

Bu contract bir scheduler kurmuyor. Yalnızca **çağrılabilir bir süpürücü** veriyor; ne zaman
çağrılacağı uygulamanın kararı (#10 dağıtık yürütmeyi getirdiğinde bu kararı da devralacak).

## Affected Modules

- [ ] `core/state` — `StateStore.findStale(...)`
- [ ] `core/execution` — `RecoverySweeper`, `StepExecutor.repeatable(...)`
- [ ] `core/observability` — `executionRecovered` olayı ve metriği
- [ ] `pipemesh-postgres` — sorgu + `(status, updated_at)` index'i
- [ ] `pipemesh-opentelemetry` — kurtarma sayacı

## Tasarımın kalbi: iki farklı güvenlik sorusu

Kurtarma, "bu execution'ın sahibi öldü mü?" sorusuna kesin cevap veremez. Uzun süren bir step
hâlâ koşuyor olabilir. İki ayrı mekanizma bunu güvenli kılıyor:

**1. Çift ilerletmeyi optimistic locking engelliyor.** Süpürücü yanlışlıkla canlı bir
execution'ı alsa bile, ikisinden yalnızca biri `advance` edebilir; diğeri
`StaleExecutionException` ile reddedilir. Yani yanlış bir süpürme *durumu bozmaz*, yalnızca
israf eder.

**2. Çift yan etkiyi `repeatable` engelliyor.** İsraf masum değil: yeniden koşan step gerçek bir
çağrı yapar. `idempotent: false` diyen bir capability'nin adımı **yeniden koşturulmaz**;
execution `execution.unrecoverable` ile `FAILED` olur ve insan müdahalesi bekler. Retry'daki
kuralın aynısı: *olmuş olabilecek bir şeyi tekrarlama.*

```
RUNNING + uzun süredir dokunulmamış
        ↓
step yeniden koşturulabilir mi?
        ├── evet →  execution yeniden sürülür
        └── hayır → FAILED (execution.unrecoverable)
```

`repeatable` kararı adımın config'ini yorumlayan executor'a ait — `outgoing()` ile aynı desen.
Motor hangi capability'nin idempotent olduğunu bilmez, bilmemeli.

## Eşik

Bir execution ne kadar süre dokunulmazsa öksüz sayılır? **Eşik, en uzun step timeout'undan
büyük olmalı** — aksi halde 3 dakikalık bir model çağrısı süpürülür. Varsayılan 5 dakika,
yapılandırılabilir. Yanlış süpürmenin bedeli yukarıdaki iki mekanizma sayesinde bozulma değil
israf olduğu için, eşik muhafazakâr seçilebilir.

## Acceptance Criteria

- [ ] Uzun süredir dokunulmamış `RUNNING` execution bulunup yeniden sürülüyor ve tamamlanıyor
- [ ] Eşiğin altındaki `RUNNING` execution'a dokunulmuyor
- [ ] `WAITING` execution süpürülmüyor — o bir sahipsizlik değil, bekleyiş
- [ ] Terminal durumdaki execution süpürülmüyor
- [ ] Yeniden koşturulamaz bir adımda takılan execution `execution.unrecoverable` ile `FAILED`
- [ ] Kurtarma `executionRecovered` olayı yayınlıyor ve trace'e aynı execution olarak bağlanıyor
- [ ] Aynı execution'ı iki süpürücü aynı anda alırsa biri stale olarak reddediliyor
- [ ] Süpürücü tek seferde kaç execution alacağını sınırlıyor (limit)
- [ ] Postgres sorgusu index kullanıyor
- [ ] Kurtarma bir başka process'te çalışabiliyor — süpürücü execution'ı başlatan process olmak zorunda değil

## Split Decision

**Decision:** single-prompt
**Tarih:** 2026-08-20

**Reasoning:** Küçük ve tek yönlü: bir sorgu, bir bileşen, bir SPI metodu. Paralelleştirilecek
parça yok.

### Build order

1. `StepExecutor.repeatable(...)` + `CapabilityStepExecutor` override'ı
2. `StateStore.findStale(...)` + in-memory ve Postgres implementasyonları + index
3. `RecoverySweeper` + `executionRecovered` olayı
4. Postgres testi: gerçekten öksüz kalmış bir execution kurtarılıyor

### Kapsam dışı

- **Zamanlama.** Süpürücüyü kim, ne sıklıkta çağırır — uygulamanın kararı. #10 bunu devralacak.
- **Süresi dolan approval'lar.** `workflow_approval.expires_at` duruyor ama işlenmiyor;
  süresi dolan bir onayın `onRejected`'a mı gitmesi yoksa ayrı bir `onExpired` mi gerektiği
  ayrı bir karar. Not düşüldü.
- **Heartbeat/lease.** Gerçek sahiplik takibi dağıtık yürütmenin işi; burada zaman damgası yeterli.

## Implementation Notes

### Tamamlandı (2026-08-20) ✅

**197 test yeşil** (149 core + 13 Postgres + 16 provider + 9 MCP + 10 OTel).

```
core/execution/       RecoverySweeper, StepExecutor.repeatable(...)
core/state/           StateStore.findStale(...)
core/observability/   executionRecovered olayı
pipemesh-postgres/    findStale sorgusu + (status, updated_at) kısmi index'i
pipemesh-opentelemetry/ pipemesh.workflow.recoveries sayacı
```

**Tasarım kararları:**

- **`repeatable` retry'ın `retryable`'ından ayrı bir soru.** Retry *bildirilen* bir hatanın
  ardından gelir; kurtarma *sessizliğin* ardından. İkisi de "olmuş olabilecek bir şeyi tekrarlama"
  kuralına uyuyor ama farklı bilgiyle: biri provider'ın hata sınıflandırması, diğeri capability'nin
  idempotency beyanı. `repeatable` kararı adımın config'ini yorumlayan executor'a ait — `outgoing()`
  ile aynı desen, motor hangi capability'nin idempotent olduğunu bilmiyor.
- **Yanlış süpürme bozulma değil israf.** Uzun süren canlı bir step ile ölmüş bir step ayırt
  edilemez. İki mekanizma bunu güvenli kılıyor: optimistic locking iki yazardan yalnızca birinin
  ilerlemesine izin veriyor (`StaleExecutionException` yakalanıp sessizce geçiliyor), `repeatable`
  ise çift yan etkiyi engelliyor. Postgres testi capability step'inin **bir kez** kaydedildiğini
  doğruluyor.
- **Kurtarılamayan execution `FAILED` oluyor, `RUNNING`'de bırakılmıyor.** Sonsuza kadar yarım
  bırakmak kimseye yardım etmiyor; `execution.unrecoverable` koduyla insan müdahalesi bekliyor.
  Kaydı silinmiş workflow için de aynısı (`execution.unknown_workflow`).
- **`executionRecovered` ayrı bir olay, resume değil.** Resume birinin verdiği kararın ardından
  gelir; kurtarma kimsenin bildirmediği bir hatanın. Metrikte de ayrı: bu sayacın yükselmesi
  workflow'ların başarısız olmasından farklı bir problem — process'ler step ortasında ölüyor.
- **Kurtarma orijinal trace'e bağlı kalıyor.** `traceparent` state'te durduğu için kurtarılan
  execution aynı trace'te devam ediyor; iki test (in-memory + Postgres) bunu doğruluyor.
- **Eşik en uzun step timeout'undan büyük olmalı.** Varsayılan 5 dakika. Yanlış süpürmenin bedeli
  israf olduğu için muhafazakâr seçilebilir.

**Test yaklaşımı:** Postgres testinde öksüz, bir crash'in bıraktığı gibi üretiliyor — satır
yazılıp `updated_at` geçmişe çekiliyor — ve **sıfırdan kurulan** bir runtime tarafından
kurtarılıyor. Süpüren process, execution'ı başlatan process değil.

**Kapsam dışı kaldı:** zamanlama (süpürücüyü kim çağırır — #10), süresi dolan approval'lar,
heartbeat/lease tabanlı gerçek sahiplik takibi.
