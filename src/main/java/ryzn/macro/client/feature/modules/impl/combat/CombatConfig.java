package ryzn.macro.client.feature.modules.impl.combat;

/**
 * How the fight is configured. Combat only ever runs as a macro's borrowed session (Rev Slayer,
 * Dwarven commissions) - the standalone Combat Bot and its settings card are gone - so every
 * setting is either the session's own or a fixed answer a session always wanted.
 *
 * <p>The fixed ones read as constants on purpose: they were the user-facing toggles of the old bot,
 * and a session overrode all of them anyway. Anything a session never decides is still read
 * straight off {@link CombatModule} (aim feel, repath pacing) at its call site.
 */
record CombatConfig(CombatModule.ManagedSettings session) {

  static CombatConfig of(CombatModule.ManagedSettings session) {
    return new CombatConfig(session);
  }

  /** A session always wants the head assist. */
  boolean aimAssist() {
    return true;
  }

  /** A session only ever fights what it can see. */
  boolean requireSight() {
    return true;
  }

  /** Player-only conveniences: a session drives its own attack pacing. */
  boolean oneTap() {
    return false;
  }

  boolean instantAttack() {
    return false;
  }

  /**
   * Hazard zones were a Graveyard notion; a session picks its own ground and is not filtered by
   * them.
   */
  boolean avoidDanger() {
    return false;
  }

  boolean searchEnabled() {
    return session.search();
  }

  int scanRange() {
    return session.scanRange();
  }

  String bounds() {
    return session.bounds();
  }

  /**
   * Attack range. A clustered session swings a held-item ability across a group, so it uses its own
   * (long) range only while a group is actually up; alone it falls back to melee reach.
   */
  double attackRange(boolean clustered) {
    if (session.attackMode() == CombatModule.ManagedAttackMode.CLUSTERED_USE_HELD_ITEM
        && !clustered) {
      return CombatModule.MANAGED_ATTACK_RANGE;
    }
    return session.attackRange();
  }
}
