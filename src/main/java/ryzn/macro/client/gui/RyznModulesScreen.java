package ryzn.macro.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ryzn.macro.client.config.ConfigManager;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.groups.RotationSettings;
import ryzn.macro.client.config.groups.RyznMenuSettings.Position;
import ryzn.macro.client.config.groups.WalkingSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.Level7ActionEvent;
import ryzn.macro.client.debug.DebugControl;
import ryzn.macro.client.feature.macros.impl.foraging.AfkAutoHarpMacro;
import ryzn.macro.client.feature.macros.impl.level7.AutoLvl7Macro;
import ryzn.macro.client.feature.macros.impl.wheat.WheatBreakerMacro;
import ryzn.macro.client.feature.rotation.impl.abcurves.AbcRotation;

public final class RyznModulesScreen extends Screen {
  private enum Tab {
    LEVEL_7("Macros"),
    ROTATIONS("Rotations"),
    PATHFINDER("Pathfinder"),
    HUD("HUD"),
    DEVELOPER("Developer");

    String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    final String label;

    Tab(String label) {
      this.label = label;
    }
  }

  private static boolean menuKeyDown;
  private static boolean openWalkingOnNextTick;
  private final Screen parent;
  private final EnumSet<Tab> openSections = EnumSet.noneOf(Tab.class);
  private List<Tab> sectionOrder = List.of();
  private int left;
  private int top;
  private int panelWidth;
  private int panelHeight;
  private static final int HEADER_HEIGHT = 27;
  private static final int MARGIN = 10;
  private final Map<String, SectionBounds> sections = new LinkedHashMap<>();
  private final List<String> windowOrder = new ArrayList<>();
  private final Map<String, RyznMenuStyle.MenuButton> headerButtons = new LinkedHashMap<>();
  private String pressedHeader;
  private int pressX;
  private int pressY;
  private int dragOffsetX;
  private int dragOffsetY;
  private String buildingWindow;
  private int firstWindowControl;
  private final long openedAt = System.nanoTime();
  private long categoryChangedAt = openedAt;
  private final Map<String, List<AbstractWidget>> panelWidgets = new LinkedHashMap<>();
  private int scroll;
  private int maxScroll;
  private static final int SIDEBAR_WIDTH = 136;
  private static final int SIDEBAR_MIN_HEIGHT = 245;
  private static final String SIDEBAR_POSITION = "sidebar";
  private static final String ROUTE_WINDOW = "Auto Level 7";
  private boolean draggingSidebar;
  private final List<RyznMenuStyle.MenuButton> navigationButtons = new ArrayList<>();
  private boolean draggingHeader;
  private String configStatus = "";
  private boolean confirmingReset;
  private boolean sprintKeyDown;
  private boolean sprintToggled;
  private boolean sprintToggleChanged;
  private RyznMenuStyle.MenuButton cancelReset;
  private RyznMenuStyle.MenuButton applyReset;

  private record SectionBounds(int x, int y, int width, int height) {
    boolean contains(double mx, double my) {
      return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    boolean containsHeader(double mx, double my) {
      return contains(mx, my) && my < y + HEADER_HEIGHT;
    }
  }

  private int contentWidth;
  private int contentX;
  private RyznMenuStyle.MenuButton levelToggle;
  private RyznMenuStyle.MenuButton freshStart;

  public RyznModulesScreen(Screen parent) {
    super(Component.literal("Ryzn Modules"));
    this.parent = parent;
    var client = Minecraft.getInstance();
    sprintKeyDown = RyznMenuMovement.down(client, client.options.keySprint);
    sprintToggled = client.options.toggleSprint().get() && client.options.keySprint.isDown();
    readExpansion();
  }

  public static void openWalkingOnNextTick() {
    openWalkingOnNextTick = true;
  }

  public static void tickMenuKey(Minecraft client) {
    if (openWalkingOnNextTick
        && client.player != null
        && client.gui.screen() == null
        && RyznBootstrap.isReady()) {
      openWalkingOnNextTick = false;
      var screen = new RyznModulesScreen(null);
      screen.openSections.clear();
      screen.openSections.add(Tab.PATHFINDER);
      client.gui.setScreen(screen);
    }
    boolean down = InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_RSHIFT);
    boolean pressed = down && !menuKeyDown;
    menuKeyDown = down;
    if (!pressed || !RyznBootstrap.isReady()) return;
    if (client.gui.screen() instanceof RyznModulesScreen modules) {
      if (modules.confirmingReset) modules.confirmingReset = false;
      else modules.onClose();
    } else if (client.player != null && client.gui.screen() == null) {
      client.gui.setScreen(new RyznModulesScreen(null));
    }
  }

  @Override
  protected void init() {
    readSectionOrder();
    layoutSections();
    levelToggle = freshStart = null;
    panelWidgets.clear();
    headerButtons.clear();
    buildingWindow = null;
    switch (selectedTab()) {
      case LEVEL_7 -> {
        beginWindow(ROUTE_WINDOW);
        addMacroCatalog();
      }
      case ROTATIONS -> addRotationControls();
      case PATHFINDER -> addWalkingControls();
      case HUD -> addHudControls();
      case DEVELOPER -> addDeveloperControls();
    }
    finishWindow();
    for (var entry : sections.entrySet()) {
      String title = entry.getKey();
      SectionBounds bounds = entry.getValue();
      var header =
          addWidget(
              new RyznMenuStyle.MenuButton(
                  font,
                  title,
                  bounds.x(),
                  bounds.y(),
                  bounds.width(),
                  HEADER_HEIGHT,
                  true,
                  RyznMenuStyle.ButtonKind.SECTION,
                  () -> {}));
      header.setTooltip(Tooltip.create(Component.literal("Drag to move")));
      headerButtons.put(title, header);
    }

    navigationButtons.clear();
    if (hasSidebar()) {
      SectionBounds sidebar = sidebarBounds();
      int sidebarY = sidebar.y() + 42;
      for (Tab page : sectionOrder) {
        navigationButtons.add(
            addWidget(
                new RyznMenuStyle.MenuButton(
                    font,
                    page.label,
                    sidebar.x() + 7,
                    sidebarY,
                    SIDEBAR_WIDTH - 14,
                    24,
                    openSections.contains(page),
                    RyznMenuStyle.ButtonKind.ROUTE,
                    () -> selectSection(page))));
        sidebarY += 30;
      }
    } else {
      int tabWidth = (width - 2 * MARGIN - 16) / sectionOrder.size();
      int x = MARGIN;
      for (Tab page : sectionOrder) {
        navigationButtons.add(
            addWidget(
                new RyznMenuStyle.MenuButton(
                    font,
                    page.label,
                    x,
                    40,
                    tabWidth,
                    22,
                    openSections.contains(page),
                    RyznMenuStyle.ButtonKind.LIST_ROW,
                    () -> selectSection(page))));
        x += tabWidth + 4;
      }
    }
    if (hasSidebar()) {
      SectionBounds sidebar = sidebarBounds();
      int y = sidebar.y() + sidebar.height() - 31;
      int x = sidebar.x() + 7;
      navigationAction("Save", "Save config", x, y, 29, this::saveGlobalConfig);
      navigationAction("Reset", "Reset config", x + 31, y, 29, this::confirmResetGlobalConfig);
      navigationAction("Layout", "Reset window positions", x + 62, y, 29, this::resetLayout);
      navigationAction("Exit", "Close menu", x + 93, y, 29, this::onClose);
    } else {
      int w = Math.min(62, (width - 2 * MARGIN - 45) / 4);
      int x = width - MARGIN - 4 * w - 6;
      navigationAction("Save", "Save config", x, 10, w, this::saveGlobalConfig);
      navigationAction("Reset", "Reset config", x + w + 2, 10, w, this::confirmResetGlobalConfig);
      navigationAction(
          "Layout", "Reset window positions", x + 2 * (w + 2), 10, w, this::resetLayout);
      navigationAction("Exit", "Close menu", x + 3 * (w + 2), 10, w, this::onClose);
    }
    int dialogX = (width - resetDialogWidth()) / 2;
    int buttonY = height / 2 + 20;
    int buttonWidth = (resetDialogWidth() - 32) / 2;
    cancelReset =
        new RyznMenuStyle.MenuButton(
            font,
            "Cancel",
            dialogX + 12,
            buttonY,
            buttonWidth,
            20,
            false,
            () -> confirmingReset = false);
    applyReset =
        new RyznMenuStyle.MenuButton(
            font,
            "Reset config",
            dialogX + 20 + buttonWidth,
            buttonY,
            buttonWidth,
            20,
            true,
            this::resetGlobalConfig);
    tick();
  }

  private void navigationAction(
      String label, String tooltip, int x, int y, int w, Runnable action) {
    var control = addWidget(new RyznMenuStyle.MenuButton(font, label, x, y, w, 22, false, action));
    control.setTooltip(Tooltip.create(Component.literal(tooltip)));
    navigationButtons.add(control);
  }

  private void resetLayout() {
    var settings = RyznSettings.get();
    settings.ryznMenu().setSectionOrder(List.of());
    settings.ryznMenu().resetPositions();
    windowOrder.clear();
    settings.save();
    scroll = 0;
    rebuildWidgets();
  }

  private void saveGlobalConfig() {
    configStatus = ConfigManager.global().saveNow() ? "Config saved" : "Could not save config";
  }

  boolean sprintInput() {
    boolean down = RyznMenuMovement.down(minecraft, minecraft.options.keySprint);
    boolean pressed = down && !sprintKeyDown;
    sprintKeyDown = down;
    if (!minecraft.options.toggleSprint().get()) return down;
    if (pressed) {
      sprintToggled = !sprintToggled;
      sprintToggleChanged = true;
    }
    return sprintToggled;
  }

  boolean isConfirmingReset() {
    return confirmingReset;
  }

  private void confirmResetGlobalConfig() {
    setFocused(null);
    confirmingReset = true;
  }

  private int resetDialogWidth() {
    return Math.min(280, width - 2 * MARGIN);
  }

  private void resetGlobalConfig() {
    confirmingReset = false;
    RyznBootstrap.failsafeManager().stopAllMacros("config reset");
    if (ConfigManager.global().resetToDefaults()) {
      configStatus = "Defaults restored";
      scroll = 0;
      windowOrder.clear();
    } else {
      configStatus = "Could not reset config";
    }
    readExpansion();
    rebuildWidgets();
  }

  private void readExpansion() {
    var menu = RyznSettings.get().ryznMenu();
    openSections.clear();
    for (Tab page : Tab.values()) {
      if (menu.openSections().contains(page.id())) {
        openSections.add(page);
        break;
      }
    }
    if (openSections.isEmpty()) openSections.add(Tab.LEVEL_7);
  }

  private void saveExpansion() {
    var settings = RyznSettings.get();
    settings.ryznMenu().setOpenSections(openSections.stream().map(Tab::id).toList());
    settings.save();
  }

  private void readSectionOrder() {
    List<String> saved = RyznSettings.get().ryznMenu().sectionOrder();
    List<Tab> ordered = new ArrayList<>(Tab.values().length);
    for (String id : saved) {
      for (Tab page : Tab.values()) {
        if (page.id().equals(id) && !ordered.contains(page)) ordered.add(page);
      }
    }
    for (Tab page : Tab.values()) {
      if (!ordered.contains(page)) ordered.add(page);
    }
    sectionOrder = List.copyOf(ordered);
  }

  private int workspaceTop() {
    return hasSidebar() ? MARGIN : 68;
  }

  private boolean hasSidebar() {
    return width >= 640 && height >= SIDEBAR_MIN_HEIGHT + 2 * MARGIN;
  }

  private int workspaceLeft() {
    return hasSidebar() ? MARGIN + SIDEBAR_WIDTH + 12 : MARGIN;
  }

  private SectionBounds sidebarBounds() {
    Position saved = RyznSettings.get().ryznMenu().position(SIDEBAR_POSITION);
    int x = saved == null ? 0 : Math.round(saved.x() * sidebarTravelX() / 1000f);
    int y = saved == null ? 0 : Math.round(saved.y() * sidebarTravelY() / 1000f);
    return new SectionBounds(MARGIN + x, workspaceTop() + y, SIDEBAR_WIDTH, sidebarHeight());
  }

  private int sidebarHeight() {
    return Math.min(390, Math.max(SIDEBAR_MIN_HEIGHT, height - workspaceTop() - 2 * MARGIN));
  }

  private int sidebarTravelX() {
    return Math.max(0, width - 2 * MARGIN - SIDEBAR_WIDTH);
  }

  private int sidebarTravelY() {
    return Math.max(0, height - workspaceTop() - MARGIN - sidebarHeight());
  }

  private Tab selectedTab() {
    return openSections.iterator().next();
  }

  private Map<String, Integer> windowRows() {
    Map<String, Integer> rows = new LinkedHashMap<>();
    switch (selectedTab()) {
      case LEVEL_7 -> rows.put(ROUTE_WINDOW, 0);
      case ROTATIONS -> {
        var settings = RyznSettings.get().rotations();
        rows.put("Engine", 2);
        if (settings.isAbcEnabled()) {
          rows.put("Speed & smoothing", 4);
          rows.put("Precision", 2);
          rows.put("Planner seed", settings.isRandomSeed() ? 1 : 2);
        }
      }
      case PATHFINDER -> {
        rows.put("Walking basics", 4);
        String group = "Walking basics";
        for (var control : WalkingSettings.Control.values()) {
          String next = walkingGroup(control);
          if (next != null) group = next;
          rows.merge(group, 1, Integer::sum);
        }
        rows.merge("Diagnostics", 1, Integer::sum);
      }
      case HUD -> rows.put("Display", 2);
      case DEVELOPER -> {
        rows.put("Menu input", 1);
        rows.put("GUI action timing", 3);
        rows.put("Auto Harp", 2);
        rows.put("Wheat Breaker", 4);
      }
    }
    return rows;
  }

  private void layoutSections() {
    sections.clear();
    int usableWidth = width - MARGIN - workspaceLeft();
    int columns = usableWidth >= 780 ? 3 : usableWidth >= 490 ? 2 : 1;
    int w = Math.min(320, (usableWidth - (columns - 1) * 12) / columns);
    int[] nextY = new int[columns];
    java.util.Arrays.fill(nextY, workspaceTop());
    int column = 0;
    int bottom = workspaceTop();
    var menu = RyznSettings.get().ryznMenu();
    for (var entry : windowRows().entrySet()) {
      String title = entry.getKey();
      boolean route = title.equals(ROUTE_WINDOW);
      int windowWidth = route ? Math.min(usableWidth, 650) : w;
      int windowHeight =
          route ? routeHeight(windowWidth) : HEADER_HEIGHT + 14 + entry.getValue() * ROTATION_STEP;
      int x = workspaceLeft() + column * (w + 12);
      int y = nextY[column];
      Position saved = menu.position(windowKey(title));
      if (route && saved == null) saved = menu.position(Tab.LEVEL_7.id());
      var bounds = new SectionBounds(x, y, windowWidth, windowHeight);
      if (saved != null) {
        bounds =
            new SectionBounds(
                workspaceLeft() + Math.round(saved.x() * travelX(bounds) / 1000f),
                workspaceTop() + Math.round(saved.y() * headerTravelY() / 1000f),
                windowWidth,
                windowHeight);
      }
      sections.put(title, bounds);
      bottom = Math.max(bottom, bounds.y() + bounds.height());
      nextY[column] += windowHeight + 12;
      column = (column + 1) % columns;
    }
    windowOrder.retainAll(sections.keySet());
    for (String title : sections.keySet()) {
      if (!windowOrder.contains(title)) windowOrder.add(title);
    }
    maxScroll = Math.max(0, bottom + MARGIN - height);
    scroll = Math.clamp(scroll, 0, maxScroll);
    sections.replaceAll(
        (title, b) -> new SectionBounds(b.x(), b.y() - scroll, b.width(), b.height()));
  }

  private String windowKey(String title) {
    return selectedTab().id() + "/" + title;
  }

  private int travelX(SectionBounds bounds) {
    return Math.max(0, width - MARGIN - workspaceLeft() - bounds.width());
  }

  private int headerTravelY() {
    return Math.max(0, height - workspaceTop() - MARGIN - HEADER_HEIGHT);
  }

  private int routeHeight(int panelWidth) {
    var level = RyznSettings.get().level7();
    int checkpoints = AutoLvl7Macro.routeCheckpointOrder().length;
    int stages = AutoLvl7Macro.resumeStageLabels(level.selectedCheckpoint()).length;
    int rows = panelWidth >= 520 ? Math.max(checkpoints, stages) : checkpoints + stages + 2;
    return HEADER_HEIGHT + 47 + rows * 24;
  }

  private void useWindow(String title) {
    SectionBounds bounds = sections.get(title);
    left = bounds.x();
    top = bounds.y() + HEADER_HEIGHT;
    panelWidth = bounds.width();
    panelHeight = bounds.height();
    contentX = left + 9;
    contentWidth = panelWidth - 18;
  }

  private void beginWindow(String title) {
    finishWindow();
    buildingWindow = title;
    firstWindowControl = children().size();
    useWindow(title);
  }

  private void finishWindow() {
    if (buildingWindow == null) return;
    panelWidgets.put(
        buildingWindow,
        children().subList(firstWindowControl, children().size()).stream()
            .filter(AbstractWidget.class::isInstance)
            .map(AbstractWidget.class::cast)
            .toList());
  }

  private void selectSection(Tab page) {
    if (openSections.contains(page)) return;
    openSections.clear();
    openSections.add(page);
    categoryChangedAt = System.nanoTime();
    scroll = 0;
    saveExpansion();
    rebuildWidgets();
  }

  private void addMacroCatalog() {
    var level = RyznSettings.get().level7();
    boolean wide = panelWidth >= 520;
    int checkpointWidth = wide ? (contentWidth - 16) * 2 / 5 : contentWidth;
    int stageX = wide ? contentX + checkpointWidth + 16 : contentX;
    int stageWidth = contentX + contentWidth - stageX;
    int[] order = AutoLvl7Macro.routeCheckpointOrder();
    String[] checkpoints = AutoLvl7Macro.resumeCheckpointLabels();
    String[] stages = AutoLvl7Macro.resumeStageLabels(level.selectedCheckpoint());
    int checkpointY = top + 38;
    int stageY = wide ? checkpointY : checkpointY + (order.length + 2) * 24;

    int actionWidth = (contentWidth - 8) / 2;
    levelToggle =
        button(
            levelLabel(),
            contentX,
            top + 2,
            actionWidth,
            true,
            () ->
                runLevelAction(
                    level.isActive()
                        ? Level7ActionEvent.Action.STOP
                        : Level7ActionEvent.Action.START));
    freshStart =
        button(
            "Start from beginning",
            contentX + actionWidth + 8,
            top + 2,
            contentWidth - actionWidth - 8,
            false,
            () -> runLevelAction(Level7ActionEvent.Action.START_FRESH));

    for (int i = 0; i < order.length; i++) {
      int checkpoint = order[i];
      routeChoice(
          (i + 1) + ". " + checkpoints[checkpoint - 1],
          contentX,
          checkpointY + i * 24,
          checkpointWidth,
          checkpoint == level.selectedCheckpoint(),
          () -> {
            if (checkpoint == level.selectedCheckpoint()) return;
            level.selectCheckpoint(checkpoint);
            rebuildWidgets();
          });
    }
    for (int i = 0; i < stages.length; i++) {
      int stage = i + 1;
      routeChoice(
          stage + ". " + stages[i],
          stageX,
          stageY + i * 24,
          stageWidth,
          stage == level.selectedStage(),
          () -> {
            level.selectStage(stage);
            rebuildWidgets();
          });
    }
  }

  private void routeChoice(
      String label, int x, int y, int width, boolean selected, Runnable action) {
    button(label, x, y, width, selected, RyznMenuStyle.ButtonKind.LIST_ROW, action)
        .setTooltip(Tooltip.create(Component.literal(label)));
  }

  private void addWheatControls(int y) {
    WheatBreakerMacro wheat = RyznSettings.get().wheat();
    sliderAt(
        "Sweep speed", -1, y, 20, 1, 5, "", wheat::getSweepLookSpeed, wheat::setSweepLookSpeed);
    sliderAt(
        "ABCurves tracking",
        -1,
        y + 24,
        20,
        WheatBreakerMacro.MIN_SWEEP_TRACKING_PERCENT,
        WheatBreakerMacro.MAX_SWEEP_TRACKING_PERCENT,
        "%",
        wheat::getSweepTrackingPercent,
        wheat::setSweepTrackingPercent);
    sliderAt(
        "Sweep width",
        -1,
        y + 48,
        20,
        60,
        150,
        "°",
        () -> Math.min(150, wheat.getSweepAmount()),
        wheat::setSweepAmount);
    sliderAt(
        "Crop aim pitch offset",
        -1,
        y + 72,
        20,
        -WheatBreakerMacro.MAX_SWEEP_PITCH_OFFSET,
        WheatBreakerMacro.MAX_SWEEP_PITCH_OFFSET,
        "°",
        wheat::getSweepPitchOffset,
        wheat::setSweepPitchOffset);
  }

  private void runLevelAction(Level7ActionEvent.Action action) {
    if (action != Level7ActionEvent.Action.STOP) {
      if (minecraft.player == null) return;
      minecraft.gui.setScreen(null);
    }
    RyznBootstrap.eventBus().post(new Level7ActionEvent(action));
  }

  private String levelLabel() {
    var level7 = RyznSettings.get().level7();
    if (level7.isActive()) return "Stop Auto Level 7";
    return "Start selected stage";
  }

  private static final int ROTATION_STEP = 24;

  private int beginSettingsWindow(String title) {
    beginWindow(title);
    return 0;
  }

  private void addRotationControls() {
    var settings = RyznSettings.get().rotations();
    int y = beginSettingsWindow("Engine");
    button(
        settings.isAbcEnabled() ? "Rotation mode · ABCurves" : "Rotation mode · Direct",
        contentX,
        settingsY(y),
        contentWidth,
        false,
        () -> {
          settings.setAbcEnabled(!settings.isAbcEnabled());
          RyznSettings.get().save();
          rebuildWidgets();
        });
    if (!settings.isAbcEnabled()) return;
    y = beginSettingsWindow("Speed & smoothing");
    slider(
        "Yaw speed cap (0 = off)",
        -1,
        y,
        RotationSettings.MAX_RATE,
        "°/s",
        settings::getYawCap,
        settings::setYawCap);
    slider(
        "Pitch speed cap (0 = off)",
        -1,
        y + 24,
        RotationSettings.MAX_RATE,
        "°/s",
        settings::getPitchCap,
        settings::setPitchCap);
    slider(
        "Extra smoothing",
        -1,
        y + 48,
        RotationSettings.MAX_DAMPING_MS,
        " ms",
        settings::getDampingMillis,
        settings::setDampingMillis);
    slider(
        "Small-correction deadzone",
        -1,
        y + 72,
        RotationSettings.MAX_DEADZONE_TENTHS,
        " × 0.1°",
        settings::getDeadzoneTenths,
        settings::setDeadzoneTenths);
    y = beginSettingsWindow("Precision");
    toggle("Limit overshoot", -1, y, settings::isLimitOvershoot, settings::setLimitOvershoot);
    toggle(
        "Snap to mouse sensitivity",
        -1,
        y + 24,
        settings::isQuantizeOutput,
        settings::setQuantizeOutput);
    y = beginSettingsWindow("Planner seed");
    toggle("Random seed per turn session", -1, y, settings::isRandomSeed, settings::setRandomSeed);
    if (!settings.isRandomSeed()) {
      slider(
          "Fixed planner seed",
          -1,
          y + 24,
          RotationSettings.MAX_SEED,
          "",
          settings::getAbcSeed,
          settings::setAbcSeed);
    }
  }

  private void addWalkingControls() {
    var settings = RyznSettings.get().pathfinder();
    var walking = settings.walking();
    String group = "Walking basics";
    int y = beginSettingsWindow(group);
    toggle("Sprint", -1, y, settings::isSprint, settings::setSprint);
    toggle(
        "Constant jumping", -1, y + 24, settings::isConstantJumping, settings::setConstantJumping);
    toggle(
        "Draw full route", -1, y + 48, settings::isPathDrawInstant, settings::setPathDrawInstant);
    toggle("Ryzn Trace", 0, y + 72, DebugControl::isEnabled, DebugControl::setEnabled);
    toggle(
        "Log to file only", 1, y + 72, settings::isPathDebugLogOnly, settings::setPathDebugLogOnly);
    for (var control : WalkingSettings.Control.values()) {
      String next = walkingGroup(control);
      if (next != null) {
        group = next;
        y = beginSettingsWindow(group);
      }
      if (control.min == 0 && control.max == 1) {
        toggle(
            control.label,
            -1,
            y,
            () -> walking.enabled(control),
            value -> walking.set(control, value ? 1 : 0));
      } else {
        slider(
            control.label,
            -1,
            y,
            control.min,
            control.max,
            control.unit,
            () -> walking.get(control),
            value -> walking.set(control, value));
      }
      y += ROTATION_STEP;
    }
    button(
        "Reset walking controls",
        contentX,
        settingsY(y),
        contentWidth,
        false,
        () -> {
          walking.reset();
          RyznSettings.get().save();
          rebuildWidgets();
        });
  }

  private static String walkingGroup(WalkingSettings.Control control) {
    return switch (control) {
      case PREVIEW -> "Movement";
      case GAZE_LEAD -> "Gaze";
      case YAW_CAP -> "Route camera";
      case ARRIVAL_COAST -> "Arrival";
      case DROP_LEAD -> "Terrain & recovery";
      case TRACE -> "Diagnostics";
      default -> null;
    };
  }

  private void addHudControls() {
    var cfg = RyznSettings.get();
    int y = beginSettingsWindow("Display");
    toggle("Status HUD", -1, y, cfg.statusHud()::isEnabled, cfg.statusHud()::setEnabled);
    toggle(
        "Show coordinates",
        -1,
        y + 24,
        cfg.statusCoords()::isEnabled,
        cfg.statusCoords()::setEnabled);
  }

  private void addDeveloperControls() {
    var cfg = RyznSettings.get();
    var settings = cfg.pathfinder();
    int y = beginSettingsWindow("Menu input");
    toggle("Move in menu", -1, y, cfg.ryznMenu()::isMoveInMenu, cfg.ryznMenu()::setMoveInMenu);
    y = beginSettingsWindow("GUI action timing");
    toggle(
        "Custom GUI delay",
        -1,
        y,
        settings::isGuiActionDelayCustom,
        settings::setGuiActionDelayCustom);
    slider(
        "GUI delay min",
        -1,
        y + 24,
        2000,
        " ms",
        settings::getGuiActionDelayMinMs,
        settings::setGuiActionDelayMinMs);
    slider(
        "GUI delay max",
        -1,
        y + 48,
        2000,
        " ms",
        settings::getGuiActionDelayMaxMs,
        settings::setGuiActionDelayMaxMs);
    y = beginSettingsWindow("Auto Harp");
    var harp = RyznBootstrap.macroManager().getByClass(AfkAutoHarpMacro.class);
    slider("Harp retry delay", -1, y, 10, 100, " ticks", harp::getRetryDelay, harp::setRetryDelay);
    slider("Harp minimum score", -1, y + 24, 50, 100, "%", harp::getMinScore, harp::setMinScore);
    y = beginSettingsWindow("Wheat Breaker");
    addWheatControls(settingsY(y));
  }

  private int settingsY(int offset) {
    return top + 7 + offset;
  }

  private int settingsRowHeight() {
    return 20;
  }

  private void toggle(String label, int column, int y, BooleanSupplier get, Consumer<Boolean> set) {
    int rowWidth = column < 0 ? contentWidth : (contentWidth - 6) / 2;
    addRenderableWidget(
        new RyznMenuStyle.MenuButton(
            font,
            label + (get.getAsBoolean() ? "  ·  On" : "  ·  Off"),
            contentX + Math.max(0, column) * (rowWidth + 6),
            settingsY(y),
            rowWidth,
            settingsRowHeight(),
            get.getAsBoolean(),
            RyznMenuStyle.ButtonKind.SWITCH,
            () -> {
              set.accept(!get.getAsBoolean());
              RyznSettings.get().save();
              rebuildWidgets();
            }));
  }

  private void slider(String label, int column, int y, IntSupplier get, IntConsumer set) {
    slider(label, column, y, 200, "%", get, set);
  }

  private void slider(
      String label, int column, int y, int max, String unit, IntSupplier get, IntConsumer set) {
    slider(label, column, y, 0, max, unit, get, set);
  }

  private void slider(
      String label,
      int column,
      int y,
      int min,
      int max,
      String unit,
      IntSupplier get,
      IntConsumer set) {
    sliderAt(label, column, settingsY(y), settingsRowHeight(), min, max, unit, get, set);
  }

  private void sliderAt(
      String label,
      int column,
      int y,
      int height,
      int min,
      int max,
      String unit,
      IntSupplier get,
      IntConsumer set) {
    int rowWidth = column < 0 ? contentWidth : (contentWidth - 6) / 2;
    int range = max - min;
    var control =
        new AbstractSliderButton(
            contentX + Math.max(0, column) * (rowWidth + 6),
            y,
            rowWidth,
            height,
            Component.literal(label + "  " + get.getAsInt() + unit),
            (double) (get.getAsInt() - min) / range) {
          @Override
          public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            if (isFocused() || isHovered()) {
              RyznMenuStyle.rounded(g, x, y, w, h, RyznTheme.CARD);
            }
            int filled = (int) Math.round((w - 8) * ((double) (get.getAsInt() - min) / range));
            int trackY = y + h - 4;
            RyznMenuStyle.rounded(g, x + 4, trackY, w - 8, 2, RyznTheme.EDGE);
            RyznMenuStyle.rounded(g, x + 4, trackY, filled, 2, RyznTheme.ACCENT);
            int thumbX = Math.clamp(x + 4 + filled - 2, x + 3, x + w - 7);
            RyznMenuStyle.rounded(g, thumbX, trackY - 1, 4, 4, RyznTheme.INK);
            String valueLabel = get.getAsInt() + unit;
            int badgeWidth = Math.min(w / 2, Math.max(21, font.width(valueLabel) + 10));
            int badgeX = x + w - badgeWidth - 4;
            int textHeight = Math.max(9, h - 6);
            RyznMenuStyle.rounded(g, badgeX, y, badgeWidth, textHeight, RyznTheme.CARD);
            RyznMenuStyle.controlText(
                g,
                font,
                label,
                x + 5,
                y,
                w - badgeWidth - 14,
                textHeight,
                RyznTheme.INK,
                false,
                false);
            RyznMenuStyle.controlText(
                g,
                font,
                valueLabel,
                badgeX + 2,
                y,
                badgeWidth - 4,
                textHeight,
                RyznTheme.INK,
                false,
                true);
          }

          @Override
          protected void updateMessage() {
            setMessage(Component.literal(label + "  " + get.getAsInt() + unit));
          }

          @Override
          protected void applyValue() {
            set.accept(min + (int) Math.round(value * range));
            RyznSettings.get().save();
          }
        };
    if (font.width(control.getMessage().getString()) > rowWidth - 8) {
      control.setTooltip(Tooltip.create(Component.literal(label)));
    }
    addRenderableWidget(control);
  }

  @Override
  public void tick() {
    if (levelToggle != null) {
      levelToggle.setMessage(Component.literal(levelLabel()));
      levelToggle.active = minecraft.player != null;
      freshStart.active = minecraft.player != null && !RyznSettings.get().level7().isActive();
      freshStart.visible = !RyznSettings.get().level7().isActive();
    }
  }

  private RyznMenuStyle.MenuButton button(
      String label, int x, int y, int w, boolean primary, Runnable action) {
    return button(label, x, y, w, primary, RyznMenuStyle.ButtonKind.ACTION, action);
  }

  private RyznMenuStyle.MenuButton button(
      String label,
      int x,
      int y,
      int w,
      boolean primary,
      RyznMenuStyle.ButtonKind kind,
      Runnable action) {
    return addRenderableWidget(
        new RyznMenuStyle.MenuButton(font, label, x, y, w, 20, primary, kind, action));
  }

  private static float easeIn(long start, int durationMs) {
    float elapsed = Math.clamp((System.nanoTime() - start) / (durationMs * 1_000_000f), 0f, 1f);
    float remaining = 1f - elapsed;
    return 1f - remaining * remaining * remaining;
  }

  @Override
  public void extractBackground(GuiGraphicsExtractor g, int x, int y, float tick) {
    extractBlurredBackground(g);
    int opacity = Math.round(0x99 * easeIn(openedAt, 180));
    g.fill(0, 0, width, height, opacity << 24 | 0x06070a);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
    int menuMouseX = confirmingReset ? -1 : mouseX;
    int menuMouseY = confirmingReset ? -1 : mouseY;
    g.enableScissor(workspaceLeft(), workspaceTop(), width, height);
    try {
      String hovered = sectionAt(menuMouseX, menuMouseY);
      for (String title : windowOrder) {
        SectionBounds bounds = sections.get(title);
        if (bounds.y() + bounds.height() <= workspaceTop() || bounds.y() >= height) continue;
        useWindow(title);
        int panelMouseX = title.equals(hovered) ? mouseX : -1;
        int panelMouseY = title.equals(hovered) ? mouseY : -1;
        if (bounds.height() > HEADER_HEIGHT) {
          drawPanelBackground(g);
          g.enableScissor(
              bounds.x(), top, bounds.x() + bounds.width(), bounds.y() + bounds.height());
          try {
            for (var control : panelWidgets.getOrDefault(title, List.of())) {
              control.extractRenderState(g, panelMouseX, panelMouseY, tick);
            }
            drawPanelLabels(g, title);

          } finally {
            g.disableScissor();
          }
        }
        headerButtons.get(title).extractRenderState(g, panelMouseX, panelMouseY, tick);
        float reveal = easeIn(categoryChangedAt, 200);
        if (reveal < 1f) {
          int accentWidth = Math.round(Math.min(64, bounds.width() - 18) * reveal);
          int alpha = Math.round(160 * (1f - reveal));
          int ruleY = bounds.y() + HEADER_HEIGHT - 1;
          g.fill(
              bounds.x() + 9,
              ruleY,
              bounds.x() + 9 + accentWidth,
              ruleY + 1,
              alpha << 24 | (RyznTheme.ACCENT & 0x00ffffff));
        }
      }
      if (maxScroll > 0) {
        int track = height - workspaceTop() - MARGIN;
        int thumb = Math.max(18, track * track / (track + maxScroll));
        int thumbY = workspaceTop() + (track - thumb) * scroll / maxScroll;
        RyznMenuStyle.rounded(g, width - 5, thumbY, 3, thumb, RyznTheme.MUTED);
      }
    } finally {
      g.disableScissor();
    }
    if (!hasSidebar()) {
      RyznMenuStyle.crown(g, MARGIN + 2, 11, 24, 18);
      RyznMenuStyle.text(
          g,
          font,
          configStatus,
          MARGIN + 34,
          17,
          Math.max(0, width - 2 * MARGIN - 292),
          RyznTheme.MUTED,
          false,
          false);
    }
    if (hasSidebar()) {
      SectionBounds sidebar = sidebarBounds();
      int x = sidebar.x();
      int y = sidebar.y();
      RyznMenuStyle.darkGlass(g, x, y, SIDEBAR_WIDTH, sidebar.height());
      RyznMenuStyle.crown(g, x + 12, y + 8, 24, 18);
      RyznMenuStyle.text(
          g, font, "RYZN", x + 44, y + 13, SIDEBAR_WIDTH - 56, RyznTheme.INK, true, false);
      g.fill(x + 10, y + 34, x + SIDEBAR_WIDTH - 10, y + 35, RyznTheme.EDGE);
      int footer = y + sidebar.height() - 40;
      RyznMenuStyle.text(
          g,
          font,
          configStatus,
          x + 10,
          footer - 14,
          SIDEBAR_WIDTH - 20,
          RyznTheme.MUTED,
          false,
          false);
      g.fill(x + 10, footer, x + SIDEBAR_WIDTH - 10, footer + 1, RyznTheme.EDGE);
    }
    for (var control : navigationButtons)
      control.extractRenderState(g, menuMouseX, menuMouseY, tick);
    if (confirmingReset) drawResetDialog(g, mouseX, mouseY, tick);
  }

  private void drawResetDialog(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
    int dialogWidth = resetDialogWidth();
    int x = (width - dialogWidth) / 2;
    int y = height / 2 - 48;
    g.nextStratum();
    g.fill(0, 0, width, height, 0x54090a12);
    RyznMenuStyle.glass(g, x, y, dialogWidth, 100, RyznTheme.TOP, RyznTheme.BOTTOM, 9);
    RyznMenuStyle.text(
        g,
        font,
        "Reset all Ryzn settings?",
        x + 12,
        y + 12,
        dialogWidth - 24,
        RyznTheme.ACCENT,
        true,
        true);
    RyznMenuStyle.text(
        g,
        font,
        "Active macros will stop and saved settings",
        x + 12,
        y + 32,
        dialogWidth - 24,
        RyznTheme.INK,
        false,
        true);
    RyznMenuStyle.text(
        g,
        font,
        "will be replaced with defaults.",
        x + 12,
        y + 45,
        dialogWidth - 24,
        RyznTheme.MUTED,
        false,
        true);
    cancelReset.extractRenderState(g, mouseX, mouseY, tick);
    applyReset.extractRenderState(g, mouseX, mouseY, tick);
  }

  private void drawPanelBackground(GuiGraphicsExtractor g) {
    int panelTop = top - HEADER_HEIGHT;
    RyznMenuStyle.darkGlass(g, left, panelTop, panelWidth, panelHeight);
  }

  private void drawPanelLabels(GuiGraphicsExtractor g, String title) {
    switch (title) {
      case ROUTE_WINDOW -> {
        boolean wide = panelWidth >= 520;
        int stageX = wide ? contentX + (contentWidth - 16) * 2 / 5 + 16 : contentX;
        int stageY =
            wide ? top + 26 : top + 26 + (AutoLvl7Macro.routeCheckpointOrder().length + 2) * 24;
        line(g, "Route checkpoints", top + 26);
        RyznMenuStyle.text(
            g,
            font,
            "Stages in selected checkpoint",
            stageX,
            stageY,
            contentX + contentWidth - stageX,
            RyznTheme.MUTED,
            false,
            false);
      }
      case "Engine" -> line(g, "ABCurves engine · " + AbcRotation.status(), top + 36);
      default -> {}
    }
  }

  private void line(GuiGraphicsExtractor g, String text, int y) {
    RyznMenuStyle.text(g, font, text, contentX, y, contentWidth, RyznTheme.MUTED, false, false);
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (confirmingReset) {
      if (!cancelReset.mouseClicked(event, doubleClick))
        applyReset.mouseClicked(event, doubleClick);
      return true;
    }
    if (hasSidebar()) {
      SectionBounds sidebar = sidebarBounds();
      if (sidebar.contains(event.x(), event.y())) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && event.y() < sidebar.y() + 34) {
          draggingSidebar = true;
          dragOffsetX = (int) event.x() - sidebar.x();
          dragOffsetY = (int) event.y() - sidebar.y();
          setFocused(null);
          setDragging(true);
          return true;
        }
        for (var control : navigationButtons) {
          if (control.mouseClicked(event, doubleClick)) return true;
        }
        return true;
      }
    }
    if (event.y() < workspaceTop() || event.x() < workspaceLeft()) {
      for (var control : navigationButtons) {
        if (control.mouseClicked(event, doubleClick)) return true;
      }
      return false;
    }
    String title = sectionAt(event.x(), event.y());
    if (title == null) {
      setFocused(null);
      return false;
    }
    SectionBounds bounds = sections.get(title);
    windowOrder.remove(title);
    windowOrder.add(title);
    if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
        && bounds.containsHeader(event.x(), event.y())) {
      pressedHeader = title;
      pressX = (int) event.x();
      pressY = (int) event.y();
      dragOffsetX = pressX - bounds.x();
      dragOffsetY = pressY - bounds.y();
      draggingHeader = false;
      setFocused(null);
      setDragging(true);
      return true;
    }

    for (var control : panelWidgets.getOrDefault(title, List.of())) {
      if (control.mouseClicked(event, doubleClick)) {
        if (children().contains(control)) {
          setFocused(control);
          setDragging(event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
        return true;
      }
    }
    setFocused(null);
    return true;
  }

  private String sectionAt(double x, double y) {
    if (x < workspaceLeft() || y < workspaceTop() || y >= height) return null;
    if (hasSidebar() && sidebarBounds().contains(x, y)) return null;
    for (int i = windowOrder.size() - 1; i >= 0; i--) {
      String title = windowOrder.get(i);
      if (sections.get(title).contains(x, y)) return title;
    }
    return null;
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
    if (confirmingReset) return true;
    if (draggingSidebar) {
      int x = Math.clamp((int) event.x() - dragOffsetX - MARGIN, 0, sidebarTravelX());
      int y = Math.clamp((int) event.y() - dragOffsetY - workspaceTop(), 0, sidebarTravelY());
      RyznSettings.get()
          .ryznMenu()
          .setPosition(
              SIDEBAR_POSITION,
              new Position(
                  sidebarTravelX() == 0 ? 0 : Math.round(x * 1000f / sidebarTravelX()),
                  sidebarTravelY() == 0 ? 0 : Math.round(y * 1000f / sidebarTravelY())));
      rebuildWidgets();
      setDragging(true);
      return true;
    }
    if (pressedHeader == null) return super.mouseDragged(event, dx, dy);
    if (!draggingHeader && Math.abs(pressX - event.x()) + Math.abs(pressY - event.y()) < 4)
      return true;
    draggingHeader = true;
    SectionBounds bounds = sections.get(pressedHeader);
    int availableX = travelX(bounds);
    int availableY = headerTravelY();
    int x = Math.clamp((int) event.x() - dragOffsetX - workspaceLeft(), 0, availableX);
    int y = Math.clamp((int) event.y() - dragOffsetY - workspaceTop() + scroll, 0, availableY);
    var position =
        new Position(
            availableX == 0 ? 0 : Math.round(x * 1000f / availableX),
            headerTravelY() == 0 ? 0 : Math.round(y * 1000f / headerTravelY()));
    var settings = RyznSettings.get().ryznMenu();
    if (!position.equals(settings.position(windowKey(pressedHeader)))) {
      settings.setPosition(windowKey(pressedHeader), position);
      rebuildWidgets();
      setDragging(true);
    }
    return true;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    if (confirmingReset) return true;
    if (draggingSidebar && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
      draggingSidebar = false;
      setDragging(false);
      RyznSettings.get().save();
      return true;
    }
    if (pressedHeader == null || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
      return super.mouseReleased(event);
    }
    pressedHeader = null;
    setDragging(false);
    if (draggingHeader) {
      draggingHeader = false;
      RyznSettings.get().save();
    }
    return true;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    if (confirmingReset) return true;
    if (scrollY == 0 || y < workspaceTop()) return false;
    int next = Math.clamp(scroll - (int) Math.round(scrollY * 34), 0, maxScroll);
    if (next == scroll) return false;
    scroll = next;
    rebuildWidgets();
    return true;
  }

  @Override
  public void removed() {
    if (draggingHeader || draggingSidebar) RyznSettings.get().save();
    super.removed();
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (confirmingReset) {
      if (event.key() == GLFW.GLFW_KEY_ESCAPE) confirmingReset = false;
      return true;
    }
    return event.key() == GLFW.GLFW_KEY_SPACE || super.keyPressed(event);
  }

  @Override
  public boolean isPauseScreen() {
    return parent != null && parent.isPauseScreen();
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
    // Minecraft restores its pre-screen toggle when gameplay regains focus.
    // Apply the menu's choice after that restoration so the next input tick agrees.
    if (minecraft.gui.screen() == null
        && sprintToggleChanged
        && minecraft.options.toggleSprint().get()
        && minecraft.options.keySprint.isDown() != sprintToggled) {
      minecraft.options.keySprint.setDown(true);
    }
  }
}
