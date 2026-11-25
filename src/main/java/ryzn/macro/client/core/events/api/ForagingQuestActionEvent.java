package ryzn.macro.client.core.events.api;

/**
 * Requests one Foraging Quest Unlocker action from the menu, failsafe, or Level 7 route.
 *
 * @description Delivers a run-state or setup request to the registered quest macro on the
 *     publishing thread, so the caller never invokes the macro directly.
 * @param action the requested operation
 * @param value the checkpoint or leg index, or 1 and 0 for switch actions
 */
public record ForagingQuestActionEvent(Action action, int value) implements Event {
  /** The Foraging Quest Unlocker request kinds. */
  public enum Action {
    /** Turns the run on or off. */
    SET_ENABLED,
    /** Changes whether the inventory is sold at the merchant. */
    SET_CLEAR_INVENTORY_AT_MERCHANT,
    /** Resumes the run from the carried checkpoint. */
    PICK_RESUME_CHECKPOINT,
    /** Selects the carried collection grind leg. */
    SELECT_GRIND_LEG,
    /** Selects the final collection sale. */
    SELECT_FINAL_SALE,
    /** Arms the resume that follows a warp to the park. */
    ARM_PARK_WARP_RESUME
  }

  /**
   * @description Creates a switch action from a Boolean value.
   * @param action a switch action such as {@link Action#SET_ENABLED}
   * @param on the requested switch state
   */
  public ForagingQuestActionEvent(Action action, boolean on) {
    this(action, on ? 1 : 0);
  }

  /**
   * @description Creates an action that carries no value.
   * @param action an action such as {@link Action#SELECT_FINAL_SALE}
   */
  public ForagingQuestActionEvent(Action action) {
    this(action, 0);
  }

  /**
   * @description Reads the value of a switch action.
   * @return true when the requested switch state is on
   */
  public boolean flag() {
    return value != 0;
  }
}
