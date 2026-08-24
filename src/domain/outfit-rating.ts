/**
 * What a set of ratings adds up to.
 *
 * Pulled out because the app already computes it twice, differently. The outfit
 * detail screen reduces the ratings it loaded and treats "none" as zero, while
 * `getAverageRating` in the outfit service asks SQLite for `AVG(rating)` and
 * treats "none" as null — and nothing calls the second one. Two answers to one
 * question, neither tested.
 *
 * This is the answer. The rounding and the formatting live here too: the star row
 * shows a whole number of stars and the caption shows one decimal place, and both
 * are decisions about what the number means rather than about layout.
 */

export interface RatingSummary {
  /** How many ratings there are. */
  count: number;

  /** The mean, or null when there is nothing to average. */
  average: number | null;

  /**
   * Stars to fill, 0 to 5.
   *
   * Rounded rather than truncated: an outfit rated 4 and 5 averages 4.5, and
   * showing four stars for that reads as the lower of the two opinions.
   */
  stars: number;

  /** The mean to one decimal place, or null when there is none. */
  label: string | null;

  /** Whether there is an average worth showing at all. */
  showsAverage: boolean;
}

/** The highest a rating goes, and so the most stars that can be filled. */
export const MAX_RATING = 5;

export function ratingSummary(ratings: number[]): RatingSummary {
  // Ratings outside the scale cannot come from the star row, but they can come
  // from a restored backup or a hand-edited database, and an average of 9 would
  // fill more stars than exist.
  const usable = ratings.filter(rating => Number.isFinite(rating) && rating > 0);

  if (usable.length === 0) {
    return { count: 0, average: null, stars: 0, label: null, showsAverage: false };
  }

  const average = usable.reduce((sum, rating) => sum + rating, 0) / usable.length;
  const clamped = Math.min(average, MAX_RATING);

  return {
    count: usable.length,
    average,
    stars: Math.round(clamped),
    label: average.toFixed(1),
    showsAverage: true,
  };
}
