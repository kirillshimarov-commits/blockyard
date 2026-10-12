package app.blockyard.ui.screen;

import app.blockyard.ui.BlockyardUi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Add or edit a server: a glass dialog over the blurred home screen. */
public class ServerEditScreen extends BlockyardScreen {
    private final HomeScreen parent;
    /** Index in the server list, or -1 to add a new server. */
    private final int index;
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder address = new StringBuilder();
    private ServerInfo.ResourcePackPolicy policy = ServerInfo.ResourcePackPolicy.PROMPT;
    private int focus = 0;
    private final long openedAt = System.currentTimeMillis();

    public ServerEditScreen(HomeScreen parent, int index) {
        super(Text.literal(index < 0 ? "Новый сервер" : "Изменение сервера"));
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
    protected void init() {
        super.init();
        parent.init(client, width, height);
    }

    @Override
    protected void drawBackdrop(DrawContext ctx, float delta) {
        parent.render(ctx, -1000, -1000, delta);
        applyBlur(delta);
        ctx.fill(0, 0, width, height, 0x59060510);
    }

    @Override
    protected void draw(DrawContext ctx, float delta) {
        float a = ease((System.currentTimeMillis() - openedAt) / 220f);
        float w = 155, h = 114;
        float x = (vw - w) / 2, y = (CANVAS_H - h) / 2 + (1 - a) * 6;
        Ui.shadow(ctx, x, y + 8, w, h, 26, 0.8f * a);
        Ui.round(ctx, x, y, w, h, 7.5f, Ui.argb(0.94f * a, 0x2A2838), Ui.argb(0.95f * a, 0x1C1A28));

        Ui.text(ctx, textRenderer, getTitle().getString(), Ui.HEAD, x + 7.5f, y + 8, Ui.argb(a, WHITE));
        float closeX = x + w - 16, closeY = y + 6.5f;
        boolean hovClose = hover(closeX, closeY, 8.5f, 8.5f);
        Ui.circle(ctx, closeX + 4.25f, closeY + 4.25f, 4.25f, Ui.argb(a, hovClose ? 0xFF7A70 : 0xF0645A));
        Ui.icon(ctx, "close", closeX + 1.5f, closeY + 1.5f, 5.5f, Ui.argb(a, 0x3A0E0E));
        region(closeX, closeY, 8.5f, 8.5f, () -> {
            click();
            close();
        });

        float fx = x + 7.5f, fw = w - 15;
        float fy = y + 22;
        fy = field(ctx, "Название", name.toString(), "Сервер Minecraft", fx, fy, fw, 0, a);
        fy = field(ctx, "IP-адрес", address.toString(), "например, play.example.ru", fx, fy, fw, 1, a);

        Ui.text(ctx, textRenderer, "Ресурспак сервера", Ui.SMALL, fx + 1, fy, Ui.argb(0.6f * a, WHITE));
        fy += 5;
        String[] names = {"Включён", "Выключен", "Спрашивать"};
        ServerInfo.ResourcePackPolicy[] values = {ServerInfo.ResourcePackPolicy.ENABLED, ServerInfo.ResourcePackPolicy.DISABLED, ServerInfo.ResourcePackPolicy.PROMPT};
        Ui.round(ctx, fx, fy, fw, 11, 3.5f, Ui.argb(0.06f * a, WHITE));
        float cx = fx + 1;
        for (int i = 0; i < names.length; i++) {
            float bw = Ui.width(textRenderer, names[i], Ui.BODY) + 9;
            boolean on = values[i] == policy;
            if (on) Ui.round(ctx, cx, fy + 1, bw, 9, 2.5f, Ui.argb(a, WHITE));
            Ui.text(ctx, textRenderer, names[i], on ? Ui.BOLD : Ui.BODY, cx + 4.5f, fy + 3.4f, on ? Ui.argb(a, INK) : Ui.argb(0.75f * a, WHITE));
            final ServerInfo.ResourcePackPolicy target = values[i];
            region(cx, fy + 1, bw, 9, () -> {
                click();
                policy = target;
            });
            cx += bw;
        }
        fy += 16;

        boolean ok = !address.toString().isBlank();
        wideButton(ctx, "Сохранить", fx, fy, fw, 13.5f, 1, a * (ok ? 1 : 0.5f), this::save);
    }

    private float field(DrawContext ctx, String label, String value, String placeholder, float x, float y, float w, int which, float a) {
        Ui.text(ctx, textRenderer, label, Ui.SMALL, x + 1, y, Ui.argb(0.6f * a, WHITE));
        y += 5;
        float h = 13;
        boolean focused = focus == which;
        if (focused) Ui.round(ctx, x - 0.5f, y - 0.5f, w + 1, h + 1, 4.25f, Ui.argb(0.5f * a, WHITE));
        Ui.round(ctx, x, y, w, h, 4, Ui.argb(a, focused ? 0x353343 : 0x302E3E));
        boolean empty = value.isEmpty();
        String shown = Ui.fit(textRenderer, empty ? placeholder : value, Ui.BODY, w - 12);
        Ui.text(ctx, textRenderer, shown, Ui.BODY, x + 5, y + 4.4f, Ui.argb(a * (empty ? 0.35f : 1), WHITE));
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            float cx = x + 5 + (empty ? 0 : Ui.width(textRenderer, shown, Ui.BODY)) + 0.6f;
            Ui.round(ctx, cx, y + 3.5f, 0.6f, 6, 0.3f, Ui.argb(a, WHITE));
        }
        region(x, y, w, h, () -> focus = which);
        return y + h + 5;
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
        client.setScreen(new HomeScreen(HomeScreen.TAB_MULTI));
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
