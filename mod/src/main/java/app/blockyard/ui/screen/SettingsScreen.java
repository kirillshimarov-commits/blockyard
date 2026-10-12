package app.blockyard.ui.screen;

import app.blockyard.ui.BlockyardUi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.ParticlesMode;
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
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Game settings in the launcher's style: sections on the left, sliders, switches and choices on the right. */
public class SettingsScreen extends BlockyardScreen {
    static final int GENERAL = 0, VIDEO = 1, SOUND = 2, CONTROLS = 3, CHAT = 4, SKIN = 5, LANGUAGE = 6, ACCESS = 7, PACKS = 8;
    private static final String[] SECTIONS = {"Основное", "Графика", "Музыка и звуки", "Управление", "Чат", "Скин", "Язык", "Доступность", "Ресурспаки"};
    private static final String[] ICONS = {"user", "cube", "globe", "gear", "users", "user", "globe", "user", "cube"};

    private final Screen parent;
    private int section;
    private float scroll = 0;
    private SliderRow dragging = null;
    private int initialGuiScale;

    // Resource packs being edited; applied with the button.
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
    }

    private boolean inGame() {
        return client.world != null;
    }

    @Override
    protected void drawBackdrop(DrawContext ctx, float delta) {
        if (inGame()) {
            applyBlur(delta);
            ctx.fill(0, 0, width, height, 0x99060510);
        } else {
            super.drawBackdrop(ctx, delta);
        }
    }

    // ================================================================ rows

    private interface Row {
        float height();

        void draw(DrawContext ctx, float x, float y, float w);
    }

    private final class SliderRow implements Row {
        final String label;
        final DoubleSupplier get;
        final DoubleConsumer set;
        final Supplier<String> value;
        float trackX, trackW;

        SliderRow(String label, DoubleSupplier get, DoubleConsumer set, Supplier<String> value) {
            this.label = label;
            this.get = get;
            this.set = set;
            this.value = value;
        }

        public float height() {
            return 12.5f;
        }

        public void draw(DrawContext ctx, float x, float y, float w) {
            rowBg(ctx, x, y, w, height());
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, label, Ui.BODY, w * 0.42f), Ui.BODY, x + 4.5f, y + 4.2f, 0xFFFFFFFF);
            float valW = 20;
            trackW = Math.min(95, w * 0.42f);
            trackX = x + w - 4.5f - valW - 4 - trackW;
            float ty = y + height() / 2;
            double v = Math.max(0, Math.min(1, get.getAsDouble()));
            Ui.round(ctx, trackX, ty - 0.75f, trackW, 1.5f, 0.75f, 0x1FFFFFFF);
            Ui.round(ctx, trackX, ty - 0.75f, (float) (trackW * v), 1.5f, 0.75f, Ui.argb(1, BlockyardUi.accent));
            float kx = trackX + (float) (trackW * v);
            Ui.shadow(ctx, kx - 2.75f, ty - 2.75f + 0.6f, 5.5f, 5.5f, 2.5f, 0.6f);
            Ui.circle(ctx, kx, ty, 2.75f, dragging == this || hover(trackX - 3, y, trackW + 6, height()) ? 0xFFFFFFFF : 0xFFF2F0FA);
            String text = value.get();
            Ui.text(ctx, textRenderer, text, Ui.BOLD, x + w - 4.5f - Ui.width(textRenderer, text, Ui.BOLD), y + 4.2f, 0xFFFFFFFF);
            region(trackX - 3, y, trackW + 6, height(), () -> {
                dragging = this;
                drag(mx);
            });
        }

        void drag(float mouseX) {
            set.accept(Math.max(0, Math.min(1, (mouseX - trackX) / trackW)));
        }
    }

    private final class ToggleRow implements Row {
        final String label, sub;
        final Supplier<Boolean> get;
        final java.util.function.Consumer<Boolean> set;

        ToggleRow(String label, String sub, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
            this.label = label;
            this.sub = sub;
            this.get = get;
            this.set = set;
        }

        public float height() {
            return sub == null ? 12.5f : 15;
        }

        public void draw(DrawContext ctx, float x, float y, float w) {
            rowBg(ctx, x, y, w, height());
            labels(ctx, label, sub, x, y, w * 0.7f);
            boolean on = get.get();
            float tw = 13, th = 7.5f, tx = x + w - 4.5f - tw, ty = y + (height() - th) / 2;
            Ui.round(ctx, tx, ty, tw, th, th / 2, on ? Ui.argb(1, BlockyardUi.accent) : 0x26FFFFFF);
            Ui.circle(ctx, on ? tx + tw - th / 2 : tx + th / 2, ty + th / 2, 3, 0xFFFFFFFF);
            region(x, y, w, height(), () -> {
                click();
                set.accept(!on);
            });
        }
    }

    private final class ChoiceRow implements Row {
        final String label;
        final String[] names;
        final IntSupplier get;
        final IntConsumer set;

        ChoiceRow(String label, String[] names, IntSupplier get, IntConsumer set) {
            this.label = label;
            this.names = names;
            this.get = get;
            this.set = set;
        }

        public float height() {
            return 14;
        }

        public void draw(DrawContext ctx, float x, float y, float w) {
            rowBg(ctx, x, y, w, height());
            Ui.text(ctx, textRenderer, label, Ui.BODY, x + 4.5f, y + 5, 0xFFFFFFFF);
            float total = 2;
            for (String n : names) total += Ui.width(textRenderer, n, Ui.BODY) + 9;
            float sx = x + w - 4.5f - total, sy = y + 2;
            Ui.round(ctx, sx, sy, total, 10, 3.5f, 0x10FFFFFF);
            float cx = sx + 1;
            int current = get.getAsInt();
            for (int i = 0; i < names.length; i++) {
                float bw = Ui.width(textRenderer, names[i], Ui.BODY) + 9;
                boolean on = i == current;
                if (on) Ui.round(ctx, cx, sy + 1, bw, 8, 2.5f, 0xFFFFFFFF);
                else if (hover(cx, sy + 1, bw, 8)) Ui.round(ctx, cx, sy + 1, bw, 8, 2.5f, 0x14FFFFFF);
                Ui.text(ctx, textRenderer, names[i], on ? Ui.BOLD : Ui.BODY, cx + 4.5f, sy + 3, on ? Ui.argb(1, INK) : 0xB8FFFFFF);
                final int target = i;
                region(cx, sy + 1, bw, 8, () -> {
                    click();
                    set.accept(target);
                });
                cx += bw;
            }
        }
    }

    private final class ButtonRow implements Row {
        final String label, sub;
        final Runnable action;

        ButtonRow(String label, String sub, Runnable action) {
            this.label = label;
            this.sub = sub;
            this.action = action;
        }

        public float height() {
            return sub == null ? 12.5f : 15;
        }

        public void draw(DrawContext ctx, float x, float y, float w) {
            boolean hov = hover(x, y, w, height());
            Ui.round(ctx, x, y, w, height(), 4, hov ? 0x1AFFFFFF : 0x0DFFFFFF);
            labels(ctx, label, sub, x, y, w - 20);
            Ui.text(ctx, textRenderer, "›", Ui.BOLD, x + w - 8, y + height() / 2 - 2.2f, Ui.argb(hov ? 1 : 0.6f, WHITE));
            region(x, y, w, height(), () -> {
                click();
                action.run();
            });
        }
    }

    private void rowBg(DrawContext ctx, float x, float y, float w, float h) {
        Ui.round(ctx, x, y, w, h, 4, 0x0AFFFFFF);
    }

    private void labels(DrawContext ctx, String label, String sub, float x, float y, float w) {
        if (sub == null) {
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, label, Ui.BODY, w), Ui.BODY, x + 4.5f, y + 4.2f, 0xFFFFFFFF);
        } else {
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, label, Ui.BODY, w), Ui.BODY, x + 4.5f, y + 3, 0xFFFFFFFF);
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, sub, Ui.SMALL, w), Ui.SMALL, x + 4.5f, y + 8.6f, Ui.argb(0.55f, WHITE));
        }
    }

    // ---------------------------------------------------------------- option helpers

    private SliderRow intSlider(String label, SimpleOption<Integer> option, int min, int max, java.util.function.IntFunction<String> text) {
        return new SliderRow(label,
                () -> (option.getValue() - min) / (double) (max - min),
                v -> option.setValue((int) Math.round(min + v * (max - min))),
                () -> text.apply(option.getValue()));
    }

    private SliderRow percent(String label, SimpleOption<Double> option) {
        return new SliderRow(label, option::getValue, option::setValue, () -> Math.round(option.getValue() * 100) + "%");
    }

    private ToggleRow toggle(String label, String sub, SimpleOption<Boolean> option) {
        return new ToggleRow(label, sub, option::getValue, option::setValue);
    }

    private <E> ChoiceRow choice(String label, SimpleOption<E> option, E[] values, String[] names) {
        return new ChoiceRow(label, names, () -> {
            for (int i = 0; i < values.length; i++) if (values[i] == option.getValue()) return i;
            return 0;
        }, i -> option.setValue(values[i]));
    }

    private record Group(String title, List<Row> rows) {}

    private List<Group> groups() {
        GameOptions o = client.options;
        List<Group> g = new ArrayList<>();
        switch (section) {
            case GENERAL -> {
                g.add(new Group("Игра", List.of(
                        intSlider("Поле зрения", o.getFov(), 30, 110, v -> v == 70 ? "Обычное" : v == 110 ? "Quake Pro" : String.valueOf(v)),
                        choice("Ведущая рука", o.getMainArm(), new Arm[]{Arm.LEFT, Arm.RIGHT}, new String[]{"Левая", "Правая"}),
                        toggle("Субтитры", "Подписи ко всем звукам", o.getShowSubtitles()))));
                g.add(new Group("Ещё", List.of(
                        new ButtonRow("Все настройки Minecraft", "Обычный экран настроек игры", () -> {
                            BlockyardUi.vanillaOptions = true;
                            client.setScreen(new OptionsScreen(this, o));
                        }),
                        new ButtonRow("Папка игры", "Сохранения, скриншоты, конфиги", () -> Util.getOperatingSystem().open(client.runDirectory)))));
            }
            case VIDEO -> {
                int maxScale = client.getWindow().calculateScaleFactor(0, client.forcesUnicodeFont());
                g.add(new Group("Экран", List.of(
                        intSlider("Дальность прорисовки", o.getViewDistance(), 2, 32, v -> v + " чанков"),
                        intSlider("Дальность симуляции", o.getSimulationDistance(), 5, 32, v -> v + " чанков"),
                        intSlider("Макс. частота кадров", o.getMaxFps(), 10, 260, v -> v >= 260 ? "Без огр." : v + " FPS"),
                        toggle("Полный экран", null, o.getFullscreen()),
                        toggle("Вертикальная синхронизация", null, o.getEnableVsync()),
                        intSlider("Размер интерфейса", o.getGuiScale(), 0, Math.max(1, maxScale), v -> v == 0 ? "Авто" : String.valueOf(v)))));
                g.add(new Group("Качество", List.of(
                        choice("Графика", o.getGraphicsMode(), new GraphicsMode[]{GraphicsMode.FAST, GraphicsMode.FANCY, GraphicsMode.FABULOUS}, new String[]{"Быстрая", "Детальная", "Лучшая"}),
                        choice("Облака", o.getCloudRenderMode(), new CloudRenderMode[]{CloudRenderMode.OFF, CloudRenderMode.FAST, CloudRenderMode.FANCY}, new String[]{"Выкл.", "Быстрые", "Детальные"}),
                        choice("Частицы", o.getParticles(), new ParticlesMode[]{ParticlesMode.MINIMAL, ParticlesMode.DECREASED, ParticlesMode.ALL}, new String[]{"Мин.", "Меньше", "Все"}),
                        toggle("Мягкое освещение", null, o.getAo()),
                        percent("Яркость", o.getGamma()),
                        toggle("Тени сущностей", null, o.getEntityShadows()),
                        new ButtonRow("Расширенные настройки графики", "Sodium и другие моды графики", () -> client.setScreen(new VideoOptionsScreen(this, client, o))))));
            }
            case SOUND -> {
                List<Row> rows = new ArrayList<>();
                for (SoundCategory c : SoundCategory.values()) rows.add(percent(soundName(c), o.getSoundVolumeOption(c)));
                int half = (rows.size() + 1) / 2;
                g.add(new Group("Громкость", rows.subList(0, half)));
                List<Row> right = new ArrayList<>(rows.subList(half, rows.size()));
                right.add(toggle("Субтитры", "Подписи ко всем звукам", o.getShowSubtitles()));
                g.add(new Group(" ", right));
            }
            case CONTROLS -> {
                g.add(new Group("Мышь", List.of(
                        new SliderRow("Чувствительность", () -> o.getMouseSensitivity().getValue(), v -> o.getMouseSensitivity().setValue(v),
                                () -> Math.round(o.getMouseSensitivity().getValue() * 200) + "%"),
                        toggle("Инверсия мыши", null, o.getInvertYMouse()))));
                g.add(new Group("Движение", List.of(
                        toggle("Автопрыжок", null, o.getAutoJump()),
                        new ChoiceRow("Присед", new String[]{"Удерживать", "Переключать"}, () -> o.getSneakToggled().getValue() ? 1 : 0, i -> o.getSneakToggled().setValue(i == 1)),
                        new ChoiceRow("Бег", new String[]{"Удерживать", "Переключать"}, () -> o.getSprintToggled().getValue() ? 1 : 0, i -> o.getSprintToggled().setValue(i == 1)),
                        new ButtonRow("Назначение клавиш", "Все клавиши управления", () -> client.setScreen(new KeybindsScreen(this, o))))));
            }
            case CHAT -> {
                g.add(new Group("Чат", List.of(
                        choice("Показывать", o.getChatVisibility(), new ChatVisibility[]{ChatVisibility.FULL, ChatVisibility.SYSTEM, ChatVisibility.HIDDEN}, new String[]{"Всё", "Команды", "Ничего"}),
                        toggle("Цвета в чате", null, o.getChatColors()),
                        toggle("Ссылки в чате", null, o.getChatLinks()))));
                g.add(new Group("Вид", List.of(
                        percent("Прозрачность текста", o.getChatOpacity()),
                        percent("Фон текста", o.getTextBackgroundOpacity()),
                        percent("Размер чата", o.getChatScale()))));
            }
            case SKIN -> {
                List<Row> rows = new ArrayList<>();
                for (PlayerModelPart part : PlayerModelPart.values()) {
                    rows.add(new ToggleRow(partName(part), null, () -> o.isPlayerModelPartEnabled(part), v -> o.togglePlayerModelPart(part, v)));
                }
                int half = (rows.size() + 1) / 2;
                g.add(new Group("Части скина", rows.subList(0, half)));
                g.add(new Group(" ", rows.subList(half, rows.size())));
            }
            case ACCESS -> {
                g.add(new Group("Экран", List.of(
                        toggle("Субтитры", "Подписи ко всем звукам", o.getShowSubtitles()),
                        toggle("Без вспышек молний", null, o.getHideLightningFlashes()),
                        percent("Эффекты искажения", o.getDistortionEffectScale()),
                        percent("Эффекты поля зрения", o.getFovEffectScale()))));
                g.add(new Group("Ещё", List.of(
                        new ButtonRow("Все настройки доступности", "Экранный диктор, контраст и другое", () -> client.setScreen(new AccessibilityOptionsScreen(this, o))))));
            }
            default -> {
            }
        }
        return g;
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

    // ================================================================ rendering

    @Override
    protected void draw(DrawContext ctx, float delta) {
        float top;
        if (inGame()) {
            Ui.text(ctx, textRenderer, "Настройки", Ui.HEAD, 12, 11, 0xFFFFFFFF);
            pillButton(ctx, "Готово", vw - 12 - (Ui.width(textRenderer, "Готово", Ui.BOLD) + 30), 9, 12, true, 1, this::close);
            top = 28;
        } else {
            drawTopBar(ctx, -1, true, 1, t -> client.setScreen(new HomeScreen(t == HomeScreen.TAB_PACKS ? HomeScreen.TAB_SINGLE : t)));
            top = 28;
        }
        float bottom = CANVAS_H - 10;
        float sideW = 75;
        panel(ctx, 10, top, sideW, bottom - top, 6.5f, 1);
        float sy = top + 3.5f;
        for (int i = 0; i < SECTIONS.length; i++) {
            boolean on = i == section;
            boolean hov = hover(13.5f, sy, sideW - 7, 13);
            if (on) Ui.round(ctx, 13.5f, sy, sideW - 7, 13, 3.5f, 0x1AFFFFFF);
            else if (hov) Ui.round(ctx, 13.5f, sy, sideW - 7, 13, 3.5f, 0x0DFFFFFF);
            Ui.round(ctx, 17.5f, sy + 2.75f, 7.5f, 7.5f, 2.25f, on ? Ui.argb(1, BlockyardUi.accent) : 0x14FFFFFF);
            Ui.icon(ctx, ICONS[i], 18.75f, sy + 4, 5, on ? 0xFFFFFFFF : 0xB0FFFFFF);
            Ui.text(ctx, textRenderer, SECTIONS[i], Ui.BODY, 28.5f, sy + 4.4f, on ? 0xFFFFFFFF : 0xB8FFFFFF);
            final int target = i;
            region(13.5f, sy, sideW - 7, 13, () -> {
                if (section == target) return;
                click();
                section = target;
                scroll = 0;
            });
            sy += 15;
        }
        Ui.text(ctx, textRenderer, "Esc — назад · Blockyard UI", Ui.SMALL, 17.5f, bottom - 9, Ui.argb(0.45f, WHITE));

        float px = 10 + sideW + 6, pw = vw - px - 10, ph = bottom - top;
        panel(ctx, px, top, pw, ph, 6.5f, 1);
        Ui.text(ctx, textRenderer, SECTIONS[section], Ui.HEAD, px + 8.5f, top + 8, 0xFFFFFFFF);
        Ui.text(ctx, textRenderer, section == PACKS ? "Включите паки и нажмите «Применить»" : "Изменения применяются сразу",
                Ui.BODY, px + 8.5f, top + 18.5f, Ui.argb(0.55f, WHITE));

        float cy = top + 30;
        ctx.enableScissor(0, (int) (cy * k), width, (int) ((bottom - 4) * k));
        if (section == LANGUAGE) drawLanguages(ctx, px + 8.5f, cy - scroll, pw - 17, bottom - 4 - cy);
        else if (section == PACKS) drawPacks(ctx, px + 8.5f, cy - scroll, pw - 17, bottom - 4 - cy);
        else drawGroups(ctx, px + 8.5f, cy - scroll, pw - 17);
        ctx.disableScissor();
    }

    private void drawGroups(DrawContext ctx, float x, float y, float w) {
        List<Group> gs = groups();
        boolean twoCols = w > 230 && gs.size() > 1;
        float colW = twoCols ? (w - 6.5f) / 2 : w;
        float[] colY = {y, y};
        for (int i = 0; i < gs.size(); i++) {
            int col = twoCols ? i % 2 : 0;
            float gx = x + col * (colW + 6.5f);
            float gy = colY[col];
            Group group = gs.get(i);
            if (!group.title().isBlank()) Ui.text(ctx, textRenderer, group.title().toUpperCase(), Ui.SMALL, gx, gy, Ui.argb(0.55f, WHITE));
            gy += 6;
            for (Row row : group.rows()) {
                row.draw(ctx, gx, gy, colW);
                gy += row.height() + 2;
            }
            colY[col] = gy + 6.5f;
        }
    }

    private void drawLanguages(DrawContext ctx, float x, float y, float w, float h) {
        Map<String, LanguageDefinition> all = client.getLanguageManager().getAllLanguages();
        String current = client.getLanguageManager().getLanguage();
        int cols = w > 230 ? 3 : 2;
        float cw = (w - (cols - 1) * 3) / cols, rh = 11;
        int i = 0;
        for (Map.Entry<String, LanguageDefinition> e : all.entrySet()) {
            float lx = x + (i % cols) * (cw + 3), ly = y + (i / cols) * (rh + 2);
            i++;
            if (ly + rh < y + scroll - 2 || ly > y + scroll + h + 40) continue;
            boolean on = e.getKey().equals(current);
            boolean hov = hover(lx, ly, cw, rh);
            Ui.round(ctx, lx, ly, cw, rh, 3.5f, on ? 0xFFFFFFFF : hov ? 0x1AFFFFFF : 0x0AFFFFFF);
            String name = Ui.fit(textRenderer, e.getValue().getDisplayText().getString(), Ui.BODY, cw - 8);
            Ui.text(ctx, textRenderer, name, on ? Ui.BOLD : Ui.BODY, lx + 4, ly + 3.4f, on ? Ui.argb(1, INK) : 0xFFFFFFFF);
            final String code = e.getKey();
            region(lx, ly, cw, rh, () -> {
                if (code.equals(client.getLanguageManager().getLanguage())) return;
                click();
                client.getLanguageManager().setLanguage(code);
                client.options.language = code;
                client.options.write();
                client.reloadResources();
            });
        }
        lastContentHeight = ((all.size() + cols - 1) / cols) * (rh + 2);
    }

    private float lastContentHeight = 0;

    private void drawPacks(DrawContext ctx, float x, float y, float w, float h) {
        ResourcePackManager manager = client.getResourcePackManager();
        if (packsEnabled == null) packsEnabled = new LinkedHashSet<>(manager.getEnabledIds());
        float bw = pillButton(ctx, packsDirty ? "Применить" : "Применено", x, y, 11, packsDirty, 1, this::applyPacks);
        pillButton(ctx, "Открыть папку", x + bw + 3, y, 11, false, 1,
                () -> Util.getOperatingSystem().open(client.getResourcePackDir().toFile()));
        float ry = y + 16;
        List<ResourcePackProfile> profiles = new ArrayList<>(manager.getProfiles());
        // Enabled first (top of the stack first), then the rest by name.
        profiles.sort((p1, p2) -> {
            boolean e1 = packsEnabled.contains(p1.getId()), e2 = packsEnabled.contains(p2.getId());
            if (e1 != e2) return e1 ? -1 : 1;
            return p1.getDisplayName().getString().compareToIgnoreCase(p2.getDisplayName().getString());
        });
        for (ResourcePackProfile p : profiles) {
            boolean on = packsEnabled.contains(p.getId());
            boolean locked = p.isRequired();
            float rh = 15;
            if (ry + rh >= y + scroll - 2 && ry <= y + scroll + h + 40) {
                rowBg(ctx, x, ry, w, rh);
                labels(ctx, p.getDisplayName().getString(), p.getDescription().getString().replace('\n', ' '), x, ry, w - 30);
                float tw = 13, th = 7.5f, tx = x + w - 4.5f - tw, ty = ry + (rh - th) / 2;
                Ui.round(ctx, tx, ty, tw, th, th / 2, on ? Ui.argb(locked ? 0.5f : 1, BlockyardUi.accent) : 0x26FFFFFF);
                Ui.circle(ctx, on ? tx + tw - th / 2 : tx + th / 2, ty + th / 2, 3, 0xFFFFFFFF);
                if (!locked) {
                    region(x, ry, w, rh, () -> {
                        click();
                        if (!packsEnabled.remove(p.getId())) packsEnabled.add(p.getId());
                        packsDirty = true;
                    });
                }
            }
            ry += rh + 2;
        }
        lastContentHeight = ry - y;
    }

    private void applyPacks() {
        if (!packsDirty) return;
        ResourcePackManager manager = client.getResourcePackManager();
        manager.setEnabledProfiles(packsEnabled);
        client.options.refreshResourcePacks(manager);
        packsDirty = false;
    }

    // ================================================================ input

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging != null) {
            dragging.drag((float) (mouseX / k));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll = Math.max(0, Math.min(scroll - (float) verticalAmount * 16, Math.max(0, lastContentHeight - 150)));
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

    @Override
    public void close() {
        client.options.write();
        if (client.options.getGuiScale().getValue() != initialGuiScale) client.onResolutionChanged();
        if (packsDirty) applyPacks();
        client.setScreen(parent);
    }
}
