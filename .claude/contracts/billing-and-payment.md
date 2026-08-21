# Billing and Payment

**Status:** Draft
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
- [ ] `pipemesh-core` ve `pipemesh-runtime` ödeme diye bir şey bilmiyor (`ModuleBoundaryTest`)

## Kapsam dışı

- **Kullanım başına faturalama.** Bugün plan başına sabit; harcamaya göre faturalamak §39.1'in
  sayılarını finansal kayıt hâline getirir ve mutabakat gerektirir.
- **Vergi, fatura belgesi, para birimi.** Sağlayıcının işi.
- **Plan yükseltmede orantılama (proration).** Sağlayıcı hesaplıyor; biz sonucu uyguluyoruz.

## Split Decision

_To be filled by Agent 0_

## Implementation Notes

_To be filled as work progresses_
