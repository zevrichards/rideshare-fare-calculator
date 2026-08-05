import {getTripHistory, saveCompletedTrip} from '../tripHistory';

describe('tripHistory', () => {
  it('returns an empty array when nothing has been saved', async () => {
    expect(await getTripHistory()).toEqual([]);
  });

  it('persists a completed trip and returns it newest-first', async () => {
    await saveCompletedTrip({distanceKm: 5, minutes: 12, total: 34.75});
    await saveCompletedTrip({distanceKm: 22, minutes: 30, total: 92});

    const history = await getTripHistory();

    expect(history).toHaveLength(2);
    expect(history[0].distanceKm).toBe(22);
    expect(history[1].distanceKm).toBe(5);
    expect(history[0].id).not.toBe(history[1].id);
  });
});
