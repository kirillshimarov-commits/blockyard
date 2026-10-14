package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.HomeView;
import app.blockyard.ui.core.SettingsView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.particle.ParticlesMode;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.network.message.ChatVisibility;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.Util;
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
        super(Text.literal("Настройки"));
        this.parent = parent;
        this.section = section;
    }

    @Override
    protected void init() {
        super.init();
        initialGuiScale = client.options.getGuiScale().getValue();
        sections = null;
    }

    @Override
    protected void backdrop(DrawContext ctx, McCanvas c, float delta) {
        if (inGame()) {
            applyBlur();
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
        return client.world != null;
    }

    public void done() {
        close();
    }

    public void openTab(int tab) {
        if (tab == HomeView.PACKS) {
            section = PACKS;
            state.scroll = 0;
            return;
        }
        apply();
        client.setScreen(new HomeScreen(tab));
    }

    public String user() {
        return client.getSession().getUsername();
    }

    // ================================================================ rows

    private SettingsView.Slider intSlider(String label, String sub, SimpleOption<Integer> option, int min, int max, IntFunction<String> text) {
        return new SettingsView.Slider(label, sub,
                () -> (option.getValue() - min) / (double) (max - min),
                v -> option.setValue((int) Math.round(min + v * (max - min))),
                () -> text.apply(option.getValue()));
    }

    private SettingsView.Slider percent(String label, String sub, SimpleOption<Double> option) {
        return new SettingsView.Slider(label, sub, option::getValue, option::setValue, () -> Math.round(option.getValue() * 100) + "%");
    }

    private SettingsView.Toggle toggle(String label, String sub, SimpleOption<Boolean> option) {
        return new SettingsView.Toggle(label, sub, option::getValue, option::setValue);
    }

    private <E> SettingsView.Choice choice(String label, String sub, SimpleOption<E> option, E[] values, String[] names) {
        return new SettingsView.Choice(label, sub, names, () -> {
            for (int i = 0; i < values.length; i++) if (values[i] == option.getValue()) return i;
            return 0;
        }, i -> option.setValue(values[i]));
    }

    private static SettingsView.Group group(String title, List<SettingsView.Row> rows) {
        return new SettingsView.Group(title, rows);
    }

    private List<SettingsView.Section> build() {
        GameOptions o = client.options;
        String now = "Изменения применяются сразу";
        List<SettingsView.Section> out = new ArrayList<>();

        out.add(new SettingsView.Section("Основное", "user", now, List.of(
                group("Игра", List.of(
                        intSlider("Поле зрения", null, o.getFov(), 30, 110, v -> v == 70 ? "Обычное" : v == 110 ? "Quake Pro" : String.valueOf(v)),
                        choice("Ведущая рука", null, o.getMainArm(), new Arm[]{Arm.LEFT, Arm.RIGHT}, new String[]{"Левая", "Правая"}),
                        toggle("Субтитры", "Подписи ко всем звукам", o.getShowSubtitles()),
                        toggle("Автопрыжок", null, o.getAutoJump()))),
                group("Ещё", List.of(
                        new SettingsView.Button("Все настройки Minecraft", "Обычный экран настроек игры", null, () -> {
                            BlockyardUi.vanillaOptions = true;
                            client.setScreen(new OptionsScreen(this, o));
                        }),
                        new SettingsView.Button("Папка игры", "Сохранения, скриншоты, конфиги", null,
                                () -> Util.getOperatingSystem().open(client.runDirectory)))))
                , false));

        int maxScale = Math.max(1, client.getWindow().calculateScaleFactor(0, client.forcesUnicodeFont()));
        String[] scales = new String[Math.min(maxScale, 6) + 1];
        scales[0] = "Авто";
        for (int i = 1; i < scales.length; i++) scales[i] = String.valueOf(i);
        out.add(new SettingsView.Section("Графика", "monitor", now, List.of(
                group("Экран", List.of(
                        intSlider("Дальность прорисовки", null, o.getViewDistance(), 2, 32, v -> v + " чанков"),
                        intSlider("Дальность симуляции", null, o.getSimulationDistance(), 5, 32, v -> v + " чанков"),
                        intSlider("Макс. частота кадров", null, o.getMaxFps(), 10, 260, v -> v >= 260 ? "Без огр." : v + " FPS"),
                        toggle("Полный экран", null, o.getFullscreen()),
                        toggle("Вертикальная синхронизация", null, o.getEnableVsync()),
                        new SettingsView.Choice("Размер интерфейса", null, scales,
                                () -> Math.min(scales.length - 1, o.getGuiScale().getValue()), i -> o.getGuiScale().setValue(i)))),
                group("Качество", List.of(
                        choice("Графика", null, o.getGraphicsMode(), new GraphicsMode[]{GraphicsMode.FAST, GraphicsMode.FANCY, GraphicsMode.FABULOUS},
                                new String[]{"Быстрая", "Детальная", "Лучшая"}),
                        choice("Облака", null, o.getCloudRenderMode(), new CloudRenderMode[]{CloudRenderMode.OFF, CloudRenderMode.FAST, CloudRenderMode.FANCY},
                                new String[]{"Выкл.", "Быстрые", "Детальные"}),
                        choice("Частицы", null, o.getParticles(), new ParticlesMode[]{ParticlesMode.MINIMAL, ParticlesMode.DECREASED, ParticlesMode.ALL},
                                new String[]{"Мин.", "Меньше", "Все"}),
                        toggle("Мягкое освещение", null, o.getAo()),
                        toggle("Тени сущностей", null, o.getEntityShadows()),
                        new SettingsView.Slider("Яркость", "Как у Mojang: «Мрачно» — «Ярко»", () -> o.getGamma().getValue(), v -> o.getGamma().setValue(v),
                                () -> o.getGamma().getValue() <= 0.001 ? "Мрачно" : o.getGamma().getValue() >= 0.999 ? "Ярко" : Math.round(o.getGamma().getValue() * 100) + "%"),
                        new SettingsView.Button("Расширенные настройки графики", "Sodium и другие моды графики", null,
                                () -> client.setScreen(new VideoOptionsScreen(this, client, o)))))), false));

        List<SettingsView.Row> sounds = new ArrayList<>();
        for (SoundCategory c : SoundCategory.values()) sounds.add(percent(soundName(c), null, o.getSoundVolumeOption(c)));
        int half = (sounds.size() + 1) / 2;
        List<SettingsView.Row> right = new ArrayList<>(sounds.subList(half, sounds.size()));
        right.add(toggle("Субтитры", "Подписи ко всем звукам", o.getShowSubtitles()));
        out.add(new SettingsView.Section("Музыка и звуки", "sound", now, List.of(
                group("Громкость", new ArrayList<>(sounds.subList(0, half))),
                group("Ещё", right)), false));

        out.add(new SettingsView.Section("Управление", "keyboard", now, List.of(
                group("Мышь", List.of(
                        new SettingsView.Slider("Чувствительность", null, () -> o.getMouseSensitivity().getValue(), v -> o.getMouseSensitivity().setValue(v),
                                () -> Math.round(o.getMouseSensitivity().getValue() * 200) + "%"),
                        toggle("Инверсия мыши", null, o.getInvertYMouse()),
                        new SettingsView.Button("Назначение клавиш", "Все клавиши управления", null, () -> client.setScreen(new KeybindsScreen(this, o))))),
                group("Движение", List.of(
                        toggle("Автопрыжок", null, o.getAutoJump()),
                        new SettingsView.Choice("Присед", null, new String[]{"Удерживать", "Переключать"},
                                () -> o.getSneakToggled().getValue() ? 1 : 0, i -> o.getSneakToggled().setValue(i == 1)),
                        new SettingsView.Choice("Бег", null, new String[]{"Удерживать", "Переключать"},
                                () -> o.getSprintToggled().getValue() ? 1 : 0, i -> o.getSprintToggled().setValue(i == 1))))), false));

        out.add(new SettingsView.Section("Чат", "chat", now, List.of(
                group("Чат", List.of(
                        choice("Показывать", null, o.getChatVisibility(), new ChatVisibility[]{ChatVisibility.FULL, ChatVisibility.SYSTEM, ChatVisibility.HIDDEN},
                                new String[]{"Всё", "Команды", "Ничего"}),
                        toggle("Цвета в чате", null, o.getChatColors()),
                        toggle("Ссылки в чате", null, o.getChatLinks()))),
                group("Вид", List.of(
                        percent("Прозрачность текста", null, o.getChatOpacity()),
                        percent("Фон текста", null, o.getTextBackgroundOpacity()),
                        percent("Размер чата", null, o.getChatScale())))), false));

        List<SettingsView.Row> parts = new ArrayList<>();
        for (PlayerModelPart part : PlayerModelPart.values()) {
            parts.add(new SettingsView.Toggle(partName(part), null, () -> o.isPlayerModelPartEnabled(part), v -> o.setPlayerModelPart(part, v)));
        }
        int ph = (parts.size() + 1) / 2;
        out.add(new SettingsView.Section("Скин", "shirt", "Что видно на вашем скине", List.of(
                group("Части скина", new ArrayList<>(parts.subList(0, ph))),
                group(" ", new ArrayList<>(parts.subList(ph, parts.size())))), false));

        out.add(new SettingsView.Section("Язык", "language", "Язык игры меняется сразу", languages(), false));

        out.add(new SettingsView.Section("Доступность", "eye", now, List.of(
                group("Экран", List.of(
                        toggle("Субтитры", "Подписи ко всем звукам", o.getShowSubtitles()),
                        toggle("Без вспышек молний", null, o.getHideLightningFlashes()),
                        percent("Эффекты искажения", "Тошнота и порталы", o.getDistortionEffectScale()),
                        percent("Эффекты поля зрения", "Бег и зелья скорости", o.getFovEffectScale()))),
                group("Ещё", List.of(
                        new SettingsView.Button("Все настройки доступности", "Экранный диктор, контраст и другое", null,
                                () -> client.setScreen(new AccessibilityOptionsScreen(this, o)))))), false));

        out.add(new SettingsView.Section("Ресурспаки", "layers", "Включите паки и нажмите «Применить»", packs(), true));
        return out;
    }

    private List<SettingsView.Group> languages() {
        Map<String, LanguageDefinition> all = client.getLanguageManager().getAllLanguages();
        String current = client.getLanguageManager().getLanguage();
        List<SettingsView.Row> left = new ArrayList<>(), rightCol = new ArrayList<>();
        int i = 0;
        int half = (all.size() + 1) / 2;
        for (Map.Entry<String, LanguageDefinition> e : all.entrySet()) {
            String code = e.getKey();
            String name = e.getValue().getDisplayText().getString();
            SettingsView.Row row = new SettingsView.Button(name, null, code.equals(current) ? "Выбран" : null, () -> {
                if (code.equals(client.getLanguageManager().getLanguage())) return;
                client.getLanguageManager().setLanguage(code);
                client.options.language = code;
                client.options.write();
                client.reloadResources();
                sections = null;
            });
            (i++ < half ? left : rightCol).add(row);
        }
        return List.of(group("Языки", left), group(" ", rightCol));
    }

    private List<SettingsView.Group> packs() {
        ResourcePackManager manager = client.getResourcePackManager();
        if (packsEnabled == null) packsEnabled = new LinkedHashSet<>(manager.getEnabledIds());
        List<SettingsView.Row> actions = List.of(
                new SettingsView.Button("Применить", "Перезагрузит ресурсы игры", packsDirty ? "Есть изменения" : null, () -> {
                    applyPacks();
                    sections = null;
                }),
                new SettingsView.Button("Открыть папку", "Положите сюда .zip с паком", null,
                        () -> Util.getOperatingSystem().open(client.getResourcePackDir().toFile())));
        List<ResourcePackProfile> profiles = new ArrayList<>(manager.getProfiles());
        profiles.sort((p1, p2) -> {
            boolean e1 = packsEnabled.contains(p1.getId()), e2 = packsEnabled.contains(p2.getId());
            if (e1 != e2) return e1 ? -1 : 1;
            return p1.getDisplayName().getString().compareToIgnoreCase(p2.getDisplayName().getString());
        });
        List<SettingsView.Row> rows = new ArrayList<>();
        for (ResourcePackProfile p : profiles) {
            String desc = p.getDescription().getString().replace('\n', ' ').trim();
            rows.add(new SettingsView.Toggle(p.getDisplayName().getString(), desc.isEmpty() ? null : desc,
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
        ResourcePackManager manager = client.getResourcePackManager();
        manager.setEnabledProfiles(packsEnabled);
        client.options.refreshResourcePacks(manager);
        packsDirty = false;
    }

    private static String soundName(SoundCategory c) {
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void apply() {
        client.options.write();
        if (packsDirty) applyPacks();
        if (client.options.getGuiScale().getValue() != initialGuiScale) {
            initialGuiScale = client.options.getGuiScale().getValue();
            client.onResolutionChanged();
        }
    }

    @Override
    public void close() {
        apply();
        client.setScreen(parent);
    }
}
