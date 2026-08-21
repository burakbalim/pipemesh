# Console and Subscriptions

**Status:** Draft
**Created:** 2026-08-21
**DESIGN.md kapsamı:** §22.2 (organizasyon izolasyonu), §23 (kimlik ve izin), §26.2 (Runtime/SDK/Provider), §39.1 (harcama muhasebesi)

## Goal

Deploy edilmiş bir PipeMesh'e insanların kendi kendine kaydolup demo planıyla workflow
çalıştırabilmesi: organizasyon kaydı, plan/kota aboneliği ve bunları yöneten bir web arayüzü.

## Bu, depodaki ilk ürün katmanı

Bugüne kadar her şey kütüphane şeklindeydi: altı Maven modülü, iki SDK, tek dış sınır gRPC.
Console bunlardan biri değil — bir **uygulama**. Bu yüzden bağımlılık yönü tek yönlü ve sert:

```text
pipemesh-console  ──depends on──▶  pipemesh-grpc, pipemesh-core
pipemesh-core     ──depends on──▶  (hiçbir zaman console'a)
```

Spring, web katmanı, oturum, parola — hepsi console'un içinde. `core/`'un framework-free olması
(§26.2) bu contract'tan sonra da doğru kalıyor; console'un Spring kullanması bunu bozmuyor,
çünkü console runtime değil.

## Console, `PrincipalResolver`'ın bugüne kadar boş kalan cevabı

`PrincipalResolver`'ın javadoc'u şunu söylüyor:

> The runtime cannot authenticate anyone — it has no idea what a valid token looks like in your
> deployment — so this is where an application plugs in the answer.

Ve DESIGN §22.2 şunu:

> A deployment that identifies nobody has no isolation, which is a property of not authenticating
> anyone rather than something the runtime can fix.

Bugün varsayılan `ANONYMOUS`. Console, "kimseyi tanımlamayan deployment"ı bitiren şey: API
anahtarı üretiyor, gRPC sınırındaki resolver onu doğruluyor, ve `Principal` nihayet gerçek bir
organizasyon taşıyor. #17'nin kurduğu izolasyon ilk kez gerçek bir kimliğe dayanıyor.

## Kota abonelik kavramı, bütçe execution kavramı

Karıştırılmaması gereken iki şey:

| | `CostBudget` (#11) | Kota (bu contract) |
|---|---|---|
| Neyi durdurur | Tek bir execution'ı | Organizasyonun tüm çalışmasını |
| Ömrü | Execution boyunca | Dönemsel, sıfırlanıyor |
| Kim yazdı | Workflow yazarı | Abonelik planı |
| Nerede bakılıyor | Motorun içinde, adım aralarında | Sınırda, execution başlamadan önce |

Kota **motora girmiyor**. Motor abonelik diye bir şey bilmiyor ve bilmemeli (§3). Kontrol
`StartExecution`'da, iş başlamadan önce. Girdi ise #11'in zaten yazdığı `Spend`: bir execution
bittiğinde harcadığı, organizasyonun dönemsel sayacına ekleniyor.

## Plan yalnızca sayı değil, yetenek de taşıyor

Kota bir plandaki sayılar; ama bir planın verdiği tek şey miktar değil. Canlı izleme
(`WatchExecution`, #20) açılıp kapanabilen bir özellik ve kararı planın:

```text
plan          → hangi izinler
API anahtarı  → o izinleri taşıyan Principal
gRPC sınırı   → izni olmayanı reddediyor
```

Mekanizma yeni değil: `Principal.holds(permission)` #8'den beri var ve #20 `WatchExecution`'ı
ona bağlıyor. Console'un yaptığı, planın izin listesini API anahtarının principal'ına
koymaktan ibaret. Motor hâlâ plan diye bir şey bilmiyor.

Kullanıcı bunu console'dan açıp kapatabiliyor — planı izin veriyorsa. Kapalıyken çağrı
`PERMISSION_DENIED` alıyor, boş bir akış değil (#20'nin kararı).

## Demo bir plan, özel durum değil

`if (isDemo)` yok. Demo, `plan` tablosunda küçük sayılar taşıyan bir satır. Aksi hâlde insanların
denediği yol, satın alacakları yoldan farklı bir kod yolu olur — ve demoda çalışan şeyin üründe
çalışacağının garantisi kalmaz.

Demo'nun küçük olması bir konfigürasyon, davranış değil.

## Halka açık bir demo, güvenlik yüzeyidir

Deploy edilmiş demo'ya herkes kaydolabiliyorsa:

- Kota **kaydolmadan önce** değil, kaydolduktan sonra da geçerli olmalı — e-posta doğrulaması
  bir kişinin sınırsız organizasyon açmasını engellemiyor, kota engelliyor
- Demo organizasyonunun ulaşabildiği capability kümesi ayrıca sınırlı olmalı; §23'ün izin
  modeli bunu zaten ifade edebiliyor, kullanmak gerekiyor
- Parola argon2 ile saklanıyor, doğrulama linki tek kullanımlık ve süreli
- API anahtarı yalnızca üretildiği anda görünüyor; saklanan şey hash'i

## API Contract

```text
POST /api/v1/organizations          organizasyon + ilk kullanıcı; demo planına düşer
POST /api/v1/sessions               e-posta + parola → oturum
POST /api/v1/verifications/{token}  e-posta doğrulama, tek kullanımlık
GET  /api/v1/organization           kim olduğum, planım, dönemsel kullanımım
POST /api/v1/api-keys               yeni anahtar; düz hâli yalnızca bu cevapta
DELETE /api/v1/api-keys/{id}        anahtarı iptal et
GET  /api/v1/usage                  dönem içi harcama: execution, token, maliyet
GET  /api/v1/plans                  plan listesi ve limitleri
```

Gövde adlandırması `snake_case` değil **camelCase** — bu depodaki JSON'ların tamamı öyle
(workflow tanımları, proto JSON eşlemesi). Monorepo'nun REST alışkanlığı burada geçerli değil.

## DB Schema Changes

Console'un kendi tabloları, `workflow_*` tablolarından ayrı:

```text
console_organization   id, name, plan_id, created_at
console_user           id, organization_id, email, password_hash, verified_at
console_verification   token_hash, user_id, expires_at, used_at
console_api_key        id, organization_id, key_hash, prefix, created_at, revoked_at
console_plan           id, name, max_executions, max_tokens, max_cost_micros, period, permissions
console_usage          organization_id, period_start, executions, tokens, cost_micros
```

`console_usage` bir sayaç, geçmiş defteri değil: geçmişi `workflow_execution` zaten tutuyor ve
iki yerde tutmak ikisinin ayrışması demek.

## Web UI

```text
/signup     organizasyon + kullanıcı oluştur
/verify     doğrulama linkinin indiği yer
/signin     oturum
/           kullanım / kota (bu dönem ne harcandı), plan
/keys       API anahtarları — üret, iptal et
/demo       çalıştırılabilir örnek workflow ve sonucu
```

`/demo` ekranı bu contract'ın "insanlar demoyu kullanabilsin" kısmı: hazır bir workflow,
çalıştır düğmesi, ve execution'ın canlı ilerleyişi. Altında hiçbir özel yol yok — kullanıcının
kendi API anahtarıyla, kendi organizasyonunda, aynı gRPC sınırından geçiyor.

## Acceptance Criteria

- [ ] Kayıt bir organizasyon + bir kullanıcı yaratıyor ve demo planına düşürüyor
- [ ] Doğrulanmamış kullanıcı oturum açamıyor
- [ ] Doğrulama linki ikinci kez kullanılamıyor
- [ ] Süresi geçmiş doğrulama linki reddediliyor
- [ ] Parola hiçbir yerde düz saklanmıyor; hash argon2
- [ ] API anahtarının düz hâli yalnızca üretim cevabında dönüyor; veritabanında hash'i var
- [ ] İptal edilmiş anahtarla gRPC çağrısı `PERMISSION_DENIED`
- [ ] Geçerli anahtar `Principal`'ı doğru organizasyonla dolduruyor
- [ ] Bir organizasyonun anahtarı başka organizasyonun execution'ını okuyamıyor (#17 ile uçtan uca)
- [ ] Kota dolmuşsa `StartExecution` reddediliyor, iş başlamadan
- [ ] Biten execution'ın `Spend`'i dönemsel sayaca ekleniyor
- [ ] Dönem değişince sayaç sıfırlanıyor, plan limitleri değişmiyor
- [ ] Demo planı `plan` tablosunda bir satır; kodda `isDemo` dalı yok
- [ ] Plan izin listesi taşıyor; API anahtarının `Principal`'ı onu taşıyor
- [ ] Canlı izleme kapalıyken `WatchExecution` `PERMISSION_DENIED` veriyor
- [ ] Kullanıcı console'dan canlı izlemeyi açıp kapatabiliyor; etkisi bir sonraki çağrıda
- [ ] Planın vermediği bir izni kullanıcı kendine açamıyor
- [ ] Demo organizasyonu ilan edilmemiş capability'lere ulaşamıyor
- [ ] `/demo` ekranı gerçek bir workflow'u uçtan uca çalıştırıp sonucu gösteriyor
- [ ] `pipemesh-core` hiçbir console sınıfına bağımlı değil (`mvn dependency:tree` ile)
- [ ] Mevcut 367 Java + 34 Python + 22 TypeScript testi değişmeden geçiyor

## Kapsam dışı

- **Gerçek ödeme.** Plan ve kota defteri var, Stripe yok. Ödeme sağlayıcısı ayrı bir contract
  ve ayrı bir güvenlik yüzeyi (webhook imzası, secret yönetimi).
- **Plan değiştirme akışı.** Ödeme olmadan "yükselt" düğmesinin anlamı yok; plan bugün elle
  değiştiriliyor.
- **Organizasyon başına izleme kotası.** Bugün izin var/yok; "ayda şu kadar izleme saati"
  ölçmeyi gerektiriyor ve ayrı bir iş.
- **Kullanıcı davet etme / roller.** Organizasyon başına tek kullanıcı yeterli; çok kullanıcı ve
  rol modeli ayrı bir iş.
- **Workflow düzenleme arayüzü.** Console kullanımı ve kimliği yönetiyor, workflow yazmayı değil.

## Split Decision

_To be filled by Agent 0_

## Implementation Notes

_To be filled as work progresses_
