package ryzn.macro.client.feature.rotation.impl.abcurves;

/** NumPy-compatible MT19937 draws used by the ABCurves continuous policy. */
final class AbcRandom {
  private static final int STATE_SIZE = 624;
  private static final int PERIOD_OFFSET = 397;
  private static final long DOUBLE_MASK = (1L << 53) - 1;
  private final int[] state = new int[STATE_SIZE];
  private int index = STATE_SIZE;

  AbcRandom(long seed) {
    state[0] = (int) seed;
    for (int i = 1; i < state.length; i++) {
      int previous = state[i - 1];
      state[i] = 1812433253 * (previous ^ (previous >>> 30)) + i;
    }
  }

  float uniform() {
    return (nextWord() & 0xffffff) * 0x1.0p-24f;
  }

  float exponential() {
    long pair = ((long) nextWord() << 32) | Integer.toUnsignedLong(nextWord());
    double uniform = (pair & DOUBLE_MASK) * 0x1.0p-53;
    return (float) -Math.log1p(-uniform);
  }

  void discardCategorical(int choices) {
    for (int i = 0; i < choices; i++) {
      nextWord();
      nextWord();
    }
  }

  private int nextWord() {
    if (index == STATE_SIZE) {
      for (int i = 0; i < STATE_SIZE; i++) {
        int pair = (state[i] & 0x80000000) | (state[(i + 1) % STATE_SIZE] & 0x7fffffff);
        state[i] =
            state[(i + PERIOD_OFFSET) % STATE_SIZE] ^ (pair >>> 1) ^ ((pair & 1) * 0x9908b0df);
      }
      index = 0;
    }
    int word = state[index++];
    word ^= word >>> 11;
    word ^= (word << 7) & 0x9d2c5680;
    word ^= (word << 15) & 0xefc60000;
    return word ^ (word >>> 18);
  }
}
