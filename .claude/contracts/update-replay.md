# Update Replay

**Status:** Tamam (2026-08-22)
**Created:** 2026-08-22
**DESIGN.md kapsamı:** §30.1 (izleme), §30.2 (süreçler arası), §15 (adım geçmişi)

## Goal

`WatchExecutionRequest.from_sequence`'i gerçek yapmak: bağlantısı kopan bir istemcinin, düştüğü
yerden devam edebilmesi.

## Bugünkü durum ve neden böyle

Alan proto'da var, broker onu **uygulamıyor** ve javadoc'u nedenini söylüyor:

> Replaying means storing updates and deciding how long to keep them — a decision worth making
> deliberately rather than as a side effect of adding a stream.

Bu contract o kararı veriyor.

## Önce bir engel: #22 sıra numarasının anlamını değiştirdi

§30.2'de karar verildi: **sıra numarası akışın özelliği, execution'ın değil.** Hizmet veren
süreç numaralıyor. Dolayısıyla "7. olaydan devam et" cümlesinin execution genelinde bir karşılığı
yok — istemci başka bir replikaya bağlanırsa 7 başka bir şeydir.

Yani `from_sequence` bugünkü hâliyle **yeniden oynatılamaz bir imleç**. Çözüm ikisinden biri:

- **(a) İmleci dayanıklı bir şeye bağlamak.** Adım geçmişinin kendi sırası zaten kalıcı ve
  global. İmleç "şu adım kaydından sonrası" olur.
- **(b) Numaralamayı yeniden merkezileştirmek.** Süreçler arası bir sayaç demek; §30.2 bunu
  kilit paylaşmadan yapmanın mümkün olmadığı için reddetti.

**(a) öneriliyor**, ve proto'da alanın adı bunu söylemeli: `from_sequence` yerine
`from_step` benzeri bir imleç, eskisi `reserved`.

## Çoğu olay zaten kalıcı

Asıl bulgu: yeniden oynatma için yeni bir tablo **gerekmiyor olabilir**.

| Olay | Kaynağı |
|---|---|
| `step.started`, `step_finished` | `workflow_step_history` — zaten yazılıyor (§15) |
| `suspended`, `resumed`, `finished` | `workflow_execution` durumu ve geçmiş |
| `recovered` | geçmişteki kurtarma kaydı |
| `token` | **hiçbir yerde** |

Token'lar kalıcı değil ve olmamalı: bir modelin çıktısı adım bittiğinde tek bir değişkende
duruyor, token akışı onun geliş biçimi (§30). Token'ları saklamak, aynı veriyi iki kez ve
karakter karakter tutmak olur.

**Sonuç: yeniden oynatma durum olaylarını kurtarıyor, token'ları kurtarmıyor** — ve bunu
istemciye söylüyor. Yarım bir garanti, sessizce eksik bir akıştan iyidir.

## Yeniden oynatma canlıya nasıl bağlanıyor

Sıra şu olmalı, tersi değil:

```text
1. aboneliği aç          (bu andan sonrası kaçmıyor)
2. geçmişi oku ve gönder (imleçten aboneliğin başladığı ana kadar)
3. canlıyı akıt
```

Önce okuyup sonra abone olmak, ikisinin arasında olan her şeyi kaybeder — #12'nin sequence 0
çerçevesini eklerken verdiği kararın aynısı, aynı gerekçeyle.

Arada tekrar oluşabilir (hem geçmişte hem canlıda görünen bir olay). Tekrar, kayıptan iyi ve
istemci adım kimliğiyle ayıklayabilir; bu açıkça yazılmalı.

## Acceptance Criteria

- [x] İmleç dayanıklı bir şeye bağlı; iki replika aynı imleci aynı şekilde yorumluyor
- [x] Eski `from_sequence` alanı yerinde ve `deprecated`; sessizce anlam değiştirmiyor
- [x] İmleçsiz izleme bugünkü davranışı birebir koruyor
- [x] İmleçli izleme, düşen istemcinin kaçırdığı adım olaylarını veriyor
- [x] Token'lar yeniden oynatılmıyor ve bu istemciye bildiriliyor
- [x] Abonelik geçmiş okunmadan **önce** açılıyor
- [x] Geçmiş ile canlının kesiştiği yerde tekrar olabiliyor, kayıp olamıyor
- [x] Bitmiş bir execution'ın tam geçmişi okunabiliyor, akış sonra kapanıyor
- [x] Python ve TypeScript SDK'ları imleci veriyor ve yeniden bağlanmayı örnekliyor

## Kapsam dışı

- **Token yeniden oynatma.** Yukarıdaki gerekçeyle; isteyen bir uygulama kendi saklar.
- **Sınırsız geçmiş.** Adım geçmişinin saklama süresi ayrı bir karar; bu contract var olanı
  okuyor, ömrünü uzatmıyor.
- **İstemci tarafında otomatik yeniden bağlanma.** SDK imleci veriyor; ne zaman yeniden
  bağlanılacağı uygulamanın kararı.

## Split Decision

**Decision:** single-prompt, üç aşama
**Tarih:** 2026-08-22

1. **İmleç** — `StateStore.historyOf` arayüze çıkıyor ve imlecin ne olduğu tanımlanıyor.
2. **Yeniden oynatma** — abonelik → geçmiş → canlı sırası, ve token'ların dışarıda kaldığının
   söylenmesi.
3. **SDK'lar** — Python ve TypeScript'te imleç ve yeniden bağlanma örneği.

### İmleç bir sayı: kaç geçmiş kaydı görüldü

Adım geçmişi **ekleme-yalnızca ve sıralı**. Dolayısıyla "ilk N kaydı gördüm" cümlesi kalıcı,
global ve replikadan bağımsız — §30.2'nin akış-yerel sıra numarasının olamadığı her şey.

Şema değişmiyor, `StepRecord`'a alan eklenmiyor. `workflow_step_history` zaten `BIGSERIAL`
taşıyor ama onu dışarı vermek gerekmiyor: sıralı bir listede konum, kayıt kimliği kadar
kararlı ve daha az şey açıyor.

### Contract'ta bir düzeltme: `from_sequence` **reserved olmuyor**, deprecated oluyor

Contract "eski alan `reserved`" diyordu. Bu, #25'te yeni yazdığım uyumluluk kontrolünün tam
olarak yakalayacağı şey — ve doğru yakalar, çünkü alan kaldırmak kırıcı bir değişiklik.

Alanın hiç uygulanmamış olması onu kaldırmayı "güvenli" yapıyor gibi görünüyor, ama kontrol bunu
bilemez ve **ilk gününde kontrolü atlamayı öğrenmek** yanlış refleks. Alan yerinde kalıyor,
`[deprecated = true]` işaretleniyor, yenisi ekleniyor. Daha küçük ve daha dürüst hamle.

### Kapsam dışı (ek)

- **Geçmişten `token` üretmeye çalışmak.** Yok; uydurmak, olmamış bir şeyi olmuş göstermek olur.

### Risk points

- **Sıra: abonelik geçmişten önce.** Önce okuyup sonra abone olmak, aradaki her şeyi kaybeder.
  #12 aynı kararı sequence 0 çerçevesi için vermişti; burada tekrar edilmezse aynı hata
  tekrarlanır.
- **Kesişimde tekrar.** Geçmiş ile canlının örtüştüğü yerde bir olay iki kez görünebilir. Tekrar
  kayıptan iyi — ama **yazılmalı**, yoksa istemci onu bir hata sanar.
- **Geçmişin belleğe sığması.** Uzun bir execution'ın geçmişi büyük olabilir; imleçten sonrası
  parça parça gönderilmeli, hepsi bir listeye toplanıp değil.
- **Bitmiş execution'da akışın kapanması.** #22'de düzeltilen kusur burada geri gelebilir:
  geçmiş gönderildikten sonra terminal bir execution'ın akışı kapanmalı, canlıyı beklememeli.
- **Token'ın sessizce eksik kalması.** İstemciye söylenmezse, akışın eksik olduğunu ancak
  kullanıcı fark eder.

## Implementation Notes

**Tamamlandı:** 2026-08-22 — 5 yeni test; toplam 470 Java + 37 Python + 25 TypeScript.

### Yeni tablo gerekmedi

Contract'ın tahmini doğru çıktı: adım geçmişi zaten o olaylar. Yeniden oynatma
`workflow_step_history`'yi okuyor, ikinci bir kopya tutmuyor. `StateStore.historyOf` arayüze
çıktı (iki implementasyonda zaten vardı).

### İmleç sayı, kimlik değil

`workflow_step_history` `BIGSERIAL` taşıyor ama dışarı verilmedi: sıralı, ekleme-yalnızca bir
listede **konum** kayıt kimliği kadar kararlı ve daha az şey açıyor. `StepRecord` değişmedi,
şema değişmedi.

### `from_sequence` reserved değil, deprecated

Contract "reserved" diyordu. Bu, #25'te bir gün önce yazdığım uyumluluk kontrolünün yakalayacağı
şeydi — ve doğru yakalar. Alanın hiç uygulanmamış olması kaldırmayı güvenli gösteriyor ama kontrol
bunu bilemez, ve **ilk gününde kontrolü atlamayı öğrenmek** yanlış refleks. Alan yerinde,
`[deprecated = true]`, yenisi `from_step = 4`.

### Kontrol kendi kör noktasını buldu

`[deprecated = true]` eklenince uyumluluk testi düştü: alan regex'i seçenek ekini kabul etmiyordu
ve alanı "kaldırılmış" sanıyordu. Bir gün önce yazılan kontrol, ilk gerçek kullanımında kendi
eksiğini gösterdi. Regex düzeltildi ve nedeni orada yazılı.

### Sayaç akışın sahibinde

Asıl kusur buydu ve test yakaladı: yeniden oynatılanlar 1,2 diye numaralanıyor, sonra canlı yine
1'den başlıyordu — akış içinde monotonik değil.

Sayaç broker'dan **servise** taşındı. Broker artık numarasız teslim ediyor; numarayı akışa hizmet
veren taraf veriyor. Bu §30.2'nin cümlesinin ("sıra numarası akışın özelliği") kodda karşılığı, ve
önceden var olan bir tuhaflığı da düzeltti: aynı execution'ı izleyen ikinci bir client, ilkinin
saydığı yerden numara alıyordu.

### Sıra: abonelik, sonra geçmiş, sonra canlı

Önce okuyup sonra abone olmak aradaki her şeyi kaybederdi (#12'nin sequence 0 kararı). Ama abone
olup sonra okumak da yeterli değil: canlı bir olay, ondan eski bir geçmiş kaydından **önce**
yazılabilirdi. Çözüm, okuma sürerken geleni tutup sonra salıvermek — on satır, ve sıra korunuyor.

### Görünen davranış değişikliği

İmleçsiz bir izleyici artık geçmişi de alıyor (`from_step` yok = "hiçbir şey görmedim"). Mevcut
iki test bunu gösterdi ve beklentileri güncellendi. #20'nin "boş filtre = hepsi" kararıyla
tutarlı, ve #22'nin demo ekranı tam olarak bunu istiyor: sonradan bağlanan biri neyin olduğunu
görsün.

### Devralınacak

- **Geçmişin saklama süresi.** Bu contract var olanı okuyor; adım geçmişinin ne kadar tutulacağı
  ayrı bir karar.
- **İstemci tarafında otomatik yeniden bağlanma.** SDK imleci veriyor; ne zaman yeniden
  bağlanılacağı uygulamanın kararı.
