# Capability Permissions

**Status:** Draft
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §23 (security model), §10 (capability kaydı), §9.8

## Goal

`CapabilityDescriptor.permissions` okunuyor, config'den yükleniyor, örneklerde yazılı —
**ve hiçbir yerde kontrol edilmiyor.** DESIGN §23 ve CLAUDE.md "permission zorlaması registry'de,
DSL'de değil" diyor; kodda karşılığı yok.

§23'ün cümlesi: *"A workflow should not automatically gain access to every registered capability."*
Bugün tam olarak öyle oluyor.

## Tasarımın merkezindeki güvenlik sorusu

**İzinleri kim beyan eder?**

Yanlış cevap: çağıran. Bir istemcinin kendi izinlerini kendisinin bildirmesi, kilidi kapının
yanına asmaktır. Uzaktan gelen bir istek `{"permissions": ["billing.refund"]}` diyebiliyorsa
permission diye bir şey yok demektir.

Doğru cevap ikiye ayrılıyor:

```text
in-process çağıran   → nesnelere zaten sahip; Principal.SYSTEM, her şeyi taşır
uzak çağıran         → kimliği sunucu tarafında çözülür; kendi izinlerini beyan edemez
```

gRPC servisi tel üzerinden gelen bir izin listesini **asla** kabul etmiyor. Kimlik çözümlemesi
uygulamanın işi (token doğrulama, header, mTLS) ve runtime yalnızca çözülmüş sonucu alıyor.
Çözümleyici yoksa çağıran anonim: hiçbir izni yok.

## Kurallar

- İzin **beyan etmeyen** capability herkese açık. Kısıt yokluğu kısıt değildir.
- İzin beyan eden capability, principal'ın **hepsine** sahip olmasını ister.
- Reddedilen çağrı `capability.forbidden`, **`retryable: false`** — yeniden denemek izin
  kazandırmaz.
- Principal **execution ile birlikte persist edilir.** Bir approval'ı çözen kişi, execution'ı
  başlatan kişi olmak zorunda değil; resume sonrası capability kontrolü **execution'ın kendi
  principal'ıyla** yapılmalı, resume edenin izinleriyle değil.

## Acceptance Criteria

- [ ] İzin beyan etmeyen capability her principal için çağrılabiliyor (bugünkü davranış korunuyor)
- [ ] İzin beyan eden capability, izni olmayan principal için `capability.forbidden` ile düşüyor
- [ ] Reddedilen çağrı yeniden denenmiyor
- [ ] Principal tüm izinlere sahipse çağrı geçiyor
- [ ] `Principal.SYSTEM` her izne sahip — in-process çağıran nesnelere zaten sahip
- [ ] Uzak çağıran varsayılan olarak anonim: hiçbir izin taşımıyor
- [ ] **gRPC isteğindeki bir izin listesi yok sayılıyor** (böyle bir alan proto'da yok)
- [ ] Principal execution ile persist ediliyor ve restart sonrası korunuyor
- [ ] Resume sonrası kontrol execution'ın principal'ıyla yapılıyor
- [ ] Reddedilme telemetride görünüyor
- [ ] Mevcut 245 test değişmeden geçiyor

## Split Decision

**Decision:** single-prompt
**Tarih:** 2026-08-21

**Reasoning:** Bir değer tipi, bir kontrol, bir sütun ve iki bağlama noktası. Paralel parça yok.

### Build order

1. `Principal` + `ExecutionRequest`/`ProcessRequest` üzerinde taşınması
2. Persist: `workflow_execution.principal` (JSONB) + `ExecutionContext` → `CapabilityCall`
3. Kontrol: `CapabilityStepExecutor` çağrıdan önce
4. gRPC: `PrincipalResolver` SPI, varsayılan anonim

### Tasarım soruları ve cevapları

1. **Ayrı bir `PermissionPolicy` SPI'ı gerekli mi?** Hayır. Rol modeli isteyen, `Principal`'ı
   kurarken rolleri izinlere çevirir. Şimdiden bir SPI eklemek, kimsenin istemediği bir
   genişletme noktasının bakımını üstlenmek olur.
2. **Workflow seviyesinde capability allowlist'i?** Bu contract'ta yok. §23'ün diyagramı
   Workflow → Allowed Capabilities diyor ama statik bir workflow'u yazan ile capability'leri
   kaydeden aynı ekip; asıl koruma çağıran tarafında. Not düşüldü.
3. **Varsayılan izinsiz mi, izinli mi?** İzin beyan etmeyen capability açık; beyan eden kapalı.
   Böylece bugünkü hiçbir şey kırılmıyor ve koruma isteyen onu beyan ederek alıyor.

### Risk points

- **Sessiz güvenlik yanılsaması.** Bir uygulama `PrincipalResolver` bağlamazsa uzak çağıranlar
  anonim kalır ve izinli capability'ler hiç çalışmaz. Bu güvenli taraf ama fark edilmesi gerekir —
  hata mesajı hangi izinlerin eksik olduğunu söylemeli.
- **Persist edilmeyen principal.** Restart sonrası principal kaybolursa kontrol sessizce
  gevşer ya da her şey reddedilir. Postgres testi bunu doğrulamalı.

## Implementation Notes

### Tamamlandı (2026-08-21) ✅

**258 Java testi yeşil** (186 core + 14 Postgres + 16 provider + 9 MCP + 10 OTel + 23 gRPC).
Mevcut testlerin hepsi değişmeden geçti.

```
core/capability/  Principal, CapabilityCall'a principal, CapabilityStepExecutor'da kontrol
core/execution/   ExecutionRequest.onBehalfOf, ProcessRequest, ExecutionContext
core/state/       ExecutionRecord.principal
postgres/         workflow_execution.principal (JSONB), Principals
grpc/             PrincipalResolver, CallMetadata interceptor'ı
```

**Merkezdeki güvenlik kararı:** çağıran kendi izinlerini beyan edemiyor. Proto'da böyle bir alan
yok ve bir test bunu doğruluyor — `StartExecutionRequest`'in alanları arasında "permission" ya da
"principal" geçen hiçbir şey olmamalı. Kimlik sunucu tarafında, `PrincipalResolver` ile çözülüyor;
gRPC metadata'sı `CallMetadata` interceptor'ı ile servise taşınıyor.

**Kararlar:**

- **`Principal.SYSTEM` her izne sahip.** Aynı process'teki kod runtime'ı, registry'leri ve
  workflow'ları zaten kurmuş; ondan saklanacak bir şey yok ve varmış gibi yapmak, hiçbir sınırın
  zorlamadığı izinler yazmak olurdu.
- **`unrestricted` bir alan, sihirli bir izin dizesi değil.** "Her şeye sahip" ile "şu listeye
  sahip" farklı ifadeler; bir set içinde saklanan joker karakter, yanlışlıkla bir config
  dosyasına kopyalanan türden bir şeydir.
- **Uzak çağıranın varsayılanı anonim.** Kapalı tarafa düşmek, kimsenin seçmediği bir varsayılan
  için tek güvenli yön. İzin istemeyen capability yine çalışıyor; isteyen, uygulama gerçek bir
  çözümleyici bağlayana kadar reddediliyor.
- **Reddedilme `retryable: false`.** Aynı çağıranla yeniden denemek izin kazandırmaz; retry
  politikasının buna deneme harcaması anlamsız.
- **Principal persist ediliyor.** Bir approval'ı çözen kişi execution'ı başlatan kişi olmak
  zorunda değil; resume sonrası kontrol **execution'ın kendi** principal'ıyla yapılıyor. Postgres
  testi bunu restart'ın iki yakasında doğruluyor.
- **İzin beyan etmeyen capability herkese açık.** Kısıt yokluğu kısıt değildir — ve bu sayede
  bugünkü hiçbir şey kırılmadı.

**Yapılmayan:** workflow seviyesinde capability allowlist'i (§23'ün diyagramındaki ikinci katman),
izin hiyerarşisi/joker eşleşme, MCP tool ve resource seviyesinde alt izinler.
