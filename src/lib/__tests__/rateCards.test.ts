import {
  ALLRIDI_RATE_CARD,
  applyCardCorrections,
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

describe('saved Allridi card corrections', () => {
  const oldAllridiCard = {
    ...ALLRIDI_RATE_CARD,
    longDistanceKmThreshold: null,
    perKmRateBeyondThreshold: null,
    minimumFare: 22,
  };

  it('upgrades a card saved before the 20km tier and $26 minimum existed', async () => {
    await saveRateCard(oldAllridiCard);

    const allridi = (await getRateCards()).find(card => card.id === 'allridi');
    expect(allridi).toEqual(ALLRIDI_RATE_CARD);
  });

  it('keeps other edits on the saved card', async () => {
    await saveRateCard({...oldAllridiCard, baseFare: 17});

    const allridi = (await getRateCards()).find(card => card.id === 'allridi');
    expect(allridi?.baseFare).toBe(17);
    expect(allridi?.perKmRateBeyondThreshold).toBe(3);
  });

  it('keeps a minimum fare the driver set themselves', async () => {
    await saveRateCard({...ALLRIDI_RATE_CARD, minimumFare: 30});

    const allridi = (await getRateCards()).find(card => card.id === 'allridi');
    expect(allridi?.minimumFare).toBe(30);
  });

  it('does not touch the TTRS card', () => {
    expect(applyCardCorrections(TTRS_RATE_CARD)).toBe(TTRS_RATE_CARD);
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
