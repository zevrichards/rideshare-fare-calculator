import {
  ALLRIDI_RATE_CARD,
  DEFAULT_RATE_CARDS,
  TTRS_RATE_CARD,
  getRateCards,
  getSelectedRateCardId,
  getSurgeMultiplier,
  saveRateCard,
  setSelectedRateCardId,
  setSurgeMultiplier,
} from '../rateCards';

describe('getRateCards', () => {
  it('returns the defaults when nothing has been saved', async () => {
    expect(await getRateCards()).toEqual(DEFAULT_RATE_CARDS);
  });

  it('overlays a saved edit onto the matching default card', async () => {
    await saveRateCard({...TTRS_RATE_CARD, minimumFare: 30});

    const cards = await getRateCards();
    expect(cards.find(card => card.id === 'ttrs')?.minimumFare).toBe(30);
    expect(cards.find(card => card.id === 'allridi')).toEqual(ALLRIDI_RATE_CARD);
  });
});

describe('selected rate card id', () => {
  it('defaults to TTRS', async () => {
    expect(await getSelectedRateCardId()).toBe('ttrs');
  });

  it('persists a selection', async () => {
    await setSelectedRateCardId('allridi');
    expect(await getSelectedRateCardId()).toBe('allridi');
  });
});

describe('surge multiplier', () => {
  it('defaults to 1', async () => {
    expect(await getSurgeMultiplier()).toBe(1);
  });

  it('persists a value', async () => {
    await setSurgeMultiplier(1.3);
    expect(await getSurgeMultiplier()).toBe(1.3);
  });
});
