package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.HomeView;
import app.blockyard.ui.core.SettingsView;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/** Game settings: {@link SettingsView} with rows bound to the game's options. */
public class SettingsScreen extends UiScreen implements SettingsView.Model {
    public static final int GENERAL = 0, VIDEO = 1, SOUND = 2, CONTROLS = 3, CHAT = 4, SKIN = 5, LANGUAGE = 6, ACCESS = 7, PACKS = 8;

    private final Screen parent;
    private int section;
    private final SettingsView.State state = new SettingsView.State();
    private int initialGuiScale;
    private List<SettingsView.Section> sections;

    // Resource packs being edited; applied with the button or on close.
    private Set<String> packsEnabled = null;
    private boolean packsDirty = false;

    public SettingsScreen(Screen parent, int section) {
        super(Component.literal("Настройки"));
        this.parent = parent;
        this.section = section;
    }

    @Override
    protected void init() {
        super.init();
        initialGuiScale = minecraft.options.guiScale().get();
        sections = null;
    }

    @Override
    protected void backdrop(GuiGraphicsExtractor ctx, McCanvas c, float delta) {
        if (inGame()) {
            extractBlurredBackground(ctx);
            c.fillScreen(0x99060510);
        }
    }

    @Override
    protected void draw(Frame f, float delta) {
        SettingsView.draw(f, this, state);
    }

    @Override
    protected void released() {
        state.dragging = null;
    }

    // ================================================================ model

    public List<SettingsView.Section> sections() {
        if (sections == null) sections = build();
        return sections;
    }

    public int section() {
        return section;
    }

    public void section(int i) {
        section = i;
    }

    public boolean inGame() {
        return minecraft.level != null;
    }

    public void done() {
        onClose();
    }

    public void openTab(int tab) {
        if (tab == HomeView.PACKS) {
            section = PACKS;
            state.scroll = 0;
            return;
        }
        apply();
        minecraft.gui.setScreen(new HomeScreen(tab));
    }

    public String user() {
        return minecraft.getUser().getName();
    }

    // ================================================================ rows

    private SettingsView.Slider intSlider(String label, String sub, OptionInstance<Integer> option, int min, int max, IntFunction<String> text) {
        return new SettingsView.Slider(label, sub,
                () -> (option.get() - min) / (double) (max - min),
                v -> option.set((int) Math.round(min + v * (max - min))),
                () -> text.apply(option.get()));
    }

    private SettingsView.Slider percent(String label, String sub, OptionInstance<Double> option) {
        return new SettingsView.Slider(label, sub, option::get, option::set, () -> Math.round(option.get() * 100) + "%");
    }

    private SettingsView.Toggle toggle(String label, String sub, OptionInstance<Boolean> option) {
        return new SettingsView.Toggle(label, sub, option::get, option::set);
    }

    private <E> SettingsView.Choice choice(String label, String sub, OptionInstance<E> option, E[] values, String[] names) {
        return new SettingsView.Choice(label, sub, names, () -> {
            for (int i = 0; i < values.length; i++) if (values[i] == option.get()) return i;
            return 0;
        }, i -> option.set(values[i]));
    }

    private static SettingsView.Group group(String title, List<SettingsView.Row> rows) {
        return new SettingsView.Group(title, rows);
    }

    private List<SettingsView.Section> build() {
        Options o = minecraft.options;
        String now = "Изменения применяются сразу";
        List<SettingsView.Section> out = new ArrayList<>();

        out.add(new SettingsView.Section("Основное", "user", now, List.of(
                group("Игра", List.of(
                        intSlider("Поле зрения", null, o.fov(), 30, 110, v -> v == 70 ? "Обычное" : v == 110 ? "Quake Pro" : String.valueOf(v)),
                        choice("Ведущая рука", null, o.mainHand(), new HumanoidArm[]{HumanoidArm.LEFT, HumanoidArm.RIGHT}, new String[]{"Левая", "Правая"}),
                        toggle("Субтитры", "Подписи ко всем звукам", o.showSubtitles()),
                        toggle("Автопрыжок", null, o.autoJump()))),
                group("Ещё", List.of(
                        new SettingsView.Button("Все настройки Minecraft", "Обычный экран настроек игры", null, () -> {
                            BlockyardUi.vanillaOptions = true;
                            minecraft.gui.setScreen(new OptionsScreen(this, o, false));
                        }),
                        new SettingsView.Button("Папка игры", "Сохранения, скриншоты, конфиги", null,
                                () -> Util.getPlatform().openFile(minecraft.gameDirectory)))))
                , false));

        int maxScale = Math.max(1, minecraft.getWindow().calculateScale(0, minecraft.isEnforceUnicode()));
        String[] scales = new String[Math.min(maxScale, 6) + 1];
        scales[0] = "Авто";
        for (int i = 1; i < scales.length; i++) scales[i] = String.valueOf(i);
        out.add(new SettingsView.Section("Графика", "monitor", now, List.of(
                group("Экран", List.of(
                        intSlider("Дальность прорисовки", null, o.renderDistance(), 2, 32, v -> v + " чанков"),
                        intSlider("Дальность симуляции", null, o.simulationDistance(), 5, 32, v -> v + " чанков"),
                        intSlider("Макс. частота кадров", null, o.framerateLimit(), 10, 260, v -> v >= 260 ? "Без огр." : v + " FPS"),
                        toggle("Полный экран", null, o.fullscreen()),
                        toggle("Вертикальная синхронизация", null, o.enableVsync()),
                        new SettingsView.Choice("Размер интерфейса", null, scales,
                                () -> Math.min(scales.length - 1, o.guiScale().get()), i -> o.guiScale().set(i)))),
                group("Качество", List.of(
                        choice("Графика", null, o.graphicsPreset(), new GraphicsPreset[]{GraphicsPreset.FAST, GraphicsPreset.FANCY, GraphicsPreset.FABULOUS, GraphicsPreset.CUSTOM},
                                new String[]{"Быстрая", "Детальная", "Лучшая", "Своя"}),
                        choice("Облака", null, o.cloudStatus(), new CloudStatus[]{CloudStatus.OFF, CloudStatus.FAST, CloudStatus.FANCY},
                                new String[]{"Выкл.", "Быстрые", "Детальные"}),
                        choice("Частицы", null, o.particles(), new ParticleStatus[]{ParticleStatus.MINIMAL, ParticleStatus.DECREASED, ParticleStatus.ALL},
                                new String[]{"Мин.", "Меньше", "Все"}),
                        toggle("Мягкое освещение", null, o.ambientOcclusion()),
                        toggle("Тени сущностей", null, o.entityShadows()),
                        new SettingsView.Slider("Яркость", "Как у Mojang: «Мрачно» — «Ярко»", () -> o.gamma().get(), v -> o.gamma().set(v),
                                () -> o.gamma().get() <= 0.001 ? "Мрачно" : o.gamma().get() >= 0.999 ? "Ярко" : Math.round(o.gamma().get() * 100) + "%"),
                        new SettingsView.Button("Расширенные настройки графики", "Sodium и другие моды графики", null,
                                () -> minecraft.gui.setScreen(new VideoSettingsScreen(this, minecraft, o)))))), false));

        List<SettingsView.Row> sounds = new ArrayList<>();
        for (SoundSource c : SoundSource.values()) sounds.add(percent(soundName(c), null, o.getSoundSourceOptionInstance(c)));
        int half = (sounds.size() + 1) / 2;
        List<SettingsView.Row> right = new ArrayList<>(sounds.subList(half, sounds.size()));
        right.add(toggle("Субтитры", "Подписи ко всем звукам", o.showSubtitles()));
        out.add(new SettingsView.Section("Музыка и звуки", "sound", now, List.of(
                group("Громкость", new ArrayList<>(sounds.subList(0, half))),
                group("Ещё", right)), false));

        out.add(new SettingsView.Section("Управление", "keyboard", now, List.of(
                group("Мышь", List.of(
                        new SettingsView.Slider("Чувствительность", null, () -> o.sensitivity().get(), v -> o.sensitivity().set(v),
                                () -> Math.round(o.sensitivity().get() * 200) + "%"),
                        toggle("Инверсия мыши", null, o.invertMouseY()),
                        new SettingsView.Button("Назначение клавиш", "Все клавиши управления", null, () -> minecraft.gui.setScreen(new KeyBindsScreen(this, o))))),
                group("Движение", List.of(
                        toggle("Автопрыжок", null, o.autoJump()),
                        new SettingsView.Choice("Присед", null, new String[]{"Удерживать", "Переключать"},
                                () -> o.toggleCrouch().get() ? 1 : 0, i -> o.toggleCrouch().set(i == 1)),
                        new SettingsView.Choice("Бег", null, new String[]{"Удерживать", "Переключать"},
                                () -> o.toggleSprint().get() ? 1 : 0, i -> o.toggleSprint().set(i == 1))))), false));

        out.add(new SettingsView.Section("Чат", "chat", now, List.of(
                group("Чат", List.of(
                        choice("Показывать", null, o.chatVisibility(), new ChatVisiblity[]{ChatVisiblity.FULL, ChatVisiblity.SYSTEM, ChatVisiblity.HIDDEN},
                                new String[]{"Всё", "Команды", "Ничего"}),
                        toggle("Цвета в чате", null, o.chatColors()),
                        toggle("Ссылки в чате", null, o.chatLinks()))),
                group("Вид", List.of(
                        percent("Прозрачность текста", null, o.chatOpacity()),
                        percent("Фон текста", null, o.textBackgroundOpacity()),
                        percent("Размер чата", null, o.chatScale())))), false));

        List<SettingsView.Row> parts = new ArrayList<>();
        for (PlayerModelPart part : PlayerModelPart.values()) {
            parts.add(new SettingsView.Toggle(partName(part), null, () -> o.isModelPartEnabled(part), v -> o.setModelPart(part, v)));
        }
        int ph = (parts.size() + 1) / 2;
        out.add(new SettingsView.Section("Скин", "shirt", "Что видно на вашем скине", List.of(
                group("Части скина", new ArrayList<>(parts.subList(0, ph))),
                group(" ", new ArrayList<>(parts.subList(ph, parts.size())))), false));

        out.add(new SettingsView.Section("Язык", "language", "Язык игры меняется сразу", languages(), false));

        out.add(new SettingsView.Section("Доступность", "eye", now, List.of(
                group("Экран", List.of(
                        toggle("Субтитры", "Подписи ко всем звукам", o.showSubtitles()),
                        toggle("Без вспышек молний", null, o.hideLightningFlash()),
                        percent("Эффекты искажения", "Тошнота и порталы", o.screenEffectScale()),
                        percent("Эффекты поля зрения", "Бег и зелья скорости", o.fovEffectScale()))),
                group("Ещё", List.of(
                        new SettingsView.Button("Все настройки доступности", "Экранный диктор, контраст и другое", null,
                                () -> minecraft.gui.setScreen(new AccessibilityOptionsScreen(this, o)))))), false));

        out.add(new SettingsView.Section("Ресурспаки", "layers", "Включите паки и нажмите «Применить»", packs(), true));
        return out;
    }

    private List<SettingsView.Group> languages() {
        Map<String, LanguageInfo> all = minecraft.getLanguageManager().getLanguages();
        String current = minecraft.getLanguageManager().getSelected();
        List<SettingsView.Row> left = new ArrayList<>(), rightCol = new ArrayList<>();
        int i = 0;
        int half = (all.size() + 1) / 2;
        for (Map.Entry<String, LanguageInfo> e : all.entrySet()) {
            String code = e.getKey();
            String name = e.getValue().toComponent().getString();
            SettingsView.Row row = new SettingsView.Button(name, null, code.equals(current) ? "Выбран" : null, () -> {
                if (code.equals(minecraft.getLanguageManager().getSelected())) return;
                minecraft.getLanguageManager().setSelected(code);
                minecraft.options.languageCode = code;
                minecraft.options.save();
                minecraft.reloadResourcePacks();
                sections = null;
            });
            (i++ < half ? left : rightCol).add(row);
        }
        return List.of(group("Языки", left), group(" ", rightCol));
    }

    private List<SettingsView.Group> packs() {
        PackRepository manager = minecraft.getResourcePackRepository();
        if (packsEnabled == null) packsEnabled = new LinkedHashSet<>(manager.getSelectedIds());
        List<SettingsView.Row> actions = List.of(
                new SettingsView.Button("Применить", "Перезагрузит ресурсы игры", packsDirty ? "Есть изменения" : null, () -> {
                    applyPacks();
                    sections = null;
                }),
                new SettingsView.Button("Открыть папку", "Положите сюда .zip с паком", null,
                        () -> Util.getPlatform().openPath(minecraft.getResourcePackDirectory())));
        List<Pack> profiles = new ArrayList<>(manager.getAvailablePacks());
        profiles.sort((p1, p2) -> {
            boolean e1 = packsEnabled.contains(p1.getId()), e2 = packsEnabled.contains(p2.getId());
            if (e1 != e2) return e1 ? -1 : 1;
            return p1.getTitle().getString().compareToIgnoreCase(p2.getTitle().getString());
        });
        List<SettingsView.Row> rows = new ArrayList<>();
        for (Pack p : profiles) {
            String desc = p.getDescription().getString().replace('\n', ' ').trim();
            rows.add(new SettingsView.Toggle(p.getTitle().getString(), desc.isEmpty() ? null : desc,
                    () -> packsEnabled.contains(p.getId()), v -> {
                        if (p.isRequired()) return;
                        if (v) packsEnabled.add(p.getId());
                        else packsEnabled.remove(p.getId());
                        packsDirty = true;
                        sections = null;
                    }));
        }
        return List.of(group("", actions), group("Паки", rows));
    }

    private void applyPacks() {
        if (!packsDirty) return;
        PackRepository manager = minecraft.getResourcePackRepository();
        manager.setSelected(packsEnabled);
        minecraft.options.updateResourcePacks(manager);
        packsDirty = false;
    }

    private static String soundName(SoundSource c) {
        return switch (c) {
            case MASTER -> "Общая громкость";
            case MUSIC -> "Музыка";
            case RECORDS -> "Музыкальные блоки";
            case WEATHER -> "Погода";
            case BLOCKS -> "Блоки";
            case HOSTILE -> "Враждебные существа";
            case NEUTRAL -> "Дружелюбные существа";
            case PLAYERS -> "Игроки";
            case AMBIENT -> "Окружение";
            case VOICE -> "Голос и речь";
            // UI sounds, a category of its own from 1.21.6.
            default -> "Интерфейс";
        };
    }

    private static String partName(PlayerModelPart p) {
        return switch (p) {
            case CAPE -> "Плащ";
            case JACKET -> "Куртка";
            case LEFT_SLEEVE -> "Левый рукав";
            case RIGHT_SLEEVE -> "Правый рукав";
            case LEFT_PANTS_LEG -> "Левая штанина";
            case RIGHT_PANTS_LEG -> "Правая штанина";
            case HAT -> "Головной убор";
        };
    }

    // ================================================================ input

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        state.scroll -= (float) verticalAmount * 16;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int keyCode = input.key();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(input);
    }

    private void apply() {
        minecraft.options.save();
        if (packsDirty) applyPacks();
        if (minecraft.options.guiScale().get() != initialGuiScale) {
            initialGuiScale = minecraft.options.guiScale().get();
            minecraft.resizeGui();
        }
    }

    @Override
    public void onClose() {
        apply();
        minecraft.gui.setScreen(parent);
    }
}
