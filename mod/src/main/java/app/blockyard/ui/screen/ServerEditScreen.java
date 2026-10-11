package app.blockyard.ui.screen;

import app.blockyard.ui.BlockyardUi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Add or edit a server, drawn as a glass dialog over the blurred home screen. */
public class ServerEditScreen extends Screen {
    private static final float CANVAS_H = 270f;
    private static final int WHITE = 0xFFFFFF, INK = 0x0B0A12;

    private final HomeScreen parent;
    /** Index in the server list, or -1 to add a new server. */
    private final int index;
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder address = new StringBuilder();
    private ServerInfo.ResourcePackPolicy policy = ServerInfo.ResourcePackPolicy.PROMPT;
    private int focus = 0;
    private final long openedAt = System.currentTimeMillis();
    private float k = 1, vw = 480;
    private float closeX, closeY, fieldX, fieldW, nameY, addrY, packY, saveY;

    public ServerEditScreen(HomeScreen parent, int index) {
        super(Text.literal(index < 0 ? "Добавление сервера" : "Изменение сервера"));
        this.parent = parent;
        this.index = index;
        if (index >= 0) {
            ServerInfo s = parent.servers.get(index);
            name.append(s.name);
            address.append(s.address);
            policy = s.getResourcePackPolicy();
        } else {
            name.append("Сервер Minecraft");
            focus = 1;
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // The home screen stays visible, blurred, behind the dialog.
        parent.render(context, -1000, -1000, delta);
        applyBlur(delta);
        context.fill(0, 0, width, height, 0x66050410);
    }

    @Override
    public void init() {
        parent.init(client, width, height);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        int accent = BlockyardUi.accent;
        float a = Math.min(1, (System.currentTimeMillis() - openedAt) / 220f);
        k = height / CANVAS_H;
        vw = width / k;
        float mx = mouseX / k, my = mouseY / k;
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.scale(k, k, 1);

        float w = 210, h = 128;
        float x = (vw - w) / 2, y = (CANVAS_H - h) / 2 + (1 - a) * 8;
        Ui.shadow(ctx, x, y + 8, w, h, 30, 0.8f * a);
        Ui.round(ctx, x, y, w, h, 14, Ui.argb(0.92f * a, 0x2A2838), Ui.argb(0.92f * a, 0x1C1A28));
        Ui.round(ctx, x + 0.5f, y + 0.5f, w - 1, 1.5f, 1, Ui.argb(0.10f * a, WHITE));

        Ui.text(ctx, textRenderer, getTitle().getString(), Ui.HEAD, x + 12, y + 11, Ui.argb(a, WHITE));
        closeX = x + w - 21;
        closeY = y + 9;
        boolean hovClose = in(mx, my, closeX, closeY, 12, 12);
        Ui.circle(ctx, closeX + 6, closeY + 6, 6, Ui.argb(a, hovClose ? 0xFF7A70 : 0xF0645A));
        Ui.icon(ctx, "close", closeX + 2, closeY + 2, 8, Ui.argb(a, 0x3A0E0E));

        fieldX = x + 10;
        fieldW = w - 20;
        nameY = y + 31;
        addrY = nameY + 21;
        packY = addrY + 23;
        saveY = packY + 20;
        drawField(ctx, mx, my, name.toString(), "Название", nameY, focus == 0, a);
        drawField(ctx, mx, my, address.toString(), "IP-адрес сервера", addrY, focus == 1, a);

        String pack = "Ресурспак: " + switch (policy) {
            case ENABLED -> "Включён";
            case DISABLED -> "Выключен";
            default -> "Спрашивать";
        };
        boolean hovPack = in(mx, my, fieldX, packY, fieldW, 15);
        Ui.round(ctx, fieldX, packY, fieldW, 15, 7.5f, Ui.argb((hovPack ? 0.30f : 0.20f) * a, WHITE));
        Ui.centered(ctx, textRenderer, pack, Ui.BODY, fieldX + fieldW / 2, packY + 4, Ui.argb(0.9f * a, WHITE));

        boolean ok = !address.toString().isBlank();
        boolean hovSave = ok && in(mx, my, fieldX, saveY, fieldW, 17);
        if (hovSave) Ui.light(ctx, Ui.tex("glow"), fieldX - 10, saveY - 14, fieldW + 20, 45, Ui.argb(0.45f * a, accent));
        Ui.round(ctx, fieldX - (hovSave ? 1 : 0), saveY - (hovSave ? 1 : 0), fieldW + (hovSave ? 2 : 0), 17 + (hovSave ? 2 : 0), 8.5f, Ui.argb(a * (ok ? 1 : 0.4f), WHITE));
        Ui.centered(ctx, textRenderer, "Сохранить", Ui.BOLD, fieldX + fieldW / 2, saveY + 4.5f, Ui.argb(a, INK));
        m.pop();
    }

    private void drawField(DrawContext ctx, float mx, float my, String value, String placeholder, float y, boolean focused, float a) {
        float h = 17;
        if (focused) Ui.round(ctx, fieldX - 1, y - 1, fieldW + 2, h + 2, 9, Ui.argb(0.45f * a, WHITE));
        Ui.round(ctx, fieldX, y, fieldW, h, 8.5f, Ui.argb(a, focused ? 0x2E2C3C : 0x343242));
        boolean empty = value.isEmpty();
        String shown = Ui.fit(textRenderer, empty ? placeholder : value, Ui.BODY, fieldW - 20);
        Ui.text(ctx, textRenderer, shown, Ui.BODY, fieldX + 9, y + 5, Ui.argb(a * (empty ? 0.4f : 1), WHITE));
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            float cx = fieldX + 9 + (empty ? 0 : Ui.width(textRenderer, shown, Ui.BODY)) + 1;
            Ui.round(ctx, cx, y + 4, 1, 9, 0.5f, Ui.argb(a, WHITE));
        }
    }

    private static boolean in(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void save() {
        if (address.toString().isBlank()) return;
        String n = name.toString().isBlank() ? "Сервер Minecraft" : name.toString().trim();
        String addr = address.toString().trim();
        if (index >= 0) {
            ServerInfo s = parent.servers.get(index);
            s.name = n;
            s.address = addr;
            s.setResourcePackPolicy(policy);
            s.setStatus(ServerInfo.Status.INITIAL);
        } else {
            ServerInfo s = new ServerInfo(n, addr, ServerInfo.ServerType.OTHER);
            s.setResourcePackPolicy(policy);
            parent.servers.add(s, false);
        }
        parent.servers.saveFile();
        parent.click();
        client.setScreen(new HomeScreen());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float mx = (float) (mouseX / k), my = (float) (mouseY / k);
        if (in(mx, my, closeX, closeY, 12, 12)) {
            close();
        } else if (in(mx, my, fieldX, nameY, fieldW, 17)) {
            focus = 0;
        } else if (in(mx, my, fieldX, addrY, fieldW, 17)) {
            focus = 1;
        } else if (in(mx, my, fieldX, packY, fieldW, 15)) {
            parent.click();
            policy = switch (policy) {
                case PROMPT -> ServerInfo.ResourcePackPolicy.ENABLED;
                case ENABLED -> ServerInfo.ResourcePackPolicy.DISABLED;
                default -> ServerInfo.ResourcePackPolicy.PROMPT;
            };
        } else if (in(mx, my, fieldX, saveY, fieldW, 17)) {
            save();
        }
        return true;
    }

    private StringBuilder field() {
        return focus == 0 ? name : address;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (chr >= ' ' && field().length() < 128) field().append(chr);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> close();
            case GLFW.GLFW_KEY_BACKSPACE -> {
                StringBuilder f = field();
                if (f.length() > 0) {
                    if (Screen.hasControlDown()) f.setLength(0);
                    else f.setLength(f.length() - 1);
                }
            }
            case GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> focus = 1 - focus;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (focus == 0) focus = 1;
                else save();
            }
            default -> {
                if (Screen.isPaste(keyCode)) {
                    String clip = client.keyboard.getClipboard().replace("\n", "").replace("\r", "");
                    field().append(clip, 0, Math.min(clip.length(), 128 - field().length()));
                } else if (Screen.isSelectAll(keyCode)) {
                    field().setLength(0);
                }
            }
        }
        return true;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
