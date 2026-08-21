# Update Replay

**Status:** Draft
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

- [ ] İmleç dayanıklı bir şeye bağlı; iki replika aynı imleci aynı şekilde yorumluyor
- [ ] Eski `from_sequence` alanı `reserved`; sessizce anlam değiştirmiyor
- [ ] İmleçsiz izleme bugünkü davranışı birebir koruyor
- [ ] İmleçli izleme, düşen istemcinin kaçırdığı adım olaylarını veriyor
- [ ] Token'lar yeniden oynatılmıyor ve bu istemciye bildiriliyor
- [ ] Abonelik geçmiş okunmadan **önce** açılıyor
- [ ] Geçmiş ile canlının kesiştiği yerde tekrar olabiliyor, kayıp olamıyor
- [ ] Bitmiş bir execution'ın tam geçmişi okunabiliyor, akış sonra kapanıyor
- [ ] Python ve TypeScript SDK'ları imleci veriyor ve yeniden bağlanmayı örnekliyor

## Kapsam dışı

- **Token yeniden oynatma.** Yukarıdaki gerekçeyle; isteyen bir uygulama kendi saklar.
- **Sınırsız geçmiş.** Adım geçmişinin saklama süresi ayrı bir karar; bu contract var olanı
  okuyor, ömrünü uzatmıyor.
- **İstemci tarafında otomatik yeniden bağlanma.** SDK imleci veriyor; ne zaman yeniden
  bağlanılacağı uygulamanın kararı.

## Split Decision

_To be filled by Agent 0_

## Implementation Notes

_To be filled as work progresses_
