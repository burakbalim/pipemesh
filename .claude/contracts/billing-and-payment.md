# Billing and Payment

**Status:** Aşama 1-3 tamam (2026-08-22)
**Created:** 2026-08-22
**DESIGN.md kapsamı:** §3 (runtime ne bilmez), §39.1 (harcama), #19 (plan/kota)

## Goal

Bir organizasyonun planını gerçekten satın alabilmesi: ödeme sağlayıcısı, abonelik durumu ve
plan değişimi.

## Runtime hâlâ hiçbir şey bilmiyor

Kota #19'da sınıra kondu, motora değil. Fatura bir adım daha uzakta: **console bile** ödeme
mantığını taşımamalı, yalnızca sonucunu — hangi organizasyon hangi planda.

```text
Stripe  ──webhook──▶  console  ──plan_id──▶  console_organization
                                                    │
                                             QuotaInterceptor
                                                    │
                                                 runtime (hiçbir şey bilmiyor)
```

## Asıl sorun: iki sistemin ikisi de "abonelik" diyor

Stripe abonelik durumunu tutuyor, biz de tutuyoruz. Hangisi doğru?

**Ödeme için Stripe otorite, yetki için biz.** Yani "kart geçti mi" sorusunun cevabı orada,
"bu organizasyon şu an ne yapabilir" sorusunun cevabı burada. Webhook ikisini uzlaştırıyor.

Bunu tersine kurmak — her kota kontrolünde Stripe'a sormak — istek yoluna bir dış servis
koymak demek: Stripe yavaşladığında hiçbir workflow başlamaz.

### Webhook'lar sırasız ve tekrarlı gelir

İkisi de varsayılmalı, umut edilmemeli:

- **Tekrar:** aynı olay iki kez gelir. `event.id` saklanıyor; ikinci kez görüldüğünde hiçbir şey
  olmuyor. Approval'ın idempotency'siyle aynı desen (#1).
- **Sırasızlık:** eski bir olay yenisinden sonra gelebilir. Her olay bir abonelik sürümü
  taşıyor; daha eski bir sürüm mevcut durumu **geri almıyor**.

### İmza doğrulaması zorunlu

Webhook uç noktası kimliği doğrulanmamış bir POST alıyor. İmza doğrulanmazsa herkes kendini
"pro" plana yükseltebilir — ve bu, izin modelinin tamamını tek bir HTTP çağrısıyla atlar (§23).

## Ödeme başarısız olduğunda veri silinmiyor

Kart geçmediğinde organizasyon demo planına düşüyor: kota daralıyor, **hiçbir execution
silinmiyor, hiçbir anahtar iptal edilmiyor**. Ödemeyi kaçıran biri müşteri olmayı bırakmıyor;
sildiğimiz şeyi geri getiremeyiz.

Bir ödemesiz dönem (grace period) var ve süresi konfigürasyon — koda gömülü bir gün sayısı,
kimsenin değiştiremeyeceği bir iş kararıdır.

## On-premise'te fatura yok — ve bu iki ayrı karar

**Ödeme dışarıda.** `PaymentProvider` yapılandırılmamışsa checkout yok, abonelik ekranı yok,
webhook uç noktası yok. Console açılıyor ve kimlik/anahtar/kullanım işini yapmaya devam ediyor.
On-prem sözleşmeyle ödenir; ölçmediğimiz şeyi faturalandıramayız ve faturalandırmayacağız
(#21'de verilen karar).

**Varsayılan plan konfigürasyon.** Bugün `RegistrationService` `"demo"` sabitini taşıyor — ve
kendi yorumu bunun konfigürasyon olması gerektiğini söylüyor:

> which plan a new account lands on is configuration, and a constant in a service is a decision
> nobody can change without a deploy

Yorum haklı, kod değil. On-prem'de yeni bir hesabın demo planına düşmesi, lisansını almış bir
müşteriyi 50 execution'da durdurmak demek.

Düzeltme: `console.defaultPlan`, cloud'da `demo`, on-prem'de `unlimited`. Ve `unlimited` planı
şemada bir satır olarak var — demo gibi, `if` ile değil.

## Sağlayıcı bir SPI

`PaymentProvider` arayüzü console'da, Stripe implementasyonu ayrı bir sınıfta. Gerekçesi
capability provider'larla aynı (§9.8): sağlayıcı değişirse plan tablosu, kota kontrolü ve
ekranlar değişmemeli.

## API Contract

```text
POST /api/v1/checkout            plan seçimi → sağlayıcının ödeme sayfasına yönlendirme
POST /api/v1/webhooks/payment    sağlayıcıdan; imzalı, idempotent, sırasız-dayanıklı
GET  /api/v1/subscription        mevcut plan, dönem sonu, ödeme durumu
POST /api/v1/subscription/cancel dönem sonunda demo'ya düşer
```

`/webhooks/payment` **oturum istemiyor** — çağıran Stripe. Kimliği imza kuruyor, çerez değil.

## DB Schema Changes

```text
console_subscription        organization_id, provider_id, plan_id, status,
                            current_period_end, updated_version
console_payment_event       event_id (PK), received_at   -- idempotency
```

Ayrıca `console_plan`'a bir satır: `unlimited` — sıfır limitler, yani sınırsız (§39.1'in
konvansiyonu).

`console_organization.plan_id` yetki için tek kaynak olmaya devam ediyor; abonelik tablosu onu
**besliyor**, yerine geçmiyor.

## Acceptance Criteria

- [ ] İmzasız veya bozuk imzalı webhook reddediliyor
- [ ] Aynı `event.id` iki kez gelince plan bir kez değişiyor
- [ ] Eski sürümlü bir olay yeni durumu geri almıyor
- [ ] Başarılı ödeme organizasyonun planını yükseltiyor; etkisi bir sonraki kota kontrolünde
- [ ] Başarısız ödeme grace period sonunda demo'ya düşürüyor
- [ ] Düşüş hiçbir execution, anahtar veya kullanıcı silmiyor
- [ ] İptal dönem **sonunda** etkili; ödenmiş süre çalınmıyor
- [ ] Kota kontrolü hiçbir zaman sağlayıcıya çağrı yapmıyor
- [ ] `PaymentProvider` yapılandırılmamışsa console açılıyor ve checkout 501 dönüyor
- [ ] Sağlayıcısız kurulumda webhook uç noktası da yok (404), sessizce kabul etmiyor
- [ ] Varsayılan plan konfigürasyondan geliyor; `RegistrationService`'te sabit yok
- [ ] `unlimited` planı şemada bir satır; kotayı hiçbir yerde `if` ile atlamıyoruz
- [ ] On-prem varsayılanıyla (`unlimited`) kota hiçbir zaman reddetmiyor
- [ ] `pipemesh-core` ve `pipemesh-runtime` ödeme diye bir şey bilmiyor (`ModuleBoundaryTest`)

## Kapsam dışı

- **Kullanım başına faturalama.** Bugün plan başına sabit; harcamaya göre faturalamak §39.1'in
  sayılarını finansal kayıt hâline getirir ve mutabakat gerektirir.
- **Vergi, fatura belgesi, para birimi.** Sağlayıcının işi.
- **Plan yükseltmede orantılama (proration).** Sağlayıcı hesaplıyor; biz sonucu uyguluyoruz.

## Split Decision

**Decision:** single-prompt, üç aşama
**Tarih:** 2026-08-22

Katmanlar sıralı: varsayılan plan olmadan abonelik durumunun yazacağı yer yok, abonelik olmadan
webhook'un uzlaştıracağı bir şey yok.

1. **Varsayılan plan konfigürasyona çıkıyor** — `console.defaultPlan`, `unlimited` satırı, ve
   on-prem'in kotayla hiç karşılaşmaması. Ödemeye hiç dokunmadan tek başına doğru.
2. **Abonelik durumu ve SPI** — `PaymentProvider`, `console_subscription`, plan değişiminin
   yetkiye dönüşmesi, grace period.
3. **Webhook** — imza, idempotency, sırasızlık; ve sağlayıcısız kurulumda uç noktanın hiç
   var olmaması.

### 1. aşama neden ayrı ve önce

Tek başına bir hata düzeltmesi: kodun kendi yorumunun söylediği şeyi yapmıyor olması. Ödeme
hiç gelmese bile doğru, ve on-prem'in bugünkü kırıklığını (lisanslı müşterinin 50 execution'da
durması) hemen kapatıyor.

### Webhook'un ayrı aşama olmasının sebebi

İmza doğrulama, idempotency ve sırasızlık üç ayrı emniyet ve üçü de **yokluğunda sessiz**.
Abonelik durumuyla birlikte yazılırsa, testler "plan değişti mi" sorusuna bakar ve bu üçünü
gözden kaçırır. Ayrı aşama, testlerin de ayrı olmasını zorluyor.

### Kapsam dışı (ek)

- **Cloud'da varsayılan planın `demo` olmaktan çıkması.** #19'un kararı duruyor: demo bir plan
  satırı ve yeni hesap oraya düşüyor. Bu contract yalnızca *hangi* planın varsayılan olduğunu
  konfigürasyona taşıyor.

### Risk points

- **Sağlayıcısız kurulumun yarım kalması.** Checkout 501 dönerken webhook'un 200 dönmesi en
  kötüsü: yapılandırılmamış bir kurulum, imzasız bir POST'u kabul ediyor görünür. Uç nokta
  **hiç var olmamalı**.
- **İmza doğrulamanın gövdeyi ayrıştırdıktan sonra yapılması.** İmza ham gövde üzerinde
  hesaplanıyor; Jackson'ın yeniden serileştirdiği bir gövde eşleşmez ve doğrulama sessizce
  hep başarısız — ya da daha kötüsü, atlanır.
- **Grace period'ın koda gömülmesi.** Kaç gün ödemesiz çalışılacağı bir iş kararı; sabit olursa
  değiştirmek deploy gerektirir. Aynı hatayı varsayılan planda bir kez yaptık.
- **Sırasızlığın "son gelen kazanır" ile geçiştirilmesi.** Sağlayıcı olayları sıralı göndermiyor;
  sürüm karşılaştırması olmadan eski bir olay planı geri alır ve bunu kimse fark etmez.
- **Düşüşün veri silmesi.** Kota daralıyor; execution, anahtar, kullanıcı **durmuyor**. Testi
  olmalı, yorumu değil.

## Implementation Notes

### Aşama 1 — varsayılan plan konfigürasyona çıktı (2026-08-22)

`RegistrationService` kendi yorumunun söylediğini yapmıyordu: yorum "hangi plana düşüleceği
konfigürasyondur" diyordu, kod `DEMO_PLAN` sabitini taşıyordu. On-prem'de bu, lisansını almış
bir müşterinin 50 execution'da durması demekti.

`console.defaultPlan` geldi; `unlimited` planı şemada bir **satır** (`V104`), kodda bir `if`
değil. Kota kodu on-prem'de de koşuyor, sadece reddedecek bir şey bulamıyor — dallanarak atlamak
iki yol üretirdi ve yalnızca biri test edilirdi.

Yolda: `QuotaTest` kendi `unlimited` planını uyduruyordu; artık şemadakini kullanıyor.

### Aşama 2 — abonelik durumu

**Sağlayıcı ödeme için otorite, biz yetki için.** `console_organization.plan_id` yetkinin tek
kaynağı olmaya devam ediyor; `console_subscription` onu **besliyor**, yerine geçmiyor. Tersi —
her kota kontrolünde sağlayıcıya sormak — istek yoluna bir dış servis koyardı.

İki emniyet de **store'da**, metodun dikkatinde değil:

- `rememberEvent` — `ON CONFLICT DO NOTHING`, yani aynı olay ikinci kez hiçbir şey yapmıyor.
- `save` — sürüm karşılaştırması `WHERE` içinde. Önce okuyup sonra yazsaydık, birlikte gelen iki
  webhook ikisi de eski satırı görür ve ikisi de yazardı.

`PAST_DUE` yetkiyi **değiştirmiyor**: hafta sonu süresi dolan bir kart, birinin işini durdurma
sebebi değil. Grace period konfigürasyon (`console.billing.gracePeriod`), çünkü kaç gün
ödemesiz çalışılacağı bir iş kararı — aynı hatayı varsayılan planda bir kez yapmıştık.

Düşüş hiçbir şey silmiyor ve bunun testi var: anahtar duruyor, execution duruyor. Ödemeyi
kaçıran biri müşteri olmayı bırakmıyor.

### Aşama 3 — webhook

Controller'ın tamamı `@ConditionalOnBean(PaymentProvider.class)`. Sağlayıcısız kurulumda uç
noktalar **404** — "yapılandırılmamış" ile "çalışıyor" arasındaki farkı bir dış gözlemci ancak
böyle görebilir. Preflight'ta en tehlikeli risk buydu ve `NoProviderTest` onu tutuyor.

Gövde `String` olarak alınıyor, ayrıştırılmış bir nesne olarak değil: imza gelen baytlar
üzerinde hesaplanıyor ve Jackson'ın yeniden serileştirdiği bir gövde asla eşleşmez — doğrulamanın
sessizce hep başarısız olmasının (ya da atlanmasının) yolu bu.

### Yolda: iki `SubscriptionRepository`

Yeni sınıf `#19`'un `subscription.SubscriptionRepository`'siyle aynı bean adını aldı ve context
hiç açılmadı. `BillingRepository` oldu — ve isim ayrımı gerçek bir kavram ayrımı: biri planın ne
verdiğini, diğeri sağlayıcının ne dediğini tutuyor.

### Devralınacak

- **Stripe implementasyonu.** SPI ve tüm davranış hazır; `PaymentProvider`'ın Stripe'a bağlanan
  hâli canlı hesap, webhook secret'ı ve uçtan uca bir doğrulama gerektiriyor.
- **Grace period'ın süpürülmesi.** `settleIfLapsed` var ve test edildi; onu düzenli koşturacak
  zamanlama (mevcut bir `RecoveryPass`'e takılabilir) ayrı.
- **Abonelik ekranı.** API hazır; `/plans` ve yükseltme akışı console UI'ında yok.
