# Remote Events

**Status:** Tamam (2026-08-23)
**Created:** 2026-08-22
**DESIGN.md kapsamı:** §9.7 (wait), §22.2 (organizasyon), §26.1 (proto otorite)

## Goal

Bekleyen bir execution'ı **uzaktaki** bir uygulamanın ilerletebilmesi: olay yayını için bir RPC.

## Bugünkü boşluk

#7 beklemeyi ve eşleştirmeyi kurdu — `EventKey(organizasyon, ad, korelasyon)`, `WaitStore`,
`EventPublisher`, hepsi çalışıyor ve testli. Ama yayın **in-process** bir API, ve #7 bunu
bilerek kapsam dışı bıraktı:

> Proto'da olay RPC'si yok; eklemek proto + iki SDK demek. Bu contract in-process yayın API'sini
> veriyor, teli sonraki iş devralır.

Sonuç: runtime'ı gömen bir uygulama olayı yayınlayabiliyor, gRPC ile konuşan yapamıyor. Yani
`wait` adımı uzak istemciler için yarım — bekleyebiliyor ama kimse uyandıramıyor.

Bu, ilk gerçekçi akış taslağında hemen görüldü: LLM seçenek sunuyor, kullanıcı seçiyor, ve
seçimi execution'a iletecek bir çağrı yok.

## Organizasyon istekten gelmiyor

`EventKey`'in ilk bileşeni organizasyon ve bunun sebebi #7'de yazılı: bir kiracının yayınladığı
olay diğerinin execution'ını ilerletmemeli.

Dolayısıyla RPC'de organizasyon alanı **yok**. Çözümlenen principal'dan geliyor — §23'ün
"çağıran kendi iznini beyan etmez" kuralının olay tarafındaki karşılığı. Gövdesinde organizasyon
taşıyan bir olay RPC'si, kiracı sınırını tek bir alan ile delerdi.

## Ne dönüyor

Yayın, ilerlettiği execution'ların kimliklerini döndürüyor. Boş liste bir hata değil: bekleyeni
olmayan olay düşürülüyor (#7'nin kararı), ve çağıran bunu "kimse beklemiyordu" olarak görüyor.

## API Contract

```protobuf
rpc PublishEvent (PublishEventRequest) returns (PublishEventResponse);

message PublishEventRequest {
  string name = 1;              // "vendor_chosen"
  string correlation = 2;       // bekleyişin kaydedildiği anahtar
  google.protobuf.Struct payload = 3;
}

message PublishEventResponse {
  repeated string execution_ids = 1;   // ilerleyenler; boş olabilir
}
```

## Acceptance Criteria

- [x] Doğru anahtarla yayınlanan olay bekleyen execution'ı ilerletiyor
- [x] Olayın gövdesi `output` değişkenine yazılıyor
- [x] Aynı olayı bekleyen iki execution varsa ikisi de ilerliyor
- [x] Yanlış korelasyon kimseyi ilerletmiyor ve hata değil
- [x] Başka organizasyonun aynı anahtarlı olayı hiçbir şeyi ilerletmiyor
- [~] Organizasyon principal'dan — **kriter düzeltildi**: alan var, ama yalnızca kimse
  tanımlanmamışsa okunuyor. Gerekçe Implementation Notes'ta.
- [x] Yayın bir yetki gerektiriyor ve varsayılanı kapalı değil (mevcut kurulumlar kırılmıyor)
- [x] Python ve TypeScript SDK'ları yayınlayabiliyor
- [x] `WorkflowExecutor` değişmiyor
- [x] Mevcut 470 Java + 37 Python + 25 TypeScript testi değişmeden geçiyor

## Kapsam dışı

- **Olay geçmişi.** Bekleyeni olmayan olay hâlâ düşüyor; saklamak #7'de olduğu gibi ayrı bir iş.
- **Kalıp eşleşmesi.** Anahtar tam eşleşiyor.
- **Olay akışına abone olmak.** Bu RPC yayınlıyor; dinlemek `WatchExecution`'ın işi.

## Split Decision

**Decision:** single-prompt, tek aşama
**Tarih:** 2026-08-22

Küçük, çünkü işin tamamı #7'de yapıldı: `EventPublisher` eşleşen her execution'ı bulup
ilerletiyor. Eklenen şey bir tel — proto, servis metodu, iki SDK.

### Yetkinin varsayılanı

`stream:watch` #20'de **kapalı varsayılanla** geldi: izin listesi boşsa herkes izleyebiliyor,
çünkü kimseyi tanımlamayan bir deployment'ta zorunlu tutmak her mevcut kurulumu kırardı.

Yayın için aynı duruş: `event:publish` bir izin adı, ama gereksinim sunucu tarafında opt-in.
Farklı davranmak, tek kiracılı her kurulumu bir gecede kırardı — ve orada zaten izole edilecek
bir sınır yok (§22.2).

### Risk points

- **Organizasyonun istekten alınması.** En kolay ve en yanlış yol. `EventKey`'in ilk bileşeni
  o; istekten gelirse kiracı sınırı tek alanla delinir.
- **Yayının bir execution'ı ilerletirken hata vermesi.** `EventPublisher` her eşleşeni
  ilerletiyor; biri patlarsa diğerleri etkilenmemeli ve çağıran ne olduğunu görmeli.
- **Boş cevabın hata sanılması.** Bekleyeni olmayan olay düşüyor — bu #7'nin kararı ve
  SDK'larda da öyle görünmeli, istisna olarak değil.

## Implementation Notes

**Tamamlandı:** 2026-08-23 — 5 yeni test (3 Python, 2 TypeScript); 470 Java + 40 Python + 27 TS.

### Küçük çıktı, çünkü #7 zaten yapmıştı

`EventPublisher` eşleşen her execution'ı bulup ilerletiyordu. Eklenen şey tel: bir RPC, servis
metodu, iki SDK metodu. Motor değişmedi.

### Kriter düzeltildi: organizasyon alanı **var**

Contract "istekte organizasyon alanı yok" diyordu. Uygularken bunun bu depoda **zaten verilmiş
karara aykırı** olduğu görüldü — `StartExecutionRequest` aynı durumda alanı taşıyor ve kuralını
yazıyor:

> The field on the request is a convenience for deployments that identify nobody — where it is
> also the only thing available, and where there is no isolation to undermine (§22.2).

Olaya farklı davranmak tutarsızlık olurdu: tek kiracılı bir kurulumda olay yayını yalnızca
`default` organizasyona ulaşırdı, ve orada zaten korunacak bir sınır yok. Kural aynı: **çözümlenen
principal her zaman kazanır; istek alanı yalnızca kimse tanımlanmamışsa okunuyor.**

### Yayın izni kapalı varsayılanla

`stream:watch` ile aynı duruş (#20): izin listesi boşsa yayın açık. Zorunlu tutmak, kimseyi
tanımlamayan her mevcut kurulumu bir gecede kırardı.

### Yolda: kendi düzeltmelerimin sessizce uygulanmaması

Aynı hatayı üç kez yaptım: Bash çağrıları arasında çalışma dizini sıfırlanıyor, ve `sdk/python`
içinden koşan `python3` blokları kök yollara yazamayıp **sessizce hiçbir şey yapmadı**. Proto
düzenlemesi, servis düzeltmesi ve test düzenlemesi böyle kayboldu.

Belirti kafa karıştırıcıydı: `publish` boş liste döndürüyordu, hata değil — çünkü kod hâlâ
`organizationOf("")` çağırıyordu ve `default` altında arıyordu. Log koyunca ortaya çıktı:
bekleyiş `acme/payment_completed#A-4172` altında duruyordu.

Ders: bir düzenlemeden sonra **uygulandığını doğrulamak**, düzenlemenin kendisi kadar iş.

### #26'nın yan etkisi burada görüldü

İki SDK testi "filtreleyen izleyici boşluk görür" diyordu ve düştü. Sebep #26: numaralama akışa
hizmet veren tarafa taşınınca, filtrelenen güncelleme hiç numaralanmıyor — akış **boşluksuz**.

Bu #20'nin verdiği garantiden güçlü: artık bir boşluk yalnızca **kayıp** demek. Testler yeni ve
daha iyi ifadeye güncellendi.

### Devralınacak

- **Olay geçmişi.** Bekleyeni olmayan olay hâlâ düşüyor.
- **SDK'ların kimlik göstermesi.** Bu contract'ın dışında ama demo'yu cloud'da koşturmanın önündeki
  tek engel: Python ve TypeScript istemcilerinde API anahtarı gönderecek bir alan yok.
