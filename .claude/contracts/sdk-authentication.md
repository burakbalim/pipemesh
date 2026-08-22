# SDK Authentication

**Status:** Tamam (2026-08-23)
**Created:** 2026-08-23
**DESIGN.md kapsamı:** §23 (kimlik ve izin), §22.2 (organizasyon izolasyonu), §26.2 (SDK sınırı)

## Goal

Python ve TypeScript istemcilerinin bir API anahtarı gönderebilmesi — yani kimlik doğrulayan bir
deployment'a erişebilmeleri.

## Bugünkü boşluk

#19 anahtarı üretiyor, #22'nin `ConsolePrincipalResolver`'ı `authorization: Bearer` başlığını
okuyup organizasyonu ve planın izinlerini taşıyan bir `Principal` kuruyor. Bu zincir çalışıyor
ve testli.

Ama **SDK'larda başlığı gönderecek bir yol yok.** Yalnızca console'un kendi Java istemcisi
ekliyor. Sonuç:

```text
on-premise   kimse tanımlanmıyor → SDK çalışıyor, izolasyon zaten yok (§22.2)
cloud        kimlik zorunlu      → SDK hiç erişemiyor
```

Yani müşterilerin kullandığı iki SDK, sattığımız deployment'a bağlanamıyor. Bu, ilk demo
taslağında `api_key=...` yazıp sonra silmek zorunda kaldığımda görüldü.

## Anahtar her çağrıda, her akışta

Üç yer var ve üçü de aynı başlığı taşımalı:

- **İstemci** — `execute`, `get`, `approve`, `publish`, `watch`
- **Worker** — `CapabilityWorker.Connect` akışı; worker da bir çağıran ve organizasyonu
  doğrulanmalı (#14 worker kaydını organizasyona bağlıyor)
- **Akış boyunca** — `watch` uzun ömürlü bir akış; başlık çağrı kurulurken bir kez gidiyor

## Anahtar dizgi olarak yaşamıyor

SDK anahtarı ortamdan okuyabilmeli — `PIPEMESH_API_KEY` — ve kodda sabit yazmak zorunda
kalınmamalı. Depoda zaten kural var: *dosyalar paylaşılır, sırlar paylaşılmaz*
(`ModelDefinition.secretFromEnvironment`).

Ayrıca **anahtar hiçbir yerde log'lanmamalı**: ne hata mesajında, ne yeniden bağlanma
uyarısında.

## Düz metin bağlantı bir uyarı gerektiriyor

Anahtarla birlikte `usePlaintext` kullanmak, anahtarı ağa açıkta göndermek demek. TLS ingress'te
sonlanıyor (#22), yani üretimde istemci TLS konuşuyor. Yerel geliştirmede düz metin doğru; ikisi
karışırsa sessizce sır sızdırılır.

SDK, anahtar verilmiş **ve** bağlantı düz metinse bunu **söylemeli**. Reddetmemeli — yerelde
meşru — ama sessiz de kalmamalı.

## Acceptance Criteria

- [x] Python istemcisi `api_key` alıyor ve her çağrıda `authorization: Bearer` gönderiyor
- [x] TypeScript istemcisi aynısını yapıyor
- [x] Python ve TypeScript worker'ları da gönderiyor
- [x] Anahtar verilmezse bugünkü davranış birebir aynı (başlık yok)
- [x] Anahtar `PIPEMESH_API_KEY` ortam değişkeninden de okunabiliyor
- [x] Geçersiz anahtarla izin isteyen bir çağrı `PERMISSION_DENIED` alıyor
- [x] Geçerli anahtarla çağrı doğru organizasyona düşüyor
- [x] Anahtar hiçbir hata mesajında veya log satırında görünmüyor
- [x] Anahtar + düz metin bağlantı bir uyarı üretiyor, ret değil
- [x] Mevcut 470 Java + 40 Python + 27 TypeScript testi değişmeden geçiyor

## Kapsam dışı

- **Anahtar döndürme (rotation).** Console iptal ve yeniden üretme veriyor; istemcinin çalışırken
  anahtar değiştirmesi ayrı bir iş.
- **mTLS.** Sertifika tabanlı kimlik `PrincipalResolver`'ın zaten ifade edebildiği bir şey;
  SDK tarafı ayrı bir karar.
- **Java SDK'sı.** Console'un istemcisi zaten gönderiyor; ayrı bir Java SDK'sı paketi henüz yok.

## Split Decision

**Decision:** single-prompt, iki aşama
**Tarih:** 2026-08-23

1. **Python** — istemci ve worker, ortam okuma, düz metin uyarısı.
2. **TypeScript** — aynısı.

Java tarafı değişmiyor: `ConsolePrincipalResolver` başlığı zaten okuyor ve testli. Bu contract
tamamen SDK işi, ve iki dilin aynı davranışı göstermesi tek gereklilik.

### Testin gerçek olması

SDK testleri bugün kimlik doğrulamayan bir test sunucusuna bağlanıyor — orada bir anahtar
gönderilse de bir şey değişmez, yani "gönderiyor" testi hiçbir şey kanıtlamaz.

Gerçek testin bir kimlik çözücüsü olan sunucuya bağlanması gerekiyor. `TestRuntimeServer`
`RuntimeAssembly`'yi kullanıyor ve orada resolver `ANONYMOUS` sabitlenmiş — **bir ortam değişkeni
ile takılabilir hâle gelmeli**, yoksa test ya sahte olur ya da ikinci bir sunucu sınıfı
gerektirir (ki #21 tam olarak onu ortadan kaldırmıştı).

### Risk points

- **Sahte test.** Anahtarın gönderildiğini değil, **fark yarattığını** göstermeli: aynı çağrı
  anahtarsız reddedilmeli, anahtarla geçmeli.
- **Anahtarın log'a düşmesi.** gRPC hata mesajları metadata taşımıyor, ama bizim yeniden bağlanma
  ve hata sarmalayıcılarımız isteği string'leyebilir. Testi olmalı.
- **Worker'ın unutulması.** İstemciyi yapıp worker'ı atlamak, capability çağıran her akışı
  cloud'da kırık bırakır — ve bu, demo koşana kadar fark edilmez.
- **Düz metin uyarısının ret'e dönüşmesi.** Yerelde düz metin meşru; reddetmek her geliştirmeyi
  TLS kurmaya zorlar.
- **`watch` akışında başlığın kaybolması.** Başlık çağrı kurulurken gidiyor; akış uzun ömürlü
  olduğu için tekrar gönderilmiyor — kesilip yeniden bağlanan bir istemcinin yeni çağrısında
  yeniden gitmeli.

## Implementation Notes

**Tamamlandı:** 2026-08-23 — 7 yeni test (4 Python, 3 TypeScript); 470 Java + 47 Python + 30 TS.

### Test sahte olmasın diye resolver takılabilir oldu

Preflight'ın çıkardığı sorun buydu: kimlik doğrulamayan bir sunucuya anahtar göndermek hiçbir
şey kanıtlamaz. `RuntimeAssembly.of` artık bir `PrincipalResolver` alabiliyor, ve
`TestRuntimeServer` `PIPEMESH_TEST_KEY` verilmişse tek anahtarlık gerçek bir çözücü kuruyor.

Testler anahtarın **fark yarattığını** gösteriyor: anahtarsız `PERMISSION_DENIED`, anahtarla
organizasyon **anahtardan** geliyor — istemci hiçbir organizasyon adı söylemiyor.

### grpc-js düz metinde kimlik bilgisi bileştirmeyi reddediyor

TypeScript'te önce `combineChannelCredentials` denendi ve `Cannot compose insecure credentials`
ile düştü. grpc-js tam olarak uyarmak istediğim şeyi **engelliyor**: sırrı açıkta göndermene
yardım etmiyor.

Bu doğru bir ret, ve yol da açık: başlık **çağrı başına metadata** olarak gidiyor — Python'daki
interceptor'ın yaptığının aynısı. Aynı kod hem düz metinde hem TLS'te çalışıyor.

### Uyarı var, ret yok

Anahtar + düz metin bir uyarı üretiyor. Reddetmek her geliştirme kurulumunu önce TLS ayağa
kaldırmaya zorlardı; sessiz kalmak ise anahtarın sızmasının yolu. İkisi arasında doğru yer bu.

### Worker da gönderiyor

İstemciyi yapıp worker'ı atlamak, capability çağıran her akışı cloud'da kırık bırakırdı — ve bu
ancak demo koşarken fark edilirdi. Worker'ın kaydı zaten organizasyona bağlı (#14); kimlik
doğrulayan bir deployment hangi organizasyonun bağlandığını bilmeli.

### Anahtar hiçbir yerde görünmüyor

`PIPEMESH_API_KEY` ortamdan okunuyor, koda yazılmak zorunda değil. Ve hata mesajlarında
görünmediğinin testi var — gRPC metadata taşımıyor ama bizim sarmalayıcılarımız isteği
string'leyebilirdi.

### Devralınacak

- **Anahtar döndürme.** Console iptal ve yeniden üretme veriyor; istemcinin çalışırken anahtar
  değiştirmesi ayrı.
- **mTLS.** `PrincipalResolver` zaten ifade edebiliyor; SDK tarafı ayrı bir karar.
